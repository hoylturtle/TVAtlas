from pathlib import Path
import subprocess
import time
import sys
import re

# ============================================================
# TVAtlas v0.4
# Mainland China Curated Edition
#
# 1. 保留上游 EXTINF 原始字节
# 2. 不重写中文
# 3. 精确识别 CCTV
# 4. 保留卫视
# 5. 同频道暂时最多保留 2 条线路
# ============================================================

VERSION = "0.4"

SOURCE = (
    "https://raw.githubusercontent.com/"
    "cs3306/IPTV-Sources/main/data/output/iptv_collection.m3u"
)

ROOT = Path(__file__).resolve().parent.parent
TEMP = ROOT / "source.m3u"
OUTPUT = ROOT / "tvatlas.m3u"


# ============================================================
# CCTV
# ============================================================

CCTV_NUMBERS = {
    "1", "2", "3", "4", "5", "5+",
    "6", "7", "8", "9", "10", "11",
    "12", "13", "14", "15", "16", "17"
}


# ============================================================
# 主流卫视
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
    x.encode("utf-8") for x in SATELLITES
]


# 每个频道最多保留两条候选线路
MAX_LINES_PER_CHANNEL = 2


# ============================================================
# 下载
# ============================================================

def download():

    print("")
    print("[1/4] Download source")

    command = [
        "curl",
        "-L",
        "--fail",
        "--silent",
        "--show-error",
        "--connect-timeout", "8",
        "--max-time", "30",
        "--retry", "1",
        "--retry-delay", "2",
        "-o", str(TEMP),
        SOURCE,
    ]

    start = time.time()

    try:
        subprocess.run(
            command,
            check=True,
            timeout=40
        )

    except subprocess.TimeoutExpired:
        print("ERROR: download timeout")
        sys.exit(1)

    except subprocess.CalledProcessError as e:
        print(f"ERROR: curl failed ({e.returncode})")
        sys.exit(1)

    if not TEMP.exists():
        print("ERROR: source not created")
        sys.exit(1)

    size = TEMP.stat().st_size

    print(
        f"OK: {size:,} bytes "
        f"in {time.time() - start:.1f}s"
    )


# ============================================================
# 获取频道显示名称
# ============================================================

def get_name(extinf):

    if b"," not in extinf:
        return b""

    return extinf.split(b",", 1)[1].strip()


# ============================================================
# CCTV 精确识别
# ============================================================

def identify_cctv(name):

    # CCTV-1
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

    number = match.group(1).decode("ascii")

    if number not in CCTV_NUMBERS:
        return None

    return f"CCTV-{number}"


# ============================================================
# 卫视识别
# ============================================================

def identify_satellite(name):

    for chinese_name, chinese_bytes in zip(
        SATELLITES,
        SATELLITE_BYTES
    ):
        if chinese_bytes in name:
            return chinese_name

    return None


# ============================================================
# 频道身份
# ============================================================

def identify_channel(extinf):

    name = get_name(extinf)

    channel = identify_cctv(name)

    if channel:
        return channel

    channel = identify_satellite(name)

    if channel:
        return channel

    return None


# ============================================================
# 筛选
# ============================================================

def filter_playlist():

    print("")
    print("[2/4] Parse playlist")

    data = TEMP.read_bytes()
    lines = data.splitlines()

    # channel_id -> [(extinf, extras, url)]
    found = {}

    i = 0

    while i < len(lines):

        line = lines[i]

        if not line.startswith(b"#EXTINF:"):
            i += 1
            continue

        extinf = line
        channel_id = identify_channel(extinf)

        j = i + 1
        extras = []
        url = None

        while j < len(lines):

            candidate = lines[j]

            if not candidate:
                j += 1
                continue

            if candidate.startswith(b"#"):
                extras.append(candidate)
                j += 1
                continue

            url = candidate
            break

        if channel_id and url:

            entries = found.setdefault(
                channel_id,
                []
            )

            # 相同 URL 不重复
            duplicate = any(
                existing[2] == url
                for existing in entries
            )

            if (
                not duplicate
                and len(entries) < MAX_LINES_PER_CHANNEL
            ):
                entries.append(
                    (extinf, extras, url)
                )

        i = max(j + 1, i + 1)

    return found


# ============================================================
# 输出
# ============================================================

def write_playlist(found):

    print("")
    print("[3/4] Generate TVAtlas")

    output = [b"#EXTM3U"]

    total = 0

    # CCTV 固定排序
    cctv_order = [
        "1", "2", "3", "4", "5", "5+",
        "6", "7", "8", "9", "10", "11",
        "12", "13", "14", "15", "16", "17"
    ]

    ordered_ids = [
        f"CCTV-{x}"
        for x in cctv_order
    ]

    ordered_ids.extend(SATELLITES)

    for channel_id in ordered_ids:

        entries = found.get(channel_id, [])

        if not entries:
            print(f"MISS : {channel_id}")
            continue

        print(
            f"FOUND: {channel_id} "
            f"({len(entries)} line(s))"
        )

        for extinf, extras, url in entries:

            # 全部使用原始 bytes
            output.append(extinf)
            output.extend(extras)
            output.append(url)

            total += 1

    result = b"\n".join(output) + b"\n"

    OUTPUT.write_bytes(result)

    return total


# ============================================================
# MAIN
# ============================================================

def main():

    print("")
    print("======================================")
    print(f"TVAtlas v{VERSION}")
    print("Mainland China Curated Edition")
    print("======================================")

    start = time.time()

    try:

        download()

        found = filter_playlist()

        total = write_playlist(found)

        if total == 0:
            print("ERROR: no channels generated")
            sys.exit(1)

    finally:

        if TEMP.exists():
            TEMP.unlink()

    print("")
    print("[4/4] Complete")
    print("--------------------------------------")
    print(f"Output entries : {total}")
    print(f"Build time     : {time.time()-start:.1f}s")
    print(f"Output         : {OUTPUT}")
    print("--------------------------------------")
    print("Chinese EXTINF : original bytes")
    print("Publish        : GitHub RAW")
    print("======================================")


if __name__ == "__main__":
    main()
