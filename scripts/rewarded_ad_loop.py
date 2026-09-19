#!/usr/bin/env python3
"""Fast rewarded-ad auto-claim loop.

Watches rewarded video ads N times and banks the in-app reward, driving the
phone over adb. Handles the common ad SDKs and the Honor "app-jump" dialog.

Speed strategy
--------------
`uiautomator dump` costs ~7 s, `dumpsys window` costs ~0.1 s. So:

  * Detect "did an ad actually open?" by polling `focus()` (cheap), NOT by
    dumping. Ad activities take 2-8 s to take focus, so a single fixed sleep
    misclassifies them (a 3.5 s sleep was landing before the ad opened and
    falling through to the expensive path).
  * If no ad activity appears, re-tap once and give up quickly. The host app
    often shows "广告加载失败，点击重试" - burning a full dump+wait cycle on it
    wastes ~40 s.
  * Only SDKs whose UI is readable (穿山甲 / byazt) ever get a `dump`.

Typical cost per ad: ~3 s detect + ~26 s video = ~29 s.

Usage:
  python rewarded_ad_loop.py 21 \
      --package com.example.app \
      --main com.example.app/.ui.MainActivity \
      --watch 635,2156 --skip 1132,254 --deny 370,2519

DISCLAIMER: automating rewarded-ad viewing may violate the target app's Terms
of Service. Run on your own device and account, at your own risk.
"""
import argparse
import re
import sys
import time

from android_control import (adb, bring_front, deny_honor_jump, dump_texts,
                             ensure_ready, find_bounds, focus, force_stop,
                             keep_awake, tap)

COUNTDOWN_RE = re.compile(r'(\d+)\s*s后可领取奖励')
READY_TEXTS = ("领取成功",)          # 穿山甲: reward banked, pill now skippable

# Activity / package fragments that mean "an ad is on screen".
AD_HINTS = ("kwad", "byazt", "ksad", "pangle", "topon", "mintegral", "sigmob",
            "klevin", "unityads", "applovin", "gdtad", "qq.e", "reward",
            "advert", "interstitial", "splash")

KWAI_WAIT = 26                      # seconds a 快手 reward video needs to bank
AD_OPEN_TIMEOUT = 10                # seconds to wait for an ad to take focus
FOCUS_POLL = 0.5


class Cfg:
    package = ""
    main = ""
    watch = (0, 0)
    skip = (0, 0)
    deny = (0, 0)
    max_noopen = 6


def is_main(f, a, cfg):
    act = cfg.main.split("/", 1)[-1].lstrip(".")
    return a.endswith(act) or f.endswith(act)


def is_ad(f, a):
    s = (f + "|" + a).lower()
    return any(h in s for h in AD_HINTS)


def is_honor_dialog(f, a):
    s = (f + "|" + a).lower()
    return "systemmanager" in s or "hihonor" in s


def ensure_main(cfg, budget=60):
    """Get back to the host app's main screen.

    Never force-closes an ad that is still on screen (that would forfeit the
    reward) - it waits it out. Backs out of a foreign app if an ad deeplink
    launched one that Honor did not intercept.
    """
    end = time.time() + budget
    pressed_back = False
    while time.time() < end:
        f, a = focus()
        if is_main(f, a, cfg):
            return True
        if is_ad(f, a):
            time.sleep(2)                       # let it finish, do not kill it
            continue
        if is_honor_dialog(f, a):
            tap(*cfg.deny)
            time.sleep(1.2)
            continue
        if not pressed_back and f and cfg.package not in f:
            adb("shell", "input", "keyevent", 4)     # KEYCODE_BACK
            pressed_back = True
            time.sleep(1.0)
            continue
        bring_front(cfg.main)
        time.sleep(2.2)
    f, a = focus()
    return is_main(f, a, cfg)


def wait_ad_open(timeout=AD_OPEN_TIMEOUT):
    """Poll (cheap) until an ad activity takes focus. Returns (focus, app)."""
    end = time.time() + timeout
    while time.time() < end:
        f, a = focus()
        if is_ad(f, a):
            return f, a
        if is_honor_dialog(f, a):
            tap(370, 2519)
            time.sleep(1.0)
        time.sleep(FOCUS_POLL)
    return focus()


def close_ad(i, cfg, reason):
    """Return to the host app after an ad."""
    f, a = focus()
    if is_ad(f, a):
        bring_front(cfg.main)
        time.sleep(1.5)
        f, a = focus()
        if is_ad(f, a):                     # still stuck -> cold reset
            force_stop(cfg.package)
            time.sleep(1)
            bring_front(cfg.main)
            time.sleep(2.2)
    print(f"[{i}] {reason}", flush=True)


def do_ad(i, cfg):
    """One ad attempt. Returns True if an ad was actually watched."""
    # Ad inventory sometimes fails to load; the button then says
    # "广告加载失败，点击重试" and a tap opens nothing. Try twice, cheaply.
    for attempt in (1, 2):
        tap(*cfg.watch)
        f, a = wait_ad_open()
        if is_ad(f, a):
            break
        if attempt == 1:
            print(f"[{i}] no ad opened -> retap", flush=True)
            time.sleep(1.5)
    else:
        print(f"[{i}] no ad opened (inventory / retry button)", flush=True)
        return False

    if "kwad" in (f + a).lower() or "ksad" in (f + a).lower():
        # 快手 renders in a SurfaceView: uiautomator is blind and the frame looks
        # frozen, but the reward still banks at video end. Just wait it out.
        time.sleep(KWAI_WAIT)
        close_ad(i, cfg, f"kwai -> waited {KWAI_WAIT}s")
        return True

    # Dumpable SDKs (穿山甲 / byazt and friends).
    xml = dump_texts()
    if deny_honor_jump(xml):
        xml = dump_texts()
    if any(t in xml for t in READY_TEXTS):
        tap(*(find_bounds(xml, "跳过") or cfg.skip))
        time.sleep(1.2)
        close_ad(i, cfg, "ready -> skip")
        return True

    m = COUNTDOWN_RE.search(xml)
    if m:
        n = int(m.group(1))
        print(f"[{i}] countdown {n}s", flush=True)
        time.sleep(max(1, n - 2))
        xml2 = dump_texts()
        if deny_honor_jump(xml2):
            xml2 = dump_texts()
        tap(*(find_bounds(xml2, "跳过") or cfg.skip))
        time.sleep(1.2)
        close_ad(i, cfg, "dumpable -> skipped")
        return True

    # Unknown layout: wait out a typical video, then skip.
    print(f"[{i}] unknown ad layout -> timed skip", flush=True)
    time.sleep(15)
    tap(*cfg.skip)
    time.sleep(1.2)
    close_ad(i, cfg, "unknown -> timed skip")
    return True


def parse_xy(s):
    x, y = s.split(",")
    return int(x), int(y)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("count", type=int, nargs="?", default=21)
    ap.add_argument("--package", required=True)
    ap.add_argument("--main", required=True, help="pkg/.Activity (adb component)")
    ap.add_argument("--watch", required=True, help="x,y of the 'watch ad' button")
    ap.add_argument("--skip", required=True, help="x,y of the ad's skip/close button")
    ap.add_argument("--deny", default="370,2519", help="x,y of the Honor deny button")
    ap.add_argument("--max-noopen", type=int, default=6,
                    help="stop after this many consecutive ads that never opened")
    ap.add_argument("--no-stayon", action="store_true",
                    help="do not force screen always-on")
    cfg = Cfg()
    args = ap.parse_args()
    cfg.package = args.package
    cfg.main = args.main
    cfg.watch = parse_xy(args.watch)
    cfg.skip = parse_xy(args.skip)
    cfg.deny = parse_xy(args.deny)
    cfg.max_noopen = args.max_noopen

    serial = ensure_ready()
    if not serial:
        print("ERROR: no authorized device. Plug in the phone, turn on USB 调试, "
              "and tap 允许 on the device (both adb USB backends were tried).",
              flush=True)
        return 1
    print(f"device ready: {serial}", flush=True)

    if not args.no_stayon:
        keep_awake()

    t0 = time.time()
    watched = 0
    noopen_streak = 0
    for i in range(1, args.count + 1):
        if not ensure_main(cfg):
            print(f"[{i}] cannot reach main, reset", flush=True)
            bring_front(cfg.main)
            time.sleep(2)
        try:
            if do_ad(i, cfg):
                watched += 1
                noopen_streak = 0
            else:
                noopen_streak += 1
                if noopen_streak >= cfg.max_noopen:
                    print(f"STOP: {noopen_streak} ads in a row never opened "
                          f"(likely daily quota done or no ad inventory).",
                          flush=True)
                    break
        except Exception as e:
            print(f"[{i}] ERR {e}", flush=True)
        time.sleep(0.8)
        print(f"    elapsed {time.time()-t0:.0f}s  watched={watched}", flush=True)

    dur = time.time() - t0
    avg = dur / watched if watched else 0
    print(f"DONE watched={watched} in {dur:.0f}s ({avg:.1f}s/ad)", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
