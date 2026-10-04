from pathlib import Path
import subprocess
import time
import sys
import json
import re


# ============================================================
# TVAtlas
# Multi-Source Builder + Region Discovery
# ============================================================

VERSION = "0.6.1"

ROOT = Path(__file__).resolve().parent.parent

CONFIG = ROOT / "config" / "channels.json"
OUTPUT = ROOT / "tvatlas.m3u"
TEMP_DIR = ROOT / ".tvatlas_temp"


# ============================================================
# IPTV Sources
#
# priority 越小，优先级越高
# ============================================================

SOURCES = [
    {
        "id": "cs3306",
        "priority": 1,
        "url": (
            "https://raw.githubusercontent.com/"
            "cs3306/IPTV-Sources/main/data/output/"
            "iptv_collection.m3u"
        ),
    },

    {
        "id": "bestfan-all",
        "priority": 2,
        "url": (
            "https://raw.githubusercontent.com/"
            "best-fan/iptv-sources/main/cn_all.m3u8"
        ),
    },

    {
        "id": "bestfan-cctv",
        "priority": 3,
        "url": (
            "https://raw.githubusercontent.com/"
            "best-fan/iptv-sources/main/cn_cctv.m3u8"
        ),
    },

    {
        "id": "bestfan-province",
        "priority": 4,
        "url": (
            "https://raw.githubusercontent.com/"
            "best-fan/iptv-sources/main/cn_province.m3u8"
        ),
    },
]


# ============================================================
# 港澳台 Discovery 关键词
#
# 这里只扫描
# 不会直接加入 tvatlas.m3u
# ============================================================

DISCOVERY_REGIONS = {

    "HONG KONG": [
        "香港",
        "翡翠",
        "明珠",
        "TVB",
        "VIUTV",
        "HOY",
        "RTHK",
        "港台",
        "凤凰中文",
        "凤凰资讯",
        "凤凰香港",
        "PHOENIX",
    ],

    "MACAU": [
        "澳门",
        "澳門",
        "澳视",
        "澳視",
        "莲花",
        "蓮花",
        "TDM",
        "MACAU",
        "MACAO",
    ],

    "TAIWAN": [
        "台湾",
        "台灣",
        "TVBS",
        "民视",
        "民視",
        "三立",
        "东森",
        "東森",
        "台视",
        "台視",
        "中视",
        "中視",
        "华视",
        "華視",
        "公视",
        "公視",
        "年代",
        "非凡",
        "寰宇",
        "镜新闻",
        "鏡新聞",
        "壹电视",
        "壹電視",
        "纬来",
        "緯來",
        "中天",
        "八大",
        "MOMO",
        "ELEVEN",
    ],
}


# ============================================================
# 加载频道配置
# ============================================================

def load_config():

    if not CONFIG.exists():

        print("")
        print(
            "ERROR: config/channels.json not found"
        )

        sys.exit(1)

    try:

        with CONFIG.open(
            "r",
            encoding="utf-8"
        ) as f:

            config = json.load(f)

    except Exception as e:

        print("")
        print(
            f"ERROR: failed to load config: {e}"
        )

        sys.exit(1)

    return config


# ============================================================
# 下载单个来源
# ============================================================

def download_source(source):

    TEMP_DIR.mkdir(
        parents=True,
        exist_ok=True
    )

    path = (
        TEMP_DIR
        / f"{source['id']}.m3u"
    )

    command = [
        "curl",
        "-L",
        "--fail",
        "--silent",
        "--show-error",

        "--connect-timeout",
        "8",

        "--max-time",
        "25",

        "--retry",
        "1",

        "--retry-delay",
        "1",

        "-o",
        str(path),

        source["url"],
    ]

    start = time.time()

    try:

        subprocess.run(
            command,
            check=True,
            timeout=35
        )

    except subprocess.TimeoutExpired:

        print(
            f"WARN : {source['id']} "
            f"download timeout"
        )

        return None

    except subprocess.CalledProcessError as e:

        print(
            f"WARN : {source['id']} "
            f"curl failed "
            f"(code={e.returncode})"
        )

        return None

    except Exception as e:

        print(
            f"WARN : {source['id']} "
            f"download failed: {e}"
        )

        return None

    if not path.exists():

        print(
            f"WARN : {source['id']} "
            f"file not created"
        )

        return None

    size = path.stat().st_size

    if size < 100:

        print(
            f"WARN : {source['id']} "
            f"file too small"
        )

        return None

    elapsed = (
        time.time()
        - start
    )

    print(
        f"OK   : "
        f"{source['id']} "
        f"{size:,} bytes "
        f"{elapsed:.1f}s"
    )

    return path


# ============================================================
# 获取 EXTINF 最后的频道名称
# ============================================================

def get_name(extinf):

    if b"," not in extinf:

        return b""

    return (
        extinf
        .split(
            b",",
            1
        )[1]
        .strip()
    )


# ============================================================
# UTF-8 解码
# ============================================================

def decode_name(name_bytes):

    try:

        return (
            name_bytes
            .decode(
                "utf-8",
                errors="replace"
            )
            .strip()
        )

    except Exception:

        return ""


# ============================================================
# 名称归一化
#
# 仅用于匹配
# 不直接修改原始数据
# ============================================================

def normalize_name(name):

    # CCTV 大小写统一
    name = re.sub(
        rb"(?i)cctv",
        b"CCTV",
        name
    )

    # 去除部分常见画质标签
    name = re.sub(
        rb"\((?:720p|1080p|2160p|4K|8K)\)",
        b"",
        name,
        flags=re.I
    )

    # 删除空格
    name = re.sub(
        rb"\s+",
        b"",
        name
    )

    # ASCII 转大写
    return name.upper()


# ============================================================
# 提取 CCTV 编号
#
# 支持：
#
# CCTV1
# CCTV-1
# CCTV1综合
# CCTV-1 综合
# CCTV5+
# CCTV-5+
# CCTV16奥林匹克
# ============================================================

def extract_cctv_number(name):

    normalized = normalize_name(
        name
    )

    match = re.match(
        rb"^CCTV-?([0-9]{1,2})(\+?)",
        normalized
    )

    if not match:

        return None

    try:

        number = (
            match
            .group(1)
            .decode("ascii")
        )

    except Exception:

        return None

    plus = match.group(2)

    if plus:

        return number + "+"

    return number


# ============================================================
# 从 channels.json 的 id 提取 CCTV 编号
# ============================================================

def config_cctv_number(channel):

    cid = (
        channel["id"]
        .lower()
    )

    if cid == "cctv5plus":

        return "5+"

    match = re.match(
        r"^cctv(\d+)$",
        cid
    )

    if match:

        return match.group(1)

    return None


# ============================================================
# 判断上游频道是否匹配目标频道
# ============================================================

def channel_matches(
    source_name,
    channel
):

    # --------------------------------
    # CCTV
    #
    # 不再依赖完整频道名称
    # 直接根据 CCTV 编号匹配
    # --------------------------------

    wanted_cctv = (
        config_cctv_number(
            channel
        )
    )

    if wanted_cctv:

        source_cctv = (
            extract_cctv_number(
                source_name
            )
        )

        return (
            source_cctv
            == wanted_cctv
        )

    # --------------------------------
    # 普通频道
    # --------------------------------

    source = normalize_name(
        source_name
    )

    aliases = channel.get(
        "match",
        []
    )

    for alias in aliases:

        target = normalize_name(
            alias.encode(
                "utf-8"
            )
        )

        if target in source:

            return True

    return False


# ============================================================
# 解析 M3U
# ============================================================

def parse_playlist(
    path,
    source
):

    try:

        data = path.read_bytes()

    except Exception as e:

        print(
            f"WARN : cannot read "
            f"{source['id']}: {e}"
        )

        return []

    lines = data.splitlines()

    entries = []

    i = 0

    while i < len(lines):

        line = lines[i]

        if not line.startswith(
            b"#EXTINF:"
        ):

            i += 1
            continue

        extinf = line

        extras = []

        url = None

        j = i + 1

        while j < len(lines):

            candidate = (
                lines[j]
                .strip()
            )

            if not candidate:

                j += 1
                continue

            if candidate.startswith(
                b"#"
            ):

                extras.append(
                    candidate
                )

                j += 1
                continue

            url = candidate

            break

        if url:

            entries.append(
                {
                    "source_id":
                        source["id"],

                    "priority":
                        source["priority"],

                    "extinf":
                        extinf,

                    "name":
                        get_name(
                            extinf
                        ),

                    "extras":
                        extras,

                    "url":
                        url,
                }
            )

        i = max(
            j + 1,
            i + 1
        )

    return entries


# ============================================================
# 下载并合并全部来源
# ============================================================

def collect_sources():

    print("")
    print(
        "[1/6] Download sources"
    )
    print("")

    all_entries = []

    successful = 0

    ordered_sources = sorted(
        SOURCES,
        key=lambda x: x[
            "priority"
        ]
    )

    for source in ordered_sources:

        path = download_source(
            source
        )

        if not path:

            continue

        entries = parse_playlist(
            path,
            source
        )

        print(
            f"     "
            f"{source['id']}: "
            f"{len(entries)} entries"
        )

        all_entries.extend(
            entries
        )

        successful += 1

    if successful == 0:

        print("")
        print(
            "ERROR: all sources failed"
        )

        sys.exit(1)

    print("")
    print(
        f"Total candidates: "
        f"{len(all_entries)}"
    )

    return all_entries


# ============================================================
# 重写 EXTINF
#
# 保留：
# tvg-id
# tvg-logo
# 其他 metadata
#
# 修改：
# group-title
# 最终显示名称
# CCTV ASCII 大小写
# ============================================================

def rewrite_extinf(
    extinf,
    display_name,
    group
):

    # CCTV ASCII 统一
    extinf = re.sub(
        rb"(?i)cctv",
        b"CCTV",
        extinf
    )

    group_bytes = (
        group.encode(
            "utf-8"
        )
    )

    name_bytes = (
        display_name.encode(
            "utf-8"
        )
    )

    # --------------------------------
    # group-title
    # --------------------------------

    if re.search(
        rb'group-title="[^"]*"',
        extinf
    ):

        extinf = re.sub(
            rb'group-title="[^"]*"',
            (
                b'group-title="'
                + group_bytes
                + b'"'
            ),
            extinf
        )

    else:

        extinf = extinf.replace(
            b"#EXTINF:-1",
            (
                b'#EXTINF:-1 '
                b'group-title="'
                + group_bytes
                + b'"'
            ),
            1
        )

    # --------------------------------
    # 最终显示名称
    # --------------------------------

    if b"," in extinf:

        metadata = (
            extinf
            .split(
                b",",
                1
            )[0]
        )

        extinf = (
            metadata
            + b","
            + name_bytes
        )

    return extinf


# ============================================================
# 匹配 channels.json
# ============================================================

def match_channels(
    config,
    entries
):

    print("")
    print(
        "[2/6] Match channels"
    )
    print("")

    max_lines = (
        config
        .get(
            "settings",
            {}
        )
        .get(
            "max_lines_per_channel",
            2
        )
    )

    results = []

    sorted_entries = sorted(
        entries,
        key=lambda x: x[
            "priority"
        ]
    )

    for wanted in config[
        "channels"
    ]:

        candidates = []

        seen_urls = set()

        for entry in sorted_entries:

            if not channel_matches(
                entry["name"],
                wanted
            ):

                continue

            url = entry["url"]

            if url in seen_urls:

                continue

            seen_urls.add(
                url
            )

            candidates.append(
                entry
            )

            if (
                len(candidates)
                >= max_lines
            ):

                break

        results.append(
            {
                "config":
                    wanted,

                "sources":
                    candidates,
            }
        )

        if candidates:

            source_names = (
                ", ".join(
                    item["source_id"]
                    for item
                    in candidates
                )
            )

            print(
                f"FOUND: "
                f"{wanted['name']} "
                f"[{source_names}]"
            )

        else:

            print(
                f"MISS : "
                f"{wanted['name']}"
            )

    return results


# ============================================================
# 生成 tvatlas.m3u
# ============================================================

def write_output(
    results
):

    print("")
    print(
        "[3/6] Generate playlist"
    )

    output = [
        b"#EXTM3U"
    ]

    total_lines = 0

    logical_channels = 0

    for result in results:

        wanted = (
            result["config"]
        )

        sources = (
            result["sources"]
        )

        if not sources:

            continue

        logical_channels += 1

        for source in sources:

            extinf = rewrite_extinf(
                source["extinf"],
                wanted["name"],
                wanted["group"]
            )

            output.append(
                extinf
            )

            output.extend(
                source["extras"]
            )

            output.append(
                source["url"]
            )

            total_lines += 1

    playlist = (
        b"\n".join(
            output
        )
        + b"\n"
    )

    OUTPUT.write_bytes(
        playlist
    )

    return (
        logical_channels,
        total_lines
    )


# ============================================================
# 港澳台 Discovery
#
# 只扫描候选频道
#
# 不写入 tvatlas.m3u
# ============================================================

def discover_regions(
    entries
):

    print("")
    print(
        "[4/6] Region discovery"
    )

    print("")
    print(
        "======================================"
    )
    print(
        "TVAtlas Region Discovery"
    )
    print(
        "Hong Kong / Macau / Taiwan"
    )
    print(
        "======================================"
    )

    records = []

    # --------------------------------
    # 整理候选频道
    # --------------------------------

    for entry in entries:

        name = decode_name(
            entry["name"]
        )

        if not name:

            continue

        records.append(
            {
                "name":
                    name,

                "source":
                    entry[
                        "source_id"
                    ],

                "url":
                    entry["url"],
            }
        )

    # --------------------------------
    # 分地区扫描
    # --------------------------------

    for (
        region,
        keywords
    ) in DISCOVERY_REGIONS.items():

        print("")
        print(
            f"========== "
            f"{region} "
            f"=========="
        )

        found = {}

        for record in records:

            channel_name = (
                record["name"]
            )

            upper_name = (
                channel_name
                .upper()
            )

            matched = False

            for keyword in keywords:

                if (
                    keyword.upper()
                    in upper_name
                ):

                    matched = True
                    break

            if not matched:

                continue

            if (
                channel_name
                not in found
            ):

                found[
                    channel_name
                ] = {
                    "sources":
                        set(),

                    "urls":
                        set(),
                }

            found[
                channel_name
            ][
                "sources"
            ].add(
                record["source"]
            )

            found[
                channel_name
            ][
                "urls"
            ].add(
                record["url"]
            )

        # --------------------------------
        # 没有候选
        # --------------------------------

        if not found:

            print(
                "No candidates found."
            )

            continue

        # --------------------------------
        # 排序
        # --------------------------------

        sorted_names = sorted(
            found.keys(),
            key=lambda x:
                x.upper()
        )

        # --------------------------------
        # 打印
        # --------------------------------

        for name in sorted_names:

            info = found[
                name
            ]

            sources = (
                ", ".join(
                    sorted(
                        info[
                            "sources"
                        ]
                    )
                )
            )

            print("")
            print(
                f"CHANNEL: {name}"
            )

            print(
                f"  SOURCE : "
                f"{sources}"
            )

            print(
                f"  LINES  : "
                f"{len(info['urls'])}"
            )

        print("")
        print(
            f"TOTAL: "
            f"{len(found)} "
            f"candidate names"
        )

    print("")
    print(
        "======================================"
    )
    print(
        "Discovery complete"
    )
    print(
        "======================================"
    )


# ============================================================
# 验证输出
# ============================================================

def validate_output():

    print("")
    print(
        "[5/6] Validate output"
    )

    if not OUTPUT.exists():

        print(
            "ERROR: output missing"
        )

        sys.exit(1)

    size = (
        OUTPUT
        .stat()
        .st_size
    )

    if size < 50:

        print(
            "ERROR: output too small"
        )

        sys.exit(1)

    data = (
        OUTPUT
        .read_bytes()
    )

    if not data.startswith(
        b"#EXTM3U"
    ):

        print(
            "ERROR: invalid M3U header"
        )

        sys.exit(1)

    print(
        f"OK: {size:,} bytes"
    )


# ============================================================
# 清理临时文件
# ============================================================

def cleanup():

    if not TEMP_DIR.exists():

        return

    for path in (
        TEMP_DIR.iterdir()
    ):

        try:

            if path.is_file():

                path.unlink()

        except Exception:

            pass

    try:

        TEMP_DIR.rmdir()

    except OSError:

        pass


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
        "Multi-Source + Region Discovery"
    )
    print(
        "======================================"
    )

    start = time.time()

    config = load_config()

    logical = 0
    lines = 0

    try:

        # --------------------------------
        # 下载所有来源
        # --------------------------------

        entries = (
            collect_sources()
        )

        # --------------------------------
        # 构建当前正式频道
        # --------------------------------

        results = (
            match_channels(
                config,
                entries
            )
        )

        # --------------------------------
        # 写 tvatlas.m3u
        # --------------------------------

        (
            logical,
            lines
        ) = write_output(
            results
        )

        if logical == 0:

            print("")
            print(
                "ERROR: "
                "no channels generated"
            )

            sys.exit(1)

        # --------------------------------
        # 港澳台 Discovery
        # --------------------------------

        discover_regions(
            entries
        )

        # --------------------------------
        # 验证
        # --------------------------------

        validate_output()

    finally:

        cleanup()

    elapsed = (
        time.time()
        - start
    )

    print("")
    print(
        "[6/6] Complete"
    )

    print(
        "--------------------------------------"
    )

    print(
        f"Logical channels : "
        f"{logical}"
    )

    print(
        f"Stream lines     : "
        f"{lines}"
    )

    print(
        f"Build time       : "
        f"{elapsed:.1f}s"
    )

    print(
        f"Output           : "
        f"{OUTPUT}"
    )

    print(
        "--------------------------------------"
    )

    print(
        "Primary source   : "
        "cs3306"
    )

    print(
        "Fallback source  : "
        "best-fan"
    )

    print(
        "Discovery        : "
        "HK / Macau / Taiwan"
    )

    print(
        "Publish          : "
        "GitHub RAW"
    )

    print(
        "======================================"
    )


if __name__ == "__main__":
    main()
