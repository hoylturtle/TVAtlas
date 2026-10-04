from pathlib import Path
import subprocess
import time
import sys
import re

# ============================================================
# TVAtlas v0.4.1
# Mainland China Curated Edition
#
# 功能：
# 1. 保留上游 EXTINF 原始字节
# 2. 不重新编码中文
# 3. 精确识别 CCTV
# 4. 筛选主流卫视
# 5. 同频道最多保留 2 条线路
# 6. Cctv / cctv / CCTV 统一显示为 CCTV
# ============================================================

VERSION = "0.4.1"

SOURCE = (
    "https://raw.githubusercontent.com/"
    "cs3306/IPTV-Sources/main/data/output/iptv_collection.m3u"
)

ROOT = Path(__file__).resolve().parent.parent

TEMP = ROOT / "source.m3u"
OUTPUT = ROOT / "tvatlas.m3u"


# ============================================================
# CCTV 白名单
# ============================================================

CCTV_NUMBERS = {
    "1",
    "2",
    "3",
    "4",
    "5",
    "5+",
    "6",
    "7",
    "8",
    "9",
    "10",
    "11",
    "12",
    "13",
    "14",
    "15",
    "16",
    "17",
}


# ============================================================
# 主流卫视白名单
# ============================================================

SATELLITES = [
    "湖南卫视",
    "浙江卫视",
    "江苏卫视",
    "东方卫视",
    "北京卫视",
    "广东卫视",
    "深圳卫视",
    "山东卫视",
    "安徽卫视",
    "湖北卫视",
    "河南卫视",
    "四川卫视",
    "重庆卫视",
    "天津卫视",
    "辽宁卫视",
    "黑龙江卫视",
    "江西卫视",
    "河北卫视",
    "东南卫视",
    "广西卫视",
    "贵州卫视",
    "云南卫视",
]

SATELLITE_BYTES = [
    name.encode("utf-8")
    for name in SATELLITES
]


# ============================================================
# 每个频道最多保留多少条线路
# ============================================================

MAX_LINES_PER_CHANNEL = 2


# ============================================================
# 下载上游 M3U
# ============================================================

def download():

    print("")
    print("[1/4] Download source")
    print(SOURCE)
    print("")

    command = [
        "curl",
        "-L",
        "--fail",
        "--silent",
        "--show-error",

        # 建立连接最长 8 秒
        "--connect-timeout",
        "8",

        # 整个下载最长 30 秒
        "--max-time",
        "30",

        # 下载失败重试一次
        "--retry",
        "1",

        "--retry-delay",
        "2",

        "-o",
        str(TEMP),

        SOURCE,
    ]

    start = time.time()

    try:

        subprocess.run(
            command,
            check=True,
            timeout=40,
        )

    except subprocess.TimeoutExpired:

        print("")
        print("ERROR: download timeout")
        sys.exit(1)

    except subprocess.CalledProcessError as e:

        print("")
        print(
            f"ERROR: curl failed "
            f"(code={e.returncode})"
        )
        sys.exit(1)

    if not TEMP.exists():

        print("")
        print("ERROR: source file not created")
        sys.exit(1)

    size = TEMP.stat().st_size

    if size < 100:

        print("")
        print("ERROR: source file too small")
        sys.exit(1)

    elapsed = time.time() - start

    print(
        f"OK: {size:,} bytes "
        f"in {elapsed:.1f}s"
    )


# ============================================================
# 获取频道显示名称
#
# EXTINF 格式：
#
# #EXTINF:-1 ....,Cctv-1 综合
#
# 我们只读取逗号后面的 bytes
# 不进行中文 decode
# ============================================================

def get_name(extinf):

    if b"," not in extinf:
        return b""

    return extinf.split(
        b",",
        1
    )[1].strip()


# ============================================================
# CCTV 精确识别
# ============================================================

def identify_cctv(name):

    # 支持：
    #
    # CCTV-1
    # Cctv-1
    # cctv-1
    # CCTV1
    # CCTV-1 综合
    # CCTV1综合
    # CCTV-5+
    # CCTV5+

    upper = name.upper()

    match = re.match(
        rb"^CCTV[\-\s]?([0-9]{1,2}\+?)",
        upper
    )

    if not match:
        return None

    try:

        number = match.group(1).decode(
            "ascii"
        )

    except UnicodeDecodeError:

        return None

    if number not in CCTV_NUMBERS:
        return None

    return f"CCTV-{number}"


# ============================================================
# 卫视识别
# ============================================================

def identify_satellite(name):

    for (
        chinese_name,
        chinese_bytes
    ) in zip(
        SATELLITES,
        SATELLITE_BYTES
    ):

        if chinese_bytes in name:
            return chinese_name

    return None


# ============================================================
# 判断频道身份
# ============================================================

def identify_channel(extinf):

    name = get_name(extinf)

    # CCTV
    channel = identify_cctv(name)

    if channel:
        return channel

    # 卫视
    channel = identify_satellite(name)

    if channel:
        return channel

    return None


# ============================================================
# 标准化 EXTINF
#
# 重要：
# 这里只修改 ASCII：
#
# Cctv -> CCTV
# cctv -> CCTV
#
# 中文 bytes 完全不动
# ============================================================

def normalize_extinf(extinf):

    extinf = re.sub(
        rb"(?i)cctv",
        b"CCTV",
        extinf
    )

    return extinf


# ============================================================
# 筛选频道
# ============================================================

def filter_playlist():

    print("")
    print("[2/4] Parse playlist")

    # 全程读取 bytes
    # 不 decode 整个 M3U

    data = TEMP.read_bytes()

    lines = data.splitlines()

    # 格式：
    #
    # channel_id:
    # [
    #   (
    #       extinf,
    #       extra_lines,
    #       url
    #   )
    # ]

    found = {}

    i = 0

    while i < len(lines):

        line = lines[i]

        if not line.startswith(
            b"#EXTINF:"
        ):

            i += 1
            continue

        extinf = line

        channel_id = identify_channel(
            extinf
        )

        j = i + 1

        extras = []

        url = None

        while j < len(lines):

            candidate = lines[j]

            if not candidate:

                j += 1
                continue

            # EXTINF 后可能还有额外标签
            if candidate.startswith(b"#"):

                extras.append(candidate)

                j += 1
                continue

            # 第一个非 # 行认为是 URL
            url = candidate

            break

        if channel_id and url:

            entries = found.setdefault(
                channel_id,
                []
            )

            # ------------------------------------------------
            # 相同 URL 不重复
            # ------------------------------------------------

            duplicate = any(
                existing[2] == url
                for existing in entries
            )

            # ------------------------------------------------
            # 每个频道最多保留两条线路
            # ------------------------------------------------

            if (
                not duplicate
                and len(entries)
                < MAX_LINES_PER_CHANNEL
            ):

                entries.append(
                    (
                        extinf,
                        extras,
                        url,
                    )
                )

        i = max(
            j + 1,
            i + 1
        )

    return found


# ============================================================
# 输出 TVAtlas
# ============================================================

def write_playlist(found):

    print("")
    print("[3/4] Generate TVAtlas")

    output = [
        b"#EXTM3U"
    ]

    total = 0


    # --------------------------------------------------------
    # CCTV 固定排序
    # --------------------------------------------------------

    cctv_order = [
        "1",
        "2",
        "3",
        "4",
        "5",
        "5+",
        "6",
        "7",
        "8",
        "9",
        "10",
        "11",
        "12",
        "13",
        "14",
        "15",
        "16",
        "17",
    ]

    ordered_ids = [
        f"CCTV-{number}"
        for number in cctv_order
    ]


    # --------------------------------------------------------
    # CCTV 后面接卫视
    # --------------------------------------------------------

    ordered_ids.extend(
        SATELLITES
    )


    # --------------------------------------------------------
    # 写入
    # --------------------------------------------------------

    for channel_id in ordered_ids:

        entries = found.get(
            channel_id,
            []
        )

        if not entries:

            print(
                f"MISS : {channel_id}"
            )

            continue

        print(
            f"FOUND: {channel_id} "
            f"({len(entries)} line(s))"
        )

        for (
            extinf,
            extras,
            url
        ) in entries:

            # ================================================
            # 关键修改
            #
            # Cctv / cctv / CCTV
            # 全部统一为：
            #
            # CCTV
            #
            # 只操作 ASCII bytes
            # 不碰中文
            # ================================================

            extinf = normalize_extinf(
                extinf
            )

            # 原始 EXTINF
            output.append(
                extinf
            )

            # 原始额外标签
            output.extend(
                extras
            )

            # 原始直播 URL
            output.append(
                url
            )

            total += 1


    # --------------------------------------------------------
    # 输出文件
    # --------------------------------------------------------

    result = (
        b"\n".join(output)
        + b"\n"
    )

    OUTPUT.write_bytes(
        result
    )

    return total


# ============================================================
# MAIN
# ============================================================

def main():

    print("")
    print(
        "======================================"
    )

    print(
        f"TVAtlas v{VERSION}"
    )

    print(
        "Mainland China Curated Edition"
    )

    print(
        "======================================"
    )

    start = time.time()

    try:

        # 下载
        download()

        # 筛选
        found = filter_playlist()

        # 生成
        total = write_playlist(
            found
        )

        if total == 0:

            print("")
            print(
                "ERROR: no channels generated"
            )

            sys.exit(1)

    finally:

        # 删除临时大合集
        if TEMP.exists():

            TEMP.unlink()


    elapsed = (
        time.time()
        - start
    )

    print("")
    print("[4/4] Complete")

    print(
        "--------------------------------------"
    )

    print(
        f"Output entries : {total}"
    )

    print(
        f"Build time     : {elapsed:.1f}s"
    )

    print(
        f"Output         : {OUTPUT}"
    )

    print(
        "--------------------------------------"
    )

    print(
        "Chinese EXTINF : original bytes"
    )

    print(
        "CCTV naming    : normalized to CCTV"
    )

    print(
        "Publish        : GitHub RAW"
    )

    print(
        "======================================"
    )


if __name__ == "__main__":
    main()
