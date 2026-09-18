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

## Windows 坑位
- Windows 版 `python.exe` **不认 Git-Bash 的 `/c/...` 路径** → 传 `C:/...`。
- 截图要写到读端能访问的位置（别用 `/tmp`）。

## 安全与合规
- 破坏性操作（卸载、rm、改系统设置）动手前先确认。
- 自动化刷激励广告**可能违反目标 App 的用户协议**，有封号风险 —— 先提醒用户。
