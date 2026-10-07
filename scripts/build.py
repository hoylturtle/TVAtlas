from pathlib import Path
import subprocess
import time
import sys
import json
import re
from urllib.parse import urljoin
from concurrent.futures import ThreadPoolExecutor, as_completed


VERSION = "1.0.5"

ROOT = Path(__file__).resolve().parent.parent
CONFIG = ROOT / "config" / "channels.json"
OUTPUT = ROOT / "tvatlas.m3u"
ORANGE_OUTPUT = ROOT / "tvatlas-orange.m3u"
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
        "id": "hk-auto-updater",
        "region": "HK",
        "priority": 5,
        "url": "https://raw.githubusercontent.com/zhujiefeng0402/hk-iptv/main/hk_live.m3u",
    },
    {
        "id": "myiptv-ipv4",
        "region": "CN",
        "priority": 12,
        "url": "https://raw.githubusercontent.com/suxuang/myIPTV/refs/heads/main/ipv4.m3u",
    },
    {
        "id": "yang-gather",
        "region": "CN",
        "priority": 18,
        "url": "https://tv.iill.top/m3u/Gather",
    },
    {
        "id": "free-tv-hk",
        "region": "HK",
        "priority": 6,
        "url": "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlists/playlist_hong_kong.m3u8",
    },
    {
        "id": "guovin-hk-pool",
        "region": "HK",
        "priority": 7,
        "url": "https://raw.githubusercontent.com/Guovin/iptv-api/gd/output/result.m3u",
    },
    {
        "id": "yuechan-global",
        "region": "HK",
        "priority": 8,
        "url": "https://raw.githubusercontent.com/YueChan/Live/main/Global.m3u",
    },
    {
        "id": "kimentanm-aptv",
        "region": "HK",
        "priority": 9,
        "url": "https://raw.githubusercontent.com/Kimentanm/aptv/master/m3u/iptv.m3u",
    },
    {
        "id": "myiptv-hk-dynamic",
        "region": "HK",
        "priority": 11,
        "url": "https://raw.githubusercontent.com/suxuang/myIPTV/refs/heads/main/ipv4.m3u",
    },
    {
        "id": "yang-hk-dynamic",
        "region": "HK",
        "priority": 12,
        "url": "https://tv.iill.top/m3u/Gather",
    },
    {
        "id": "vip-weizhen-hk-dynamic",
        "region": "HK",
        "priority": 13,
        "url": "https://raw.githubusercontent.com/vip-weizhen/tvlist/main/test.txt",
    },
    {
        "id": "judy-gotv-hk-dynamic",
        "region": "HK",
        "priority": 14,
        "url": "https://raw.githubusercontent.com/judy-gotv/iptv/main/smart.m3u",
    },
    {
        "id": "s14685-hk-dynamic",
        "region": "HK",
        "priority": 15,
        "url": "https://raw.githubusercontent.com/s14685/tv/main/jade.m3u8",
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
        "id": "iptvorg-eastasia-hk",
        "region": "HK",
        "priority": 20,
        "url": "https://iptv-org.github.io/iptv/regions/eas.m3u",
    },
    {
        "id": "iptvorg-apac-hk",
        "region": "HK",
        "priority": 30,
        "url": "https://iptv-org.github.io/iptv/regions/apac.m3u",
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


def url_conflicts_with_channel(url, channel):
    """Reject obvious upstream mislabels such as a CCTV-12 URL named CCTV-1."""
    wanted = config_cctv(channel)
    if not wanted:
        return False
    text = url.decode("utf-8", errors="ignore").upper()
    m = re.search(r"CCTV[-_]?([0-9]{1,2})(PLUS|\+)?", text)
    if not m:
        return False
    found = m.group(1) + ("+" if m.group(2) else "")
    return found != wanted


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
    hk_stable = TEMP_DIR / "stable-hk.m3u"
    hk_stable.write_text(
        '#EXTM3U\n'
        '#EXTINF:-1 group-title="HK Stable",RTHK TV 31\n'
        'https://www.rthk.hk/feeds/dtt/rthktv31_https.m3u8\n'
        '#EXTINF:-1 group-title="HK Stable",RTHK TV 32\n'
        'https://www.rthk.hk/feeds/dtt/rthktv32_https.m3u8\n'
        '#EXTINF:-1 group-title="HK Stable",RTHK TV 33\n'
        'https://rthktv33-live.akamaized.net/hls/live/2101641/RTHKTV33/stream03/streamPlaylist.m3u8\n'
        '#EXTINF:-1 group-title="HK Stable",RTHK TV 34\n'
        'https://rthktv34-live.akamaized.net/hls/live/2101642/RTHKTV34/stream02/streamPlaylist.m3u8\n'
        '#EXTINF:-1 group-title="HK Stable",HOY TV\n'
        'https://hoytv-live-stream.hoy.tv/ch77/index-fhd.m3u8\n'
        '#EXTINF:-1 group-title="HK Stable",HOY Infotainment\n'
        'https://hoytv-live-stream.hoy.tv/ch78/index-fhd.m3u8\n',
        encoding="utf-8"
    )
    recovery = TEMP_DIR / "recovery-cn.m3u"
    recovery.write_text(
        '#EXTM3U\n'
        '#EXTINF:-1 group-title="Recovery",海南卫视\n'
        'http://ottrrs.hl.chinamobile.com/PLTV/88888888/224/3221226465/index.m3u8\n',
        encoding="utf-8"
    )
    # Jade is intentionally not pinned here. It is rebuilt from live upstream
    # pools on every Action run so expired relays do not become permanent debt.
    hk_recovery = TEMP_DIR / "recovery-hk.m3u"
    hk_recovery.write_text(
        '#EXTM3U\n'
        '#EXTINF:-1 group-title="HK Recovery",翡翠台\n'
        'http://r.jdshipin.com/qClQf\n'
        '#EXTINF:-1 group-title="HK Recovery",翡翠台\n'
        'http://r.jdshipin.com/n90gt\n',
        encoding="utf-8"
    )
    mo_recovery = TEMP_DIR / "recovery-mo.m3u"
    mo_recovery.write_text(
        '#EXTM3U\n'
        '#EXTINF:-1 group-title="MO Recovery",澳视澳门\n'
        'http://live4.tdm.com.mo:80/ch1/_definst_/ch1.live/playlist.m3u8\n'
        '#EXTINF:-1 group-title="MO Recovery",澳视澳门\n'
        'http://live3.tdm.com.mo:1935/ch1/ch1.live/playlist.m3u8\n',
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
        "id": "stable-hk",
        "region": "HK",
        "priority": 1,
        "path": hk_stable,
    }, {
        "id": "recovery-cn",
        "region": "CN",
        "priority": 50,
        "path": recovery,
    }, {
        "id": "recovery-hk",
        "region": "HK",
        "priority": 50,
        "path": hk_recovery,
    }, {
        "id": "recovery-mo",
        "region": "MO",
        "priority": 1,
        "path": mo_recovery,
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



HEALTH_WORKERS = 32
HEALTH_CONNECT_TIMEOUT = 3
HEALTH_MAX_TIME = 8

# URLs confirmed failing in a real TV player. They remain documented here so
# upstream rediscovery cannot immediately promote them again.
PLAYER_BLOCKLIST = {
    "http://59.120.242.104:9000/live/live2.m3u8",  # real-device failure: Mei Ah Movie
    "https://live.tvfix.org/live/mydy/index.m3u8",  # real-device failure: Mei Ah Movie
    "http://103.172.187.30:12000/stream/mytv/null-1/master.m3u8",
    "http://120.84.96.28:808/hls/25/index.m3u8",
    "http://r.jdshipin.com/GeWKr",
    "http://r.jdshipin.com/qClQf",
    "http://live4.tdm.com.mo:80/ch1/_definst_/ch1.live/playlist.m3u8",
}


def curl_bytes(url, max_time=HEALTH_MAX_TIME, byte_range="0-65535"):
    cmd = [
        "curl", "-L", "--fail", "--silent", "--show-error",
        "--connect-timeout", str(HEALTH_CONNECT_TIMEOUT),
        "--max-time", str(max_time),
        "-A", f"Mozilla/5.0 TVAtlas/{VERSION}",
    ]
    if byte_range:
        cmd += ["--range", byte_range]
    cmd.append(url)
    proc = subprocess.run(
        cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        timeout=max_time + 2,
        check=False,
    )
    return proc.returncode, proc.stdout[:131072]


def first_hls_uri(text, base_url, want_variant=False):
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    if want_variant:
        for i, line in enumerate(lines):
            if line.startswith("#EXT-X-STREAM-INF"):
                for nxt in lines[i + 1:]:
                    if not nxt.startswith("#"):
                        return urljoin(base_url, nxt)
    for line in lines:
        if not line.startswith("#"):
            return urljoin(base_url, line)
    return None


def check_stream(entry):
    """Playback-oriented probe: playlist -> media playlist -> real media bytes."""
    url = entry["url"].decode("utf-8", errors="ignore")
    started = time.time()

    if url in PLAYER_BLOCKLIST:
        return False, 0.0, "player-blocklisted"

    # Real-device feedback has higher value than runner latency. This Jade relay
    # renders video but produced no audio in the target player, so never promote
    # it as the universal primary again.
    if url == "http://r.jdshipin.com/GeWKr":
        return False, 0.0, "player-audio-incompatible"

    try:
        code, data = curl_bytes(url)
        elapsed = time.time() - started
        if code != 0 or not data:
            return False, elapsed, "empty-or-http-error"

        stripped = data.lstrip()
        lower = stripped[:512].lower()
        if lower.startswith(b"<!doctype html") or lower.startswith(b"<html"):
            return False, elapsed, "html"

        is_hls = ".m3u8" in url.lower() or stripped.startswith(b"#EXTM3U")
        if not is_hls:
            return (len(data) >= 188), elapsed, "media-bytes" if len(data) >= 188 else "short"

        if not stripped.startswith(b"#EXTM3U"):
            return False, elapsed, "invalid-hls"

        text = data.decode("utf-8", errors="ignore")
        media_url = url

        # Master playlist: follow one variant before looking for a segment.
        if "#EXT-X-STREAM-INF" in text:
            variant = first_hls_uri(text, url, want_variant=True)
            if not variant:
                return False, time.time() - started, "master-no-variant"
            code, variant_data = curl_bytes(variant)
            if code != 0 or not variant_data:
                return False, time.time() - started, "variant-unreachable"
            variant_text = variant_data.decode("utf-8", errors="ignore")
            if not variant_text.lstrip().startswith("#EXTM3U"):
                return False, time.time() - started, "invalid-variant"
            text = variant_text
            media_url = variant

        segment = first_hls_uri(text, media_url, want_variant=False)
        if not segment:
            return False, time.time() - started, "media-no-segment"

        code, segment_data = curl_bytes(segment, max_time=HEALTH_MAX_TIME, byte_range="0-65535")
        elapsed = time.time() - started
        if code != 0 or not segment_data:
            return False, elapsed, "segment-unreachable"
        if segment_data.lstrip()[:64].lower().startswith((b"<html", b"<!doctype")):
            return False, elapsed, "segment-html"
        if len(segment_data) < 188:
            return False, elapsed, "segment-short"

        return True, elapsed, "hls-segment"

    except subprocess.TimeoutExpired:
        return False, time.time() - started, "timeout"
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
            # Keep all verified candidates ordered for players that merge
            # duplicate channel names into automatic failover lines.
            result["ranked"] = [item[2] for item in healthy]
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
            # GitHub runners may be geo-blocked, but a URL explicitly confirmed
            # broken in a real player must never be emitted as a fallback.
            eligible = [
                entry for entry in candidates
                if entry["url"].decode("utf-8", errors="ignore") not in PLAYER_BLOCKLIST
            ]
            selected = eligible[0] if eligible else None
            result["selected"] = selected
            # Dynamic channels keep the freshly discovered eligible pool. The
            # TV/player can still succeed where GitHub's runner is geo-blocked.
            result["ranked"] = eligible
            result["health"] = "fallback-unverified" if selected else "missing-player-blocked"
            result["probe_results"] = {entry["url"]: health.get(entry["url"], (False, 99.0, "not-probed")) for entry in candidates}
            if selected:
                fallback_channels += 1
                print(
                    f"FALLBACK: {channel['name']} "
                    f"[{selected['source_id']}] "
                    f"0/{len(candidates)} verified"
                )
                if channel["id"] == "hk-jade":
                    for entry in candidates:
                        ok, elapsed, reason = health.get(entry["url"], (False, 99.0, "not-probed"))
                        print(
                            f"JADE-PROBE: source={entry['source_id']} "
                            f"ok={ok} time={elapsed:.2f}s reason={reason} "
                            f"url={entry['url'].decode('utf-8', errors='replace')}"
                        )
            else:
                missing_channels += 1
                print(f"BLOCKED : {channel['name']} no eligible fallback")
        else:
            result["selected"] = None
            result["ranked"] = []
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

    # Upstream EPG metadata can be wrong even when the display name matches
    # (we observed CCTV-1 carrying CCTV12 metadata). Prefer the curated TVAtlas
    # display name over poisoned upstream tvg-name/tvg-id fields.
    if name.upper().startswith("CCTV-"):
        metadata = re.sub(rb'\s+tvg-name="[^"]*"', b"", metadata)
        metadata = re.sub(rb'\s+tvg-id="[^"]*"', b"", metadata)

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
                if not url_conflicts_with_channel(entry["url"], channel):
                    candidates.append(entry)

        if not candidates and region == "CN":
            for entry in ordered:
                if channel_matches(entry["name"], channel):
                    if not url_conflicts_with_channel(entry["url"], channel):
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
    DIAGNOSTICS.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Diagnostics       : {DIAGNOSTICS.name}")


def build_playlist(results, orange=False):
    output = [b"#EXTM3U"]
    logical = 0
    region_counts = {}
    for result in results:
        selected = result["selected"]
        if not selected:
            continue
        channel = result["channel"]
        def emit(entry):
            output.append(rewrite_extinf(entry["extinf"], channel["name"], channel["group"]))
            output.extend(entry["extras"])
            output.append(entry["url"])
        emit(selected)
        if orange:
            region = channel.get("region", "CN")
            is_cctv = config_cctv(channel) is not None
            if region == "HK" or is_cctv:
                emitted = {selected["url"]}
                max_lines = 4 if is_cctv else 3
                for alternate in result.get("ranked", []):
                    if len(emitted) >= max_lines:
                        break
                    if alternate["url"] in emitted:
                        continue
                    if is_cctv and url_conflicts_with_channel(alternate["url"], channel):
                        continue
                    emit(alternate)
                    emitted.add(alternate["url"])
        logical += 1
        region = channel.get("region", "CN")
        region_counts[region] = region_counts.get(region, 0) + 1
    return output, logical, region_counts


def write_playlists(results):
    print("")
    print("[4/6] Generate playlists")
    standard, logical, region_counts = build_playlist(results, orange=False)
    orange, _, _ = build_playlist(results, orange=True)
    OUTPUT.write_bytes(b"\n".join(standard) + b"\n")
    ORANGE_OUTPUT.write_bytes(b"\n".join(orange) + b"\n")
    standard_lines = sum(1 for x in standard if x and not x.startswith(b"#"))
    orange_lines = sum(1 for x in orange if x and not x.startswith(b"#"))
    print(f"Standard lines   : {standard_lines}")
    print(f"Orange lines     : {orange_lines}")
    return logical, region_counts, standard_lines, orange_lines


def validate():
    print("")
    print("[5/6] Validate")
    for path in (OUTPUT, ORANGE_OUTPUT):
        if not path.exists() or not path.read_bytes().startswith(b"#EXTM3U"):
            print(f"ERROR: invalid playlist {path.name}")
            sys.exit(1)
    standard = OUTPUT.read_text(encoding="utf-8", errors="replace")
    orange = ORANGE_OUTPUT.read_text(encoding="utf-8", errors="replace")
    # Count channel records structurally. Do not search for a literal escaped
    # "\\n": playlists contain real newline characters.
    def count_channel(text, display_name):
        return sum(
            1 for line in text.splitlines()
            if line.startswith("#EXTINF:") and line.rsplit(",", 1)[-1].strip() == display_name
        )

    standard_cctv1 = count_channel(standard, "CCTV-1 综合")
    orange_cctv1 = count_channel(orange, "CCTV-1 综合")
    print(f"Standard CCTV-1 lines: {standard_cctv1}")
    print(f"Orange CCTV-1 lines  : {orange_cctv1}")
    if standard_cctv1 != 1:
        print("ERROR: standard playlist must emit exactly one CCTV-1")
        sys.exit(1)
    if orange_cctv1 < 2:
        print("ERROR: Orange multi-line CCTV-1 output missing")
        sys.exit(1)
    print(f"OK standard: {OUTPUT.stat().st_size:,} bytes")
    print(f"OK orange  : {ORANGE_OUTPUT.stat().st_size:,} bytes")


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

        logical, regions, standard_lines, orange_lines = write_playlists(results)

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
        f"Standard lines   : "
        f"{standard_lines}"
    )

    print(
        f"Orange lines     : "
        f"{orange_lines}"
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
