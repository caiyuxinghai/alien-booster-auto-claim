# 安卓 App 版（claimer v0.2.0）

无需电脑。Kotlin 无障碍服务在手机本机自动完成每日激励视频任务，零第三方依赖。

## 使用

1. 安装 APK（Release 页有已构建好的包）。
2. 开启 claimer 无障碍服务（App 内点"开始"会引导跳转设置）。
3. 打开 App 点「开始」，跑完「今日已领完」自动停。
4. 日志同时写应用私有目录 `claimer.log`，可用 `adb shell cat /sdcard/Android/data/com.alienbooster.claimer/files/claimer.log` 查看。

## v0.2.0 相对 v0.1 的加固

| 机制 | 说明 |
|---|---|
| 真值校验 | 以宿主界面"X时Y分"总时长为准，涨了才算到账；基准下跌自动重锁 |
| 计数拆分 | 开不出任务页（库存真空）只退避不退出；连续 6 次"开了未到账"重置加载器 |
| 自适应退避 | 30→120s 指数退避 ±20% 抖动 |
| 网络探针 | ping 223.5.5.5，断网休眠 120s 不计失败 |
| 冻帧检测 | 节点树签名连续两次相同判定卡死，强关重来 |
| 锁屏守卫 | 自动唤醒+上滑解锁；唤不醒明确告警（如口袋模式遮挡） |
| 文本定位 | 按钮先按文字找，硬编码坐标（1272×2800）仅兜底 |
| 保活与日志 | specialUse 前台服务 + PARTIAL_WAKE_LOCK；日志落盘可复盘 |

## 构建

AGP 8.7.3 + Kotlin 2.0.21 + Gradle 8.9。项目不含 wrapper，自备 gradle 后在根目录
放 `local.properties`（一行 `sdk.dir=...`），`gradle assembleDebug`。

> 自动化刷激励广告违反目标 App 用户协议，有封号风险。仅限本人设备学习研究。
