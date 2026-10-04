from pathlib import Path
import subprocess
import time
import sys
import json
import re


VERSION = "0.6"

ROOT = Path(__file__).resolve().parent.parent
CONFIG = ROOT / "config" / "channels.json"
OUTPUT = ROOT / "tvatlas.m3u"
TEMP_DIR = ROOT / ".tvatlas_temp"


# ============================================================
# 多源
# priority 越小优先级越高
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
# 配置
# ============================================================

def load_config():

    if not CONFIG.exists():
        print("ERROR: config/channels.json not found")
        sys.exit(1)

    with CONFIG.open(
        "r",
        encoding="utf-8"
    ) as f:
        return json.load(f)


# ============================================================
# 下载单个来源
# ============================================================

def download_source(source):

    TEMP_DIR.mkdir(
        parents=True,
        exist_ok=True
    )

    path = (
        TEMP_DIR /
        f"{source['id']}.m3u"
    )

    command = [
        "curl",
        "-L",
        "--fail",
        "--silent",
        "--show-error",
        "--connect-timeout", "8",
        "--max-time", "25",
        "--retry", "1",
        "--retry-delay", "1",
        "-o", str(path),
        source["url"],
    ]

    start = time.time()

    try:

        subprocess.run(
            command,
            check=True,
            timeout=35
        )

    except Exception as e:

        print(
            f"WARN: {source['id']} "
            f"download failed: {e}"
        )

        return None

    if not path.exists():
        return None

    size = path.stat().st_size

    if size < 100:
        print(
            f"WARN: {source['id']} "
            f"file too small"
        )
        return None

    print(
        f"OK   : {source['id']} "
        f"{size:,} bytes "
        f"{time.time() - start:.1f}s"
    )

    return path


# ============================================================
# 频道名
# ============================================================

def get_name(extinf):

    if b"," not in extinf:
        return b""

    return extinf.split(
        b",",
        1
    )[1].strip()


# ============================================================
# 名称归一化
# ============================================================

def normalize_name(name):

    name = re.sub(
        rb"(?i)cctv",
        b"CCTV",
        name
    )

    # 去除常见画质标记
    name = re.sub(
        rb"\((?:720p|1080p|2160p|4K|8K)\)",
        b"",
        name,
        flags=re.I
    )

    # 删除空格，方便 CCTV4 / CCTV-4中文国际 等匹配
    name = re.sub(
        rb"\s+",
        b"",
        name
    )

    return name.upper()


# ============================================================
# CCTV 编号提取
#
# 解决：
# CCTV4
# CCTV-4
# CCTV-4中文国际
# CCTV10
# CCTV-16
# ============================================================

def extract_cctv_number(name):

    normalized = normalize_name(name)

    match = re.match(
        rb"^CCTV-?([0-9]{1,2})(\+?)",
        normalized
    )

    if not match:
        return None

    number = match.group(1).decode(
        "ascii"
    )

    plus = match.group(2)

    if plus:
        return number + "+"

    return number


# ============================================================
# 从配置 ID 获取 CCTV 编号
# ============================================================

def config_cctv_number(channel):

    cid = channel["id"].lower()

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
# 匹配
# ============================================================

def channel_matches(
    source_name,
    channel
):

    # --------------------------------
    # CCTV 直接按编号识别
    # --------------------------------

    wanted_cctv = (
        config_cctv_number(channel)
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
    # 普通频道 alias 匹配
    # --------------------------------

    source = normalize_name(
        source_name
    )

    for alias in channel["match"]:

        target = normalize_name(
            alias.encode("utf-8")
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

    data = path.read_bytes()
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

            candidate = lines[j]

            if not candidate:

                j += 1
                continue

            if candidate.startswith(b"#"):

                extras.append(
                    candidate
                )

                j += 1
                continue

            url = candidate.strip()
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
                        get_name(extinf),

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
# 下载 + 合并所有来源
# ============================================================

def collect_sources():

    print("")
    print("[1/5] Download sources")
    print("")

    all_entries = []

    successful = 0

    for source in sorted(
        SOURCES,
        key=lambda x: x["priority"]
    ):

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
            f"     {source['id']}: "
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
# EXTINF 输出
# ============================================================

def rewrite_extinf(
    extinf,
    display_name,
    group
):

    extinf = re.sub(
        rb"(?i)cctv",
        b"CCTV",
        extinf
    )

    group_bytes = group.encode(
        "utf-8"
    )

    name_bytes = display_name.encode(
        "utf-8"
    )

    # group-title
    if re.search(
        rb'group-title="[^"]*"',
        extinf
    ):

        extinf = re.sub(
            rb'group-title="[^"]*"',
            b'group-title="'
            + group_bytes
            + b'"',
            extinf
        )

    else:

        extinf = extinf.replace(
            b"#EXTINF:-1",
            b'#EXTINF:-1 group-title="'
            + group_bytes
            + b'"',
            1
        )

    # 显示名称
    if b"," in extinf:

        metadata = extinf.split(
            b",",
            1
        )[0]

        extinf = (
            metadata
            + b","
            + name_bytes
        )

    return extinf


# ============================================================
# 匹配所有频道
# ============================================================

def match_channels(
    config,
    entries
):

    print("")
    print("[2/5] Match channels")
    print("")

    max_lines = (
        config["settings"]
        .get(
            "max_lines_per_channel",
            2
        )
    )

    results = []

    for wanted in config["channels"]:

        candidates = []

        seen_urls = set()

        # 已按来源 priority 排序
        sorted_entries = sorted(
            entries,
            key=lambda x: x["priority"]
        )

        for entry in sorted_entries:

            if not channel_matches(
                entry["name"],
                wanted
            ):
                continue

            url = entry["url"]

            if url in seen_urls:
                continue

            seen_urls.add(url)

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
                "config": wanted,
                "sources": candidates,
            }
        )

        if candidates:

            source_names = ", ".join(
                item["source_id"]
                for item in candidates
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
# 写文件
# ============================================================

def write_output(results):

    print("")
    print("[3/5] Generate playlist")

    output = [
        b"#EXTM3U"
    ]

    total_lines = 0
    logical_channels = 0

    for result in results:

        wanted = result["config"]
        sources = result["sources"]

        if not sources:
            continue

        logical_channels += 1

        for source in sources:

            extinf = rewrite_extinf(
                source["extinf"],
                wanted["name"],
                wanted["group"]
            )

            output.append(extinf)

            output.extend(
                source["extras"]
            )

            output.append(
                source["url"]
            )

            total_lines += 1

    OUTPUT.write_bytes(
        b"\n".join(output)
        + b"\n"
    )

    return (
        logical_channels,
        total_lines
    )


# ============================================================
# 清理
# ============================================================

def cleanup():

    if not TEMP_DIR.exists():
        return

    for path in TEMP_DIR.iterdir():

        if path.is_file():
            path.unlink()

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
        "Multi-Source Builder"
    )
    print(
        "======================================"
    )

    start = time.time()

    config = load_config()

    try:

        entries = collect_sources()

        results = match_channels(
            config,
            entries
        )

        logical, lines = (
            write_output(results)
        )

        if logical == 0:

            print(
                "ERROR: no channels generated"
            )

            sys.exit(1)

        print("")
        print(
            "[4/5] Validate output"
        )

        if not OUTPUT.exists():

            print(
                "ERROR: output missing"
            )

            sys.exit(1)

        print(
            f"OK: {OUTPUT.stat().st_size:,} bytes"
        )

    finally:

        cleanup()

    print("")
    print("[5/5] Complete")
    print(
        "--------------------------------------"
    )
    print(
        f"Logical channels : {logical}"
    )
    print(
        f"Stream lines     : {lines}"
    )
    print(
        f"Build time       : "
        f"{time.time() - start:.1f}s"
    )
    print(
        f"Output           : {OUTPUT}"
    )
    print(
        "--------------------------------------"
    )
    print(
        "Primary source   : cs3306"
    )
    print(
        "Fallback source  : best-fan"
    )
    print(
        "Publish          : GitHub RAW"
    )
    print(
        "======================================"
    )


if __name__ == "__main__":
    main()
