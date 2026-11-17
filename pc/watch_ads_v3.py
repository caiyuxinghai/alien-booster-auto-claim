#!/usr/bin/env python3
# watch_ads_v3.py — 外星仔加速器「看广告领时长」引擎 v3
#
# 从 watch_batch_fast.py / watch_batch_vivo.py 两年实测经验重写（见 EXPERIENCE.md）：
#   1. 权威信号 = 焦点组件名(包/Activity)，不是屏幕文本：广告 Activity 窗口归本包但
#      Activity 名 != MainActivity；离开=广告开始，回 MainActivity=广告结束。
#   2. 弱机友好：焦点探针 0.14s，dump(2.3s，视频期 12s 超时)只在需要文本信号时用；
#      倒计时归零前 3s 不 dump；广告前台文本连续 2 次不变(Surface stale)停 dump。
#   3. 奖励安全不变式：绝不在 SDK 发奖前退出广告；发奖线实测 <=28s，兜底 30s；
#      发奖后 settle 8s 才杀进程；判账看阶段计数 + 时长白名单。
#   4. 设备抽象：uiautomator(HONOR 等) / mobilecli(OriginOS，uiautomator 被 ROM 禁用)。
#   5. 多设备并行：每设备一个 worker 线程，锁/flag/日志按 serial 隔离。
#   6. 自愈：USB 掉线、app 崩溃、空 dump、弹窗遮挡全部有重试路径。
#
# 用法：
#   py watch_ads_v3.py --selftest                  # 只探活+解析自检，不点广告(零消耗)
#   py watch_ads_v3.py --serial <设备序列号>          # 单设备跑满 21 轮
#   py watch_ads_v3.py --all                       # 所有在线设备并行
import argparse
import json
import os
import re
import subprocess
import sys
import threading
import time
import xml.etree.ElementTree as ET

# ADB_LIBUSB=1：新版 platform-tools 默认 USB 后端在 HONOR 上枚举不稳/挂死
# (get-state 卡死、devices 全空)。交接文档连接坑 #1，所有 adb subprocess 都继承此变量。
os.environ.setdefault("ADB_LIBUSB", "1")

HERE = os.path.dirname(os.path.abspath(__file__))
VERSION = "v3.4"

# ---------------- 经验常量（全部来自真机实测，勿随意改） ----------------
CD_PATTERNS = [           # 倒计时/秒数文案：v3 实测 + 0.6.0(代码-0.6.0)全部形态
    re.compile(r"(\d+)\s*(?:s|秒)\s*后(?:即可)?获[得取]奖励"),
    re.compile(r"(\d+)\s*s\s*后可领取奖励"),
    re.compile(r"奖励将于\s*(\d+)\s*秒后发放"),
    re.compile(r"(\d+)\s*秒后可领"),
    re.compile(r"(?:体验|浏览|去看)\s*(\d+)\s*秒"),
    re.compile(r"(\d+)\s*秒可立即领奖"),
    re.compile(r"(\d+)\s*秒可直接"),
]


def cd_seconds(t):
    """从屏幕文本读 SDK 倒计时秒数(3-60 有效，端口侧 App 的取值范围)"""
    for rex in CD_PATTERNS:
        m = rex.search(t)
        if m:
            n = int(m.group(1))
            if 3 <= n <= 60:
                return n
    return None


CD_MARGIN = 2.0          # 倒计时预计归零后再等 2s 才允许 BACK
CD_BACK_EVERY = 3.0      # 倒计时完成后每 3s BACK 催结束卡关闭
GRACE_AFTER_GRANT = 15   # 发奖后等回主界面的最长时间
FALLBACK_WAIT = 30.0     # 无文本信号形态的兜底(实测发奖 <=28s，覆盖最长标准激励视频)
SETTLE_AFTER_GRANT = 8   # 发奖后进程存活落账时间(实测 2s 杀=丢，8s 稳)
# 奖励页出现后的等待策略(10-22~10-23 真机四轮击杀实验 + 用户手动实验结论)：
# - 端侧 App(0.7.16)用「奖励页+1.2s 强杀」在其无障碍路径上成立；我的 ADB 路径
#   dumpsys 实时检测，4.6s 杀 = 奖励不到账(阴性实测)——杀太早。
# - 用户手动实验：广告看一半(~12-13s)就杀也到账——发奖点在视频中段。
# - 实测击杀窗口：4.6s 丢 / ~20s 到账(Octopus、穿山丁 2/2) / ~33s 到账(BeiZi 2/2)
#   / ~38s 到账(10-22 十天轮 10/10)。
# - 取值 15s：+5s 落账 = ~20s 起杀，覆盖全部已到账样本且比 25s 每轮快 ~11s。
#   主信号仍是焦点回归(Activity 自己关闭，BeiZi ~10s，更快)；15s 只是不关时的上限。
# 击杀点(从 tap 起算)——10-28 真机二分实验(每档 2 轮, force-stop 即时杀):
#   8s 丢 / 12s 丢 / 15s+2s落账(17s杀) 丢 / 18s+2s落账(20s杀) 到账 2/2
# 发奖落在 tap 后约 18-20s，与用户手动实验「看到一半就杀也到账」一致(~12-13s 视频中段)。
# 取 18s + 2s 落账 = 20s 起杀(比 15s 档多 3s 余量)，比旧版 23s 省 3s。
KILL_AT = 18.0
KILL_AFTER_AD_OPEN = 15.0   # 广告打开后至少再等这么久(发奖锚点在广告开始播放，不在 tap)
SETTLE_FAST = 2.0      # 落账等待(实测 2s 足够；旧版 5-8s 是过度保守)
# 旧值保留兼容
SESSION_MAX_WAIT = 15.0
QUOTA_RE = re.compile(r"今日已领完|明日再来|已领完|全部领取|已到上限|今日广告已看完")
# 强奖励文本(未知 SDK 类名时的文本判据；单独「跳过/加载中」不杀，走倒计时/兜底)
REWARD_TEXT_STRONG = ("领取奖励", "可立即领奖", "立即前往", "奖励已发放", "去看")
AD_TEXT_HINTS = ("跳过", "立即前往", "可立即领奖", "领取奖励", "去体验", "去看", "加载中", "请稍等")
HOME_TEXT_HINTS = ("阶段", "领时长", "可暂停", "点击重试")
AD_TIMEOUT = 45          # 单轮硬顶(比兜底大，防异常视频失控)
RELAUNCH_CAP = 18        # 重启后轮询分数上限(与端侧 App 的 waitCredit 18s 一致)
UI_POLL = 8.0            # dump 基础间隔
STALE_LOCK = 20 * 60     # 锁过期(分钟制)
DELTA_FLOOR = 500
DELTA_OK = (600, 1200, 1800)   # 时长增量白名单(10/20/30 分钟)
DEFAULT_PKG = "com.etalien.booster"
DEFAULT_MAIN_ACT = "com.etalien.booster.ui.MainActivity"
CTAS = ("看广告", "点广告")          # CTA 文案(HONOR:看广告 / vivo:点广告)
CTA_LABELS = ("领时长", "点击重试")    # 端侧 App 的 CTA 判据(取最低处节点)
DONE_TEXTS = ("明日再来", "已看完", "今日广告已看完")   # App 亲口说领完
GRANT_TEXTS = ("恭喜获得奖励", "奖励已发放", "领取成功", "已获得奖励")
AD_MARKS = ("跳过", "立即下载", "立即打开", "广告加载中", "获得奖励", "点击安装",
            "下载并安装", "继续观看", "再坚持", "秒后可领", "后可得奖励", "反馈")
MOBILE_CLI = os.path.join(HERE, "mobilecli-windows-amd64.exe")


def log(msg, logfile=None):
    line = "%s %s" % (time.strftime("%H:%M:%S"), msg)
    print(line, flush=True)
    if logfile:
        try:
            with open(logfile, "a", encoding="utf-8") as f:
                f.write(line + "\n")
        except Exception:
            pass


# ---------------- 设备后端 ----------------
class UiautoDevice:
    """正常 ROM：adb shell uiautomator dump。返回统一 nodes: [{text,desc,clickable,cx,cy}]"""

    name = "uiautomator"

    def __init__(self, serial, adb="adb"):
        self.serial = serial
        self.adb = adb
        self.user = 0          # 荣耀应用分身：0=机主/主号，128=分身

    def _run(self, args, timeout=15):
        try:
            p = subprocess.run([self.adb, "-s", self.serial] + args,
                               capture_output=True, text=True,
                               encoding="utf-8", errors="replace", timeout=timeout)
            return (p.stdout or "") + (p.stderr or "")
        except subprocess.TimeoutExpired:
            return ""          # 超时返空：调用方按无信息处理，绝不炸批(历史教训)

    def online(self):
        out = self._run(["get-state"], timeout=8)
        return out.strip() == "device"

    def focus_comp(self):
        out = self._run(["shell", "dumpsys", "window", "|", "grep", "mCurrentFocus"], timeout=10)
        m = re.search(r"mCurrentFocus=Window\{[^}]*?\s+([A-Za-z0-9_.]+/[A-Za-z0-9_.$]+)\}", out)
        return m.group(1) if m else ""

    def focus_user(self):
        """焦点窗口所属的 Android user(荣耀分身=u128)。mCurrentFocus 行形如
        Window{hash u128 com.etalien.booster/...} —— 用它确认前台是哪个账号实例。"""
        out = self._run(["shell", "dumpsys", "window", "|", "grep", "mCurrentFocus"], timeout=10)
        m = re.search(r"mCurrentFocus=Window\{[^}]*?\s+(u\d+)\s", out)
        return int(m.group(1)[1:]) if m else None

    def _dump_xml(self, timeout=12):
        # 视频 Surface 期 uiautomator 卡死 -> 超时返空(由上层 stale 逻辑消费)
        self._run(["shell", "uiautomator", "dump", "/sdcard/ui_v3.xml"], timeout=timeout)
        return self._run(["exec-out", "cat", "/sdcard/ui_v3.xml"], timeout=timeout)

    def dump_nodes(self, timeout=12):
        xml = self._dump_xml(timeout)
        if not xml.strip():
            return []
        try:
            root = ET.fromstring(xml)
        except Exception:
            return []
        nodes = []
        for n in root.iter("node"):
            b = n.get("bounds", "")
            m = re.search(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b)
            if not m:
                continue
            x1, y1, x2, y2 = map(int, m.groups())
            nodes.append({
                "text": (n.get("text") or "").strip(),
                "desc": (n.get("content-desc") or "").strip(),
                "clickable": n.get("clickable") == "true",
                "id": n.get("resource-id") or "",
                "cx": (x1 + x2) // 2, "cy": (y1 + y2) // 2,
            })
        return nodes

    def tap(self, x, y):
        self._run(["shell", "input", "tap", str(int(x)), str(int(y))], timeout=10)

    def back(self):
        self._run(["shell", "input", "keyevent", "KEYCODE_BACK"], timeout=10)

    def force_stop(self, pkg):
        self._run(["shell", "am", "force-stop", "--user", str(self.user), pkg], timeout=10)

    def start(self, comp):
        self._run(["shell", "am", "start", "--user", str(self.user), "-n", comp], timeout=15)

    def wake(self):
        self._run(["shell", "input", "keyevent", "KEYCODE_WAKEUP"], timeout=10)
        self._run(["shell", "svc", "power", "stayon", "true"], timeout=10)


class MobilecliDevice:
    """OriginOS：uiautomator 被 ROM 禁用(系统服务层拒绝 shell 注册)，用 mobilecli dex+RPC"""

    name = "mobilecli"

    def __init__(self, serial, mc=MOBILE_CLI, adb="adb"):
        self.serial = serial
        self.mc = mc
        self.adb = adb
        if not os.path.exists(mc):
            raise RuntimeError("mobilecli binary missing: %s" % mc)

    def _run(self, args, timeout=15):
        try:
            p = subprocess.run([self.adb, "-s", self.serial] + args,
                               capture_output=True, text=True,
                               encoding="utf-8", errors="replace", timeout=timeout)
            return (p.stdout or "") + (p.stderr or "")
        except subprocess.TimeoutExpired:
            return ""

    def _mc(self, args, timeout=25):
        try:
            p = subprocess.run([self.mc] + args + ["--device", self.serial],
                               capture_output=True, text=True,
                               encoding="utf-8", errors="replace", timeout=timeout)
            return p.stdout or ""
        except subprocess.TimeoutExpired:
            return ""

    def online(self):
        return self._run(["get-state"], timeout=8).strip() == "device"

    def focus_comp(self):
        out = self._run(["shell", "dumpsys", "window", "|", "grep", "mCurrentFocus"], timeout=10)
        m = re.search(r"mCurrentFocus=Window\{[^}]*?\s+([A-Za-z0-9_.]+/[A-Za-z0-9_.$]+)\}", out)
        return m.group(1) if m else ""

    def focus_user(self):
        out = self._run(["shell", "dumpsys", "window", "|", "grep", "mCurrentFocus"], timeout=10)
        m = re.search(r"mCurrentFocus=Window\{[^}]*?\s+(u\d+)\s", out)
        return int(m.group(1)[1:]) if m else None

    def dump_nodes(self, timeout=25):
        out = self._mc(["dump", "ui"], timeout=timeout)
        if not out.strip():
            return []
        try:
            j = json.loads(out)
        except Exception:
            return []
        nodes = []

        def walk(e):
            r = e.get("rect") or {}
            nodes.append({
                "text": (e.get("text") or "").strip(),
                "desc": (e.get("contentDescription") or e.get("desc") or "").strip(),
                "clickable": bool(e.get("clickable")),
                "id": e.get("id") or "",
                "cx": int(r.get("x") or 0) + int(r.get("width") or 0) // 2,
                "cy": int(r.get("y") or 0) + int(r.get("height") or 0) // 2,
            })
            for c in (e.get("children") or []):
                walk(c)

        for e in (j.get("data") or {}).get("elements") or []:
            walk(e)
        return nodes

    def tap(self, x, y):
        self._mc(["io", "tap", "%d,%d" % (int(x), int(y))], timeout=15)

    def back(self):
        self._run(["shell", "input", "keyevent", "KEYCODE_BACK"], timeout=10)

    def force_stop(self, pkg):
        self._run(["shell", "am", "force-stop", "--user", str(self.user), pkg], timeout=10)

    def start(self, comp):
        self._run(["shell", "am", "start", "--user", str(self.user), "-n", comp], timeout=15)

    def wake(self):
        self._run(["shell", "input", "keyevent", "KEYCODE_WAKEUP"], timeout=10)
        self._run(["shell", "svc", "power", "stayon", "true"], timeout=10)


def make_device(serial, backend="auto"):
    """auto：先探 uiautomator，dump 拿不到东西(OriginOS 静默失败)就上 mobilecli"""
    dev = UiautoDevice(serial)
    if backend in ("auto", "uiauto"):
        nodes = dev.dump_nodes(timeout=8)
        if nodes or backend == "uiauto":
            return dev
    if backend in ("auto", "mobilecli"):
        if os.path.exists(MOBILE_CLI):
            return MobilecliDevice(serial)
    return dev


# ---------------- 通用解析(设备无关) ----------------
def stext(nodes):
    return " ".join((n["text"] or n["desc"]) for n in nodes if (n["text"] or n["desc"]).strip())


def find(nodes, *kws, clickable=None):
    for n in nodes:
        if any(k in n["text"] or k in n["desc"] for k in kws):
            if clickable is None or n["clickable"] == clickable:
                return n
    return None


TIER_TOTAL = {"阶段一": 9, "阶段二": 3, "加油包": 9}


def _stages_by_id(nodes):
    """HONOR：阶段行有 resource-id(...UITitle 标题 / ...UIStateTitle 状态)"""
    out = []; cur = None
    for n in nodes:
        i = n.get("id") or ""
        if i.endswith("UITitle"):
            cur = {"title": (n["text"] or "").strip(), "state": None}; out.append(cur)
        elif i.endswith("UIStateTitle") and out:
            out[-1]["state"] = (n["text"] or "").strip()
    return out


def _stages_by_text(nodes):
    """无 resource-id 设备(vivo/OriginOS)：标题文字 + 右侧同纵带状态文字"""
    out = []
    for e in nodes:
        t = (e["text"] or "").strip()
        if t in TIER_TOTAL:
            st = None
            for s in nodes:
                if s is e:
                    continue
                if s["cx"] >= e["cx"] + 200 and abs(s["cy"] - e["cy"]) <= 120:
                    stt = (s["text"] or "").strip()
                    if re.search(r"\d+\s*/\s*\d+", stt) or "待解锁" in stt or "已完成" in stt:
                        st = stt; break
            out.append({"title": t, "state": st})
    return out


def stages(nodes):
    s = _stages_by_id(nodes)
    return s if s else _stages_by_text(nodes)


def score(nodes):
    """阶段计数之和：X/Y 取 X；已完成按满额(9/3/9)；待解锁 0"""
    tot = 0
    for s in stages(nodes):
        state = s["state"] or ""
        m = re.search(r"(\d+)\s*/\s*(\d+)", state)
        if m:
            tot += int(m.group(1))
        elif "已完成" in state:
            tot += TIER_TOTAL.get(s["title"], 0)
    return tot


def dur(nodes):
    """总时长(秒)：HONOR 走 resource-id；无 id 设备走 joined-text 正则"""
    h = next((n["text"] for n in nodes if "DurationHourTxt" in (n.get("id") or "")), None)
    m = next((n["text"] for n in nodes if "DurationMinuteTxt" in (n.get("id") or "")), None)
    s = next((n["text"] for n in nodes if "DurationSecondTxt" in (n.get("id") or "")), None)
    if h is None or m is None:
        mm = re.search(r"(\d+)\s*时\s*(\d+)\s*分\s*(\d+)\s*秒", stext(nodes))
        if mm:
            return int(mm.group(1)) * 3600 + int(mm.group(2)) * 60 + int(mm.group(3))
        return None
    try:
        return int(h) * 3600 + int(m) * 60 + int(s or 0)
    except Exception:
        return None


def on_main(nodes):
    t = stext(nodes)
    if not find(nodes, *CTAS):
        return False
    return not any(k in t for k in AD_MARKS)


def app_done(nodes):
    """App 亲口说领完：QUOTA 文案，或三个「已完成」且无待解锁/无比例(端侧 App 判据)"""
    t = stext(nodes)
    if QUOTA_RE.search(t):
        return True
    texts = [(n["text"] or "").strip() for n in nodes]
    done = sum(1 for x in texts if x == "已完成")
    still_open = any(x == "待解锁" or re.search(r"\d+\s*/\s*\d+", x) for x in texts)
    return done >= 3 and not still_open


def left_main(comp, pkg):
    """焦点在 booster 包内但不在 MainActivity = 广告 Activity 在前台"""
    if not comp:
        return False
    p, _, cls = comp.partition("/")
    return bool(p == pkg and cls and not cls.endswith("MainActivity"))


def reward_act(comp, pkg):
    """奖励页类名判定——端口侧 App(0.7.16)生产验证的 rewardActivity()：
    transparent 排除；beizi 必须含 reward/video；stopover 或 *AdActivity 结尾 = 奖励页。
    实测覆盖：广点通 PortraitADActivity / BeiZi / Octopus AdActivity。"""
    if not left_main(comp, pkg):
        return False
    c = comp.partition("/")[2].lower()
    if "transparent" in c:
        return False
    if "beizi" in c:
        return ("reward" in c) or ("video" in c)
    return "stopover" in c or c.endswith("adactivity")


AD_SESSION_HINTS = ("kwad", "byazt", "ksad", "pangle", "topon", "mintegral", "sigmob",
                    "klevin", "unityads", "applovin", "gdtad", "qq.e", "reward",
                    "advert", "interstitial", "splash")
# 注意：AD_SESSION_HINTS 是端侧 App 的 isAd() 列表，只用于导航/等待判断；
# v3 的击杀判定不用它——通用提示会误杀非奖励页(如 PangleSplashActivity)。


def ad_session(comp, pkg):
    """广告页(奖励页)判定 = 0.7.16 的 sessionOpen/rewardActivity 融合：
    - 已离开 MainActivity 且焦点归本包(boosterLeftMain)；
    - transparent 一律排除(cj.mobile 透明壳停了不加时)；
    - beizi 严格：必须含 reward/video；
    - 类名精确：stopover 或 *AdActivity 结尾；
    - 激励视频活动：类名同时含 reward+video(如 Kwad KsRewardVideoActivity，
      与 beizi+video 同款规则形状，类名自带描述)；
    - 其余类名不猜(未验证形态走倒计时/30s 兜底，宁可慢不丢奖励)。"""
    if not left_main(comp, pkg):
        return False
    c = comp.partition("/")[2].lower()
    if "transparent" in c:
        return False
    if "beizi" in c:
        return ("reward" in c) or ("video" in c)
    if "stopover" in c or c.endswith("adactivity"):
        return True
    return ("reward" in c) and ("video" in c)


def home_like_text(t):
    return any(k in t for k in HOME_TEXT_HINTS)


def strong_reward_text(t):
    return any(k in t for k in REWARD_TEXT_STRONG)


def find_cta(nodes):
    """CTA 定位：端侧 App 判据是「领时长/点击重试」取最低处节点；退回看广告/点广告"""
    cands = [n for n in nodes
             if any(k in (n["text"] or "") or k in (n["desc"] or "") for k in CTA_LABELS)]
    if cands:
        return max(cands, key=lambda n: n["cy"])
    for kw in CTAS:
        for n in nodes:
            if kw in (n["text"] or "") or kw in (n["desc"] or ""):
                return n
    return None


def find_cancel(nodes):
    """App 自己的退出确认框「温馨提醒 确定要退出吗？ 取消 确认」——
    BACK 误触后退出会弹它，盖住 CTA，必须点「取消」。"""
    t = stext(nodes)
    if not (("确定要退出" in t) or ("是否退出" in t) or ("退出应用" in t)):
        return None
    for n in nodes:
        if (n["text"] or "").strip() == "取消":
            return n
    return None


def find_retry(nodes):
    """广告加载失败态「广告加载失败，点击重试」——点重试恢复取广告"""
    t = stext(nodes)
    if not (("加载失败" in t) or ("广告加载失败" in t)):
        return None
    for n in nodes:
        txt = (n["text"] or "") + (n["desc"] or "")
        if "重试" in txt:
            return n
    return None


def find_deny(nodes):
    """荣耀「想要打开其他应用」弹窗：拒绝+允许同时在场才点拒绝"""
    t = stext(nodes)
    if "拒绝" not in t or "允许" not in t:
        return None
    for n in nodes:
        if (n["text"] or "").strip() == "拒绝":
            return n
    return None


# ---------------- 状态机 ----------------
class Worker:
    def __init__(self, dev, args):
        self.dev = dev
        self.pkg = args.pkg
        self.main_act = args.main_act
        self.comp = args.comp
        self.max_slots = args.max_slots
        self.user = getattr(args, "user", 0)
        self.dev.user = self.user
        # flag/log/stats 按 user 隔离(两号额度独立)；锁按设备共享(同机必须串行)
        self.logfile = os.path.join(HERE, "batch_%s_u%d.log" % (dev.serial, self.user))
        self.flag = os.path.join(HERE, "claimed_%s_u%d_%s.flag" %
                                 (dev.serial, self.user, time.strftime("%Y-%m-%d")))
        self.lock = os.path.join(HERE, "run_%s.lock" % dev.serial)
        self.stats = os.path.join(HERE, "stats_%s_u%d.jsonl" % (dev.serial, self.user))
        self.stop = False

    def lg(self, msg):
        log(msg, self.logfile)

    # -- 锁 --
    @staticmethod
    def _pid_alive(pid):
        try:
            import ctypes
            h = ctypes.windll.kernel32.OpenProcess(0x1000, False, int(pid))
            if h:
                ctypes.windll.kernel32.CloseHandle(h)
                return True
            return False
        except Exception:
            return True      # 判不出来就当活着，宁可等 stale

    def acquire(self):
        for _ in range(3):
            try:
                fd = os.open(self.lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
                os.write(fd, str(os.getpid()).encode()); os.close(fd)
                return True
            except FileExistsError:
                # 锁里的进程已死(被杀/崩溃) -> 立即清掉，别等 20 分钟 stale
                try:
                    with open(self.lock, "r") as f:
                        owner = f.read().strip()
                    if owner and not self._pid_alive(owner):
                        os.remove(self.lock); continue
                except Exception:
                    pass
                try:
                    if time.time() - os.path.getmtime(self.lock) > STALE_LOCK:
                        os.remove(self.lock); continue
                except Exception:
                    pass
                return False
            except Exception:
                return True
        return False

    def heartbeat(self):
        try:
            open(self.lock, "a").close()
        except Exception:
            pass

    # -- 基础动作 --
    def wait_ready(self, cap=10):
        t = time.time(); first = True
        while time.time() - t < cap:
            comp = self.dev.focus_comp()
            if comp and comp.split("/")[0] == self.pkg:
                if first:
                    time.sleep(0.7); first = False     # 首帧渲染完再 dump
                nodes = self.dev.dump_nodes()
                self.clear_blockers(nodes)
                if score(nodes) or find_cta(nodes):
                    return nodes
                if not stext(nodes):
                    time.sleep(1.0)                    # 冷启动抽空：多等等
                else:
                    time.sleep(0.35)
            else:
                # 前台不是本包：可能是跳转弹窗/设置页——清一次再试
                nd = self.dev.dump_nodes()
                if self.clear_blockers(nd):
                    continue
                time.sleep(0.25)
        return []

    def relaunch(self):
        self.dev.force_stop(self.pkg)
        time.sleep(0.5)
        self.dev.start(self.comp)
        return self.wait_ready()

    def clear_blockers(self, nodes):
        """清理遮挡（10-22/10-28 真机教训，端侧 App 在三处都做了同类处理）：
        ① 荣耀跳转确认弹窗「想要打开拼多多」拒绝/允许——不点拒绝则 force-stop 也清不掉；
        ② App 退出确认框「确定要退出吗？取消 确认」——BACK 误触弹出的，盖住 CTA；
        ③ 广告加载失败「点击重试」——点它重新取广告。"""
        deny = find_deny(nodes)
        if deny:
            self.lg("  jump dialog -> tap 拒绝 (%d,%d)" % (deny["cx"], deny["cy"]))
            self.dev.tap(deny["cx"], deny["cy"])
            time.sleep(0.8)
            return True
        cancel = find_cancel(nodes)
        if cancel:
            self.lg("  exit-confirm dialog -> tap 取消 (%d,%d)" % (cancel["cx"], cancel["cy"]))
            self.dev.tap(cancel["cx"], cancel["cy"])
            time.sleep(0.8)
            return True
        retry = find_retry(nodes)
        if retry:
            self.lg("  ad load failed -> tap 点击重试 (%d,%d)" % (retry["cx"], retry["cy"]))
            self.dev.tap(retry["cx"], retry["cy"])
            time.sleep(1.5)
            return True
        return False

    def ensure_cta(self):
        """返回 (nodes, cta_node, status)；status: ok/done/login/none"""
        for attempt in range(3):
            # 前台实例校验：主号(u0)/分身(u128) 包名与 Activity 完全相同，读错实例
            # 会看到另一个账号的「今日已看完」而误判 done(10-26/10-28 各发生一次)。
            # 另外分身广告的跳转会把应用市场开到默认 user0 并抢前台，更要靠这个守卫。
            fu = self.dev.focus_user()
            if fu is not None and fu != self.user:
                self.lg("  foreground is user %d, want %d -> relaunch" % (fu, self.user))
                self.dev.force_stop(self.pkg); time.sleep(0.5)
                self.dev.start(self.comp); time.sleep(1.2)
                continue
            nodes = self.dev.dump_nodes()
            self.clear_blockers(nodes)
            pop = find(nodes, "领取", "开心收下", "确定", "好的", clickable=True)
            if pop:
                self.dev.tap(pop["cx"], pop["cy"]); time.sleep(1.0)
                nodes = self.dev.dump_nodes()
                self.clear_blockers(nodes)
            if find(nodes, "登录/注册"):
                return nodes, None, "login"
            if app_done(nodes):
                return nodes, None, "done"
            cta = find_cta(nodes)
            if cta:
                return nodes, cta, "ok"
            # 自愈：冷启(隔夜/弹窗遮挡时 CTA 可能还没画出来)
            self.lg("  no CTA (attempt %d) -> force-stop + relaunch" % (attempt + 1))
            nodes = self.relaunch()
            self.clear_blockers(nodes)
            pop = find(nodes, "领取", "开心收下", "确定", "好的", clickable=True)
            if pop:
                self.dev.tap(pop["cx"], pop["cy"]); time.sleep(1.0)
                nodes = self.dev.dump_nodes()
                self.clear_blockers(nodes)
            if find(nodes, "登录/注册"):
                return nodes, None, "login"
            if app_done(nodes):
                return nodes, None, "done"
            cta = find_cta(nodes)
            if cta:
                return nodes, cta, "ok"
        return self.dev.dump_nodes(), None, "none"

    # -- 核心：看到广告结束为止 --
    def watch(self, base_text):
        """轮询直到广告结束。返回 (end_signal, t_wait, extra)
        信号优先级(=可靠度排序，端口侧 App 生产逻辑 + ADB 路径实测修正)：
          0. session     奖励页类名出现 -> 等焦点回归(Activity 关闭)；25s 上限才杀
          1. focus_back  焦点回 MainActivity = 广告结束
          2. countdown   SDK 倒计时归零 = 发奖(浏览页形态)
          3. grant_text  发奖文案
          4. fallback    30s 兜底
        """
        t0 = time.time()
        last_ui = 0.0; ended_main = False; t_ad = None; t_grant = None
        saw_ad = False; poll = UI_POLL
        cd_expect = None; cd_done = None; cd_back = 0.0; cd_n0 = None
        ad_seen = False; stale = 0; last_ad_text = ""; dump_off = False
        grant_at = None; grant_back = 0.0
        session_at = None; session_why = ""      # 奖励页(端侧 App 的 sessionOpen)
        fb_logged = False                        # 焦点回归(广告 UI 关闭)只记录不提前杀
        last_t_strong = False                     # 最近一次 dump 是否为强奖励文本
        away_checked = False                      # 焦点离开本包时是否已查过弹窗
        while time.time() - t0 < AD_TIMEOUT:
            now = time.time()
            comp = self.dev.focus_comp()               # 0.14s 骨架探针
            pkg, _, act = comp.partition("/")
            ad_front = bool(pkg == self.pkg and act and act != self.main_act)
            if ad_front and not ad_seen:
                ad_seen = True
                t_ad = now - t0
                self.lg("  ad activity @+%.1fs: %s" % (now - t0, act))
            # 信号1：焦点回主界面 = 广告 UI 关闭。但实测「UI 关闭 ≠ 奖励已落地」
            # (10-28 二分：17s 杀丢、20s 杀到账)，所以这里只记录不提前杀。
            if ad_seen and pkg == self.pkg and act == self.main_act and not fb_logged:
                fb_logged = True
                self.lg("  focus back to main @+%.1fs (ad closed, keep waiting for grant)" % (now - t0))
            # 焦点被别的包抢走(系统弹窗/应用市场)：查一次跳转弹窗并点拒绝
            if pkg and pkg != self.pkg and not away_checked:
                away_checked = True
                nd = self.dev.dump_nodes()
                if self.clear_blockers(nd):
                    continue
            if pkg == self.pkg:
                away_checked = False
            if dump_off and pkg == self.pkg and act == self.main_act:
                dump_off = False; stale = 0; last_ad_text = ""
            # 信号0a：奖励页类名(端口侧 App 的 rewardActivity，四家 SDK 全覆盖)
            if ad_front and session_at is None and ad_session(comp, self.pkg):
                session_at = now; session_why = "class:%s" % act
                dump_off = True        # 类名已确认，转纯探针等焦点回归(慢 dump 会推迟判定)
                self.lg("  reward session armed @+%.1fs (%s)" % (now - t0, session_why))
            # 信号0b：未知类名 + 强奖励文本(领取奖励/可立即领奖/立即前往)
            if ad_front and session_at is None and last_t_strong:
                session_at = now; session_why = "text"
                dump_off = True
                self.lg("  reward session armed @+%.1fs (strong text)" % (now - t0))
            # 击杀点(从 tap 起算)：发奖实测在 18-20s 落地，倒计时完成则更晚
            kill_at = KILL_AT
            if ad_seen:
                # 广告打开得慢(网络/素材加载慢，实测见过 15s 才开)时顺延：
                # 发奖锚点在「广告开始播放」而不是 tap，必须按广告打开时刻 +15s 算
                kill_at = max(kill_at, (t_ad or 0.0) + KILL_AFTER_AD_OPEN)
            if cd_done is not None:
                kill_at = max(kill_at, cd_done - t0 + 3.0)
            if now - t0 >= kill_at:
                self.lg("  kill-at +%.0fs (grant landed window)" % kill_at)
                return "kill_at", now - t0, {"cd": cd_n0}
            # 信号4：兜底(无任何文本信号形态，实测发奖 <=28s)——必须在节食 continue 之前判，
            # 否则 stale 停 dump 后每轮都 continue，兜底永远不触发
            if session_at is None and (ad_seen or saw_ad) and cd_expect is None \
                    and grant_at is None and now - t0 > FALLBACK_WAIT:
                self.lg("  no text signal by +%.0fs -> fallback" % FALLBACK_WAIT)
                return "fallback", now - t0, {"cd": cd_n0}
            due = (now - last_ui >= poll) or (pkg == self.pkg and now - last_ui >= 1.0)
            # dump 节食：倒计时归零前 3s / stale 期间，只探针
            if due and cd_expect is not None and pkg == self.pkg and now < cd_expect - 3.0:
                time.sleep(0.5); continue
            if due and dump_off:
                time.sleep(0.5); continue
            if due:
                d0 = time.time()
                nodes = self.dev.dump_nodes(); last_ui = time.time()
                t = stext(nodes)
                if last_ui - d0 > 5.0:
                    poll = min(poll * 1.5, 15.0)
                if t and t != base_text:
                    saw_ad = True
                # 荣耀「想要打开其他应用」弹窗：点拒绝(和取消同坐标，端侧 App 经验)
                deny = find_deny(nodes)
                if deny:
                    self.dev.tap(deny["cx"], deny["cy"]); time.sleep(0.5)
                    continue
                # 视频 Surface 文本 stale：连续 2 次不变/空 -> 停 dump
                if ad_front:
                    if (t and t == last_ad_text) or not t:
                        stale += 1
                    else:
                        stale = 0
                    if t:
                        last_ad_text = t
                    if stale >= 2 and not dump_off:
                        dump_off = True
                        self.lg("  stale screen during ad -> probe-only")
                # 信号2：SDK 倒计时(精确发奖预测，覆盖 0.6.0 全部文案形态)
                n = cd_seconds(t)
                if n is not None:
                    if cd_expect is None:
                        cd_expect = now + n; cd_n0 = n
                        self.lg("  CD first seen: %ds -> expect grant @+%.0fs" % (n, n))
                    else:
                        alt = now + n
                        if alt > cd_expect:
                            cd_expect = alt
                elif cd_expect is not None and cd_done is None and now >= cd_expect + CD_MARGIN:
                    cd_done = now
                    self.lg("  CD done @+%.0fs (expect +%.0f)" % (cd_done - t0, cd_expect - t0))
                # 信号0b 的文本判据更新(供下一轮焦点分支用)
                last_t_strong = ad_front and bool(t) and not home_like_text(t) \
                    and strong_reward_text(t)
                # 信号3：发奖文案
                if any(k in t for k in GRANT_TEXTS):
                    sk = find(nodes, "跳过", "关闭", "知道了", clickable=True)
                    if sk:
                        self.dev.tap(sk["cx"], sk["cy"]); time.sleep(1.0)
                    if grant_at is None:
                        grant_at = time.time()
                if grant_at is not None:
                    if now - grant_back > CD_BACK_EVERY:
                        self.dev.back(); grant_back = time.time()
                    if now - grant_at > GRACE_AFTER_GRANT:
                        return "grant_grace", now - t0, {"cd": cd_n0}
                elif saw_ad and on_main(nodes) and pkg == self.pkg and act == self.main_act:
                    return "on_main", now - t0, {"cd": cd_n0}
            # 倒计时完成后催关
            if cd_done is not None and grant_at is None:
                if now - cd_done > CD_BACK_EVERY and now - cd_back > CD_BACK_EVERY:
                    self.dev.back(); cd_back = time.time()
                if now - cd_done > GRACE_AFTER_GRANT:
                    return "cd_grace", now - t0, {"cd": cd_n0}
            time.sleep(0.5)
        return "timeout", time.time() - t0, {"cd": cd_n0}

    # -- 单轮 --
    def cycle(self):
        nodes, cta, status = self.ensure_cta()
        if status == "done":
            return "done", None
        if status == "login":
            self.lg("  NOT LOGGED IN (login screen) -> exit 4")
            return "login", None
        if cta is None:
            self.lg("  NO CTA after retries: %s" % stext(nodes)[:200])
            return "nocta", None
        before_s = score(nodes); before_d = dur(nodes)
        base_text = stext(nodes)
        t0 = time.time()
        self.dev.tap(cta["cx"], cta["cy"])
        sig, t_wait, extra = self.watch(base_text)
        if sig == "reward_act":
            pass
        else:
            # 落账等待：发奖/倒计时/兜底/超时 8s(实测 2s 杀=丢奖励)；
            # 焦点回主/已在主界面的形态广告已确定结束，5s 保底(异步回调未落完也要等)
            time.sleep(SETTLE_FAST if sig in ("kill_at", "focus_back", "on_main", "session_max")
                       else SETTLE_AFTER_GRANT)
            # 回主界面确认(焦点探针优先，省 dump)
            for _ in range(3):
                comp = self.dev.focus_comp()
                pkg, _, act = comp.partition("/")
                if pkg == self.pkg and act == self.main_act:
                    nd = self.dev.dump_nodes()
                    if on_main(nd):
                        break
                self.dev.back(); time.sleep(0.8)
        # 同步：软退广告(1 次 BACK，让 SDK 走完关闭回调) → force-stop → 冷启动重开
        # 10-28 教训：热拉起(不 force-stop)会残留广告 Activity 在前台，App 卡死、
        # 后续点 CTA 全无效(6 连 X)；且热拉起刷新计数不可靠。必须 force-stop。
        comp = self.dev.focus_comp()
        pkg, _, act = comp.partition("/")
        if not (pkg == self.pkg and act == self.main_act):
            self.dev.back(); time.sleep(0.6)
        self.dev.force_stop(self.pkg); time.sleep(0.5)
        nd = self.relaunch()
        t_sync = time.time()
        ok = False
        retried = 0
        while time.time() - t_sync < RELAUNCH_CAP:
            if not nd:
                nd = self.dev.dump_nodes()
            # 跳转弹窗/应用市场挡前台：点拒绝；还拉不到本包就重杀重拉(10-22 真机教训)
            if self.clear_blockers(nd):
                nd = None
                comp = self.dev.focus_comp()
                if (not comp or comp.split("/")[0] != self.pkg) and retried < 2:
                    retried += 1
                    self.dev.force_stop(self.pkg); time.sleep(0.5)
                    nd = self.relaunch()
                continue
            if score(nd) > before_s:
                ok = True; break
            d = dur(nd)
            if d is not None and before_d is not None and (d - before_d) >= DELTA_FLOOR:
                ok = True; break
            nd = None
            time.sleep(0.3)
        nd = nd or self.dev.dump_nodes()
        s1 = score(nd); d1 = dur(nd)
        # 注意用 is not None：新账号 before_d=0 是 falsy，不能用 (d1 and before_d)
        dd = (d1 - before_d) if (d1 is not None and before_d is not None) else None
        credited = ok or (s1 > before_s) or (dd is not None and dd >= DELTA_FLOOR)
        rec = {"t": round(time.time() - t0, 1), "wait": round(t_wait, 1), "sig": sig,
               "credited": credited, "score": s1, "dur_delta": dd}
        try:
            with open(self.stats, "a", encoding="utf-8") as f:
                f.write(json.dumps(rec, ensure_ascii=False) + "\n")
        except Exception:
            pass
        self.lg("  [cycle] %.0fs (wait %.0f) sig=%s score=%d dur+=%s %s" %
                (rec["t"], rec["wait"], sig, s1, dd, "OK" if credited else "X"))
        return "ok" if credited else "fail", rec

    # -- 主循环 --
    def run(self):
        if os.path.exists(self.flag):
            self.lg("already claimed today, exit 0")
            return 0
        if not self.dev.online():
            self.lg("DEVICE OFFLINE - plug in and enable USB debugging, exit 9")
            return 9
        if not self.acquire():
            self.lg("another run in progress, exit 0")
            return 0
        self.dev.wake()
        # 先把本 user 的实例拉到前台再读屏：主号/分身的包名与 Activity 完全相同，
        # 只靠 dump 无法区分是哪个实例——必须先抢前台，否则会读到另一个账号的界面。
        self.lg("  bring user %d to front" % self.user)
        self.relaunch()
        credits = 0; soft = 0
        self.lg("=== %s start serial=%s backend=%s %s ===" %
                (VERSION, self.dev.serial, self.dev.name, time.strftime("%Y-%m-%d %H:%M:%S")))
        try:
            for slot in range(self.max_slots):
                self.heartbeat()
                status, rec = self.cycle()
                if status == "done":
                    self.lg("[slot %d] app says done -> write flag, exit 0" % (slot + 1))
                    self._write_flag(credits)
                    return 0
                if status == "login":
                    self._write_flag(credits, suffix="_LOGIN")
                    return 4
                if status == "nocta":
                    if slot == 0:
                        self.lg("[slot 1] NO CTA after retries -> exit 3")
                        return 3
                    self.lg("[slot %d] NO CTA -> today's quota exhausted" % (slot + 1))
                    self._write_flag(credits)
                    return 0
                if status == "ok":
                    credits += 1; soft = 0
                    if credits >= self.max_slots:
                        break
                else:
                    soft += 1
                    if soft >= 4:
                        self.lg("  stop: too many consecutive fails")
                        break
                    time.sleep(10)
        finally:
            try:
                os.remove(self.lock)
            except Exception:
                pass
            # stayon 复原：跑完允许熄屏(交接文档坑：长亮发热且掉线时关不掉)
            try:
                self.dev._run(["shell", "svc", "power", "stayon", "false"], timeout=8)
            except Exception:
                pass
        if credits >= self.max_slots:
            self._write_flag(credits)
        self.lg("=== DONE credits=%d/%d %s ===" % (credits, self.max_slots, time.strftime("%H:%M:%S")))
        return 0 if credits >= self.max_slots else 2

    def _write_flag(self, credits, suffix=""):
        try:
            with open(self.flag + suffix, "w", encoding="utf-8") as f:
                f.write("credits=%d at %s\n" % (credits, time.strftime("%H:%M:%S")))
        except Exception:
            pass


# ---------------- 入口 ----------------
def list_devices():
    try:
        p = subprocess.run(["adb", "devices"], capture_output=True, text=True,
                           encoding="utf-8", errors="replace", timeout=10)
        out = []
        for line in (p.stdout or "").splitlines()[1:]:
            parts = line.split()
            if len(parts) >= 2 and parts[1] == "device":
                out.append(parts[0])
        return out
    except Exception:
        return []


def selftest(serial):
    print("=== selftest serial=%s (零消耗：不点广告) ===" % serial)
    dev = make_device(serial)
    print("backend:", dev.name)
    print("online:", dev.online())
    comp = dev.focus_comp()
    print("focus_comp:", comp)
    nodes = dev.dump_nodes()
    print("nodes:", len(nodes))
    print("text:", stext(nodes)[:200])
    print("score:", score(nodes), " dur:", dur(nodes))
    cta = find_cta(nodes)
    print("cta:", cta)
    print("on_main:", on_main(nodes), " app_done:", app_done(nodes))
    print("=== selftest done ===")


def main():
    ap = argparse.ArgumentParser(description="watch ads claim-time engine v3")
    ap.add_argument("--serial", help="device serial (default: the only online device)")
    ap.add_argument("--all", action="store_true", help="run all online devices in parallel")
    ap.add_argument("--selftest", action="store_true", help="probe+parse only, no tapping")
    ap.add_argument("--backend", choices=["auto", "uiauto", "mobilecli"], default="auto")
    ap.add_argument("--pkg", default=DEFAULT_PKG)
    ap.add_argument("--main-act", default=DEFAULT_MAIN_ACT)
    ap.add_argument("--comp", default=DEFAULT_PKG + "/.ui.MainActivity",
                    help="launch component (OriginOS uses .ui.SplashActivity)")
    ap.add_argument("--max-slots", type=int, default=24)
    ap.add_argument("--user", type=int, default=0, help="Android user id: 0=机主, 128=分身")
    args = ap.parse_args()

    if args.selftest:
        serials = [args.serial] if args.serial else list_devices()
        for s in serials:
            selftest(s)
        return 0

    serials = []
    if args.all:
        serials = list_devices()
    elif args.serial:
        serials = [args.serial]
    else:
        online = list_devices()
        if len(online) == 1:
            serials = online
        elif not online:
            print("no device online, exit 9", flush=True)
            return 9
        else:
            print("multiple devices, use --serial or --all: %s" % online, flush=True)
            return 9

    results = {}

    def work(s):
        try:
            dev = make_device(s, args.backend)
            results[s] = Worker(dev, args).run()
        except Exception as e:
            import traceback
            tb = traceback.format_exc()
            log("worker %s crashed: %r" % (s, e))
            try:
                with open(os.path.join(HERE, "batch_%s_crash.log" % s), "a", encoding="utf-8") as f:
                    f.write(time.strftime("%Y-%m-%d %H:%M:%S") + "\n" + tb + "\n")
            except Exception:
                pass
            results[s] = 1

    if len(serials) == 1:
        return work(serials[0])
    threads = [threading.Thread(target=work, args=(s,)) for s in serials]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    worst = max(results.values()) if results else 1
    return worst


if __name__ == "__main__":
    sys.exit(main())
