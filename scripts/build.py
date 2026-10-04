from pathlib import Path
from urllib.request import Request, urlopen

# ============================================================
# TVAtlas v0.3
# 中国大陆核心频道
#
# 核心原则：
# 1. 下载已验证中文正常的 IPTV-Sources 原始 M3U
# 2. 不 decode 整个文件
# 3. 不重新生成中文 EXTINF
# 4. 使用 UTF-8 bytes 直接匹配
# 5. 命中的频道块原样写回
# ============================================================

SOURCE = (
    "https://raw.githubusercontent.com/"
    "cs3306/IPTV-Sources/main/data/output/iptv_collection.m3u"
)

OUTPUT = Path(__file__).resolve().parent.parent / "tvatlas.m3u"


# ------------------------------------------------------------
# 第一阶段白名单
# 使用频道名称关键词筛选
# ------------------------------------------------------------

WHITELIST = [

    # ===== CCTV =====
    "CCTV-1",
    "CCTV1",

    "CCTV-2",
    "CCTV2",

    "CCTV-3",
    "CCTV3",

    "CCTV-4",
    "CCTV4",

    "CCTV-5",
    "CCTV5",

    "CCTV-5+",
    "CCTV5+",

    "CCTV-6",
    "CCTV6",

    "CCTV-7",
    "CCTV7",

    "CCTV-8",
    "CCTV8",

    "CCTV-9",
    "CCTV9",

    "CCTV-10",
    "CCTV10",

    "CCTV-11",
    "CCTV11",

    "CCTV-12",
    "CCTV12",

    "CCTV-13",
    "CCTV13",

    "CCTV-14",
    "CCTV14",

    "CCTV-15",
    "CCTV15",

    "CCTV-16",
    "CCTV16",

    "CCTV-17",
    "CCTV17",

    # ===== 主要卫视 =====
    "湖南卫视",
    "浙江卫视",
    "江苏卫视",
    "东方卫视",
    "北京卫视",
    "广东卫视",
    "深圳卫视",
    "山东卫视",
    "安徽卫视",
    "湖北卫视",
    "河南卫视",
    "四川卫视",
    "重庆卫视",
    "天津卫视",
    "辽宁卫视",
    "黑龙江卫视",
    "江西卫视",
    "河北卫视",
    "东南卫视",
    "广西卫视",
    "贵州卫视",
    "云南卫视",
]


# ------------------------------------------------------------
# 下载
# ------------------------------------------------------------

def download():

    request = Request(
        SOURCE,
        headers={
            "User-Agent": "Mozilla/5.0 TVAtlas/0.3"
        }
    )

    with urlopen(request, timeout=60) as response:
        return response.read()


# ------------------------------------------------------------
# 判断 EXTINF 是否属于白名单
#
# 注意：
# keyword.encode("utf-8")
# 只负责生成匹配用 bytes
#
# 原始 EXTINF 本身不会 decode / encode
# ------------------------------------------------------------

def is_wanted(extinf):

    upper = extinf.upper()

    for keyword in WHITELIST:

        keyword_bytes = keyword.encode("utf-8").upper()

        if keyword_bytes in upper:
            return True

    return False


# ------------------------------------------------------------
# 解析并筛选
# ------------------------------------------------------------

def filter_playlist(data):

    # splitlines() 在 bytes 上操作
    # 不发生字符解码

    lines = data.splitlines()

    output = [
        b"#EXTM3U"
    ]

    selected = 0

    i = 0

    while i < len(lines):

        line = lines[i]

        if line.startswith(b"#EXTINF:"):

            extinf = line

            # 找该频道真正的 URL
            j = i + 1

            extra_lines = []

            while j < len(lines):

                candidate = lines[j]

                if not candidate:
                    j += 1
                    continue

                # EXTINF 后可能存在额外标签
                if candidate.startswith(b"#"):

                    extra_lines.append(candidate)
                    j += 1
                    continue

                url = candidate

                if is_wanted(extinf):

                    # 最关键：
                    # 原始 EXTINF 字节原封不动写入

                    output.append(extinf)

                    for extra in extra_lines:
                        output.append(extra)

                    output.append(url)

                    selected += 1

                i = j

        i += 1

    return b"\n".join(output) + b"\n", selected


# ------------------------------------------------------------
# MAIN
# ------------------------------------------------------------

def main():

    print("======================================")
    print("TVAtlas v0.3")
    print("Byte-safe curated playlist")
    print("======================================")

    data = download()

    print(f"Downloaded : {len(data)} bytes")

    playlist, selected = filter_playlist(data)

    # 直接写 bytes
    # 不进行任何 encode/decode

    OUTPUT.write_bytes(playlist)

    print("--------------------------------------")
    print(f"Selected   : {selected}")
    print(f"Output size: {len(playlist)} bytes")
    print(f"Output     : {OUTPUT}")
    print("--------------------------------------")
    print("EXTINF     : original bytes preserved")
    print("Encoding   : untouched")
    print("======================================")


if __name__ == "__main__":
    main()
