from pathlib import Path
from urllib.request import Request, urlopen

SOURCE = "https://iptv-org.github.io/iptv/countries/hk.m3u"

output = Path(__file__).resolve().parent.parent / "tvatlas.m3u"

request = Request(
    SOURCE,
    headers={
        "User-Agent": "Mozilla/5.0"
    }
)

with urlopen(request, timeout=30) as response:
    data = response.read()

# 关键：
# 不 decode
# 不 encode
# 不修改任何文字
# 不增加 BOM
# 直接把原始字节 1:1 写入 TVAtlas
output.write_bytes(data)

print("TVAtlas A/B encoding test")
print(f"Source: {SOURCE}")
print(f"Downloaded bytes: {len(data)}")
print(f"First 16 bytes: {data[:16].hex(' ')}")
print(f"Output: {output}")
