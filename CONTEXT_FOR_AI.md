# CONTEXT_FOR_AI.md — 交接上下文（压缩版）

## 项目目的
外星仔加速器（`com.etalien.booster`）每日 9 次激励视频广告全自动领取「可暂停时长」。两条路线：
- **手机端** `android-app/`：Kotlin 无障碍服务，独立运行（当前主推，v0.3.0 已实测）
- **PC 端** `scripts/`：adb 驱动 Python 脚本（成熟稳定，手机插电脑时用）

## 关键事实
- 宿主包名 `com.etalien.booster`，主 Activity `.ui.MainActivity`，启动 Activity `.ui.SplashActivity`
- 宿主主界面是 WebView：uiautomator dump 看不到文本，但**无障碍服务能读到**（真值校验依赖这点）
- 计数器格式 `X时Y分`，正则 `(\d+)时(\d+)分`
- 常用广告 SDK 类名关键词：`kwad/ksad`（快手）、`bytedance.sdk.openadsdk`/含 `reward`（穿山甲）
- 广告关闭后可能弹「确定要退出吗？ 坚持退出/残忍离开/狠心离开」确认框，必须按文本点掉
- MagicOS 跳转会弹「想要打开...」系统框 → 按「拒绝」
- 屏幕 1272x2800；兜底坐标：看广告按钮 (635,2156)，跳过 (1132,254)，拒绝 (370,2519)
- 广告库存真空期/0-2s 闪退广告是**服务端正常行为**，只退避不报错
- 奖励入账以界面计数器增长为唯一真值；延迟入账常见（verify 8×2.5s + 下轮兜底补检）

## 踩过的坑（不要重蹈）
1. **无障碍 CONTENT_CHANGED 事件的 className 是控件名**（FrameLayout 等），会覆盖 Activity 类名缓存 → 永远以为没到主界面。只从 WINDOW_STATE_CHANGED 更新 curCls（v0.2.2 修复）。
2. **App 沙箱内 ping 被内核拒绝（EPERM）**→ 用 ConnectivityManager 的 NET_CAPABILITY_VALIDATED，且要 fail-open（v0.2.1 修复）。
3. **残留广告不能强杀**：奖励在播完时才入账，强杀丢奖励还触发退出确认框死循环。领养收尾（v0.3.0）。
4. **`ActivityManager.appTasks` 只返回自己 App 的任务**，拿不到宿主任务栈，别用它判前台。
5. `flagRequestEnhancedWebAccessibility` 在这台 MagicOS 设备上会导致服务异常，不要加。
6. **claimer 运行时会把任何非宿主前台窗口按返回键**——adb 安装/升级前必须先停用它的无障碍服务，否则安装确认框被它按掉（INSTALL_FAILED_ABORTED: User rejected）。
7. 安装被拒时用 `settings put global verifier_verify_adb_installs 0` + `package_verifier_enable 0`。

## 版本历史
- v0.1.0：初版（8 项缺陷）
- v0.2.0：PC 逻辑移植（ping 网络判定 → 沙箱内瘫痪）
- v0.2.1：ConnectivityManager 网络判定
- v0.2.2：修 CONTENT_CHANGED 污染 curCls → 能到主界面了
- v0.2.5/0.2.7：残留广告 70s 强杀 + 退出确认框处理（强杀方向错误，但仍卡）
- **v0.3.0（当前）**：残留广告改为领养收尾 + 主循环兜底补检，实测 3 轮各 +20 分钟稳定到账

## 设备与环境
- 手机：Honor MagicOS（Android 15），USB 调试常开，WiFi ADB 可用
- PC：Windows 11 25H2，adb 在 `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`
- 构建：`C:\Users\Ricky\.gradle\wrapper\dists\gradle-8.9-bin\90cnw93cvbtalezasaz0blq0a\gradle-8.9\bin\gradle.bat`（无 wrapper 脚本）
- GitHub：`caiyuxinghai/alien-booster-auto-claim`（公开）

## 装机流程（claimer 在运行时必守顺序）
1. `settings put secure enabled_accessibility_services <去掉claimer>` 停服务
2. `am force-stop com.alienbooster.claimer`（可选，更稳）
3. `pm install -r` 安装
4. 恢复 enabled_accessibility_services（加上 claimer）
5. monkey 启动 claimer → `input tap 346 612` 点「开始」
6. `run-as com.alienbooster.claimer sh -c 'tail files/claimer.log'` 看日志
