from pathlib import Path
import subprocess
import time
import sys
import json
import re


VERSION = "0.7.1"

ROOT = Path(__file__).resolve().parent.parent
CONFIG = ROOT / "config" / "channels.json"
OUTPUT = ROOT / "tvatlas.m3u"
TEMP_DIR = ROOT / ".tvatlas_temp"


SOURCES = [

    {
        "id": "cs3306",
        "region": "CN",
        "priority": 1,
        "url":
        "https://raw.githubusercontent.com/"
        "cs3306/IPTV-Sources/main/data/output/"
        "iptv_collection.m3u"
    },

    {
        "id": "bestfan-all",
        "region": "CN",
        "priority": 2,
        "url":
        "https://raw.githubusercontent.com/"
        "best-fan/iptv-sources/main/cn_all.m3u8"
    },

    {
        "id": "bestfan-cctv",
        "region": "CN",
        "priority": 3,
        "url":
        "https://raw.githubusercontent.com/"
        "best-fan/iptv-sources/main/cn_cctv.m3u8"
    },

    {
        "id": "bestfan-province",
        "region": "CN",
        "priority": 4,
        "url":
        "https://raw.githubusercontent.com/"
        "best-fan/iptv-sources/main/cn_province.m3u8"
    },

    {
        "id": "iptvorg-hk",
        "region": "HK",
        "priority": 10,
        "url":
        "https://iptv-org.github.io/"
        "iptv/countries/hk.m3u"
    },

    {
        "id": "iptvorg-mo",
        "region": "MO",
        "priority": 10,
        "url":
        "https://iptv-org.github.io/"
        "iptv/countries/mo.m3u"
    },

    {
        "id": "iptvorg-tw",
        "region": "TW",
        "priority": 10,
        "url":
        "https://iptv-org.github.io/"
        "iptv/countries/tw.m3u"
    }
]


def load_config():

    try:

        with CONFIG.open(
            "r",
            encoding="utf-8"
        ) as f:

            return json.load(f)

    except Exception as e:

        print(
            f"ERROR config: {e}"
        )

        sys.exit(1)


def download_source(source):

    TEMP_DIR.mkdir(
        parents=True,
        exist_ok=True
    )

    path = (
        TEMP_DIR
        / f"{source['id']}.m3u"
    )

    cmd = [
        "curl",
        "-L",
        "--fail",
        "--silent",
        "--show-error",
        "--connect-timeout",
        "8",
        "--max-time",
        "30",
        "--retry",
        "1",
        "-o",
        str(path),
        source["url"]
    ]

    start = time.time()

    try:

        subprocess.run(
            cmd,
            check=True,
            timeout=40
        )

    except Exception as e:

        print(
            f"WARN {source['id']}: {e}"
        )

        return None

    if (
        not path.exists()
        or path.stat().st_size < 50
    ):

        print(
            f"WARN {source['id']}: "
            f"invalid file"
        )

        return None

    print(
        f"OK   {source['id']} "
        f"[{source['region']}] "
        f"{path.stat().st_size:,} bytes "
        f"{time.time()-start:.1f}s"
    )

    return path


def get_name(extinf):

    if b"," not in extinf:
        return b""

    return (
        extinf
        .split(b",", 1)[1]
        .strip()
    )


def clean_text(value):

    value = re.sub(
        rb"\[[^\]]*\]",
        b"",
        value
    )

    value = re.sub(
        rb"\([^\)]*(?:720|1080|2160|4K|576|480)[^\)]*\)",
        b"",
        value,
        flags=re.I
    )

    value = re.sub(
        rb"\s+",
        b"",
        value
    )

    return value.upper()


def extract_cctv(name):

    value = clean_text(name)

    match = re.match(
        rb"^CCTV-?([0-9]{1,2})(\+?)",
        value
    )

    if not match:
        return None

    number = (
        match.group(1)
        .decode("ascii")
    )

    if match.group(2):
        return number + "+"

    return number


def config_cctv(channel):

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


def channel_matches(
    source_name,
    channel
):

    wanted_cctv = config_cctv(
        channel
    )

    if wanted_cctv:

        return (
            extract_cctv(source_name)
            == wanted_cctv
        )

    source = clean_text(
        source_name
    )

    for alias in channel.get(
        "match",
        []
    ):

        target = clean_text(
            alias.encode("utf-8")
        )

        if source == target:
            return True

    return False


def parse_playlist(
    path,
    source
):

    lines = (
        path
        .read_bytes()
        .splitlines()
    )

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
                    url
                }
            )

        i = max(
            j + 1,
            i + 1
        )

    return entries


def collect_sources():

    print("")
    print(
        "[1/5] Download sources"
    )
    print("")

    entries = []

    for source in sorted(
        SOURCES,
        key=lambda x: x["priority"]
    ):

        path = download_source(
            source
        )

        if not path:
            continue

        source_entries = (
            parse_playlist(
                path,
                source
            )
        )

        print(
            f"     "
            f"{len(source_entries)} entries"
        )

        entries.extend(
            source_entries
        )

    if not entries:

        print(
            "ERROR: no source data"
        )

        sys.exit(1)

    print("")
    print(
        f"Total candidates: "
        f"{len(entries)}"
    )

    return entries


def rewrite_extinf(
    extinf,
    name,
    group
):

    extinf = re.sub(
        rb"(?i)cctv",
        b"CCTV",
        extinf
    )

    group_bytes = (
        group.encode("utf-8")
    )

    name_bytes = (
        name.encode("utf-8")
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

    metadata = (
        extinf
        .split(b",", 1)[0]
    )

    return (
        metadata
        + b","
        + name_bytes
    )


def match_channels(
    config,
    entries
):

    print("")
    print(
        "[2/5] Match channels"
    )
    print("")

    results = []

    ordered = sorted(
        entries,
        key=lambda x: x["priority"]
    )

    for channel in config[
        "channels"
    ]:

        region = channel.get(
            "region",
            "CN"
        )

        candidates = []

        # =================================
        # 第一轮
        # 只找相同地区
        # =================================

        for entry in ordered:

            if (
                entry["region"]
                != region
            ):
                continue

            if channel_matches(
                entry["name"],
                channel
            ):

                candidates.append(
                    entry
                )

        # =================================
        # CN 特殊 fallback
        #
        # CCTV 等仍然允许 CN 多源
        # =================================

        if (
            not candidates
            and region == "CN"
        ):

            for entry in ordered:

                if channel_matches(
                    entry["name"],
                    channel
                ):

                    candidates.append(
                        entry
                    )

        # =================================
        # URL 去重
        # =================================

        unique = []
        seen = set()

        for item in candidates:

            if item["url"] in seen:
                continue

            seen.add(
                item["url"]
            )

            unique.append(
                item
            )

        candidates = unique

        # =================================
        # 当前正式版本
        #
        # 只发布优先级最高的一条
        # =================================

        selected = (
            candidates[0]
            if candidates
            else None
        )

        results.append(
            {
                "channel":
                channel,

                "selected":
                selected,

                "candidate_count":
                len(candidates)
            }
        )

        if selected:

            print(
                f"FOUND: "
                f"{channel['name']} "
                f"[{selected['source_id']}] "
                f"candidates="
                f"{len(candidates)}"
            )

        else:

            print(
                f"MISS : "
                f"{channel['name']}"
            )

    return results


def write_playlist(results):

    print("")
    print(
        "[3/5] Generate playlist"
    )

    output = [
        b"#EXTM3U"
    ]

    logical = 0

    region_counts = {
        "CN": 0,
        "HK": 0,
        "MO": 0,
        "TW": 0
    }

    for result in results:

        selected = result[
            "selected"
        ]

        if not selected:
            continue

        channel = result[
            "channel"
        ]

        output.append(
            rewrite_extinf(
                selected["extinf"],
                channel["name"],
                channel["group"]
            )
        )

        output.extend(
            selected["extras"]
        )

        output.append(
            selected["url"]
        )

        logical += 1

        region = channel.get(
            "region",
            "CN"
        )

        region_counts[
            region
        ] = (
            region_counts
            .get(region, 0)
            + 1
        )

    OUTPUT.write_bytes(
        b"\n".join(output)
        + b"\n"
    )

    return (
        logical,
        region_counts
    )


def validate():

    print("")
    print(
        "[4/5] Validate"
    )

    if not OUTPUT.exists():

        print(
            "ERROR: output missing"
        )

        sys.exit(1)

    data = OUTPUT.read_bytes()

    if not data.startswith(
        b"#EXTM3U"
    ):

        print(
            "ERROR: invalid playlist"
        )

        sys.exit(1)

    print(
        f"OK: "
        f"{len(data):,} bytes"
    )


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

    except Exception:
        pass


def main():

    print("")
    print(
        "===================================="
    )
    print(
        f"TVAtlas v{VERSION}"
    )
    print(
        "Curated Regional Playlist"
    )
    print(
        "CN / HK / MO / TW"
    )
    print(
        "===================================="
    )

    start = time.time()

    try:

        config = load_config()

        entries = collect_sources()

        results = match_channels(
            config,
            entries
        )

        logical, regions = (
            write_playlist(
                results
            )
        )

        validate()

    finally:

        cleanup()

    print("")
    print(
        "[5/5] Complete"
    )

    print(
        "------------------------------------"
    )

    print(
        f"Logical channels : "
        f"{logical}"
    )

    print(
        f"CN               : "
        f"{regions.get('CN', 0)}"
    )

    print(
        f"HK               : "
        f"{regions.get('HK', 0)}"
    )

    print(
        f"MO               : "
        f"{regions.get('MO', 0)}"
    )

    print(
        f"TW               : "
        f"{regions.get('TW', 0)}"
    )

    print(
        f"Stream lines     : "
        f"{logical}"
    )

    print(
        f"Build time       : "
        f"{time.time()-start:.1f}s"
    )

    print(
        "Publish          : "
        "GitHub RAW"
    )

    print(
        "===================================="
    )


if __name__ == "__main__":
    main()
