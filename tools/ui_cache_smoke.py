import argparse
import hashlib
import json
import shlex
import subprocess
import time
from pathlib import Path
from xml.etree import ElementTree

import uiautomator2 as u2


parser = argparse.ArgumentParser(description="ZViewer 缓存自动回收端到端验收（需要可调试构建）")
parser.add_argument("--serial", required=True)
parser.add_argument("--package", default="dev.zolive.zviewer.regression")
parser.add_argument("--library", default="ZViewer测试书库")
parser.add_argument("--verify-source-changes", action="store_true", help="修改测试书库首本漫画的时间戳；仅对专用样本目录使用")
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


def start():
    device.app_stop(arguments.package)
    shell("am", "start", "-W", "-n", f"{arguments.package}/dev.zolive.zviewer.MainActivity", "-f", "0x10008000")
    assert wait_for(lambda: device.app_current()["package"] == arguments.package)
    time.sleep(1)


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
    assert wait_for(lambda: device(className="android.widget.EditText").get_text() == str(page))
    time.sleep(.35)
    device(text="跳转").click()
    if not device(textMatches=rf"第 {page} / \d+ 页").wait(timeout=10):
        capture(f"跳转第{page}页失败")
        raise AssertionError(f"未跳转到第 {page} 页")
    assert device(descriptionStartsWith=f"第 {page} 页").wait(timeout=10)


app_shell("id")
start()
if device(text="选择书库目录").wait(timeout=3):
    device(text="选择书库目录").click()
    if device(text="Download").wait(timeout=2):
        device(text="Download").click()
        time.sleep(.6)
    elif device(description="Show roots").exists:
        device(description="Show roots").click()
        device(text="Downloads").click()
    device(text=arguments.library).click()
    assert device(resourceId="com.google.android.documentsui:id/breadcrumb_text", text=arguments.library).wait(timeout=10)
    device(resourceId="android:id/button1", enabled=True).click()
    device(textMatches="(?i)allow|允许").click()

record("系统文件夹授权后显示漫画", device(text="01 山野来信").wait(timeout=20))
time.sleep(2)
source_path = f"/sdcard/Download/{arguments.library}/01 山野来信.cbz"
original = shell("sha256sum", source_path)


def books():
    preferences = ElementTree.fromstring(app_shell("cat shared_prefs/zviewer.xml"))
    return json.loads(preferences.find("./string[@name='books']").text)


def cover_path(book):
    key = f"{book['id']}-{book['modified']}-{book['size']}"
    if book.get("contentSignature"):
        key += "-" + book["contentSignature"]
    return "files/covers/" + hashlib.sha256(key.encode()).hexdigest()[:32] + ".jpg"


def fingerprint(path):
    if not exists(path):
        return None
    return app_shell(f"stat -c '%i %s %Y' {shlex.quote(path)}") + app_shell(f"sha256sum {shlex.quote(path)}")


first = cover_path(next(book for book in books() if book["title"] == "01 山野来信"))
other = cover_path(next(book for book in books() if book["title"] == "02 海边慢车"))
record("封面生成在持久目录", wait_for(lambda: exists(first) and exists(other)))
app_shell(f"touch -t 200001010000 {shlex.quote(first)} {shlex.quote(other)}")
initial = {path: fingerprint(path) for path in (first, other)}
device.app_stop(arguments.package)
app_shell(f"mkdir -p cache/covers && mv {shlex.quote(first)} cache/covers/")
start()
record("重新启动迁移并复用旧目录封面", wait_for(lambda: fingerprint(first) == initial[first]))
record("长期未使用的其他封面保持原文件", fingerprint(other) == initial[other])
device.app_stop(arguments.package)
app_shell("rm -rf cache/books cache/covers")
start()
assert device(text="01 山野来信").wait(timeout=20)
time.sleep(1)
record("系统缓存目录被回收后仍复用原封面", all(fingerprint(path) == saved for path, saved in initial.items()))

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
record("漫画原文件内容不变", original == shell("sha256sum", source_path))
record("超限阅读与退出始终保留原封面", all(fingerprint(path) == saved for path, saved in initial.items()))
controls()
device(description="返回书库").click()
if arguments.verify_source_changes:
    previous_book = next(book for book in books() if book["title"] == "01 山野来信")
    changed_time = time.strftime("%Y%m%d%H%M.%S", time.gmtime(previous_book["modified"] / 1000 + 86400))
    shell("touch", "-t", changed_time, source_path)
    device(description="刷新书库").click()
    record("源文件变化仅删除对应旧封面", wait_for(lambda: not exists(first)))
    assert wait_for(lambda: cover_path(next(book for book in books() if book["title"] == "01 山野来信")) != first)
    first = cover_path(next(book for book in books() if book["title"] == "01 山野来信"))
    record("变化书目生成新的封面", wait_for(lambda: exists(first)))
    record("未变化书目不会重新生成封面", fingerprint(other) == initial[other])
    record("刷新后漫画内容不变", original == shell("sha256sum", source_path))
    capture("04-书库变化后按书目刷新")

before_clear = {path: fingerprint(path) for path in (first, other)}
device(description="设置").click()
for attempt in range(5):
    if device(text="清理阅读缓存").exists:
        break
    device.swipe(.5, .8, .5, .3, .4)
record("可进入缓存设置且应用正常响应", device(text="清理阅读缓存").wait(timeout=10))
capture("05-回收后设置")
device(text="清理阅读缓存").click()
time.sleep(.6)
record("单击清理不删除封面", all(fingerprint(path) == saved for path, saved in before_clear.items()))
preferences = ElementTree.fromstring(app_shell("cat shared_prefs/zviewer.xml"))
clear_covers = preferences.find("./boolean[@name='clear_covers']")
if clear_covers is None or clear_covers.get("value") != "true":
    device(text="清理封面").click()
clear_button = device(text="清理阅读缓存").center()
device.double_click(*clear_button, duration=.1)
record("双击清理会删除或重建封面", wait_for(lambda: all(fingerprint(path) != saved for path, saved in before_clear.items())))
device.press("back")
record("手动清理后书库正常重新生成封面", wait_for(lambda: exists(first) and exists(other)))
capture("06-手动清理后封面重建")
print(f"缓存端到端验收完成：{output}", flush=True)
