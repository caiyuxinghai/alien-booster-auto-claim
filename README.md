# alien-booster-auto-claim

个人设备上的自动化工具集：自动完成某加速器 App 的每日激励视频任务，把"看广告领时长"变成一键/无人值守运行。

> **免责声明**：自动化观看激励广告可能违反目标 App 的服务条款。本项目仅供个人在自己持有的设备和账号上学习研究无障碍自动化/ADB 自动化技术，请在运行前自行评估风险，勿用于批量养号、刷量等用途。

## 组成

### `android-app/` — 手机端 App（claimer v0.2.0，独立运行，无需电脑）
Kotlin 无障碍服务，零第三方依赖。核心机制：
- **真值校验**：以宿主界面"X时Y分"总时长计数器为准，广告接口返回值一律不信
- **计数拆分**：开不出任务页（库存真空）只退避不退出；连续 6 次"开了未到账"才重置加载器
- **自适应退避**：30→120s 指数退避 ±20% 抖动
- **网络探针**：ping 223.5.5.5，断网休眠 120s 不计失败（区分"断网"与"没库存"）
- **冻帧检测**：节点树签名连续两次相同判定卡死，强关重来
- **锁屏守卫**：自动唤醒+上滑解锁，口袋模式遮挡会明确告警
- **文本定位优先**：按钮先按文字找，写死坐标只作兜底
- 日志落盘 `claimer.log`，前台服务保活

构建：AGP 8.7.3 + Kotlin 2.0.21 + Gradle 8.9，`gradle assembleDebug`（无 wrapper，需自行准备 `local.properties` 指向本机 SDK）。

### `scripts/` — PC 端方案（手机插电脑，adb 驱动）
- `patient_loop.py`：主循环（单实例锁/真值校验/退避/adb 看门狗）
- `rewarded_ad_loop.py`：单轮广告观看（状态枚举/闪退快速通道/冻帧检测）
- `android_control.py`：adb 工具层（设备锁定/网络探针/锁屏守卫）
- `find_region.py`：按钮区域定位辅助（需 Pillow）
- `gh_push_via_api.py`：无 git 环境下走 GitHub API 推送的小工具

PC 端依赖：Python 3.10+，adb 在 PATH（或设 `ADB_PATH`），多设备环境设 `AD_SERIAL`。

## 用法
手机端：安装 APK → 开启 claimer 无障碍服务 → 打开 App 点"开始"。
PC 端：`python patient_loop.py`（参数见各文件 docstring）。
