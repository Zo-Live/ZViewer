from pathlib import Path
import math
import shutil
import subprocess
import zipfile

from PIL import Image, ImageDraw, ImageFont
import pillow_heif
import py7zr
from reportlab.pdfgen import canvas

ROOT = Path(__file__).resolve().parent / "fixtures"
LIBRARY = ROOT / "书库"
ASSETS = ROOT / "formats"
FONT = "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc"
pillow_heif.register_heif_opener()


def comic_page(title, number, background, accent):
    image = Image.new("RGB", (1000, 1440), background)
    draw = ImageDraw.Draw(image)
    draw.text((74, 64), "ZVIEWER / 原创格式测试", font=ImageFont.truetype(FONT, 23), fill=accent)
    draw.text((68, 132), title, font=ImageFont.truetype(FONT, 85), fill=accent)
    draw.text((74, 252), f"第 {number:02d} 页  ·  把故事留在此刻", font=ImageFont.truetype(FONT, 26), fill=accent)
    draw.rounded_rectangle((64, 340, 936, 1100), radius=20, fill=accent)
    draw.ellipse((600, 420, 816, 636), fill=background)
    draw.polygon([(64, 1100), (64, 912), (304, 596), (520, 940), (700, 750), (936, 1010), (936, 1100)], fill=background)
    draw.line((64, 1140, 936, 1140), fill=accent, width=3)
    draw.text((74, 1190), "山的那边，会有怎样的风景？", font=ImageFont.truetype(FONT, 32), fill=accent)
    draw.text((74, 1290), f"{number:02d} / 03", font=ImageFont.truetype(FONT, 26), fill=accent)
    return image


def generate():
    LIBRARY.mkdir(parents=True, exist_ok=True)
    ASSETS.mkdir(parents=True, exist_ok=True)
    pages = [comic_page("山野来信", number, "#E8EDDE", "#38574E") for number in (1, 2, 10)]
    for number, page in zip((1, 2, 10), pages):
        page.save(ASSETS / f"page{number}.png")
    for extension in ("jpg", "jpeg", "png", "webp", "bmp", "heic", "heif", "avif"):
        pages[0].save(ASSETS / f"static.{extension}", format="HEIF" if extension in ("heic", "heif") else None)
    frames = []
    for frame_index in range(8):
        frame = Image.new("RGB", (400, 600), "#E8EDDE")
        draw = ImageDraw.Draw(frame)
        draw.text((35, 45), "动态格式测试", font=ImageFont.truetype(FONT, 38), fill="#38574E")
        center = 70 + frame_index * 36
        draw.ellipse((center - 32, 250, center + 32, 314), fill="#38574E")
        draw.text((35, 420), f"动画帧 {frame_index + 1} / 8", font=ImageFont.truetype(FONT, 28), fill="#38574E")
        frames.append(frame)
    for extension in ("png", "webp", "gif", "avif"):
        frames[0].save(ASSETS / f"animated.{extension}", save_all=True, append_images=frames[1:], duration=160, loop=0)
    shutil.copyfile(ASSETS / "animated.png", ASSETS / "animated.apng")
    large_frames = [Image.new("RGB", (4800, 3200), color) for color in ("#38574E", "#E8EDDE")]
    large_frames[0].save(ASSETS / "large.apng", format="PNG", save_all=True,
                         append_images=large_frames[1:], duration=150, loop=0)
    for extension, title, colors in (
        ("cbz", "01 山野来信", ("#E8EDDE", "#38574E")),
        ("zip", "02 海边慢车", ("#E0EAF1", "#385873")),
        ("7z", "03 星间旅行", ("#252D46", "#D4C4A3")),
        ("cbr", "04 午后放映室", ("#EEE2DA", "#8A5153")),
        ("rar", "05 雨季手记", ("#DBE7D7", "#496848")),
    ):
        staging = ROOT / "staging" / extension
        staging.mkdir(parents=True, exist_ok=True)
        for number in (1, 2, 10):
            comic_page(title[3:], number, *colors).save(staging / f"page{number}.png")
        output = LIBRARY / f"{title}.{extension}"
        output.unlink(missing_ok=True)
        if extension in ("cbz", "zip"):
            with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
                for number in (10, 2, 1):
                    archive.write(staging / f"page{number}.png", f"chapter1/page{number}.png")
                archive.writestr("__MACOSX/._page0.png", b"metadata")
        elif extension == "7z":
            with py7zr.SevenZipFile(output, "w") as archive:
                for number in (10, 2, 1):
                    archive.write(staging / f"page{number}.png", f"page{number}.png")
        else:
            subprocess.run(["/tmp/rar/rar", "a", "-idq", "-ma5", str(output), "page10.png", "page2.png", "page1.png"], cwd=staging, check=True)
    pdf = canvas.Canvas(str(LIBRARY / "06 远方的灯塔.pdf"), pagesize=(500, 720))
    for number in (1, 2, 10):
        page = comic_page("远方的灯塔", number, "#EFE8CD", "#806941")
        path = ROOT / f"pdf{number}.png"
        page.save(path)
        pdf.drawImage(str(path), 0, 0, width=500, height=720)
        pdf.showPage()
    pdf.save()
    folder = LIBRARY / "07 图片文件夹"
    folder.mkdir(exist_ok=True)
    for number, page in zip((1, 2, 10), pages):
        page.save(folder / f"page{number}.jpg")
    animation_folder = LIBRARY / "08 动画与图片格式"
    animation_folder.mkdir(exist_ok=True)
    for index, source in enumerate(sorted(ASSETS.glob("animated.*")) + sorted(ASSETS.glob("static.*"))):
        shutil.copyfile(source, animation_folder / f"{index + 1:02d}-{source.name}")
    with zipfile.ZipFile(ASSETS / "unsafe.zip", "w") as archive:
        archive.write(ASSETS / "page1.png", "../../escape.png")
        archive.write(ASSETS / "page2.png", "/absolute.png")
    (ASSETS / "broken.cbz").write_bytes(b"invalid zip")
    with zipfile.ZipFile(ASSETS / "empty.cbz", "w") as archive:
        archive.writestr("README.txt", "无图片")
    print(f"已生成测试书库：{LIBRARY}")


if __name__ == "__main__":
    generate()
