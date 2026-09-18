from pathlib import Path
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]
SOURCES = {
    "AndroidX / Jetpack Compose / Kotlin / APNG4Android / libarchive-android / Mbed TLS (Apache 2.0)": "https://www.apache.org/licenses/LICENSE-2.0.txt",
    "libarchive (BSD)": "https://raw.githubusercontent.com/libarchive/libarchive/master/COPYING",
    "libavif (BSD)": "https://raw.githubusercontent.com/AOMediaCodec/libavif/main/LICENSE",
    "dav1d (BSD)": "https://raw.githubusercontent.com/videolan/dav1d/master/COPYING",
    "libyuv (BSD)": "https://raw.githubusercontent.com/lemenkov/libyuv/master/LICENSE",
    "libaom (BSD / Alliance for Open Media)": "https://raw.githubusercontent.com/mozilla/aom/master/LICENSE",
    "libaom (Patent License)": "https://raw.githubusercontent.com/mozilla/aom/master/PATENTS",
    "bzip2": "https://raw.githubusercontent.com/Distrotech/bzip2/master/LICENSE",
    "XZ / liblzma": "https://raw.githubusercontent.com/tukaani-project/xz/master/COPYING",
    "LZ4 (BSD)": "https://raw.githubusercontent.com/lz4/lz4/dev/lib/LICENSE",
    "Zstandard (BSD)": "https://raw.githubusercontent.com/facebook/zstd/dev/LICENSE",
    "zlib": "https://raw.githubusercontent.com/madler/zlib/develop/LICENSE",
}

sections = ["ZViewer 开源组件许可\n\n以下为所使用组件及其原生依赖的许可文本。许可原文保留其原始语言。\n"]
for name, url in SOURCES.items():
    print(f"读取许可：{name}", flush=True)
    with urlopen(url, timeout=30) as response:
        data = response.read()
        try:
            license_text = data.decode("utf-8")
        except UnicodeDecodeError:
            license_text = data.decode("cp1252")
    sections.append(f"\n{'=' * 72}\n{name}\n{url}\n{'=' * 72}\n\n{license_text}\n")
destination = ROOT / "app/src/main/assets/open_source_notices.txt"
destination.parent.mkdir(parents=True, exist_ok=True)
destination.write_text("".join(sections), encoding="utf-8")
print(f"已收集 {len(SOURCES)} 份许可文本：{destination}")
