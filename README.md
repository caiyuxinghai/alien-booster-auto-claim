# alien-booster-auto-claim · 外星仔加速器自动领时长

用 `adb` 自动看完激励视频广告并领取每日时长（额度），一次跑满当天全部阶段。
底层是一套通用安卓 adb 控制工具：设备授权、屏幕常亮、截图、UI 检查、坐标点按、
系统弹窗处理，加一个**高速广告自动循环**。

实测环境：**Windows (Git Bash) + 荣耀 MagicOS / Android 17**（机型 `AAP-AN00`，
1272×2800）。

> 仅供**自己设备、自己账号**上的个人实验。自动化刷激励广告可能违反目标 App
> 用户协议和广告主政策，风险自担，别拿去规模化刷量。

## 快速开始

```bash
adb devices                        # 手机显示 unauthorized 就去手机上点「允许 USB 调试」
adb shell svc power stayon true    # 插着 USB 时保持屏幕常亮

python scripts/rewarded_ad_loop.py 21 \
    --package com.etalien.booster \
    --main    com.etalien.booster/.ui.MainActivity \
    --watch   635,2156 \
    --skip    1132,254 \
    --deny    370,2519
```

坐标是 `AAP-AN00`（1272×2800）上的实测值。换机型需重测——用
`scripts/find_region.py` 按颜色定位，或直接看截图量。

## 速度技巧（核心）

实测常见 adb 操作耗时（荣耀 AAP-AN00，USB）：

| 操作 | 耗时 |
|---|---|
| `uiautomator dump` | **7.3 s** |
| `dumpsys window` | 0.12 s |
| `screencap` | 0.33 s |
| `am start` | 0.09 s |

朴素的自动点击器每隔几秒就 `uiautomator dump` 一次去问"奖励好了没"。按每次
7.3 秒算，**光 dump 每个广告就白烧 ~35 秒**。本工具改用廉价的 `dumpsys window`
轮询状态，`uiautomator` **每个广告最多调一次**。

21 个广告实测：**~106 秒/广告 → ~37 秒/广告（约 3 倍提速）**。

## 处理了什么

- **设备授权 / 状态**：`unauthorized` → 用户在手机上点"允许 USB 调试"；用
  `svc power stayon true` 防熄屏。
- **`uiautomator` 盲区**：很多激励广告渲染在 `SurfaceView` / WebView 里，
  `dump` 拿不到任何节点。退路是截图 + 按颜色定位坐标（`scripts/find_region.py`）。
- **两类广告 SDK**：
  - **穿山甲 / byazt** —— `uiautomator` **可读**。播放中气泡显示
    `Ns后可领取奖励`，放完变 `领取成功`。**必须等到 `领取成功` 再点"跳过"**，
    提前点直接放弃奖励。
  - **快手 / kwad** —— `uiautomator` **读不到**，画面看着像卡住，但奖励**已经
    发放到服务端**。等 ~26 秒后把 App 拉回前台即可（force-stop 兜底）。
- **荣耀"跳转"弹窗** —— 广告深链到别的 App 时会被荣耀安全中心拦下
  （`... 想要打开 XXX，是否允许？`）。自动点**拒绝**，别让它跳走。

## 目录结构

```
scripts/
  android_control.py      # 轻量 adb 辅助库
  rewarded_ad_loop.py     # 高速 N 连刷循环（CLI 可配置）
  find_region.py          # 按颜色找按钮坐标（依赖 Pillow）
  gh_push_via_api.py      # 附带小工具：github.com:443 不通时，改走 API 推文件
```

`gh_push_via_api.py` 用于某些沙箱网络里 `github.com:443` 被拦、但
`api.github.com` 仍可访问的场景 —— 此时 `git push` 必然失败，改用 GitHub
Contents API 逐个文件提交：

```bash
python scripts/gh_push_via_api.py <owner>/<repo> main README.md SKILL.md
```

## 环境要求

- `adb` 在 PATH 里（`platform-tools`），手机已开 USB 调试。
- Python 3.8+（仅标准库；`find_region.py` 额外需要 `pip install pillow`）。

## 外星仔每日额度结构

| 阶段 | 广告数 | 每个奖励 | 小计 |
|---|---|---|---|
| 阶段一 | 9 | 20 分钟 | 180 分钟 |
| 阶段二（阶段一后解锁） | 3 | 30 分钟 | 90 分钟 |
| 阶段三 / 加油包 | 9 | 10 分钟 | 90 分钟 |
| **合计** | **21** | | **360 分钟（6 小时）** |

每天刷新。跑完按钮会变成"今日广告已看完，请明日再来"。

## 连不上手机？按这个顺序查

**① `adb devices` 全空 —— 先换 USB 后端（最常见、最容易误判）**

```bash
ADB_LIBUSB=1 adb kill-server && ADB_LIBUSB=1 adb start-server && ADB_LIBUSB=1 adb devices
```

新版 platform-tools 在部分 Windows 环境下**默认 USB 后端抢不到荣耀的 ADB 接口**，
`adb devices` 会一直空，特别容易被误判成"线坏了 / 驱动没装 / 手机没连"。
实测加上 `ADB_LIBUSB=1` 后设备立刻出现。用 `adb server-status` 可确认后端是否为
`usb_backend: LIBUSB`。**建议本项目所有 adb 调用都带上这个变量。**

**② 刚 `start-server` 就查会误报空**

服务刚起时 USB 枚举尚未完成，`adb devices` 可能返回空表。等 2~3 秒或轮询几次再判断。

**③ 状态显示 `unauthorized`**

手机上会弹"是否允许 USB 调试"→ 勾 **一律允许** → 允许。若弹窗不出现，去
开发人员选项点 **撤销 USB 调试授权**，或把 USB 调试**关掉再打开**，强制重新弹一次。

**④ 手机侧前置条件**

- USB 连接方式不能是"**仅充电**"（仅充电模式不暴露 adb 接口）→ 切 **传输文件(MTP)**。
- 开发人员选项里 **USB 调试** 必须打开。
- 状态栏"已连接 USB 调试"只说明开关是开的，**不代表电脑已经认到设备**。

**⑤ HDB 和 adb 是两条独立通道**

荣耀手机助手走 HDB（`VID_339B&PID_xxxx&MI_03`），adb 走 MI_02。HDB 报错
**不影响** adb，别为了 adb 去折腾手机助手。

**⑥ 想看电脑到底认没认到这台手机**

```bash
reg query "HKLM\SYSTEM\CurrentControlSet\Enum\USB" | grep -i 339B
```

荣耀的 USB 厂商 ID 是 `VID_339B`。注意：注册表条目**含历史缓存**，只能用来确认
"这台机器曾经认过这台设备"，判断当前是否在线要用 `Get-PnpDevice -PresentOnly`。

## Windows 坑位

- 拉起 App 优先用 `am start -n pkg/.Activity`，别用 `force-stop` + `monkey` ——
  约 0.1 秒 vs 约 5 秒冷启动。
- 如果之后要有别的工具读截图，写到它能访问的位置，**别写 `/tmp`**
  （部分沙箱读不回来）。
- Windows 版 `python.exe` **不认 Git-Bash 的 `/c/...` 路径** —— 传 `C:/...`。
- `wmic` 在 Windows 11 26200 已移除；`pnputil` 输出是 UTF-16 且在部分沙箱里拿不到。

## 免责声明

自动化观看激励广告可能违反目标 App 的服务条款及其广告主政策。本工具仅供在
**你自己的设备和个人账号**上做个人实验，风险自担。请勿用于规模化广告欺诈。

## License

MIT
