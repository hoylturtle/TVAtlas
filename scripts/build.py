from pathlib import Path
import subprocess
import time
import sys
import json
import re


# ============================================================
# TVAtlas v0.5
# Config Driven Builder
# ============================================================

VERSION = "0.5"

SOURCE = (
    "https://raw.githubusercontent.com/"
    "cs3306/IPTV-Sources/main/data/output/iptv_collection.m3u"
)

ROOT = Path(__file__).resolve().parent.parent

CONFIG = ROOT / "config" / "channels.json"
TEMP = ROOT / "source.m3u"
OUTPUT = ROOT / "tvatlas.m3u"


# ============================================================
# 加载配置
# ============================================================

def load_config():

    if not CONFIG.exists():

        print("ERROR: config/channels.json not found")
        sys.exit(1)

    with CONFIG.open(
        "r",
        encoding="utf-8"
    ) as f:

        config = json.load(f)

    return config


# ============================================================
# 下载
# ============================================================

def download():

    print("")
    print("[1/5] Download source")

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

        print(
            f"ERROR: curl failed "
            f"({e.returncode})"
        )

        sys.exit(1)

    size = TEMP.stat().st_size

    print(
        f"OK: {size:,} bytes "
        f"in {time.time() - start:.1f}s"
    )


# ============================================================
# 获取频道显示名
# ============================================================

def get_name(extinf):

    if b"," not in extinf:
        return b""

    return extinf.split(
        b",",
        1
    )[1].strip()


# ============================================================
# 名称规范化，仅用于匹配
# ============================================================

def normalize_match_name(name):

    # CCTV 大小写统一
    name = re.sub(
        rb"(?i)cctv",
        b"CCTV",
        name
    )

    # 转大写只影响 ASCII
    return name.upper()


# ============================================================
# 精确匹配
# ============================================================

def channel_matches(
    source_name,
    channel
):

    source = normalize_match_name(
        source_name
    )

    for match_text in channel["match"]:

        target = match_text.encode(
            "utf-8"
        )

        target = normalize_match_name(
            target
        )

        # CCTV 使用前缀边界匹配
        if target.startswith(b"CCTV"):

            pattern = (
                rb"^"
                + re.escape(target)
                + rb"(?:[\s]|$)"
            )

            if re.search(
                pattern,
                source
            ):
                return True

        # 中文卫视允许包含匹配
        else:

            if target in source:
                return True

    return False


# ============================================================
# 解析上游频道
# ============================================================

def parse_source():

    print("")
    print("[2/5] Parse source")

    data = TEMP.read_bytes()

    lines = data.splitlines()

    channels = []

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

            url = candidate

            break

        if url:

            channels.append(
                {
                    "extinf": extinf,
                    "name": get_name(extinf),
                    "extras": extras,
                    "url": url
                }
            )

        i = max(
            j + 1,
            i + 1
        )

    print(
        f"Source entries: {len(channels)}"
    )

    return channels


# ============================================================
# 重写 EXTINF
#
# 只处理：
# group-title
# 最终频道名
#
# 其他 tvg-id / tvg-logo 等全部保留
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

    group_bytes = group.encode(
        "utf-8"
    )

    name_bytes = display_name.encode(
        "utf-8"
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

    # --------------------------------
    # 最终显示名称
    # --------------------------------

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
# 构建
# ============================================================

def build(
    config,
    source_channels
):

    print("")
    print("[3/5] Match channels")

    max_lines = config[
        "settings"
    ].get(
        "max_lines_per_channel",
        2
    )

    result = []

    for wanted in config["channels"]:

        matches = []

        seen_urls = set()

        for source in source_channels:

            if not channel_matches(
                source["name"],
                wanted
            ):
                continue

            if source["url"] in seen_urls:
                continue

            seen_urls.add(
                source["url"]
            )

            matches.append(
                source
            )

            if len(matches) >= max_lines:
                break

        result.append(
            {
                "config": wanted,
                "sources": matches
            }
        )

        if matches:

            print(
                f"FOUND: "
                f"{wanted['name']} "
                f"({len(matches)})"
            )

        else:

            print(
                f"MISS : "
                f"{wanted['name']}"
            )

    return result


# ============================================================
# 输出
# ============================================================

def write_output(result):

    print("")
    print("[4/5] Generate playlist")

    lines = [
        b"#EXTM3U"
    ]

    total = 0

    for item in result:

        wanted = item["config"]

        for source in item["sources"]:

            extinf = rewrite_extinf(
                source["extinf"],
                wanted["name"],
                wanted["group"]
            )

            lines.append(
                extinf
            )

            lines.extend(
                source["extras"]
            )

            lines.append(
                source["url"]
            )

            total += 1

    playlist = (
        b"\n".join(lines)
        + b"\n"
    )

    OUTPUT.write_bytes(
        playlist
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
        "Config Driven Builder"
    )
    print(
        "======================================"
    )

    start = time.time()

    config = load_config()

    try:

        download()

        source_channels = (
            parse_source()
        )

        result = build(
            config,
            source_channels
        )

        total = write_output(
            result
        )

        if total == 0:

            print(
                "ERROR: no channels generated"
            )

            sys.exit(1)

    finally:

        if TEMP.exists():
            TEMP.unlink()

    print("")
    print("[5/5] Complete")
    print(
        "--------------------------------------"
    )
    print(
        f"Channels : {total}"
    )
    print(
        f"Time     : "
        f"{time.time() - start:.1f}s"
    )
    print(
        f"Output   : {OUTPUT}"
    )
    print(
        "--------------------------------------"
    )
    print(
        "Publish  : GitHub RAW"
    )
    print(
        "======================================"
    )


if __name__ == "__main__":
    main()
