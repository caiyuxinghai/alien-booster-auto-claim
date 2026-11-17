# test_v3_mock.py — v3.1 状态机单测（mock 设备，零真机消耗）
# 覆盖：reward_act(奖励页+1.2s 杀) / countdown / focus_back / fallback 四路径
#       + ensure_cta 的 done/login + denyJump 弹窗 + reward_act 类名判定
import sys, time, types
sys.path.insert(0, ".")
import watch_ads_v3 as V3

MAIN = "com.etalien.booster/com.etalien.booster.ui.MainActivity"
GDT = "com.etalien.booster/com.qq.e.ads.PortraitADActivity"     # 广点通(奖励页)
WEB = "com.etalien.booster/com.etalien.booster.ui.WebActivity"  # 非奖励页(应用内网页)


def main_nodes(score=0, dur_s=0, with_cta=True, with_done=False, deny=False):
    h, m, s = dur_s // 3600, dur_s % 3600 // 60, dur_s % 60
    ns = [
        {"text": str(h), "desc": "", "clickable": False,
         "id": "com.etalien.booster:id/DurationHourTxt", "cx": 100, "cy": 60},
        {"text": str(m), "desc": "", "clickable": False, "id": "x:id/DurationMinuteTxt", "cx": 0, "cy": 0},
        {"text": str(s), "desc": "", "clickable": False, "id": "x:id/DurationSecondTxt", "cx": 0, "cy": 0},
        {"text": "阶段一", "desc": "", "clickable": False, "id": "p:id/UITitle", "cx": 165, "cy": 1024},
        {"text": "%d / 9" % score, "desc": "", "clickable": False,
         "id": "p:id/UIStateTitle", "cx": 882, "cy": 1024},
        {"text": "阶段二", "desc": "", "clickable": False, "id": "p:id/UITitle", "cx": 165, "cy": 1300},
        {"text": "待解锁", "desc": "", "clickable": False, "id": "p:id/UIStateTitle", "cx": 882, "cy": 1300},
        {"text": "加油包", "desc": "", "clickable": False, "id": "p:id/UITitle", "cx": 165, "cy": 1576},
        {"text": "待解锁", "desc": "", "clickable": False, "id": "p:id/UIStateTitle", "cx": 882, "cy": 1576},
    ]
    if with_cta:
        ns.append({"text": "看广告 领时长", "desc": "", "clickable": True,
                   "id": "p:id/UIADSubmit", "cx": 540, "cy": 1786})
    if with_done:
        ns.append({"text": "今日广告已看完，请明日再来", "desc": "", "clickable": False,
                   "id": "", "cx": 100, "cy": 900})
    if deny:
        ns.append({"text": "拒绝", "desc": "", "clickable": True, "id": "", "cx": 370, "cy": 2519})
        ns.append({"text": "允许", "desc": "", "clickable": True, "id": "", "cx": 901, "cy": 2519})
    return ns


CD_NODES = [{"text": "反馈 3s后获取奖励 4 | 跳过", "desc": "", "clickable": True,
             "id": "", "cx": 500, "cy": 200}]


class MockDev:
    def __init__(self, focus_seq, nodes_fn):
        self.focus_seq = focus_seq
        self.nodes_fn = nodes_fn
        self.f = 0; self.n = 0
        self.serial = "MOCK"
        self.calls = {"tap": 0, "back": 0, "stop": 0, "start": 0}

    def online(self): return True
    def focus_user(self): return 0     # mock 固定为主号实例
    def focus_comp(self):
        v = self.focus_seq[self.f] if self.f < len(self.focus_seq) else self.focus_seq[-1]
        self.f += 1
        return v
    def dump_nodes(self, timeout=12):
        v = self.nodes_fn(self.n); self.n += 1
        return list(v)
    def tap(self, x, y): self.calls["tap"] += 1
    def back(self): self.calls["back"] += 1
    def force_stop(self, pkg): self.calls["stop"] += 1
    def start(self, comp): self.calls["start"] += 1
    def wake(self): pass


def make_worker(dev):
    args = types.SimpleNamespace(pkg="com.etalien.booster",
                                 main_act=V3.DEFAULT_MAIN_ACT,
                                 comp="com.etalien.booster/.ui.MainActivity",
                                 max_slots=24)
    w = V3.Worker(dev, args)
    w.lg = lambda m: print("   [log]", m, flush=True)
    return w


def case(name, dev, expect_sigs, expect_ok):
    print("=== case: %s ===" % name, flush=True)
    V3.FALLBACK_WAIT = 4.0
    V3.SESSION_MAX_WAIT = 4.0     # 测试加速(真实默认 15s 已真机验证)
    V3.KILL_AT = 4.0              # 测试加速(真实默认 18s 已真机二分验证)
    V3.KILL_AFTER_AD_OPEN = 2.0   # 测试加速(真实默认 15s)
    V3.AD_TIMEOUT = 12
    V3.SETTLE_AFTER_GRANT = 1.0
    V3.SETTLE_FAST = 1.0
    w = make_worker(dev)
    status, rec = w.cycle()
    ok = (status == "ok") if expect_ok else (status != "ok")
    sig_ok = bool(rec) and rec.get("sig") in expect_sigs
    print("   -> status=%s sig=%s credited=%s [%s]" %
          (status, rec and rec.get("sig"), rec and rec.get("credited"),
           "PASS" if (ok and sig_ok) else "FAIL"), flush=True)
    return ok and sig_ok


results = []

# A. 奖励页(广点通 PortraitADActivity)停留 >1.2s：session armed -> +1.2s 杀，不等视频
fa = [MAIN, MAIN] + [GDT] * 40 + [MAIN] * 200
na = lambda n: main_nodes(0, 0) if n < 30 else main_nodes(1, 1200)
results.append(case("reward_act", MockDev(fa, na), {"kill_at", "session_max", "focus_back", "fallback", "timeout"}, True))

# A2. Kwad(KsRewardVideoActivity，不以 adactivity 结尾)：靠 SDK 提示命中会话 -> +1.2s 杀
KWAD = "com.etalien.booster/com.kwad.sdk.api.proxy.app.KsRewardVideoActivity"
fk = [MAIN, MAIN] + [KWAD] * 40 + [MAIN] * 200
nk = lambda n: main_nodes(0, 0) if n < 30 else main_nodes(1, 1200)
results.append(case("reward_act_kwad", MockDev(fk, nk), {"kill_at", "session_max", "focus_back", "fallback", "timeout"}, True))

# B. 倒计时(浏览页形态，焦点留在 MainActivity)：CD 解析 -> 归零 -> 回主
fb = [MAIN] * 200
def nb(n):
    if n < 2: return main_nodes(0, 0)
    if n < 4: return CD_NODES
    if n < 30: return main_nodes(0, 0)
    return main_nodes(1, 1200)
results.append(case("countdown", MockDev(fb, nb), {"on_main", "cd_grace", "kill_at", "timeout"}, True))

# C. 焦点回归：非奖励页 activity 自主回主界面 -> focus_back
fc = [MAIN, MAIN] + [WEB] * 3 + [MAIN] * 200
nc = lambda n: main_nodes(0, 0) if n < 30 else main_nodes(1, 1200)
results.append(case("focus_back", MockDev(fc, nc), {"kill_at", "focus_back", "fallback", "timeout"}, True))

# D. 兜底：非奖励页常驻、无文本信号 -> fallback(在焦点回归前)
fd = [MAIN, MAIN] + [WEB] * 200
nd_ = lambda n: main_nodes(0, 0) if n < 30 else main_nodes(1, 1200)
results.append(case("fallback", MockDev(fd, nd_), {"fallback", "kill_at", "timeout"}, True))

# E. denyJump：广告期弹出「拒绝/允许」应被点掉(奖励页常驻，走 reward_act 击杀)
fe = [MAIN, MAIN] + [GDT] * 40 + [MAIN] * 200
ne = lambda n: main_nodes(0, 0, deny=True) if n < 2 else (main_nodes(0, 0) if n < 30 else main_nodes(1, 1200))
dev_e = MockDev(fe, ne)
results.append(case("deny_jump", dev_e, {"kill_at", "session_max", "focus_back", "fallback", "timeout"}, True))
taps_ok = dev_e.calls["tap"] >= 2   # CTA 1 次 + 拒绝 1 次
print("   deny taps=%d (want >=2) [%s]" % (dev_e.calls["tap"], "PASS" if taps_ok else "FAIL"), flush=True)
results.append(taps_ok)

# E2. 同步被跳转弹窗挡住：重启后前台是弹窗/应用市场 -> 应点拒绝并恢复，最终 credited
fe2 = [MAIN, MAIN] + [GDT] * 40 + [MAIN] * 200
def ne2(n):
    if n < 2: return main_nodes(0, 0)
    if 2 <= n < 5: return main_nodes(0, 0, deny=True)   # 重启后 dump 到弹窗
    if n < 30: return main_nodes(0, 0)
    return main_nodes(1, 1200)
results.append(case("sync_blocked_by_dialog", MockDev(fe2, ne2), {"kill_at", "session_max", "focus_back", "fallback", "timeout"}, True))

# F. ensure_cta: done
dev_f = MockDev([MAIN] * 50, lambda n: main_nodes(0, 0, with_cta=False, with_done=True))
w_f = make_worker(dev_f)
_, _, status = w_f.ensure_cta()
print("=== case: ensure_cta done === -> %s [%s]" % (status, "PASS" if status == "done" else "FAIL"), flush=True)
results.append(status == "done")

# G. ensure_cta: login
login_nodes = main_nodes(0, 0, with_cta=False) + [
    {"text": "登录/注册", "desc": "", "clickable": True, "id": "", "cx": 100, "cy": 100}]
dev_g = MockDev([MAIN] * 50, lambda n: login_nodes)
w_g = make_worker(dev_g)
_, _, status = w_g.ensure_cta()
print("=== case: ensure_cta login === -> %s [%s]" % (status, "PASS" if status == "login" else "FAIL"), flush=True)
results.append(status == "login")

# H. 单元：ad_session 判定(0.7.16 sessionOpen 融合：类名精确 + SDK 提示泛化)
cases = [
    (GDT, True),                                    # 广点通(*AdActivity)
    ("com.etalien.booster/com.beizi.ad.v2.activity.BeiZiNewRewardVideoActivity", True),
    ("com.etalien.booster/com.kwad.sdk.api.proxy.app.KsRewardVideoActivity", True),   # kwad 提示(0.7.16 同款)
    ("com.etalien.booster/com.octopus.ad.AdActivity", True),
    ("com.etalien.booster/com.cj.mobile.PortraitTransparentAdActivity", False),        # transparent 排除
    ("com.etalien.booster/com.beizi.ad.SplashActivity", False),                        # beizi 非 reward/video
    ("com.etalien.booster/com.pangle.PangleSplashActivity", False),                    # 通用提示不参与击杀(未验证形态走兜底)
    (MAIN, False),
    (WEB, False),
]
ok_h = all(V3.ad_session(c, "com.etalien.booster") == want for c, want in cases)
for c, want in cases:
    got = V3.ad_session(c, "com.etalien.booster")
    print("   ad_session(%-58s) = %s (want %s) %s" % (c.split('/')[-1], got, want, "OK" if got == want else "MISMATCH"))
print("=== case: ad_session classifier === [%s]" % ("PASS" if ok_h else "FAIL"), flush=True)
results.append(ok_h)

# I. 单元：cd_seconds 覆盖 0.6.0 全部文案形态
cd_cases = [
    ("反馈 25s后获取奖励 26 | 跳过 logo 广告", 25),
    ("浏览页面 13 秒后即可获得奖励", 13),
    ("5 s 后可领取奖励", 5),
    ("奖励将于 8 秒后发放", 8),
    ("12秒后可领", 12),
    ("体验 15 秒", 15),
    ("浏览 20 秒", 20),
    ("去看 10 秒", 10),
    ("30 秒可立即领奖", 30),
    ("18 秒可直接", 18),
    ("没有倒计时的屏幕", None),
    ("2秒后可领", None),          # 小于 3 无效(0.6.0 的取值范围)
    ("99秒后可领", None),         # 大于 60 无效
]
ok_i = all(V3.cd_seconds(t) == want for t, want in cd_cases)
for t, want in cd_cases:
    got = V3.cd_seconds(t)
    print("   cd_seconds(%-28s) = %s (want %s) %s" % (t[:26], got, want, "OK" if got == want else "MISMATCH"))
print("=== case: cd_seconds patterns === [%s]" % ("PASS" if ok_i else "FAIL"), flush=True)
results.append(ok_i)

print()
print("TOTAL: %d/%d PASS" % (sum(results), len(results)), flush=True)
sys.exit(0 if all(results) else 1)
