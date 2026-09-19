---
name: alien-booster-auto-claim
description: >
  外星仔加速器（com.etalien.booster）手机自动领时长：用 adb 控制安卓手机，
  自动看完激励视频广告并领取每日额度。也适用于任何安卓 adb 控制场景——
  设备授权、常亮、截图、uiautomator 界面检查、坐标点按、荣耀"跳转拦截"弹窗
  处理，以及高速广告自动循环。Use when the user asks to auto-claim daily
  quota/time in 外星仔加速器, or to control, tap, automate, scrape on their
  USB-connected Android phone (incl. Honor/MagicOS).
---

# 外星仔加速器 · 自动领时长

完整文档见 `README.md`，脚本在 `scripts/`。

## 核心要点（最重要的一条）

`uiautomator dump` 一次要 **~7 秒**，而 `dumpsys window` 只要 **~0.1 秒**。
用 `dumpsys` 轮询状态（`scripts/android_control.py: focus()`），`uiautomator`
**每个广告最多调一次**。仅这一项就有 **~3 倍**吞吐差距。

## 快速索引

| 需求 | 调用 |
|---|---|
| 设备就绪 | `adb devices`（显示 `unauthorized` 就去手机点授权） |
| 屏幕常亮 | `adb shell svc power stayon true` |
| 截图 | `screencap(path)` —— 若别的工具要读，**别写 `/tmp`** |
| 当前前台 App | `focus()` → `(currentFocus, focusedApp)` |
| UI 文本 / 按钮坐标 | `dump_texts()` + `find_bounds(xml, "跳过")` |
| 点按 | `tap(x, y)` |
| 快速拉起 App | `bring_front("pkg/.Activity")` —— 比 force-stop + monkey 快得多 |
| 荣耀跳转弹窗 | `deny_honor_jump()`（点"拒绝"） |

## 领时长循环

```bash
python scripts/rewarded_ad_loop.py <N> \
  --package com.etalien.booster \
  --main com.etalien.booster/.ui.MainActivity \
  --watch X,Y --skip X,Y [--deny X,Y]
```

外星仔的实测坐标（AAP-AN00，1272×2800）：`--watch 635,2156`
`--skip 1132,254` `--deny 370,2519`。

按广告 SDK 分开处理：
- **穿山甲 (byazt)**：uiautomator **可读**。必须等出现 `领取成功` **再点**
  "跳过"（提前点 = 放弃奖励）。
- **快手 (kwad)**：uiautomator **读不到**、画面看似卡住，但奖励**照常发放** →
  等 ~26 秒后 `bring_front` 拉回主界面（force-stop 兜底）。
- 荣耀深链广告会触发 `com.hihonor.systemmanager` 弹窗 → 点"拒绝"。

## 每日额度结构（外星仔）

- 阶段一：9 个广告 × 20 分钟
- 阶段二：3 个广告 × 30 分钟（阶段一完成后解锁）
- 阶段三 / 加油包：9 个广告 × 10 分钟
- 合计 21 个广告 = **360 分钟（6 小时）**，三阶段每天刷新。

## 连不上手机时（按这个顺序排查，别瞎猜硬件）

**① `adb devices` 全空（连 `unauthorized` 都没有）→ 先试 USB 后端**

```bash
ADB_LIBUSB=1 adb kill-server && ADB_LIBUSB=1 adb start-server && ADB_LIBUSB=1 adb devices
```

新版 platform-tools 在某些 Windows 环境下**默认后端抢不到荣耀的 ADB 接口**，
`adb devices` 永远空，容易误判成"线坏了/驱动没装/手机没连"。实测 `ADB_LIBUSB=1`
立刻就能识别。**本项目所有 adb 调用都建议带这个环境变量。**
确认后端：`adb server-status` 应显示 `usb_backend: LIBUSB`。

**② `start-server` 后立刻查会误报空**

刚起服务时 USB 枚举还没完成，`adb devices` 可能返回空。**起服务后等 2~3 秒，
或轮询几次**再判断，否则会得出"设备没连"的错误结论。

**③ 状态 `unauthorized`**

手机要弹"是否允许 USB 调试"，勾 **一律允许** 再点允许。若弹窗不出现，让用户
**撤销 USB 调试授权**（开发人员选项）或把 USB 调试**关掉再打开**，强制重新弹。

**④ 手机侧前置条件**

- USB 模式不能是"仅充电"（仅充电不暴露 adb 接口）→ 切 **传输文件(MTP)**。
- 开发人员选项里 **USB 调试** 必须开。
- 状态栏出现"已连接 USB 调试"只说明开关开着，**不代表电脑认到了设备**。

**⑤ HDB ≠ adb**

荣耀手机助手走 HDB（`VID_339B&PID_xxxx&MI_03`，常见状态 Error），和 adb（MI_02）
是两条独立通道。HDB 连不上**不影响** adb；别为了 adb 去折腾手机助手。

## Windows 坑位
- Windows 版 `python.exe` **不认 Git-Bash 的 `/c/...` 路径** → 传 `C:/...`。
- 截图要写到读端能访问的位置（别用 `/tmp`）。
- 命令行字符串里**含 "PowerShell" 字样可能被沙箱安全策略拒绝**。
- `wmic` 在 Windows 11 26200 已移除；`pnputil` / `Get-PnpDevice` 在部分沙箱里
  拿不到输出（可让 PowerShell 把结果**写文件**再读）。
- 注册表 `HKLM\SYSTEM\CurrentControlSet\Enum\USB` 里的 `VID_*` 条目**含历史缓存**，
  不能用来判断设备当前是否在线。

## 安全与合规
- 破坏性操作（卸载、rm、改系统设置）动手前先确认。
- 自动化刷激励广告**可能违反目标 App 的用户协议**，有封号风险 —— 先提醒用户。
