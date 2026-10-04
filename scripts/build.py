from pathlib import Path

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

lines = ["#EXTM3U"]

for channel in channels:
    lines.append(
        f'#EXTINF:-1 group-title="{channel["group"]}",{channel["name"]}'
    )
    lines.append(channel["url"])

content = "\n".join(lines) + "\n"

output = Path(__file__).resolve().parent.parent / "tvatlas.m3u"

# utf-8-sig 会在文件开头写入 UTF-8 BOM：
# EF BB BF
# 用于提高部分 IPTV 播放器对中文 M3U 的识别兼容性。
with open(output, "w", encoding="utf-8-sig", newline="\n") as f:
    f.write(content)

print(f"Generated: {output}")
print(f"Channels: {len(channels)}")
