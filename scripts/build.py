from pathlib import Path
import subprocess
import time
import sys

# ============================================================
# TVAtlas v0.3.1
# Fast / Safe Build
#
# 目标：
# 1. 保留上游原始字节，避免中文乱码
# 2. 下载最长 30 秒
# 3. 下载失败立即结束，不再无限等待
# 4. 输出清晰日志
# ============================================================

SOURCE = (
    "https://raw.githubusercontent.com/"
    "cs3306/IPTV-Sources/main/data/output/iptv_collection.m3u"
)

ROOT = Path(__file__).resolve().parent.parent

TEMP = ROOT / "source.m3u"
OUTPUT = ROOT / "tvatlas.m3u"


# ============================================================
# 白名单
# ============================================================

WHITELIST = [
    # CCTV
    "CCTV-1", "CCTV1",
    "CCTV-2", "CCTV2",
    "CCTV-3", "CCTV3",
    "CCTV-4", "CCTV4",
    "CCTV-5", "CCTV5",
    "CCTV-5+", "CCTV5+",
    "CCTV-6", "CCTV6",
    "CCTV-7", "CCTV7",
    "CCTV-8", "CCTV8",
    "CCTV-9", "CCTV9",
    "CCTV-10", "CCTV10",
    "CCTV-11", "CCTV11",
    "CCTV-12", "CCTV12",
    "CCTV-13", "CCTV13",
    "CCTV-14", "CCTV14",
    "CCTV-15", "CCTV15",
    "CCTV-16", "CCTV16",
    "CCTV-17", "CCTV17",

    # 卫视
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


# ============================================================
# 下载
# ============================================================

def download():

    print("")
    print("[1/3] Downloading source...")
    print(SOURCE)
    print("")

    start = time.time()

    command = [
        "curl",
        "-L",
        "--fail",
        "--silent",
        "--show-error",

        # 建立连接最多 8 秒
        "--connect-timeout", "8",

        # 整个下载最多 30 秒
        "--max-time", "30",

        # 失败重试一次
        "--retry", "1",
        "--retry-delay", "2",

        "-o", str(TEMP),

        SOURCE,
    ]

    try:

        subprocess.run(
            command,
            check=True,
            timeout=40
        )

    except subprocess.TimeoutExpired:

        print("")
        print("ERROR: Download exceeded 40 seconds.")
        sys.exit(1)

    except subprocess.CalledProcessError as e:

        print("")
        print(f"ERROR: Download failed. curl code={e.returncode}")
        sys.exit(1)

    elapsed = time.time() - start

    if not TEMP.exists():

        print("ERROR: Source file not created.")
        sys.exit(1)

    size = TEMP.stat().st_size

    if size < 100:

        print("ERROR: Source file is unexpectedly small.")
        sys.exit(1)

    print(
        f"OK: downloaded {size:,} bytes "
        f"in {elapsed:.1f}s"
    )


# ============================================================
# 字节级筛选
# ============================================================

def wanted(extinf):

    upper = extinf.upper()

    for keyword in WHITELIST:

        if keyword.encode("utf-8").upper() in upper:
            return True

    return False


def filter_channels():

    print("")
    print("[2/3] Filtering channels...")

    # 关键：
    # 全程 bytes
    # 不 decode 中文

    data = TEMP.read_bytes()

    lines = data.splitlines()

    output = [b"#EXTM3U"]

    selected = 0

    i = 0

    while i < len(lines):

        line = lines[i]

        if not line.startswith(b"#EXTINF:"):
            i += 1
            continue

        extinf = line

        j = i + 1
        extra = []
        url = None

        while j < len(lines):

            candidate = lines[j]

            if not candidate:
                j += 1
                continue

            if candidate.startswith(b"#"):
                extra.append(candidate)
                j += 1
                continue

            url = candidate
            break

        if url is not None and wanted(extinf):

            # EXTINF 原字节直接复制
            output.append(extinf)

            # 保留 EXTINF 后的额外标签
            output.extend(extra)

            # URL 原字节直接复制
            output.append(url)

            selected += 1

        i = max(j + 1, i + 1)

    result = b"\n".join(output) + b"\n"

    OUTPUT.write_bytes(result)

    print(f"OK: selected {selected} source entries")
    print(f"Output size: {len(result):,} bytes")

    return selected


# ============================================================
# 清理
# ============================================================

def cleanup():

    if TEMP.exists():
        TEMP.unlink()


# ============================================================
# MAIN
# ============================================================

def main():

    print("")
    print("======================================")
    print("        TVAtlas v0.3.1")
    print("      Fast / Safe Builder")
    print("======================================")

    total_start = time.time()

    try:

        download()

        selected = filter_channels()

        if selected == 0:
            print("")
            print("ERROR: No channels matched.")
            sys.exit(1)

    finally:

        cleanup()

    elapsed = time.time() - total_start

    print("")
    print("[3/3] Build completed")
    print("")
    print(f"Channels : {selected}")
    print(f"Time     : {elapsed:.1f}s")
    print(f"Output   : {OUTPUT}")
    print("")
    print("Chinese metadata: original bytes preserved")
    print("======================================")


if __name__ == "__main__":
    main()
