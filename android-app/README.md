# 外星仔自动领时长 · 安卓 App 版

无需电脑。无障碍服务驱动，在手机本机自动看完「外星仔加速器」的每日激励广告。

## 使用

1. 安装 `app/build/outputs/apk/debug/app-debug.apk`（荣耀会要求指纹确认）。
2. 打开 App，确认顶部显示「无障碍服务：已开启」。
   重装/覆盖安装后权限通常保留；若掉了，点「打开无障碍设置」手动开。
3. 点「开始（21 轮）」。跑完自动停；检测到「今日广告已看完」会立即停。
4. 日志同时写 `/sdcard/Android/data/com.alienbooster.claimer/files/claim-log.txt`，
   可用 `adb shell cat` 读取，不用打断运行。

## 工作原理（对应 PC 版 rewarded_ad_loop.py v4）

| PC 版 | 本 App |
|---|---|
| dumpsys window 轮询 | TYPE_WINDOW_STATE_CHANGED 事件（零开销） |
| uiautomator dump ~7s | rootInActiveWindow 本地遍历（毫秒级） |
| input tap | dispatchGesture |
| keyevent BACK | GLOBAL_ACTION_BACK |
| am force-stop | 无权限，BACK + 重新拉起代替 |

- 快手（kwad）广告 SurfaceView 读不到 → 固定等 26 秒。
- 穿山甲（byazt）可读 → 等「领取成功 / 奖励已发放」或解析倒计时
  （`Ns后可领取奖励`、`奖励将于 N 秒后发放`）再点「跳过」。
- 荣耀「想要打开」拦截弹窗 → 自动点「拒绝」。
- 坐标为荣耀 WIN Y（1272×2800）硬编码，在 `AdClaimService.kt` 的 companion object。

## 已知事项

- 额度用完后该 App 仍会播广告（不发奖励），所以每轮点按前先在主界面查
  「今日广告已看完」再决定是否继续。
- 冷启动外星仔较慢（可能带开屏广告），首轮 ensureMain 可能耗满 60 秒预算后
  靠 reset 恢复，属正常。
- 自动化刷激励广告违反该 App 用户协议，有封号风险。仅限本人设备使用。

## 构建

```
set JAVA_HOME=C:\Users\杨瞻丞\android-build\jdk\jdk-21.0.12.1+1
set ANDROID_HOME=C:\Users\杨瞻丞\android-sdk
set GRADLE_USER_HOME=C:\dev\gradle-home
C:\Users\杨瞻丞\android-build\gradle-8.9\bin\gradle.bat :app:assembleDebug
```

注意：工程必须放在纯 ASCII 路径（如 `C:\dev`），AGP 拒绝中文路径。
C:\dev\gradle-home\init.d\mirrors.gradle 配了阿里云镜像（本机直连 Maven 慢）。
