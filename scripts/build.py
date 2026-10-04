from pathlib import Path
import subprocess
import time
import sys
import json
import re
from concurrent.futures import ThreadPoolExecutor, as_completed


VERSION = "0.9.5"

ROOT = Path(__file__).resolve().parent.parent
CONFIG = ROOT / "config" / "channels.json"
OUTPUT = ROOT / "tvatlas.m3u"
DIAGNOSTICS = ROOT / "tvatlas-diagnostics.json"
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
        "id": "iptvorg-jp",
        "region": "JP",
        "priority": 10,
        "url": "https://iptv-org.github.io/iptv/countries/jp.m3u",
    },
    {
        "id": "iptvorg-kr",
        "region": "KR",
        "priority": 10,
        "url": "https://iptv-org.github.io/iptv/countries/kr.m3u",
    },
    {
        "id": "iptvorg-sg",
        "region": "SG",
        "priority": 10,
        "url": "https://iptv-org.github.io/iptv/countries/sg.m3u",
    },
    {
        "id": "iptvorg-eastasia-kr",
        "region": "KR",
        "priority": 20,
        "url": "https://iptv-org.github.io/iptv/regions/eas.m3u",
    },
    {
        "id": "iptvorg-sea-sg",
        "region": "SG",
        "priority": 15,
        "url": "https://iptv-org.github.io/iptv/regions/sea.m3u",
    },
    {
        "id": "iptvorg-asean-sg",
        "region": "SG",
        "priority": 20,
        "url": "https://iptv-org.github.io/iptv/regions/asean.m3u",
    },
    {
        "id": "iptvorg-apac-sg",
        "region": "SG",
        "priority": 30,
        "url": "https://iptv-org.github.io/iptv/regions/apac.m3u",
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
        .rsplit(b",", 1)[1]
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


def build_recovery_sources():
    """Create small, explicit recovery playlists for channels missing from upstream aggregators."""
    TEMP_DIR.mkdir(parents=True, exist_ok=True)
    recovery = TEMP_DIR / "recovery-cn.m3u"
    recovery.write_text(
        '#EXTM3U\n'
        '#EXTINF:-1 group-title="Recovery",海南卫视\n'
        'http://ottrrs.hl.chinamobile.com/PLTV/88888888/224/3221226465/index.m3u8\n',
        encoding="utf-8"
    )
    sg_recovery = TEMP_DIR / "recovery-sg.m3u"
    sg_recovery.write_text(
        '#EXTM3U\n'
        '#EXTINF:-1 group-title="Recovery",CNA\n'
        'https://mediacorp-videosbclive.akamaized.net/dd724cfb0e8e4cdc921bbc4ac94614bf/ap-southeast-1/6057994443001/profile_1/chunklist.m3u8\n'
        '#EXTINF:-1 group-title="Recovery",Channel 5\n'
        'https://dlau142f16b92.cloudfront.net/hls/ch5ctv/master02.m3u8\n'
        '#EXTINF:-1 group-title="Recovery",Channel 8\n'
        'https://d34e90s3s13i7n.cloudfront.net/hls/ch8ctv/master02.m3u8\n'
        '#EXTINF:-1 group-title="Recovery",Vasantham\n'
        'https://d39v9xz8f7n8tk.cloudfront.net/hls/vsnthmctv/master02.m3u8\n',
        encoding="utf-8"
    )
    return [{
        "id": "recovery-cn",
        "region": "CN",
        "priority": 50,
        "path": recovery,
    }, {
        "id": "recovery-sg",
        "region": "SG",
        "priority": 50,
        "path": sg_recovery,
    }]


def collect_sources():

    print("")
    print(
        "[1/6] Download sources"
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

    for recovery in build_recovery_sources():
        source_entries = parse_playlist(recovery["path"], recovery)
        print(f"OK   {recovery['id']} [{recovery['region']}] {len(source_entries)} entries")
        entries.extend(source_entries)

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



HEALTH_WORKERS = 16
HEALTH_CONNECT_TIMEOUT = 3
HEALTH_MAX_TIME = 6


def check_stream(entry):
    """Fast bounded probe. It only ranks candidates; it never deletes the last fallback."""
    url = entry["url"].decode("utf-8", errors="ignore")
    started = time.time()
    cmd = [
        "curl", "-L", "--silent", "--show-error",
        "--connect-timeout", str(HEALTH_CONNECT_TIMEOUT),
        "--max-time", str(HEALTH_MAX_TIME),
        "--range", "0-16383",
        "-A", "Mozilla/5.0 TVAtlas/0.8",
        url,
    ]
    try:
        proc = subprocess.run(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            timeout=HEALTH_MAX_TIME + 2,
            check=False,
        )
        data = proc.stdout[:32768]
        elapsed = time.time() - started
        if not data:
            return False, elapsed, "empty"

        stripped = data.lstrip()
        lower = stripped[:512].lower()

        # Reject obvious web error pages.
        if lower.startswith(b"<!doctype html") or lower.startswith(b"<html"):
            return False, elapsed, "html"

        # HLS playlists must look like HLS, not merely return HTTP 200.
        if b".m3u8" in entry["url"].lower() or stripped.startswith(b"#EXTM3U"):
            ok = stripped.startswith(b"#EXTM3U") and (
                b"#EXT-X-" in data or b"#EXTINF:" in data
            )
            return ok, elapsed, "hls" if ok else "invalid-hls"

        # Direct MPEG-TS / other stream: receiving a meaningful payload is enough
        # for this lightweight build-time ranking probe.
        if len(data) >= 188:
            return True, elapsed, "data"

        return False, elapsed, "short"

    except Exception as exc:
        return False, time.time() - started, type(exc).__name__


def probe_candidates(results):
    print("")
    print("[3/6] Health selection")
    print("")

    unique = {}
    for result in results:
        for entry in result["candidates"]:
            unique.setdefault(entry["url"], entry)

    print(f"Probe URLs       : {len(unique)}")
    print(f"Workers          : {HEALTH_WORKERS}")
    print(f"Per-URL max time : {HEALTH_MAX_TIME}s")
    print("")

    health = {}
    with ThreadPoolExecutor(max_workers=HEALTH_WORKERS) as pool:
        futures = {
            pool.submit(check_stream, entry): url
            for url, entry in unique.items()
        }
        for future in as_completed(futures):
            url = futures[future]
            try:
                health[url] = future.result()
            except Exception as exc:
                health[url] = (False, 99.0, type(exc).__name__)

    healthy_channels = 0
    fallback_channels = 0
    missing_channels = 0

    for result in results:
        channel = result["channel"]
        candidates = result["candidates"]
        healthy = []

        for entry in candidates:
            ok, elapsed, reason = health.get(
                entry["url"], (False, 99.0, "not-probed")
            )
            if ok:
                healthy.append((entry["priority"], elapsed, entry, reason))

        if healthy:
            healthy.sort(key=lambda item: (item[0], item[1]))
            selected = healthy[0][2]
            result["selected"] = selected
            result["health"] = "healthy"
            result["probe_results"] = {entry["url"]: health.get(entry["url"], (False, 99.0, "not-probed")) for entry in candidates}
            healthy_channels += 1
            print(
                f"HEALTHY : {channel['name']} "
                f"[{selected['source_id']}] "
                f"{healthy[0][1]:.2f}s "
                f"healthy={len(healthy)}/{len(candidates)}"
            )
        elif candidates:
            # Important: GitHub runners may be geo-blocked while the user's
            # player is not. Never erase a logical channel solely because the
            # runner could not verify it.
            selected = candidates[0]
            result["selected"] = selected
            result["health"] = "fallback-unverified"
            result["probe_results"] = {entry["url"]: health.get(entry["url"], (False, 99.0, "not-probed")) for entry in candidates}
            fallback_channels += 1
            print(
                f"FALLBACK: {channel['name']} "
                f"[{selected['source_id']}] "
                f"0/{len(candidates)} verified"
            )
        else:
            result["selected"] = None
            result["health"] = "missing"
            result["probe_results"] = {}
            missing_channels += 1
            print(f"MISS    : {channel['name']}")

    print("")
    print(
        f"Health summary   : healthy={healthy_channels} "
        f"fallback={fallback_channels} missing={missing_channels}"
    )
    return results


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
        .rsplit(b",", 1)[0]
    )

    return (
        metadata
        + b","
        + name_bytes
    )


def match_channels(config, entries):
    print("")
    print("[2/6] Match channels")
    print("")

    results = []
    ordered = sorted(entries, key=lambda x: x["priority"])

    for channel in config["channels"]:
        region = channel.get("region", "CN")
        candidates = []

        for entry in ordered:
            if entry["region"] != region:
                continue
            if channel_matches(entry["name"], channel):
                candidates.append(entry)

        if not candidates and region == "CN":
            for entry in ordered:
                if channel_matches(entry["name"], channel):
                    candidates.append(entry)

        unique = []
        seen = set()
        for item in candidates:
            if item["url"] in seen:
                continue
            seen.add(item["url"])
            unique.append(item)

        results.append({
            "channel": channel,
            "candidates": unique,
            "selected": None,
            "candidate_count": len(unique),
        })

        print(
            f"{'FOUND' if unique else 'MISS '} : "
            f"{channel['name']} candidates={len(unique)}"
        )

    return results

def write_diagnostics(results):
    report = {
        "version": VERSION,
        "generated_at_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "summary": {"healthy": 0, "fallback-unverified": 0, "missing": 0},
        "channels": []
    }
    for result in results:
        status = result.get("health", "missing")
        report["summary"][status] = report["summary"].get(status, 0) + 1
        selected = result.get("selected")
        candidates = []
        for entry in result.get("candidates", []):
            candidates.append({
                "source": entry["source_id"],
                "priority": entry["priority"],
                "url": entry["url"].decode("utf-8", errors="replace"),
                "selected": bool(selected and entry["url"] == selected["url"]),
                "probe_ok": bool(result.get("probe_results", {}).get(entry["url"], (False, 0, "not-probed"))[0]),
                "probe_seconds": round(float(result.get("probe_results", {}).get(entry["url"], (False, 0, "not-probed"))[1]), 3),
                "probe_reason": result.get("probe_results", {}).get(entry["url"], (False, 0, "not-probed"))[2]
            })
        report["channels"].append({
            "id": result["channel"]["id"],
            "name": result["channel"]["name"],
            "region": result["channel"].get("region", "CN"),
            "status": status,
            "candidate_count": len(candidates),
            "candidates": candidates
        })
    DIAGNOSTICS.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\\n", encoding="utf-8")
    print(f"Diagnostics       : {DIAGNOSTICS.name}")


def write_playlist(results):

    print("")
    print(
        "[4/6] Generate playlist"
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
        "[5/6] Validate"
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
        "CN / HK / MO / TW / JP / KR / SG"
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

        results = probe_candidates(results)

        write_diagnostics(results)

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
        "[6/6] Complete"
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
