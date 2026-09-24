#!/usr/bin/env python3
"""Daily rewarded-ad auto-claim with GROUND-TRUTH verification.

The 2026-09-23 false-positive bug taught us: never trust do_ad()'s return
value (an ad can open and die in <2s). Verify every reward against the app's
real "可暂停时长" TOTAL (minutes): only a genuine increase counts as watched.

2026-09-24 hardening (after loop16/loop17 raced one phone, and loop17 then
suicided mid-vacuum on an over-eager stuck-abort):

  * Single-instance lockfile with heartbeat (TTL 300s) - two loops racing one
    phone cross-tap and corrupt each other's truth reads.
  * "Stuck" no longer aborts the run. no-ad-open (inventory vacuum - normal)
    and opened-but-unpaid (real stuck) are SEPARATE counters now; 6 unpaid
    opens reset the ad loader and carry on instead of quitting.
  * Baseline re-locks when the total DROPS (user spent minutes mid-run);
    otherwise every later real reward reads < base and dies as "false
    positive" forever.
  * Adaptive backoff 30->60->120s with jitter: a fixed 30s hammer may itself
    prolong server-side rate limiting.
  * Network probe (ping via adb) tells "network down" apart from "no ad
    inventory" - identical symptoms otherwise.
  * adb watchdog: 3 consecutive adb timeouts -> kill-server/start-server and
    wait for the device, without burning the fail counters.
  * Lockscreen wake+swipe and stayon re-asserted every loop iteration.
  * Watch button relocated by text if a popup eats the hardcoded coordinate.
"""
import os
import random
import re
import subprocess
import sys
import time

from android_control import (dump_texts, ensure_ready, find_bounds_contains,
                             is_locked, keep_awake, log, net_ok, recover_adb,
                             swipe, use_device, wake)
import rewarded_ad_loop as R

TARGET = 21         # 9 + 3 + 9 = full daily quota (180 + 90 + 90 = 360 min)
BUDGET = int(os.environ.get("AD_BUDGET", 55)) * 60  # hard stop; override via AD_BUDGET (min)
BACKOFF = 30        # base backoff; consecutive failures grow it adaptively
FLASH_BACKOFF = 12  # flash-closed ads cycle fast - they cost almost nothing
MAX_BACKOFF = 120
NET_DOWN_SLEEP = 120
WATCH_TEXTS = ("看广告", "领时长", "观看广告")

LOCK = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                    "patient_loop.lock")
LOCK_TTL = 300      # no heartbeat for this long => holder died, take over


# ---------- single-instance lock ----------

def acquire_lock():
    """Refuse to start if another patient_loop is alive (heartbeat-fresh lock)."""
    try:
        if os.path.exists(LOCK):
            age = time.time() - os.path.getmtime(LOCK)
            if age < LOCK_TTL:
                log(f"FATAL: lock held (age {age:.0f}s < {LOCK_TTL}s) - "
                    f"another patient_loop is running, refusing to start")
                return False
            log(f"stale lock (age {age:.0f}s) -> taking over")
    except Exception:
        pass
    touch_lock()
    return True


def touch_lock():
    try:
        with open(LOCK, "w") as f:
            f.write(f"{os.getpid()} {time.time():.0f}")
    except Exception:
        pass


def release_lock():
    try:
        os.remove(LOCK)
    except Exception:
        pass


# ---------- ground truth ----------

def pause_minutes():
    """App's true '可暂停时长' total in minutes, parsed from UI text."""
    try:
        xml = dump_texts()
    except Exception:
        return None
    ts = [t for t in re.findall(r'text="([^"]*)"', xml) if t.strip()]
    joined = "".join(ts)
    m = re.search(r'(\d+)时(\d+)分', joined)
    return (int(m.group(1)) * 60 + int(m.group(2))) if m else None


def all_done():
    """True only when the daily quota is genuinely exhausted (UI proof)."""
    try:
        xml = dump_texts()
    except Exception:
        return False
    ts = [t for t in re.findall(r'text="([^"]*)"', xml) if t.strip()]
    joined = "".join(ts)
    # explicit quota-exhausted wording
    if re.search(r'(今日已领完|明日再来|已领完|全部领取|已到上限|已满)', joined):
        return True
    # last stage (加油包) fully claimed -> daily done
    if re.search(r'9\s*/\s*9', joined):
        return True
    return False


def verify_reward(base, cfg, polls=8, gap=2.5):
    """Poll the true '可暂停时长' for a DELAYED reward after an ad closes.

    Rewards often post several seconds after the ad Activity leaves the
    foreground, so a single early read misses them and we'd wrongly count a
    real claim as a false positive. Poll the CURRENT screen; only nudge the
    app to front when focus has actually LEFT the app (an unconditional
    bring_front can dismiss a reward interstitial and forfeit the reward).
    If the total ever DROPS mid-poll (user spent minutes), re-lock the base
    and keep polling against it. Returns (gained_minutes, new_base); gained
    is None when no increase is seen within the window.
    """
    for _ in range(polls):
        time.sleep(gap)
        new = pause_minutes()
        if new is None:
            f, a = R.focus()
            if cfg.package not in (f + a):
                R.bring_front(cfg.main)
                time.sleep(1)
                new = pause_minutes()
        if new is None:
            continue
        if new > base:
            return (new - base, new)
        if new < base - 2:
            log(f"base dropped {base}->{new} (spent elsewhere?) -> rebase")
            base = new
    return (None, base)


# ---------- health helpers ----------

def backoff(streak):
    """30s -> 60s -> 120s (cap) with +-20% jitter. Caller resets the streak."""
    base = min(BACKOFF * (2 ** max(0, streak - 1)), MAX_BACKOFF)
    return base * random.uniform(0.8, 1.2)


def screen_guard():
    """Keep the screen usable: re-assert stayon, wake+swipe past lockscreen."""
    keep_awake()
    try:
        if is_locked():
            log("lockscreen detected -> wake + swipe")
            wake()
            time.sleep(1)
            swipe(540, 2300, 540, 900, 300)
            time.sleep(1)
    except Exception:
        pass


def relocate_watch(cfg):
    """If a popup covers the hardcoded watch button, find it by text instead."""
    try:
        xml = dump_texts()
    except Exception:
        return
    for t in WATCH_TEXTS:
        b = find_bounds_contains(xml, t)
        if b and b != cfg.watch:
            log(f"watch button relocated via text '{t}': {cfg.watch} -> {b}")
            cfg.watch = b
            return


# ---------- main ----------

def run():
    serial = ensure_ready()
    if not serial:
        log("ERROR: no device")
        return 1
    use_device(serial)
    log(f"device ready: {serial}")
    R.AD_OPEN_TIMEOUT = 5   # detect "ad failed to open" faster -> retry often
    keep_awake()

    cfg = R.Cfg()
    cfg.package = "com.etalien.booster"
    cfg.main = "com.etalien.booster/.ui.MainActivity"
    cfg.watch = (635, 2156)
    cfg.skip = (1132, 254)
    cfg.deny = (370, 2519)
    cfg.max_noopen = 99

    # Bring the app to its main screen and read the true "可暂停时长" baseline.
    # Retry a few times: the app may be on an ad/transition screen at start and
    # the total isn't visible yet. If still None, the in-loop safety net sets it
    # from the first good read (that one ad simply isn't counted as a reward).
    base = None
    for _ in range(6):
        R.ensure_main(cfg, budget=20)
        base = pause_minutes()
        if base is not None:
            break
        time.sleep(2)
    log(f"baseline pause = {base} min")

    t0 = time.time()
    watched = 0           # REAL rewards (verified by pause-minutes increase)
    fails = 0             # consecutive no-ad-open / flash (drives backoff)
    no_reward_streak = 0  # ads opened but never paid (the REAL stuck signal)
    adb_timeouts = 0
    n = 0
    while watched < TARGET and time.time() - t0 < BUDGET:
        touch_lock()
        screen_guard()
        # Backstop: catch any reward that posted late during the previous
        # backoff / poll tail. Idempotent - base is updated on first detect,
        # so it never double-counts. Also rebase if the user spent minutes.
        if base is not None:
            cur = pause_minutes()
            if cur is not None:
                if cur > base:
                    gained = cur - base
                    watched += 1
                    base = cur
                    fails = 0
                    no_reward_streak = 0
                    log(f"[pre] REAL +{gained}min total={cur} "
                        f"watched={watched}/{TARGET} elapsed={time.time()-t0:.0f}s")
                elif cur < base - 2:
                    log(f"[pre] base dropped {base}->{cur} "
                        f"(spent elsewhere?) -> rebase")
                    base = cur
        if not R.ensure_main(cfg, budget=30):
            log("cannot reach main, retry later")
            time.sleep(BACKOFF)
            continue
        n += 1
        try:
            status = R.do_ad(n, cfg)
        except subprocess.TimeoutExpired:
            adb_timeouts += 1
            log(f"[{n}] adb timeout #{adb_timeouts}")
            if adb_timeouts >= 3:
                log("adb unhealthy -> recover_adb()")
                recover_adb()
                adb_timeouts = 0
            time.sleep(10)
            continue
        except Exception as e:
            log(f"[{n}] ERR {e}")
            status = "no_open"
        else:
            adb_timeouts = 0

        if base is None:
            # Safety net: lock the baseline from the first good read; this one
            # ad simply isn't counted (documented trade-off).
            if status != "no_open":
                time.sleep(3)
                R.bring_front(cfg.main)
                time.sleep(2)
                base = pause_minutes()
                log(f"[{n}] baseline set = {base}min (no count)")
            time.sleep(BACKOFF)
            continue

        if status == "watched":
            gained, nb = verify_reward(base, cfg)
            base = nb
            if gained:
                watched += 1
                fails = 0
                no_reward_streak = 0
                log(f"[{n}] REAL +{gained}min total={nb} "
                    f"watched={watched}/{TARGET} elapsed={time.time()-t0:.0f}s")
            else:
                no_reward_streak += 1
                log(f"[{n}] no reward (unpaid streak {no_reward_streak})")
                if no_reward_streak >= 6:
                    # Real stuck: ads open but never pay. Reset the ad loader
                    # and carry on - quitting wastes the whole budget window.
                    log("6 unpaid opens -> reset ad loader (not quitting)")
                    R.force_stop(cfg.package)
                    time.sleep(2)
                    R.bring_front(cfg.main)
                    time.sleep(2)
                    no_reward_streak = 0
                    time.sleep(BACKOFF)
                else:
                    time.sleep(backoff(no_reward_streak))
        elif status == "flash":
            # Ad died in <FLASH_SEC: a reward is nearly impossible, so a quick
            # 2-poll check is enough. Flash = something responded, so network
            # is alive; keep cycling fast to catch inventory the moment it's back.
            gained, nb = verify_reward(base, cfg, polls=2, gap=2)
            base = nb
            if gained:
                watched += 1
                fails = 0
                no_reward_streak = 0
                log(f"[{n}] REAL +{gained}min total={nb} (flash ad!) "
                    f"watched={watched}/{TARGET}")
            else:
                fails += 1
                log(f"[{n}] flash-close, no reward -> short backoff")
                time.sleep(FLASH_BACKOFF * random.uniform(0.8, 1.4))
        else:  # no_open
            fails += 1
            if fails % 10 == 0:
                log(f"[{n}] {fails} fails -> reset app ad loader")
                R.force_stop(cfg.package)
                time.sleep(2)
                R.bring_front(cfg.main)
                time.sleep(2)
            if fails >= 3 and not net_ok():
                log(f"[{n}] network DOWN -> sleep {NET_DOWN_SLEEP}s "
                    f"(not burning ad-failure counters)")
                time.sleep(NET_DOWN_SLEEP)
                continue
            # Only stop on real UI proof of exhaustion, never on a dry streak
            if fails >= 3 and all_done():
                log("STOP: all stages complete -> daily quota done")
                break
            if fails % 3 == 0:
                relocate_watch(cfg)  # a popup may be eating the coordinate
            d = backoff(fails)
            log(f"[{n}] no ad (fail #{fails}) -> backoff {d:.0f}s")
            time.sleep(d)

    final = pause_minutes()
    log(f"DONE watched={watched}/{TARGET} final={final}min "
        f"in {time.time()-t0:.0f}s")
    return 0


def main():
    if not acquire_lock():
        return 2
    try:
        return run()
    finally:
        release_lock()


if __name__ == "__main__":
    sys.exit(main())
