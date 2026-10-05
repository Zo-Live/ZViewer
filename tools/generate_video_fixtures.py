from pathlib import Path
import subprocess
import zipfile


def generate():
    formats = Path(__file__).resolve().parent / "fixtures" / "formats"
    folder = formats / "video-folder"
    folder.mkdir(parents=True, exist_ok=True)
    for name, color, size in (("video2.mp4", "red", "640x360"), ("video10.mp4", "blue", "360x640")):
        subprocess.run([
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi",
            "-i", f"color=c={color}:s={size}:r=15:d=12", "-c:v", "libx264",
            "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(folder / name),
        ], check=True)
    with zipfile.ZipFile(formats / "videos.zip", "w", zipfile.ZIP_DEFLATED) as archive:
        for name in ("video10.mp4", "video2.mp4"):
            archive.write(folder / name, f"chapter/{name}")
        archive.writestr("__MACOSX/._video1.mp4", b"metadata")
    rotated = formats / "rotated.mp4"
    subprocess.run([
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(folder / "video2.mp4"),
        "-c", "copy", "-metadata:s:v:0", "rotate=90", str(rotated),
    ], check=True)
    (folder / ".hidden.mp4").write_bytes(b"metadata")
    print(f"已生成视频测试样本：{folder}")


if __name__ == "__main__":
    generate()
