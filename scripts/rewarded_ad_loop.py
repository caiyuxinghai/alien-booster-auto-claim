#!/usr/bin/env python3
"""Fast rewarded-ad auto-claim loop.

Watches rewarded video ads N times and banks the in-app reward, driving the
phone over adb. Handles two common ad SDKs and the Honor "app-jump" dialog.

Speed: the naive version polls `uiautomator dump` (~7 s each) every few seconds,
burning ~35 s of pure dump time per ad. This version polls with the cheap
`dumpsys window` (~0.1 s) and uses `uiautomator` at most once per ad.
Measured: ~106 s/ad -> ~37 s/ad (~3x).

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
COUNTDOWN_HINT = "可领取奖励"


class Cfg:
    package = ""
    main = ""
    watch = (0, 0)
    skip = (0, 0)
    deny = (0, 0)


def is_main(f, a, cfg):
    act = cfg.main.split("/", 1)[-1].lstrip(".")
    return a.endswith(act) or f.endswith(act)


def ensure_main(cfg):
    for _ in range(5):
        f, a = focus()
        if is_main(f, a, cfg):
            return True
        if "systemmanager" in f or "systemmanager" in a:
            tap(*cfg.deny); time.sleep(1.2); continue
        bring_front(cfg.main); time.sleep(2.2)
    f, a = focus()
    return is_main(f, a, cfg)


def do_ad(i, cfg):
    tap(*cfg.watch)
    time.sleep(3.5)
    f, a = focus()

    # --- 快手 SDK (ksad/kwad): uiautomator is blind (SurfaceView). The reward is
    #     still banked server-side, so just wait, then reset the app to foreground.
    if "kwad" in a or "kwad" in f:
        print(f"[{i}] kwai ad -> wait + reset", flush=True)
        time.sleep(26)
        bring_front(cfg.main); time.sleep(1.5)
        f2, a2 = focus()
        if "kwad" in f2 or "kwad" in a2:
            force_stop(cfg.package); time.sleep(1)
            bring_front(cfg.main); time.sleep(2.2)
        return True

    # --- 穿山甲 (byazt) and unknowns: read the reward countdown once, wait, skip.
    for _ in range(3):
        xml = dump_texts()
        if deny_honor_jump(xml):
            continue
        if any(t in xml for t in READY_TEXTS):
            print(f"[{i}] ready -> skip", flush=True)
            tap(*(find_bounds(xml, "跳过") or cfg.skip)); time.sleep(1.2)
            return True
        m = COUNTDOWN_RE.search(xml)
        if m:
            n = int(m.group(1))
            print(f"[{i}] countdown {n}s", flush=True)
            time.sleep(max(1, n - 3))     # the dump itself already ate ~7s
            xml2 = dump_texts()
            if deny_honor_jump(xml2):
                xml2 = dump_texts()
            tap(*(find_bounds(xml2, "跳过") or cfg.skip)); time.sleep(1.2)
            f3, a3 = focus()
            if "byazt" in f3 or "byazt" in a3:   # tap missed -> retry once
                tap(*cfg.skip); time.sleep(1.2)
            return True
        time.sleep(1.5)

    print(f"[{i}] unknown ad -> fallback", flush=True)
    time.sleep(18)
    xml = dump_texts()
    if deny_honor_jump(xml):
        xml = dump_texts()
    tap(*(find_bounds(xml, "跳过") or cfg.skip)); time.sleep(1.2)
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
    ap.add_argument("--no-stayon", action="store_true",
                    help="do not force screen always-on")
    cfg = Cfg()
    args = ap.parse_args()
    cfg.package = args.package
    cfg.main = args.main
    cfg.watch = parse_xy(args.watch)
    cfg.skip = parse_xy(args.skip)
    cfg.deny = parse_xy(args.deny)

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
    for i in range(1, args.count + 1):
        if not ensure_main(cfg):
            print(f"[{i}] cannot reach main, reset", flush=True)
            bring_front(cfg.main); time.sleep(2)
        try:
            do_ad(i, cfg)
        except Exception as e:
            print(f"[{i}] ERR {e}", flush=True)
        time.sleep(1)
        print(f"    elapsed {time.time()-t0:.0f}s", flush=True)
    print(f"DONE {args.count} ads in {time.time()-t0:.0f}s", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
