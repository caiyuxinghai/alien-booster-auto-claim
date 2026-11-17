# 手机 App 版 — 外星仔领时长 v0.8.0（Kotlin + Shizuku）

`pc/watch_ads_v3.py` 的手机本机移植版：Shizuku 授权后无需电脑，在手机上自动完成外星仔加速器每日激励广告任务（主号 + 荣耀应用分身双账号串行，各 21 条 = +6h）。

- 包名 `com.alienbooster.claimer`，minSdk 26 / targetSdk 34，Kotlin 2.0 + AGP 8.7
- **无 INTERNET 权限**：只做正常 UI 操作（tap / back / home / am start / am force-stop），通过 Shizuku 的 shell 执行；不调目标 App 私有 API、不改包、不伪造奖励、不用 root、不含无障碍服务
- 完整设计规格见 [SPEC.md](SPEC.md)（安全不变式、状态机、AIDL 接口、UI 解析规则）

## 工作原理

与 PC 引擎同一套判定逻辑（详见 `pc/EXPERIENCE.md`）：

1. 焦点组件名（`dumpsys window` mCurrentFocus）判定广告开始/结束；
2. 击杀线 `KILL_AT=18s`（实测发奖点在 tap 后 18–20s）+ 广告打开后至少 15s，焦点已回 MainActivity 则提前 break 并再等 2s；
3. 每轮 `am force-stop --user N` + 冷启动，靠 onResume 拉取的服务端阶段计数验收；
4. 时长增量只认 600/1200/1800s 白名单；
5. 主号（user 0）、分身（user 128）串行，跨 user 操作前用 mCurrentFocus 的 user 号校验前台归属。

Shizuku 提供 `am`/`dumpsys`/`uiautomator dump` 的 shell 身份执行能力（0.5s 直接 force-stop），比无障碍走系统设置页强制停止（10–20s/轮且脆）可靠得多。

## 构建

JDK 17 + Gradle 8.9 + Android SDK（compileSdk 35）。仓库不含 wrapper：

```bash
# 根目录写 local.properties：sdk.dir=你的 SDK 路径
gradle :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
gradle :app:testDebugUnitTest
```

Shizuku SDK 通过 MavenCentral 依赖（`dev.rikka.shizuku:api` / `:provider` 13.1.5），aidl/shared 由传递依赖自动带入。

## 安装与授权

参考 `deploy.sh.example`：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.alienbooster.claimer moe.shizuku.manager.permission.API_V23
adb shell pm grant com.alienbooster.claimer android.permission.POST_NOTIFICATIONS
```

1. 安装并启动 **Shizuku**（USB 调试开启时用 adb 启动；重启手机后需要重新启动 Shizuku）。
2. 打开 claimer，在 App 内完成 Shizuku 授权（选「始终允许」）。
3. 设置每日启动时间，点开始。前台服务通知显示进度（阶段计数 9/9、3/3、9/9）。

调试用 Intent extras：`--ez selftest true`（只读自检零消耗）、`--ez auto_start true`、`--ei max_ads 21`、`--ei user 0`。

## 已知限制

- Shizuku 是最脆环节：USB 调试关闭/手机重启后需重新用 adb 拉起 Shizuku 并确认授权；
- 荣耀后台管控较严，已加开机自启、精确闹钟、电池优化白名单，但长时间被杀后仍可能漏跑——日常仍建议保留 PC 计划任务兜底；
- 单条广告偶发失败（SDK 超时/加载失败）时，重跑会从 App 阶段计数续上；不要相信 App 自身 flag 文件里的本轮条数，以阶段 9/9+3/3+9/9 为准。

## 风险提示

自动化播放激励广告可能违反外星仔加速器用户协议，有封号风险。仅限本人设备学习研究。
