from pathlib import Path
from urllib.request import Request, urlopen
import re

# ============================================================
# TVAtlas v0.2
# 香港精选频道自动生成器
#
# 原则：
# 1. 使用已经在 OpenIPTV 验证中文正常的上游 M3U
# 2. 保留原始 EXTINF 属性，例如 tvg-id / tvg-logo
# 3. 只修改频道显示名称和 group-title
# 4. 输出 UTF-8，无 BOM
# ============================================================

SOURCE = "https://iptv-org.github.io/iptv/countries/hk.m3u"

OUTPUT = Path(__file__).resolve().parent.parent / "tvatlas.m3u"


# ------------------------------------------------------------
# TVAtlas 香港频道白名单
#
# match:
#   用于匹配上游频道名称
#
# name:
#   OpenIPTV 最终显示的中文名称
# ------------------------------------------------------------

CHANNELS = [
    {
        "match": ["Jade"],
        "name": "翡翠台",
    },
    {
        "match": ["TVB Plus"],
        "name": "TVB Plus",
    },
    {
        "match": ["Pearl"],
        "name": "明珠台",
    },
    {
        "match": ["TVB News"],
        "name": "TVB新闻台",
    },
    {
        "match": ["ViuTV"],
        "exclude": ["ViuTVsix"],
        "name": "ViuTV",
    },
    {
        "match": ["ViuTVsix", "ViuTV Six"],
        "name": "ViuTVsix",
    },
    {
        "match": ["HOY TV"],
        "name": "HOY TV",
    },
    {
        "match": ["HOY Info"],
        "name": "HOY资讯台",
    },
    {
        "match": ["RTHK TV 31", "RTHK 31"],
        "name": "香港电台31",
    },
    {
        "match": ["RTHK TV 32", "RTHK 32"],
        "name": "香港电台32",
    },
    {
        "match": ["RTHK TV 33", "RTHK 33"],
        "name": "香港电台33",
    },
    {
        "match": ["RTHK TV 34", "RTHK 34"],
        "name": "香港电台34",
    },
    {
        "match": ["RTHK TV 35", "RTHK 35"],
        "name": "香港电台35",
    },
    {
        "match": ["Phoenix Chinese"],
        "name": "凤凰卫视中文台",
    },
    {
        "match": ["Phoenix Info", "Phoenix Information"],
        "name": "凤凰卫视资讯台",
    },
]


def download(url):
    request = Request(
        url,
        headers={
            "User-Agent": "Mozilla/5.0 TVAtlas/0.2"
        }
    )

    with urlopen(request, timeout=30) as response:
        return response.read()


def decode_playlist(data):
    """
    上游已经验证可以在 OpenIPTV 正确显示中文。
    这里统一按 UTF-8 解析。
    """
    return data.decode("utf-8-sig")


def parse_playlist(text):
    """
    将 M3U 转成：
    [
        {
            "extinf": "...",
            "url": "..."
        }
    ]
    """

    lines = text.splitlines()
    channels = []

    i = 0

    while i < len(lines):

        line = lines[i].strip()

        if line.startswith("#EXTINF:"):

            extinf = line

            j = i + 1

            while j < len(lines):

                candidate = lines[j].strip()

                if not candidate:
                    j += 1
                    continue

                if candidate.startswith("#"):
                    j += 1
                    continue

                channels.append(
                    {
                        "extinf": extinf,
                        "url": candidate,
                    }
                )

                break

            i = j

        i += 1

    return channels


def get_channel_name(extinf):
    """
    EXTINF 最后一个逗号后的内容就是频道显示名称。
    """

    if "," not in extinf:
        return ""

    return extinf.split(",", 1)[1].strip()


def matches_rule(channel_name, rule):

    name_lower = channel_name.lower()

    # 必须至少命中一个 match
    matched = any(
        keyword.lower() in name_lower
        for keyword in rule["match"]
    )

    if not matched:
        return False

    # 排除关键词
    for keyword in rule.get("exclude", []):
        if keyword.lower() in name_lower:
            return False

    return True


def replace_group(extinf, group_name):

    if 'group-title="' in extinf:

        return re.sub(
            r'group-title="[^"]*"',
            f'group-title="{group_name}"',
            extinf
        )

    # 如果原来没有 group-title，则补进去
    return extinf.replace(
        "#EXTINF:-1",
        f'#EXTINF:-1 group-title="{group_name}"',
        1
    )


def replace_name(extinf, new_name):

    if "," not in extinf:
        return extinf

    metadata = extinf.split(",", 1)[0]

    return f"{metadata},{new_name}"


def build():

    print("======================================")
    print("TVAtlas v0.2")
    print("Hong Kong curated playlist")
    print("======================================")

    raw = download(SOURCE)

    print(f"Downloaded: {len(raw)} bytes")

    text = decode_playlist(raw)

    source_channels = parse_playlist(text)

    print(f"Source channels: {len(source_channels)}")

    selected = []

    used_names = set()

    for rule in CHANNELS:

        for channel in source_channels:

            original_name = get_channel_name(
                channel["extinf"]
            )

            if not matches_rule(original_name, rule):
                continue

            chinese_name = rule["name"]

            # 同一个频道只保留一次
            if chinese_name in used_names:
                continue

            extinf = channel["extinf"]

            extinf = replace_group(
                extinf,
                "香港"
            )

            extinf = replace_name(
                extinf,
                chinese_name
            )

            selected.append(
                {
                    "name": chinese_name,
                    "extinf": extinf,
                    "url": channel["url"],
                }
            )

            used_names.add(chinese_name)

            print(
                f"✓ {original_name} -> {chinese_name}"
            )

            break

    # --------------------------------------------------------
    # 输出
    # --------------------------------------------------------

    lines = ["#EXTM3U"]

    for channel in selected:

        lines.append(
            channel["extinf"]
        )

        lines.append(
            channel["url"]
        )

    content = "\n".join(lines) + "\n"

    OUTPUT.write_bytes(
        content.encode("utf-8")
    )

    print("======================================")
    print(f"Generated: {OUTPUT}")
    print(f"Channels : {len(selected)}")
    print("Encoding : UTF-8")
    print("BOM      : No")
    print("Group    : 香港")
    print("======================================")


if __name__ == "__main__":
    build()
