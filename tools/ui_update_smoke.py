"""开发 Release 包设置与真实 GitHub 检查验收，使用已授权的模拟器测试书库。"""
import argparse
import json
import time
from pathlib import Path
import xml.etree.ElementTree as ET
import uiautomator2 as u2

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--version', default='1.8.0')
parser.add_argument('--output', default='test-output/app-updates/release-ui')
args = parser.parse_args()
out = Path(args.output)
out.mkdir(parents=True, exist_ok=True)
d = u2.connect('emulator-5554')
d.settings['wait_timeout'] = 15
results = []


def record(name, passed):
    results.append({'项目': name, '通过': bool(passed)})
    (out / 'results.json').write_text(json.dumps(results, ensure_ascii=False, indent=2))
    print(('通过：' if passed else '失败：') + name, flush=True)
    assert passed, name


def capture(name):
    d.screenshot(str(out / f'{name}.png'))
    (out / f'{name}.xml').write_text(d.dump_hierarchy())


def scroll_to(label, down=True):
    for _ in range(18):
        if d(text=label).exists:
            return
        d.swipe(.5, .65 if down else .4, .5, .4 if down else .65, .5)
        time.sleep(.3)
    raise AssertionError(label)


def checked(label):
    root = ET.fromstring(d.dump_hierarchy())
    return any(n.attrib.get('checked') == 'true' and any(c.attrib.get('text') == label for c in n.iter()) for n in root.iter())


d.app_start('dev.zolive.zviewer', stop=True)
record('覆盖安装保留书库', d(description='刷新书库').wait(timeout=20))
d(description='设置').click()
scroll_to('关于 ZViewer')
record('应用分区在诊断后且包含三个项目', all(d(text=t).exists for t in ('应用', '检查更新', '启动时检查更新', '关于 ZViewer')))
record('当前版本显示正确', d(text=f'当前版本：{args.version}').exists)
record('启动检查默认关闭', not checked('启动时检查更新'))
record('旧关于入口已移除', not d(text=f'关于 ZViewer {args.version}').exists)
capture('应用-默认')
d(text='启动时检查更新').click()
record('点击文字可开启启动检查', checked('启动时检查更新'))
d.app_stop('dev.zolive.zviewer')
d.app_start('dev.zolive.zviewer')
d(description='设置').wait(timeout=20)
d(description='设置').click()
scroll_to('关于 ZViewer')
record('重启保留启动检查状态', checked('启动时检查更新'))
d(text='启动时检查更新').click()
record('再次点击文字可关闭启动检查', not checked('启动时检查更新'))
d(text='检查更新').click()
record('真实 GitHub 检查返回最新版本', d(text='当前为最新版本').wait(timeout=20))
capture('最新版本-提示')
d.click(.8, .2)
time.sleep(.4)
record('点击任意位置关闭提示', not d(text='当前为最新版本').exists)
d(text='检查更新').click()
record('短期重复检查仍返回最新版本', d(text='当前为最新版本').wait(timeout=20))
time.sleep(4)
record('提示数秒后自动消失', not d(text='当前为最新版本').exists)
d(text='关于 ZViewer').click()
record('关于入口打开原有弹窗', d(text='ZViewer · 本地漫画阅读器').wait(timeout=3))
record('关于隐私说明与联网更新一致', d(textContains='仅检查和下载更新时联网').exists)
capture('关于')
d(text='知道了').click()
time.sleep(.5)
scroll_to('记录设备信息、运行状态与错误', down=False)
record('诊断说明更新且不带句号', d(text='记录设备信息、运行状态与错误').exists)
for theme in ('深色', '浅色'):
    scroll_to('应用主题', down=False)
    d(text=theme).click()
    scroll_to('关于 ZViewer')
    d(text='检查更新').click()
    record(f'{theme}主题最新提示可见', d(text='当前为最新版本').wait(timeout=20))
    capture(f'应用-{theme}')
    d.click(.8, .2)
scroll_to('应用主题', down=False)
d(text='跟随系统').click()
d(description='返回书库').click()
record('设置返回书库正常', d(description='刷新书库').wait(timeout=5))
