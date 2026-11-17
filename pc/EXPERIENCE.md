# 外星仔加速器「看广告领时长」ADB 自动化 — 实战经验全集

> 来源：2026-10-14 至 10-22 在 荣耀 WIN（型号 AAP-AN00，MagicOS）与 vivo iQOO Z5x（OriginOS）上的真机实测。
> 应用：`com.etalien.booster`（外星仔加速器），任务：每天自动看完 21 个激励广告领 6 小时时长
> （阶段一 9×20min + 阶段二 3×30min + 加油包 9×10min）。

## 1. 广告 SDK 全景（实测四家）

| SDK | Activity 组件名 | 屏幕文本特征 | 形态 |
|---|---|---|---|
| BeiZi 杯子 | `com.beizi.ad.v2.activity.BeiZiNewRewardVideoActivity` | 有倒计时「25s后获取奖励」 | 应用内页面 |
| Kwad 快手联盟 | `com.kwad.sdk.api.proxy.app.KsRewardVideoActivity` | 文本层 stale 假死（「广告加载中」50s 不动） | Surface 视频 |
| 腾讯广点通 | `com.qq.e.ads.PortraitADActivity` | 无有效文本信号 | Surface 视频 |
| Octopus | `com.octopus.ad.AdActivity` | 无有效文本信号 | Surface 视频 |

**核心结论：屏幕文本不可信，焦点组件名才可信。**

## 2. 权威信号（按可靠度排序）

1. **焦点组件名（dumpsys window → mCurrentFocus 的 包/Activity 完整名）**
   - 广告 Activity 的**窗口归本包但 Activity 名 ≠ MainActivity`；
   - 组件离开 MainActivity = 广告开始；**回到 MainActivity = 广告结束**（奖励状态已定）；
   - 通知弹窗（微信等）会换包名，**不会伪造广告 Activity**——比包名探针可靠一个量级；
   - 成本 0.14s/次，是整个系统的骨架信号。
2. **SDK 倒计时文案**：`(\d+)\s*(?:s|秒)\s*后(?:即可)?获[得取]奖励`，覆盖「25s后获取奖励」与「浏览页面 13 秒后即可获得奖励」。倒计时归零 = 发奖。首次 sighting 定预计归零点 = now+N，之后只扩展不提前（文案提前消失也按原估计等）。
3. **发奖文案**：「恭喜获得奖励/奖励已发放/领取成功/已获得奖励」。注意倒计时文案「13秒后即可获得奖励」也含「获得奖励」，**不能当发奖**。
4. **兜底固定等待 30s**：无任何文本信号的形态（Surface 视频），实测发奖 ≤28s，30s 覆盖最长标准激励视频。

## 3. 奖励安全不变式（最高优先级，违反=丢奖励）

- **绝不在 SDK 发奖前退出广告**。提前退出=该轮无奖励（消耗时间不消耗额度，重试补）。
- **发奖后不能立刻杀进程**：实测等 2s 杀=不到账，等 23s=到账。落账等待 8s。
- 杀进程前焦点回主界面或兜底已到期，两种都证明「广告已结束」，奖励状态已定。
- 奖励**扛后台**：广告播放中微信跳前台 30s+，奖励照样到账（SDK 先发奖、进程不死即可）。
- 分数/时长**只在 App 重启后同步**：停留主界面不刷新；必须 force-stop + 重开。
- **判账看阶段计数**（阶段一/二/加油包的 X/Y 之和）+ 时长增量白名单（600/1200/1800s），时长字段刷新滞后不可靠。

## 4. 性能账（弱机实测，荣耀 WIN 老机）

- uiautomator dump：**2.3s/次**；视频 Surface 期 **12s 超时**（旧代码不捕获异常，一次超时炸掉整个批处理——必须兜底返空）。
- 旧代码等待段 40s 里 ~34s 全耗在反复 dump 上；广告本体只 25-30s。
- **dump 双节食**：①倒计时预计归零前 3s 不 dump；②广告前台文本连续 2 次不变/空（Surface 视频 stale）→ 停止 dump 只做焦点探针，回主界面恢复。
- 单轮演进：旧 71.9s（wait 41.3 + post 30.7）→ 优化后 45.2s（38.2 + 7.0）→ 立即 break 后预期 ~37-38s。
- **硬下限 = 广告视频 25-30s**（SDK 规定观看时长），占单轮 2/3，动不了。FALLBACK_WAIT=30 是理论最优（压到 26s 若遇 28-30s 视频反而净亏）。
- 收尾 7s（force-stop 0.5 + 冷启 ~4.5 + 同步 ~1 + 焦点确认 ~1）已近极限。

## 5. 设备层差异

| | 荣耀 WIN（MagicOS） | vivo（OriginOS） |
|---|---|---|
| dump | `adb shell uiautomator dump /sdcard/ui.xml` + cat | **uiautomator 被 ROM 禁用**（系统服务层拒绝 shell 注册 UiTestAutomationService，logcat 实证，dump 静默失败 rc=0 无输出） |
| 替代 | — | **mobilecli**（dex + localabstract socket JSON-RPC，`mobilecli dump ui` 2.0s，绕开被禁路径） |
| 启动 | `am start -n pkg/.ui.MainActivity`（跳过 Splash） | `.ui.SplashActivity |
| 解锁 | 本机锁屏 PIN（自备） | 本机锁屏 PIN（自备） |
| 元素 id | 有 resource-id | **无 resource-id**，score/CTA 全靠文字+位置启发式 |
| 稳定性 | USB 调试会自掉线 | 同上，且更频繁 |

## 6. 无人值守要点

- **必须 `schtasks /run` 触发**：AI 会话里直拉的进程会被回收（静默消失、无报错、锁残留）。
- **锁**：`O_CREAT|O_EXCL` 原子创建 + 每轮心跳 + 20min 过期（exists→create 有 TOCTOU，实测两实例抢手机）。
- **bat 必须 CRLF + 纯 ASCII**：LF 的 bat 在 cmd 下串行啃字。
- **GBK 解码坑**：中文 Windows 读 adb 输出必须 `encoding="utf-8", errors="replace"`。
- **设备离线自检要精确匹配**：`adb get-state` 离线时输出 `error: device 'xxx' not found`（含 "device" 字样，模糊匹配会被骗）；用 `for /f` 抓 stdout 精确比 `"device"`，离线 exit 9 秒退。
- **flag 机制**：当天领完写 `claimed_YYYY-MM-DD.flag`，后续运行 0.5s 秒退；换账号要删 flag。
- **调度**：`\WatchAdsdaily` 每天 08:20 主跑 + `\WatchAdsretry` 全天每 30min 兜底（有 flag 或设备离线即退）。
- **并发互锁三层**：Task Scheduler `IgnoreNew` + 脚本原子锁 + flag 秒退。
- **NO CTA 不是「领完」**：找不到 CTA 时若屏幕含「明日再来/已看完」→ 写 flag 正常退出；否则 slot 1 重试 3 次后 exit 3 明确失败（旧逻辑 exit 0 假成功导致兜底任务整天空转）。

## 7. 已知坑速查

- OriginOS 禁 uiautomator → 用 mobilecli，别修 uiautomator，别改无障碍服务。
- 冷启动首轮 dump 可能抽空（隔夜渲染慢）→ 多等 1s 重试。
- 广告结束卡可能挂 20s+ 不关 → BACK 软退出（每 3s 一次），别点「跳过」（那是广告内的跳过，点了没奖励）。
- 「广告加载中」文本是 stale 的，别等它消失。
- 弹窗遮挡（领取/开心收下/确定/好的）→ 先点掉再找 CTA。
- 未登录时点 CTA 弹登录页 → 检测「登录/注册」字样 exit 4，必须用户手动登录。

## 10-23 补充：击杀窗口实验（第五家 SDK + 发奖点定位）

### 广告 SDK 全景（实测五家）
| SDK | Activity 组件名 |
|---|---|
| BeiZi 杯子 | `com.beizi.ad.v2.activity.BeiZiNewRewardVideoActivity` |
| Kwad 快手 | `com.kwad.sdk.api.proxy.app.KsRewardVideoActivity` |
| 腾讯广点通 | `com.qq.e.ads.PortraitADActivity` |
| Octopus 章鱼 | `com.octopus.ad.AdActivity` |
| **字节穿山丁** | `com.bytedance.sdk.openadsdk.core.component.reward.activity.TTRewardVideoActivity` |

### 击杀窗口实测（同一台荣耀 WIN，奖励页打开后多久杀才到账）
| 杀点(奖励页打开后) | 结果 | 形态 |
|---|---|---|
| +1.2s（端侧 App 的生产值） | **丢** ✗ | BeiZi |
| +15s（+5s 落账 ≈ 20s 起杀） | **到账** ✓ 2/2 | Octopus、穿山丁 |
| +25s（+5s 落账 ≈ 30s 起杀） | **到账** ✓ 2/2 | BeiZi |
| 30s 盲等 +8s 落账（≈38s 起杀） | **到账** ✓ 10/10 | 10-22 全形态 |

**结论：发奖点在视频中段（用户手动实验：看一半就杀也到账 ≈12-13s），不在奖励页打开那一刻**。端侧 App 的 1.2s 在其无障碍路径上成立（事件感知有滞后，实际击杀更晚），ADB 实时检测路径上不成立。v3 取值：主信号焦点回归（Activity 自己关，BeiZi ~10s 最快），session_max=15s 兜底。

### 弹窗坑（10-22 真机教训）
广告中途跳转拼多多会弹荣耀安全窗（拒绝 370,2519 / 允许 901,2519）。**不点拒绝则 force-stop 也清不掉**（系统弹窗不归目标应用），同步全废。denyJump 必须覆盖所有阶段（watch/settle/同步/启动）。另：重启后 uiautomator dump 会连续 3+ 次返回弹窗的 stale 树，要持续重试。

### logcat 尝试（未果）
奖励回调信号没抓到（设备 logcat 缓冲区只剩 20 行，`-d` 无所获）；`logcat -b all` 待试。这是唯一可能把每轮压到 ~15s 的信号源。

## 10-26 补充：双账号分身 + 连接稳定性（另一会话交接文档）

### 应用分身 = 独立 Android user
- 荣耀「应用分身」：`pm list users` → user 0（机主/主号）、user 128（分身应用），user 100（平行空间，无关）。
- 双账号串行领（先主号后分身），**不能并行**（同机同 App 两个实例会抢前台）。
- `am start --user N -n <comp>`、`am force-stop --user N <pkg>`（10-25 实机验证有效）。
- v3.4 已接入：`--user N` CLI；flag/log/stats 按 user 隔离；锁按设备共享（保证串行）；跑完 stayon 复原。

### 连接稳定性（荣耀 WIN 专用）
- **ADB_LIBUSB=1 必须**：新版 platform-tools 默认 USB 后端在 荣耀 WIN 上枚举不稳（get-state 挂死、devices 全空）。所有 adb 调用（含脚本内部 subprocess）都要带，否则脚本会自行重启 server。
- LIBUSB 枚举延迟 15-60s：kill-server+start-server 后立即查会误报空，等 15s+ 再查。
- USB 模式须是「传输文件(MTP)」，不能是「仅充电」。
- 荣耀 WIN HDB（手机助手）与 adb 是独立通道，HDB 报错不影响 adb。

### 电源管理
- `svc power stayon true` 只在批处理期间开，**跑完必须 `stayon false`**——长亮发热，且掉线时远程关不掉（用户只能拔线自救）。
- 批处理前唤醒屏幕+解锁（KEYCODE_WAKEUP + 上滑）。

### 另一实现的对照（rewarded_ad_loop.py）
- 原版（15s 兜底）~37s/广告；fast 版（10s 兜底）~22.6s 但**漏奖**（toast「获取奖励失败」）。与我的击杀窗口实验一致：<15s 会漏。
- 其落地页「乱跳」问题 = 盲点跳过坐标 + 硬切，v3 用组件探针 + 结束才 BACK，无此问题。
- 其「watched 计数虚高」问题 = 不验证到账就计数；v3 用阶段计数+时长增量验证，无此问题。

## 10-28：击杀点下界 + 热拉起失败 + 实例守卫

### 击杀点二分（真·即时 force-stop，每档 2 轮）
| 杀点(tap 起算) | 结果 |
|---|---|
| 8s / 12s / 15s+2s落账(17s杀) | **MISS** ✗ |
| 18s+2s落账(20s杀) | **到账** ✓ 2/2 |
→ 发奖落在 tap 后约 18-20s；旧版杀在 23s，多等 3-5s。取 KILL_AT=18 + SETTLE_FAST=2。

### 热拉起（am start 不 force-stop）——失败，已回退
- 理论上省 ~5s，实测两个问题：①刷新计数不可靠（"hot start did not refresh"）；②**广告 Activity 残留在前台，App 卡死**，后续点 CTA 全无效（连锁 6 连 X，focus 卡在 TTRewardVideoActivity）。
- 结论：必须 force-stop + 冷启动（已验证 38/38）。回退后恢复。

### 新增阻塞处理（三类）
1. 荣耀跳转弹窗「想要打开拼多多」→ 点拒绝（10-22）
2. **App 退出确认框「温馨提醒 确定要退出吗？取消 确认」**→ BACK 误触弹出，盖住 CTA → 点取消（10-28）
3. **「广告加载失败，点击重试」**→ 点重试重新取广告（10-28）

### 实例守卫（分身必备）
- 主号(u0)/分身(u128) 包名与 Activity 完全相同，读屏无法区分实例 → 曾两次误判「今日已看完」并写错 flag（10-26、10-28）。
- 解法：①批次启动先 `am start --user N` 把本实例抢到前台；②读屏前用 `dumpsys window` 的 **mCurrentFocus 行里的 user 号(u0/u128)** 校验前台归属，不符就重拉。
- 另：分身广告的跳转会把应用市场开到默认 user0 并抢前台，更需要这个守卫。

### 锁的健壮性
- 被杀/崩溃的进程会留下锁(PID 已死)，之前要等 20 分钟 stale → 改为**读锁里的 PID 判活，死了立即清**（Windows: OpenProcess + PROCESS_QUERY_LIMITED_INFORMATION）。
- worker 异常必须写进文件日志（batch_<serial>_crash.log），只打 stdout 会被管道吞掉 → 今天的「静默死亡」就是这样查了半天的。

### 单轮耗时演进
68s(v2) → 45s(v3.1) → 35-36s(v3.3/3.4) → **29-32s(v3.5，KILL_AT=18s)**；21 轮/账号 ≈ 10 分钟。
