---
name: android-adb-automation
description: >
  Drive an Android phone (incl. Honor/MagicOS) from WorkBuddy over adb:
  device auth, keep-awake, screenshots, uiautomator UI inspection, coordinate
  tapping, Honor "app-jump" dialog handling, and a fast rewarded-ad auto-claim
  loop. Use when the user asks to control, tap, automate, scrape or auto-claim
  on their USB-connected phone.
---

# Android adb automation

Full docs: see `README.md`. Scripts live in `scripts/`.

## The one idea that matters

`uiautomator dump` takes **~7 s**; `dumpsys window` takes **~0.1 s**.
Poll state with `dumpsys` (`scripts/android_control.py: focus()`), and call
`uiautomator` **at most once per ad**. That alone is ~3× throughput.

## Quick map

| need | call |
|---|---|
| device ready | `adb devices` (authorize on phone if `unauthorized`) |
| keep screen on | `adb shell svc power stayon true` |
| screenshot | `screencap(path)` — **not** `/tmp` if another tool must read it |
| current app | `focus()` → `(currentFocus, focusedApp)` |
| UI text / button bounds | `dump_texts()` + `find_bounds(xml, "跳过")` |
| tap | `tap(x, y)` |
| bring app front (fast) | `bring_front("pkg/.Activity")` — beats force-stop+monkey |
| Honor jump dialog | `deny_honor_jump()` (taps 拒绝) |

## Rewarded-ad loop

```bash
python scripts/rewarded_ad_loop.py <N> \
  --package <pkg> --main <pkg/.Activity> \
  --watch X,Y --skip X,Y [--deny X,Y]
```

Per-SDK behaviour:
- **穿山甲 (byazt)**: uiautomator readable. Wait for `领取成功` **before** tapping
  跳过 (early tap = no reward).
- **快手 (kwad)**: uiautomator blind, screen frozen, reward still banks → wait
  ~26 s then `bring_front` the main activity (force-stop as fallback).
- Honor deeplink ads trigger `com.hihonor.systemmanager` dialog → tap 拒绝.

## Windows gotchas
- Windows `python.exe` rejects Git-Bash `/c/...` paths → pass `C:/...`.
- Write screenshots where the reader can reach them (not `/tmp`).

## Safety / ethics
- Confirm before destructive ops (uninstall, rm, settings changes).
- Automating rewarded ads may violate the target app's ToS — warn the user first.
