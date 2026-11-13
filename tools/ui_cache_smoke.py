import argparse
import json
import shlex
import subprocess
import time
from pathlib import Path

import uiautomator2 as u2


parser = argparse.ArgumentParser(description="ZViewer 缓存自动回收端到端验收（需要可调试构建）")
parser.add_argument("--serial", required=True)
parser.add_argument("--package", default="dev.zolive.zviewer.regression")
parser.add_argument("--output", default="test-output/cache-reclamation/ui")
arguments = parser.parse_args()
output = Path(arguments.output)
output.mkdir(parents=True, exist_ok=True)
device = u2.connect(arguments.serial)
device.settings["wait_timeout"] = 15
results = []
limit = 1536 * 1024 * 1024


def shell(*parts):
    return subprocess.check_output(
        ["adb", "-s", arguments.serial, "shell", shlex.join(parts)], text=True
    ).strip()


def app_shell(command):
    return shell("run-as", arguments.package, "sh", "-c", command)


def exists(path):
    return app_shell(f"if test -f {shlex.quote(path)}; then echo yes; fi") == "yes"


def wait_for(predicate, timeout=15):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return True
        time.sleep(.1)
    return False


def record(name, passed):
    results.append({"项目": name, "通过": bool(passed)})
    (output / "results.json").write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"{'通过' if passed else '失败'}：{name}", flush=True)
    assert passed, name


def capture(name):
    device.screenshot(str(output / f"{name}.png"))
    (output / f"{name}.xml").write_text(device.dump_hierarchy(), encoding="utf-8")


def controls():
    if not device(description="阅读设置").exists:
        device.click(.5, .4)
    assert device(description="阅读设置").wait(timeout=10)


def jump(page):
    controls()
    device(textMatches=r"第 \d+ / \d+ 页").click()
    device(className="android.widget.EditText").set_text(str(page))
    device(text="跳转").click()
    assert device(text=f"第 {page} / 3 页").wait(timeout=10)
    assert device(descriptionStartsWith=f"第 {page} 页").wait(timeout=10)


device.app_start(arguments.package, stop=True)
if device(text="选择书库文件夹").wait(timeout=3):
    device(text="选择书库文件夹").click()
    if device(text="Download").wait(timeout=2):
        device(text="Download").click()
    elif device(description="Show roots").exists:
        device(description="Show roots").click()
        device(text="Downloads").click()
    device(text="ZViewer测试书库").click()
    device(resourceId="android:id/button1").click()
    device(textMatches="(?i)allow|允许").click()

record("系统文件夹授权后显示漫画", device(text="01 山野来信").wait(timeout=20))
time.sleep(2)
original = shell("sha256sum", "/sdcard/Download/ZViewer测试书库/01 山野来信.cbz")
stale = "cache/books/cache-smoke-old/old.bin"
app_shell(f"mkdir -p cache/books/cache-smoke-old && truncate -s {limit} {stale}")
device(text="01 山野来信").click()
record("打开漫画后旧缓存自动回收", wait_for(lambda: not exists(stale)))
jump(1)
capture("01-超限后正常阅读")
manifests = app_shell("find cache/books -name media.json").splitlines()
record("整本解压文件仍可复用", len(manifests) == 1)
folder = manifests[0].rsplit("/", 1)[0]
active = folder + "/cache-smoke-active.bin"
app_shell(f"truncate -s {limit} {shlex.quote(active)}")
jump(2)
time.sleep(1.5)
record("当前书超过阈值仍保留且可以翻页", exists(active) and device(descriptionStartsWith="第 2 页").exists)
jump(3)
jump(1)
jump(2)
capture("02-大缓存下前后翻页")
controls()
device(description="返回书库").click()
record("退出大书后自动释放整本缓存", wait_for(lambda: not exists(active)))
device(text="01 山野来信").click()
record("回收后重新打开恢复阅读进度", device(descriptionStartsWith="第 2 页").wait(timeout=20))
capture("03-重新打开恢复进度")
record("漫画原文件内容不变", original == shell("sha256sum", "/sdcard/Download/ZViewer测试书库/01 山野来信.cbz"))
controls()
device(description="返回书库").click()
device(description="设置").click()
for attempt in range(5):
    if device(text="清理阅读缓存").exists:
        break
    device.swipe(.5, .8, .5, .3, .4)
record("可进入缓存设置且应用正常响应", device(text="清理阅读缓存").wait(timeout=10))
capture("04-回收后设置")
print(f"缓存端到端验收完成：{output}", flush=True)
