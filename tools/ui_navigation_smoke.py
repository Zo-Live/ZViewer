import argparse
import json
import time
import xml.etree.ElementTree as ET
from pathlib import Path

import uiautomator2 as u2


parser = argparse.ArgumentParser(description="ZViewer 滑动切页与设置过渡端到端验收（需已授权测试书库）")
parser.add_argument("--serial", required=True)
parser.add_argument("--package", default="dev.zolive.zviewer")
parser.add_argument("--output", default="test-output/navigation-transitions/ui")
parser.add_argument("--book", default="01 山野来信")
arguments = parser.parse_args()
device = u2.connect(arguments.serial)
device.settings["wait_timeout"] = 12
output = Path(arguments.output)
output.mkdir(parents=True, exist_ok=True)
results = []


def record(name, passed):
    results.append({"项目": name, "通过": bool(passed)})
    (output / "results.json").write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"{'通过' if passed else '失败'}：{name}", flush=True)
    assert passed, name


def selected(label):
    root = ET.fromstring(device.dump_hierarchy())
    return any(
        (node.attrib.get("selected") == "true" or node.attrib.get("checked") == "true")
        and any(child.attrib.get("text") == label for child in node.iter())
        for node in root.iter("node")
    )


def click(**selector):
    device(**selector).click()
    time.sleep(.6)


def tab(label):
    matches = device(text=label)
    matches[matches.count - 1].click()
    time.sleep(.6)


def swipe(left):
    device.swipe(.85 if left else .15, .55, .15 if left else .85, .55, .45)
    time.sleep(.6)


def capture(name):
    device.screenshot(str(output / f"{name}.png"))
    (output / f"{name}.xml").write_text(device.dump_hierarchy(), encoding="utf-8")


def hide_keyboard():
    if "mInputShown=true" in device.shell("dumpsys input_method").output:
        device.press("back")
        time.sleep(.4)


device.app_start(arguments.package, stop=True)
record("启动已授权书库", device(description="刷新书库").wait(timeout=20))
tab("书库")
swipe(False)
record("书库向右滑动保持在第一页", selected("书库"))
swipe(True)
record("书库左滑进入收藏", selected("收藏"))
swipe(True)
record("收藏左滑进入最近阅读", selected("最近阅读"))
swipe(True)
record("最近阅读向左滑动保持在末页", selected("最近阅读"))
swipe(False)
record("最近阅读右滑返回收藏", selected("收藏"))
swipe(False)
record("收藏右滑返回书库", selected("书库"))
tab("最近阅读")
swipe(False)
record("点击跨页后仍可继续滑动", selected("收藏"))

click(description="搜索漫画")
device(className="android.widget.EditText").set_text("导航验收_无匹配")
hide_keyboard()
record("收藏搜索显示空结果", device(text="没有符合条件的漫画").wait(timeout=5))
swipe(True)
record("空结果仍可滑动到最近阅读", selected("最近阅读") and device(text="没有符合条件的漫画").exists)
click(text="未读")
click(description="设置")
record("进入设置页", device(description="返回书库").exists)
click(description="返回书库")
record("设置按钮返回保留原标签与搜索", selected("最近阅读") and device(text="导航验收_无匹配").exists)
record("设置返回保留阅读筛选", selected("未读"))
click(description="设置")
device.press("back")
time.sleep(.7)
record("系统返回关闭设置并保留搜索", selected("最近阅读") and device(description="关闭搜索").exists)
click(description="关闭搜索")
click(text="全部")

tab("书库")
device.swipe(.5, .8, .5, .35, .6)
time.sleep(.6)
record("纵向滚动不会切换标签", selected("书库"))
before = {node.attrib["text"]: node.attrib["bounds"] for node in ET.fromstring(device.dump_hierarchy()).iter("node")
          if node.attrib.get("text", "").startswith(("01 ", "02 ", "03 ", "04 ", "05 ", "06 ", "07 ", "08 "))}
swipe(True)
swipe(False)
click(description="设置")
click(description="返回书库")
after = {node.attrib["text"]: node.attrib["bounds"] for node in ET.fromstring(device.dump_hierarchy()).iter("node")
         if node.attrib.get("text", "").startswith(("01 ", "02 ", "03 ", "04 ", "05 ", "06 ", "07 ", "08 "))}
record("切页及设置返回保留网格滚动位置", bool(before) and before == after)
capture("书库滚动位置")

click(description="设置")
original_theme = next(label for label in ("跟随系统", "浅色", "深色") if selected(label))
try:
    for theme in ("深色", "浅色"):
        click(text=theme)
        click(description="返回书库")
        click(description="设置")
        record(f"{theme}主题设置进退后可正常操作", selected(theme))
        capture(f"设置-{theme}")
finally:
    click(text=original_theme)
    click(description="返回书库")

click(description="搜索漫画")
device(className="android.widget.EditText").set_text(arguments.book)
hide_keyboard()
matches = device(text=arguments.book, className="android.widget.TextView")
matches[matches.count - 1].click()
record("切页与设置之后仍能打开漫画", device(descriptionStartsWith="第 ").wait(timeout=20))
device.press("back")
time.sleep(.7)
record("阅读返回保留书库标签", selected("书库"))
if device(description="关闭搜索").exists:
    click(description="关闭搜索")
swipe(True)
swipe(True)
record("阅读后可滑动到最近阅读", selected("最近阅读") and device(text=arguments.book).exists)
capture("最近阅读")
tab("书库")
print(f"导航验收完成：{len(results)} 项，截图与记录：{output}", flush=True)
