# android-adb-automation

Control an Android phone from any script or AI agent over `adb` — screenshots,
UI inspection, coordinate tapping — plus a **fast rewarded-ad auto-claim loop**.

Battle-tested on **Windows (Git Bash) + Honor MagicOS / Android 17** (device
`AAP-AN00`, 1272×2800).

## The speed trick

Measured latency of common adb operations (Honor AAP-AN00, USB):

| operation | time |
|---|---|
| `uiautomator dump` | **7.3 s** |
| `dumpsys window` | 0.12 s |
| `screencap` | 0.33 s |
| `am start` | 0.09 s |

The naive auto-clicker polls `uiautomator dump` every few seconds to check
"is the reward ready?". At 7.3 s per call that burns **~35 s of pure dump time
per ad**. This toolkit polls with the cheap `dumpsys window` and calls
`uiautomator` **at most once per ad**.

Result on a 21-ad run: **~106 s/ad → ~37 s/ad (~3× faster)**.

## What it handles

- **Device auth / state**: `unauthorized` → user taps "允许 USB 调试"; keep the
  screen awake with `svc power stayon true`.
- **`uiautomator` blind spots**: many rewarded ads render in a `SurfaceView` /
  WebView, so `dump` returns nothing. Fall back to screenshot + colour-based
  coordinate finding (`scripts/find_region.py`).
- **Two ad SDKs**:
  - **穿山甲 / byazt** — `uiautomator` is readable. While playing the pill shows
    `Ns后可领取奖励`; when done it shows `领取成功`. **Only tap 跳过 after
    `领取成功`** — tapping early forfeits the reward.
  - **快手 / kwad** — `uiautomator` is blind and the screen looks frozen, but the
    reward is still banked. Wait ~26 s, then reset the app to the foreground.
- **Honor "app-jump" dialog** — ads that deeplink to another app get blocked by
  Honor's security centre (`... 想要打开 XXX，是否允许？`). Auto-tap **拒绝**.

## Layout

```
scripts/
  android_control.py      # small adb helper library
  rewarded_ad_loop.py     # the fast N-ad loop (CLI-configurable)
  find_region.py          # colour-based button locator (needs Pillow)
```

## Requirements

- `adb` on PATH (`platform-tools`), a phone with USB debugging enabled.
- Python 3.8+ (stdlib only, except `find_region.py` → `pip install pillow`).

## Usage

```bash
# sanity check
adb devices
adb shell svc power stayon true

# run the rewarded-ad loop
python scripts/rewarded_ad_loop.py 21 \
    --package      com.example.app \
    --main         com.example.app/.ui.MainActivity \
    --watch        635,2156 \
    --skip         1132,254 \
    --deny         370,2519
```

Find the `--watch` / `--skip` coordinates with `scripts/find_region.py` or by
reading a screenshot.

## Gotchas (Windows)

- Prefer `am start -n pkg/.Activity` over `force-stop` + `monkey` to bring an app
  forward — ~0.1 s vs ~5 s cold start.
- If a different tool must read a screenshot afterwards, write it somewhere that
  tool can access — **not** `/tmp` (some sandboxes can't read it back).
- Windows `python.exe` rejects Git-Bash `/c/...` paths — pass `C:/...`.

## Disclaimer

Automating rewarded-ad viewing may violate the target app's Terms of Service and
its advertisers' policies. This toolkit is provided for personal experimentation
on **your own device and account**, at your own risk. Do not use it for ad fraud
at scale.

## License

MIT
