from pathlib import Path

# ============================================================
# TVAtlas Playlist Generator
# 中文编码测试版
#
# 输出格式：
# - UTF-8
# - 无 BOM
# - LF 换行
# - 标准 Extended M3U
# ============================================================

channels = [
    {
        "group": "香港",
        "name": "香港电台31",
        "url": "https://rthktv31-live.akamaized.net/hls/live/2036818/RTHKTV31/master.m3u8",
    },
    {
        "group": "香港",
        "name": "香港电台32",
        "url": "https://rthktv32-live.akamaized.net/hls/live/2036819/RTHKTV32/master.m3u8",
    },
    {
        "group": "香港",
        "name": "凤凰卫视中文台",
        "url": "https://playtv-live.ifeng.com/live/06OLEGEGM4G_tv1.m3u8",
    },
    {
        "group": "香港",
        "name": "凤凰卫视资讯台",
        "url": "https://playtv-live.ifeng.com/live/06OLEEWQKN4_tv1.m3u8",
    },
]


def build_playlist():
    lines = ["#EXTM3U"]

    for channel in channels:
        group = channel["group"]
        name = channel["name"]
        url = channel["url"]

        lines.append(
            f'#EXTINF:-1 group-title="{group}",{name}'
        )
        lines.append(url)

    # 标准 LF 换行
    content = "\n".join(lines) + "\n"

    # 输出到项目根目录 /tvatlas.m3u
    output = Path(__file__).resolve().parent.parent / "tvatlas.m3u"

    # 关键：
    # 使用标准 UTF-8，不添加 BOM
    output.write_text(
        content,
        encoding="utf-8",
        newline="\n"
    )

    print("======================================")
    print("TVAtlas playlist generated successfully")
    print("======================================")
    print(f"Output   : {output}")
    print(f"Channels : {len(channels)}")
    print("Encoding : UTF-8")
    print("BOM      : No")
    print("Newline  : LF")
    print("======================================")


if __name__ == "__main__":
    build_playlist()
