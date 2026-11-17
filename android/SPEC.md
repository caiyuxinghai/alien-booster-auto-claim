# SPEC — 外星仔领时长 v0.8.0 (Shizuku UI automation)

Project: `android/`

This is a personal automation app for the owner's own phone and own accounts. It only performs
normal UI actions (tap / back / home / start / force-stop) through Shizuku's shell. It must NOT
call the target app's APIs, modify the target app, spoof rewards, inject network requests, or
use root. It must NOT contain an AccessibilityService. It must NOT request INTERNET.

Target app: `com.etalien.booster` (外星仔加速器), main activity
`com.etalien.booster.ui.MainActivity`. The device is 荣耀 WIN（型号 AAP-AN00）, Android 17 (SDK 37),
resolution 1272x2800, serial `<your device serial>`.

## 1. Product goal

A standalone Android app named **外星仔领时长** that, with Shizuku running and authorized,
claims the target app's daily rewarded-ad tasks for up to two Android users (user 0 = 主号,
user 128 = 分身), sequentially, unattended. The UI must look close to the existing old app
(dark purple dashboard, progress cards, log console, schedule switch).

The app is a Kotlin port of the proven PC logic in
`pc/watch_ads_v3.py`.
Read that file for reference, but do not copy its Python runtime.

## 2. Non-negotiable safety rules

1. Never leave an ad or force-stop the target app before 18 seconds from the CTA tap AND at
   least 15 seconds after the ad Activity opened. The only exception is when the ad Activity
   has already returned to MainActivity by itself; then wait 2 seconds before force-stop.
2. The reward is granted by the ad SDK around tap+18..20s. `KILL_AT_MS = 18_000`,
   `KILL_AFTER_AD_OPEN_MS = 15_000`, `SETTLE_FAST_MS = 2_000`. Do not lower these values.
3. After the kill, wait 2 seconds before force-stop. Do not tap "跳过" before the kill.
4. A text "广告加载失败" is NOT proof of failure. It is a shared UI state. Only the stage
   counters and duration delta decide credit.
5. Never fake `transId`, `isVerify`, reward callbacks, or call `/v2/account/...` endpoints.
   The app has no INTERNET permission and must not add one.
6. Do not use `QUERY_ALL_PACKAGES`; declare only `<queries>` for the target and Shizuku.
7. Force-stop is mandatory after each round before reading the next state. Do not "optimize"
   with a hot restart; it was proven to leave the ad Activity in front and freeze the app.
8. If the target app shows a login screen, stop and report exit code 4. Do not attempt login.
9. If the device is offline / Shizuku is dead / the target is not installed, stop and report;
   never blindly tap.

## 3. Architecture

Modules (all in package `com.alienbooster.claimer`):

- `MainActivity` — dashboard UI, permission/status, start/stop, selftest, settings.
- `ClaimService` — foreground service, worker thread, state machine, notification.
- `RemoteShellService` — Shizuku UserService (runs as shell UID) exposing `IRemoteShell`.
  Must extend `IRemoteShell.Stub` (an IBinder), NOT `android.app.Service`: Shizuku's
  `ServiceStarter`/`UserService.create` instantiates the class and casts it directly to IBinder.
- `IRemoteShell.aidl` — `String exec(String command, int timeoutMs)`.
- `ShizukuManager` — permission check/request, UserService bind/unbind, exec wrapper.
- `Shell` — typed commands: focus, dump XML, tap, key, start, force-stop, stayOn, installed.
- `UiXmlParser` — parse uiautomator XML by resource-id, with text fallback.
- `RewardEngine` — ensure CTA, watch/kill, settle, refresh, verify, retry, dual user.
- `AppSettings` — SharedPreferences.
- `LogStore` — file log + last 30 lines in status.
- `StatusStore` — JSON status in SharedPreferences for UI polling.
- `AlarmReceiver`, `BootReceiver`, `ScheduleManager`.
- `NotificationHelper`.

Threading: no coroutines. `ClaimService` runs a single worker `Thread`; UI polls
`StatusStore` every 500 ms. Shell calls are blocking; wrap with timeouts.

## 4. Shizuku integration

Dependencies (already in `libs/`). Local `files()` AARs do NOT resolve Maven transitives, so all
four artifacts must be present:
```
implementation(files("libs/api-13.1.5.aar"))
implementation(files("libs/provider-13.1.5.aar"))
implementation(files("libs/aidl-13.1.5.aar"))     // moe.shizuku.server.* (AIDL stubs)
implementation(files("libs/shared-13.1.5.aar"))   // moe.shizuku.ShizukuApiConstants
```
Enable AIDL in `app/build.gradle.kts`: `buildFeatures { aidl = true }`.

Manifest:
```
<uses-permission android:name="moe.shizuku.manager.permission.API_V23" />
...
<provider
    android:name="rikka.shizuku.ShizukuProvider"
    android:authorities="${applicationId}.shizuku"
    android:enabled="true"
    android:exported="true"
    android:multiprocess="false"
    android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />
```
The provider AAR also adds `moe.shizuku.client.V3_SUPPORT` meta-data.

AIDL:
```aidl
package com.alienbooster.claimer;
interface IRemoteShell {
    String exec(String command, int timeoutMs);
}
```
`RemoteShellService` extends `Service`; `onBind` returns `IRemoteShell.Stub`. `exec` runs
`ProcessBuilder("sh", "-c", command)`, reads stdout/stderr in two threads, waits up to
`timeoutMs`, destroys forcibly on timeout, returns:
```
exitCode + "\n" + base64(stdout) + "\n" + base64(stderr)
```
Use `android.util.Base64.NO_WRAP`.

`ShizukuManager`:
```kotlin
val args = Shizuku.UserServiceArgs(ComponentName(packageName, RemoteShellService::class.java.name))
    .daemon(false).processNameSuffix("shell").debuggable(BuildConfig.DEBUG)
    .version(BuildConfig.VERSION_CODE)
Shizuku.bindUserService(args, connection)
```
Check `Shizuku.pingBinder()` and `Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED`.
If permission missing, `Shizuku.requestPermission(1001)` and show a dialog/status. We will also
pre-grant via `adb shell pm grant com.alienbooster.claimer moe.shizuku.manager.permission.API_V23`.
If Shizuku is not installed/running, show "Shizuku 未连接" and a button to open
`moe.shizuku.privileged.api`; do not crash.

`ShizukuManager.exec(cmd, timeoutMs)` must wait for the UserService connection with a
`CountDownLatch` (max 8s), then call the binder. On `onServiceDisconnected`, clear the binder and
status. If `exec` throws, return a failure result and log.

## 5. Shell commands (exact)

All commands run through `IRemoteShell` as shell UID:
- Focus: `dumpsys window | grep mCurrentFocus`
- UI dump: `rm -f /data/local/tmp/claimer_ui.xml; uiautomator dump /data/local/tmp/claimer_ui.xml; cat /data/local/tmp/claimer_ui.xml`
- Tap: `input tap X Y`
- Key: `input keyevent 3|4|66|224`
- Swipe: `input swipe X1 Y1 X2 Y2 DURATION`
- Text: `input text <pin>` (only after a verified keyguard)
- Start: `am start --user N -n com.etalien.booster/.ui.MainActivity`
- Force stop: `am force-stop --user N com.etalien.booster`
- Installed: `pm list packages --user N com.etalien.booster`
- Stay on: `svc power stayon true` / `false`

`uiautomator dump` timeout 15s; if it fails or the XML is empty, return null and retry once.
On this 荣耀 WIN device uiautomator works. Do not add `mobilecli` (that backend is only for vivo/OriginOS).

## 6. Target app UI parsing

Real sample XMLs are in `app/src/test/resources/main_partial.xml` and `main_done.xml`.
Use `javax.xml.parsers.DocumentBuilderFactory` (works on Android and JVM unit tests).

Stable resource IDs:
- Duration hour/minute/second: `com.etalien.booster:id/UIPCDurationHourTxt`,
  `...MinuteTxt`, `...SecondTxt`. `durationSec = h*3600 + m*60 + s`.
- Task list: `...:id/UIPCADTaskList`; three stage blocks `...:id/UIBG`.
  Inside each: `...:id/UITitle` (阶段一/二/加油包), `...:id/UIStateTitle`
  (e.g. `5 / 9`, `1 / 3`, `已完成`, `待解锁`), `...:id/UIDescription`
  (e.g. `每看1个广告 = 20分钟 可暂停时长`).
- CTA: `...:id/UIADSubmitText` (text `立即领取 领时长` / `点击重试` / `今日广告已看完...`),
  parent `...:id/UIADSubmit` is the tappable region; use the text node's bounds center.
- User: `...:id/UIUserName`, `...:id/UIUserID`.

`UiState` fields:
```
stageCompleted: IntArray(3)   // 0..9, 0..3, 0..9
stageMax: IntArray(3)         // [9,3,9]
stageStatus: List<String>     // raw status text
durationSec: Int?
ctaText: String?
ctaBounds: Rect?
done: Boolean
login: Boolean
mainVisible: Boolean
userName: String?
userId: String?
rawText: String
```
Rules:
- Parse each `UIBG` block in document order. If `UIStateTitle` matches `(\d+)\s*/\s*(\d+)`,
  completed = group1, max = group2. If text contains "已完成", completed = max. If "待解锁",
  completed = 0. If no `UIBG`, fall back to all `UITitle`/`UIStateTitle` pairs in document order.
- `done` = any text contains one of `今日广告已看完`, `明日再来`, `已看完`, `已领完`,
  OR all three stages completed == max, OR CTA text contains the done texts.
- `login` = text contains `登录/注册` or (`登录` and no stage list).
- `mainVisible` = `UIPCADTaskList` present or at least 3 `UITitle` nodes.
- `ctaText`: use `UIADSubmitText`; if missing, find text containing `领时长`, `看广告`,
  `点广告`, `点击重试`, `立即领取`; choose the lowest on screen (largest bounds top).
- Do not treat `广告加载失败` alone as done/failure; it is just a retry state.

Focus parsing:
Line example:
`mCurrentFocus=Window{... u0 com.etalien.booster/com.etalien.booster.ui.MainActivity}`
Regex: `mCurrentFocus=Window\{[^}]*?\s+(u\d+)\s+([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)\}`
Return `FocusInfo(pkg, activity, user)`. If no `uN` token, user = 0.
`isAd = pkg == target && !activity.endsWith("MainActivity")`. Also accept activity names
containing `reward`, `adactivity`, `portraitad`, `interstitial`, etc.
`onMain = pkg == target && activity.endsWith("MainActivity")`.

## 7. State machine (RewardEngine)

Per user:
```
if daily flag exists -> skip (done)
if !ShizukuManager.ready -> error
if !installed(user) -> skip/error
check keyguard; wake/unlock (see 8)
svc power stayon true
ensureUserFront(user)
state = readState()
if state == null -> relaunch; retry up to 3
if state.done -> write flag, done
credits = 0; consecutiveFails = 0
for slot in 1..maxAds:
    cta = ensureCta(user)
    if cta == null:
        if done -> write flag; break
        if slot == 1 -> error exit 3
        else -> write flag (quota) ; break
    before = readState()
    tap(cta)
    result = watch(tapTime)
    settle(result)
    refresh(user)  // force-stop + cold start + wait
    after = readState()
    credited = after.stageTotal > before.stageTotal
               || (after.durationSec != null && before.durationSec != null
                   && after.durationSec - before.durationSec >= 500)
    if credited: credits++; consecutiveFails=0
    else: consecutiveFails++; if >=4 -> stop with error; sleep 10s
    if after.done or after.stageTotal >= 21: write flag; break
    if testMode (maxAds < 21): do not write daily flag
write partial/done flag only if normal run and (credits >= maxAds or done)
```

`ensureCta`:
- Up to 3 attempts:
  - readState
  - if login -> stop exit 4
  - if done -> done
  - clear blockers: jump dialog, exit-confirm, ad-load-retry, permission dialog
  - if cta != null -> return
  - force-stop + start + wait 1.5s
- return null.

`watch(tapTime)`:
```
adOpenAt = null; sawAd = false
loop until now-tap > 45s:
    f = focus()
    if f.isAd: adOpenAt = adOpenAt ?: now; sawAd = true
    if f.onMain && sawAd: sleep 2s; return ON_MAIN
    if adOpenAt != null && now-tap >= 18s && now-adOpenAt >= 15s:
        sleep 2s; return KILL
    if now-tap > 30s:
        sleep 2s; return FALLBACK
    sleep 300ms
return TIMEOUT
```
`settle(result)`:
- If ad still open: press BACK once (soft close), wait 0.6s; if still ad, force-stop.
- Ensure main: if focus not main, press BACK up to 3 times, wait 0.6s; then force-stop + start.
- Always force-stop + start after a round (proven), then wait up to 12s for main state.
- `SETTLE_FAST=2s` before force-stop for KILL/ON_MAIN; do not wait 8s.

`refresh(user)`:
- `am force-stop --user N pkg`; sleep 500ms; `am start --user N ...`; wait up to 12s for
  `mainVisible`; dump every 1s. If focus is another package (jump dialog/market), clear it,
  retry start up to 3.
- After returning to main, read state; delay 200ms and read again if needed (server fetch).
- Do NOT skip force-stop in v1. A setting `fastRefresh` may exist but default false and must
  fall back to force-stop if the score did not increase.

`ensureUserFront(user)`:
- Start target for the user; wait; check focus user token == requested (if present) and pkg target.
- If another user owns the display, retry start; log. This is the instance guard.

## 8. Screen / keyguard

Use `KeyguardManager` in the app to check `isKeyguardLocked` / `isDeviceLocked`.
If locked:
- `input keyevent 224` (wake), sleep 300ms.
- `input swipe 636 2200 636 700 300`, sleep 600ms.
- If PIN is non-empty: `input text <pin>`, sleep 300ms, `input keyevent 66`, sleep 800ms.
- Re-check keyguard; if still locked, stop with error "无法解锁（PIN 错误或安全锁）".
If not locked: just wake.
Use a `SCREEN_BRIGHT_WAKE_LOCK or ACQUIRE_CAUSES_WAKEUP` in the service for the run duration;
release in `finally`. Also `svc power stayon true` and `false` in `finally`.

## 9. UI specification (must resemble old app)

Reference screenshot: `ref/old_claimer.png`.
Palette:
- background `#0B0714`
- card `#151020`, stroke `#3A2D5C` 1dp, corner 20dp
- accent `#A78BFA`, accentDark `#7C3AED`
- primary text `#F5F3FF`, secondary `#A9A3B8`
- console `#080510`, log text `#D8D3E8`, timestamps `#A78BFA`
- success `#34D399`, error `#F87171`

Layout (ScrollView + vertical LinearLayout, 16dp padding):
1. Header row: 44dp purple circular clock icon; title "外星仔领时长" 26sp bold;
   right status chip (rounded 20dp, `#2A1630` background): 8dp dot + text 13sp.
   Text: "Shizuku 已连接" (green dot) / "Shizuku 未连接" (red dot) / "运行中".
2. Big button 56dp, rounded 28dp: idle "开始" (`#7C3AED`, white); running "停止" (`#2A2238`).
3. Card "今日进度":
   - rows: 阶段一 / 阶段二 / 加油包, right counts `X/9`, `X/3`, `X/9` in accent,
     each with a 6dp rounded progress bar.
   - row "预计入账" + "X 分钟" accent.
   - status line "空闲 · 今日未完成" / "运行中 · 第 X 条" / "已完成".
   - "下次 10月30日 10:10" secondary.
   - "最近日志" label + console (12sp monospace, last 12-20 lines, auto-scroll).
4. Card "设置":
   - "定时启动" + Switch; row "每天 10:10" clickable time picker.
   - "自动双账号" + Switch (default on).
   - "Shizuku 状态" row: status text + buttons "授权" and "打开 Shizuku".
   - "解锁 PIN" + numeric password EditText (hint "锁屏密码，留空则只上滑").
   - "测试模式（只跑 1 条）" + Switch.
   - buttons "自检" and "导出日志".
   - "电池优化" button (opens ignore request).
5. Footer version "v0.8.0 · 仅供本人设备使用" secondary.

Use plain Android widgets (no Material/AppCompat dependency). Custom shape drawables for
cards/chips/button/console. `Switch` with `thumbTint`/`trackTint`. `ProgressBar` horizontal
with `progressTint`/`progressBackgroundTint`. Dark status bar `#0B0714`.

App icon: adaptive icon `mipmap-anydpi-v26/ic_launcher.xml`, background `#7C3AED`,
white clock vector foreground. Label "外星仔领时长".

## 10. Settings / schedule / flags / logs

SharedPreferences keys:
`autoDual` (true), `scheduleEnabled` (false), `scheduleHour` (10), `scheduleMinute` (10),
`pin` (""), `testOneAd` (false), `fastRefresh` (false), `userMode` ("auto").
Flag: `files/claimed_u<user>_<yyyy-MM-dd>.flag`, written only for a normal (maxAds >= 21) run
when done/21. Test mode never writes a daily flag.
Log: `files/run.log`, rotate at 512 KB to last 256 KB. Status JSON in SharedPreferences key
`status_json`, updated at least once per second and on every event; fields:
`running, user, slot, maxAds, credits, stage1, stage2, stage3, durationSec, shizuku,
lastLine, updatedAt, error`.
Notification channel `claim`, low importance; ongoing progress; final result; tap opens
MainActivity. `POST_NOTIFICATIONS` requested at runtime on Android 13+.

Schedule:
- AlarmManager exact alarm if `canScheduleExactAlarms()`, else `setAndAllowWhileIdle` and
  show a warning. `AlarmReceiver` starts `ClaimService` with `auto_start=true`.
- `BootReceiver` reschedules and posts a notification "手机重启后请打开 Shizuku 并启动服务".
- The service checks today's flag and exits quickly if done.

## 11. Test mode / debug intents

MainActivity accepts:
- `--ez auto_start true`
- `--ei max_ads 1`
- `--ei user 0` (or 128; -1 = auto)
- `--ez selftest true`
It saves a one-shot override, starts the service or runs selftest. Do not expose these in UI
except the "测试模式" switch (max_ads=1, no flag).
Selftest is read-only: Shizuku status, installed users, wake/unlock (optional), start target
for the requested user, dump/parse state, log stages/duration/CTA/done; no tap, no force-stop.

## 12. Build & tests

`app/build.gradle.kts`:
- namespace/applicationId `com.alienbooster.claimer`
- versionCode 43, versionName "0.8.0"
- compileSdk 35, minSdk 26, targetSdk 34
- Java/Kotlin 17
- `buildFeatures { aidl = true }`
- dependencies: two local AARs, `testImplementation("junit:junit:4.13.2")`.
- No other runtime dependencies, no INTERNET permission, no analytics.

Unit tests (`app/src/test/java/...`):
- `UiXmlParserTest`: load `main_partial.xml` -> stage1 5/9, stage2 and stage3 as in XML;
  load `main_done.xml` -> done=true, all stages completed; duration parse; CTA text/bounds;
  user name/id; no crash on empty/invalid XML.
- `FocusParserTest`: focus line with u0/u128, MainActivity/ad activity; no user token.
- `RewardPolicyTest`: credited if stage increased or duration delta >= 500; not credited for
  a text-only "广告加载失败"; done detection.
- `ShellProtocolTest`: base64 result encode/decode.

Acceptance:
- `gradle.bat :app:testDebugUnitTest` passes.
- `gradle.bat :app:assembleDebug` passes.
- APK exists; `aapt dump badging` shows label/version; manifest has no accessibility service,
  no INTERNET, no QUERY_ALL_PACKAGES.
- Print file list and test summary when done.

## 13. Grok rules

- Work only in this project directory.
- Read SPEC.md, the old `watch_ads_v3.py`, and the sample XMLs first.
- Do not modify `(old project)` or the phone.
- Do not use AccessibilityService, root, INTERNET, or any reward API.
- Do not weaken the 18s/15s/2s safety constants.
- If a requirement is ambiguous, choose the safe option and add a TODO comment; do not invent
  a faster kill or skip force-stop.
- Build and test locally; fix compile errors until green.
- At the end print: files created/modified, test command + result, APK path, any TODOs.
