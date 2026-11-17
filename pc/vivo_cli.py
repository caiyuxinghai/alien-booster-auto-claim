# vivo_cli.py - vivo (iQOO Z5x / OriginOS) 设备操作封装
#
# 背景：OriginOS 在系统服务层禁用了 uiautomator（shell 用户注册
# UiTestAutomationService 抛 RemoteException，logcat 实证：UiAutomationManager.
# registerUiTestAutomationServiceLocked 失败），dump 静默失败（rc=0 无输出）。
#
# 替代方案：mobilecli（@mobilenext/mobile-mcp 的 PC 端二进制）。机制：
#   push mobilecli.dex -> /data/local/tmp，app_process 起
#   com.mobilenext.mobilecli.JsonRpcSocketServer（UiAutomation 事件监听 +
#   AccessibilityNodeInfo，绕过被禁的 register 路径），PC 端 localabstract
#   socket JSON-RPC 通信。mobilecli.exe 负责全部 adb 交互。
#
# 命令（均追加 --device <serial>）：
#   dump ui                      -> JSON {data:{elements:[树]}}
#   io tap x,y                   -> 点击
#   io swipe x1,y1,x2,y2         -> 滑动（--duration ms）
#   io longpress x,y --duration  -> 长按
#   io text <str>                -> 输入文本
#   apps launch/terminate <pkg>  -> 启动/结束应用
#   screenshot --format png --output -
import json
import os
import re
import subprocess
import time

HERE = os.path.dirname(os.path.abspath(__file__))
MC = os.path.join(HERE, "mobilecli-windows-amd64.exe")
ADB = os.environ.get("ADB", "adb")  # set ADB env var to a full path if adb is not in PATH
DEV = os.environ.get("ADB_DEV", "")  # vivo serial; required only for OriginOS/mobilecli backend


class CliError(Exception):
    pass


def run(args, timeout=30):
    try:
        p = subprocess.run([MC] + list(args) + ["--device", DEV],
                           capture_output=True, text=True,
                           encoding="utf-8", errors="replace", timeout=timeout)
        return p.returncode, p.stdout or "", p.stderr or ""
    except subprocess.TimeoutExpired:
        raise CliError("timeout: " + " ".join(args))


def dump_ui(timeout=15, retries=3):
    """返回扁平元素列表 [{ref,type,text,desc,id,clickable,x,y,w,h}]

    Android 设备即连即用（agent status: no agent needed）；OriginOS 可能杀
    app_process，失败直接重试即可。desc/contentDescription 一并收集——
    广告结束卡的「跳过/关闭」常常只有 desc 没有 text。
    """
    last = None
    for i in range(retries):
        try:
            rc, out, err = run(["dump", "ui"], timeout)
            if rc == 0 and out.strip():
                j = json.loads(out)
                els = []

                def walk(e):
                    r = e.get("rect") or {}
                    els.append({
                        "ref": e.get("ref"), "type": e.get("type", ""),
                        "text": (e.get("text") or "").strip(),
                        "desc": (e.get("contentDescription") or e.get("desc") or "").strip(),
                        "id": e.get("id") or "",
                        "clickable": bool(e.get("clickable")),
                        "x": int(r.get("x") or 0), "y": int(r.get("y") or 0),
                        "w": int(r.get("width") or 0), "h": int(r.get("height") or 0),
                    })
                    for c in (e.get("children") or []):
                        walk(c)

                for e in (j.get("data") or {}).get("elements") or []:
                    walk(e)
                if els:
                    return els
                last = CliError("dump ui returned empty tree")
            else:
                last = CliError("dump ui failed rc=%d err=%s" % (rc, err[:200]))
        except Exception as e:
            last = e
        time.sleep(1.0)
    raise last if isinstance(last, Exception) else CliError(str(last))


def all_text(els=None):
    if els is None:
        els = dump_ui()
    return [e["text"] or e["desc"] for e in els if (e["text"] or e["desc"])]


def find(els, *kws, clickable=None):
    for e in els:
        if any(k in e["text"] or k in e["desc"] for k in kws):
            if clickable is None or e["clickable"] == clickable:
                return e
    return None


def find_all(els, *kws):
    return [e for e in els if any(k in e["text"] or k in e["desc"] for k in kws)]


def find_by_id(els, id_suffix, clickable=None):
    for e in els:
        if e["id"].endswith(id_suffix) or e["id"] == id_suffix:
            if clickable is None or e["clickable"] == clickable:
                return e
    return None


def tap(x, y):
    rc, out, err = run(["io", "tap", "%d,%d" % (int(x), int(y))], timeout=15)
    if rc != 0:
        raise CliError("tap %d,%d rc=%d %s" % (x, y, rc, err[:100]))


def tap_el(el):
    tap(el["x"] + el["w"] // 2, el["y"] + el["h"] // 2)


def text(s):
    rc, out, err = run(["io", "text", s], timeout=15)
    if rc != 0:
        raise CliError("text rc=%d %s" % (rc, err[:100]))


def swipe(x1, y1, x2, y2, dur=300):
    rc, out, err = run(["io", "swipe", "%d,%d,%d,%d" % (x1, y1, x2, y2),
                        "--duration", str(int(dur))], timeout=15)
    if rc != 0:
        raise CliError("swipe rc=%d %s" % (rc, err[:100]))


def focus():
    """焦点包名探针（dumpsys window，约 0.08s）。adb 用绝对路径：
    计划任务的 PATH 里经常没有 adb。"""
    p = subprocess.run([ADB, "-s", DEV, "shell", "dumpsys", "window", "|", "grep", "mCurrentFocus"],
                       capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=10)
    m = re.search(r"mCurrentFocus=Window\{[^}]*?([\w.]+)/", p.stdout or "")
    return m.group(1) if m else ""


def focus_comp():
    """完整组件名 包/Activity：广告 SDK 的 Activity 窗口归本包但 Activity 名不是
    MainActivity（HONOR 实测 BeiZi/Kwad 两家都如此）——组件离开/回到 MainActivity
    就是权威的广告起止信号。"""
    p = subprocess.run([ADB, "-s", DEV, "shell", "dumpsys", "window", "|", "grep", "mCurrentFocus"],
                       capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=10)
    m = re.search(r"mCurrentFocus=Window\{[^}]*?([\w.]+/[\w.$]+)\}", p.stdout or "")
    return m.group(1) if m else ""


def adb(args, timeout=10):
    p = subprocess.run([ADB, "-s", DEV, "shell"] + list(args),
                       capture_output=True, text=True, encoding="utf-8",
                       errors="replace", timeout=timeout)
    return p.stdout or ""


def launch(pkg="com.etalien.booster"):
    rc, out, err = run(["apps", "launch", pkg], timeout=20)
    return rc == 0


def terminate(pkg="com.etalien.booster"):
    rc, out, err = run(["apps", "terminate", pkg], timeout=15)
    return rc == 0
