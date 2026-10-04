from pathlib import Path
import subprocess
import time
import sys
import json
import re


VERSION = "0.7"

ROOT = Path(__file__).resolve().parent.parent
CONFIG = ROOT / "config" / "channels.json"
OUTPUT = ROOT / "tvatlas.m3u"
TEMP_DIR = ROOT / ".tvatlas_temp"


# ============================================================
# Sources
#
# region:
# CN = 中国大陆
# HK = 香港
# MO = 澳门
# TW = 台湾
#
# priority 越小越优先
# ============================================================

SOURCES = [

    # ========================
    # MAINLAND CHINA
    # ========================

    {
        "id": "cs3306",
        "region": "CN",
        "priority": 1,
        "url": (
            "https://raw.githubusercontent.com/"
            "cs3306/IPTV-Sources/main/data/output/"
            "iptv_collection.m3u"
        ),
    },

    {
        "id": "bestfan-all",
        "region": "CN",
        "priority": 2,
        "url": (
            "https://raw.githubusercontent.com/"
            "best-fan/iptv-sources/main/cn_all.m3u8"
        ),
    },

    {
        "id": "bestfan-cctv",
        "region": "CN",
        "priority": 3,
        "url": (
            "https://raw.githubusercontent.com/"
            "best-fan/iptv-sources/main/cn_cctv.m3u8"
        ),
    },

    {
        "id": "bestfan-province",
        "region": "CN",
        "priority": 4,
        "url": (
            "https://raw.githubusercontent.com/"
            "best-fan/iptv-sources/main/cn_province.m3u8"
        ),
    },

    # ========================
    # HONG KONG
    # ========================

    {
        "id": "iptvorg-hk",
        "region": "HK",
        "priority": 10,
        "url": (
            "https://iptv-org.github.io/"
            "iptv/countries/hk.m3u"
        ),
    },

    # ========================
    # MACAU
    # ========================

    {
        "id": "iptvorg-mo",
        "region": "MO",
        "priority": 10,
        "url": (
            "https://iptv-org.github.io/"
            "iptv/countries/mo.m3u"
        ),
    },

    # ========================
    # TAIWAN
    # ========================

    {
        "id": "iptvorg-tw",
        "region": "TW",
        "priority": 10,
        "url": (
            "https://iptv-org.github.io/"
            "iptv/countries/tw.m3u"
        ),
    },
]


# ============================================================
# Config
# ============================================================

def load_config():

    if not CONFIG.exists():
        print("ERROR: config/channels.json not found")
        sys.exit(1)

    try:
        with CONFIG.open(
            "r",
            encoding="utf-8"
        ) as f:
            return json.load(f)

    except Exception as e:
        print(f"ERROR: config failed: {e}")
        sys.exit(1)


# ============================================================
# Download
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
        "--connect-timeout", "8",
        "--max-time", "30",
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
            timeout=40
        )

    except Exception as e:

        print(
            f"WARN : {source['id']} "
            f"download failed: {e}"
        )

        return None

    if not path.exists():
        return None

    size = path.stat().st_size

    if size < 50:

        print(
            f"WARN : {source['id']} "
            f"file too small"
        )

        return None

    print(
        f"OK   : {source['id']} "
        f"[{source['region']}] "
        f"{size:,} bytes "
        f"{time.time() - start:.1f}s"
    )

    return path


# ============================================================
# M3U helpers
# ============================================================

def get_name(extinf):

    if b"," not in extinf:
        return b""

    return (
        extinf
        .split(b",", 1)[1]
        .strip()
    )


def decode_name(value):

    try:
        return value.decode(
            "utf-8",
            errors="replace"
        ).strip()

    except Exception:
        return ""


def normalize_name(name):

    name = re.sub(
        rb"(?i)cctv",
        b"CCTV",
        name
    )

    name = re.sub(
        rb"\s+",
        b"",
        name
    )

    return name.upper()


# ============================================================
# CCTV matching
# ============================================================

def extract_cctv_number(name):

    normalized = normalize_name(name)

    match = re.match(
        rb"^CCTV-?([0-9]{1,2})(\+?)",
        normalized
    )

    if not match:
        return None

    try:
        number = match.group(1).decode(
            "ascii"
        )
    except Exception:
        return None

    if match.group(2):
        return number + "+"

    return number


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
# Channel matching
# ============================================================

def channel_matches(
    source_name,
    channel
):

    wanted_cctv = (
        config_cctv_number(channel)
    )

    if wanted_cctv:

        return (
            extract_cctv_number(
                source_name
            )
            == wanted_cctv
        )

    source = normalize_name(
        source_name
    )

    for alias in channel.get(
        "match",
        []
    ):

        target = normalize_name(
            alias.encode("utf-8")
        )

        if target in source:
            return True

    return False


# ============================================================
# Parse playlist
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

        if not lines[i].startswith(
            b"#EXTINF:"
        ):

            i += 1
            continue

        extinf = lines[i]

        extras = []
        url = None

        j = i + 1

        while j < len(lines):

            candidate = (
                lines[j].strip()
            )

            if not candidate:

                j += 1
                continue

            if candidate.startswith(b"#"):

                extras.append(candidate)

                j += 1
                continue

            url = candidate
            break

        if url:

            entries.append(
                {
                    "source_id":
                        source["id"],

                    "region":
                        source["region"],

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
# Collect all sources
# ============================================================

def collect_sources():

    print("")
    print("[1/6] Download sources")
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
            f"     {source['id']} "
            f"[{source['region']}]: "
            f"{len(entries)} entries"
        )

        all_entries.extend(
            entries
        )

        successful += 1

    if successful == 0:

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
# EXTINF rewrite
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

    if b"," in extinf:

        metadata = (
            extinf.split(
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
# Existing CN playlist matching
# ============================================================

def match_channels(
    config,
    entries
):

    print("")
    print("[2/6] Match formal channels")
    print("")

    max_lines = (
        config
        .get("settings", {})
        .get(
            "max_lines_per_channel",
            2
        )
    )

    results = []

    # 正式大陆频道仍允许使用全部来源 fallback
    ordered = sorted(
        entries,
        key=lambda x: x["priority"]
    )

    for wanted in config["channels"]:

        candidates = []

        seen_urls = set()
        seen_sources = set()

        # --------------------------------
        # 第一轮
        # 尽量选不同 source_id
        # --------------------------------

        for entry in ordered:

            if not channel_matches(
                entry["name"],
                wanted
            ):
                continue

            if entry["url"] in seen_urls:
                continue

            if (
                entry["source_id"]
                in seen_sources
            ):
                continue

            candidates.append(entry)

            seen_urls.add(
                entry["url"]
            )

            seen_sources.add(
                entry["source_id"]
            )

            if (
                len(candidates)
                >= max_lines
            ):
                break

        # --------------------------------
        # 第二轮
        # 如果不同来源不足，再允许同源第二条
        # --------------------------------

        if (
            len(candidates)
            < max_lines
        ):

            for entry in ordered:

                if not channel_matches(
                    entry["name"],
                    wanted
                ):
                    continue

                if entry["url"] in seen_urls:
                    continue

                candidates.append(entry)

                seen_urls.add(
                    entry["url"]
                )

                if (
                    len(candidates)
                    >= max_lines
                ):
                    break

        results.append(
            {
                "config": wanted,
                "sources": candidates
            }
        )

        if candidates:

            source_names = ", ".join(
                x["source_id"]
                for x in candidates
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
# Write current formal playlist
# ============================================================

def write_output(results):

    print("")
    print("[3/6] Generate playlist")

    output = [
        b"#EXTM3U"
    ]

    logical = 0
    stream_lines = 0

    for result in results:

        wanted = result["config"]

        sources = result["sources"]

        if not sources:
            continue

        logical += 1

        for source in sources:

            output.append(
                rewrite_extinf(
                    source["extinf"],
                    wanted["name"],
                    wanted["group"]
                )
            )

            output.extend(
                source["extras"]
            )

            output.append(
                source["url"]
            )

            stream_lines += 1

    OUTPUT.write_bytes(
        b"\n".join(output)
        + b"\n"
    )

    return logical, stream_lines


# ============================================================
# Region Discovery
#
# 这次不猜关键词
# 直接根据来源 region 分类
# ============================================================

def discover_regions(entries):

    print("")
    print("[4/6] Region source discovery")

    region_names = {
        "HK": "HONG KONG",
        "MO": "MACAU",
        "TW": "TAIWAN",
    }

    for region in [
        "HK",
        "MO",
        "TW",
    ]:

        print("")
        print(
            "======================================"
        )

        print(
            f"{region_names[region]}"
        )

        print(
            "======================================"
        )

        found = {}

        for entry in entries:

            if (
                entry["region"]
                != region
            ):
                continue

            name = decode_name(
                entry["name"]
            )

            if not name:
                continue

            if name not in found:

                found[name] = {
                    "sources": set(),
                    "urls": set(),
                }

            found[name][
                "sources"
            ].add(
                entry["source_id"]
            )

            found[name][
                "urls"
            ].add(
                entry["url"]
            )

        if not found:

            print(
                "No candidates found."
            )
            continue

        for name in sorted(
            found.keys(),
            key=lambda x: x.upper()
        ):

            info = found[name]

            sources = ", ".join(
                sorted(
                    info["sources"]
                )
            )

            print("")
            print(
                f"CHANNEL: {name}"
            )

            print(
                f"  SOURCE : {sources}"
            )

            print(
                f"  LINES  : "
                f"{len(info['urls'])}"
            )

        print("")
        print(
            f"TOTAL {region}: "
            f"{len(found)} "
            f"candidate names"
        )


# ============================================================
# Validate
# ============================================================

def validate_output():

    print("")
    print("[5/6] Validate output")

    if not OUTPUT.exists():

        print(
            "ERROR: output missing"
        )

        sys.exit(1)

    size = OUTPUT.stat().st_size

    if size < 50:

        print(
            "ERROR: output too small"
        )

        sys.exit(1)

    data = OUTPUT.read_bytes()

    if not data.startswith(
        b"#EXTM3U"
    ):

        print(
            "ERROR: invalid M3U"
        )

        sys.exit(1)

    print(
        f"OK: {size:,} bytes"
    )


# ============================================================
# Cleanup
# ============================================================

def cleanup():

    if not TEMP_DIR.exists():
        return

    for path in TEMP_DIR.iterdir():

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
# Main
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
        "Region Source Architecture"
    )

    print(
        "CN / HK / MO / TW"
    )

    print(
        "======================================"
    )

    start = time.time()

    logical = 0
    lines = 0

    config = load_config()

    try:

        entries = collect_sources()

        results = match_channels(
            config,
            entries
        )

        logical, lines = (
            write_output(
                results
            )
        )

        discover_regions(
            entries
        )

        validate_output()

    finally:

        cleanup()

    print("")
    print("[6/6] Complete")

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
        "CN sources : "
        "cs3306 + best-fan"
    )

    print(
        "HK source  : "
        "iptv-org HK"
    )

    print(
        "MO source  : "
        "iptv-org MO"
    )

    print(
        "TW source  : "
        "iptv-org TW"
    )

    print(
        "Publish    : GitHub RAW"
    )

    print(
        "======================================"
    )


if __name__ == "__main__":
    main()
