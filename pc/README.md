# PC 引擎 — watch_ads_v3.py

电脑通过 ADB 自动完成外星仔加速器每日 21 条激励广告。Python 3.8+，**仅标准库**。

## 文件

| 文件 | 作用 |
|---|---|
| `watch_ads_v3.py` | 引擎本体：焦点组件探针、uiautomator/mobilecli 双后端、倒计时/发奖信号、force-stop 冷启循环、flag/锁/stats |
| `watch_ads_launcher.bat` | Windows 调度入口：设备自检 → user 0 → 检测分身安装后跑 user 128（参数化模板） |
| `run_force_uiauto.bat.example` | 强制 uiautomator 后端的模板（荣耀 WIN 上 auto 误退 mobilecli 时用） |
| `vivo_cli.py` | mobilecli 适配层。**仅 vivo/OriginOS 需要**（该 ROM 禁用了 uiautomator），荣耀 WIN 不用 |
| `test_v3_mock.py` | 离线 mock 测试（不连手机） |
| `EXPERIENCE.md` | 真机实战经验全集（建议先读） |

## 使用

```bash
# 零消耗探活（只解析 UI、不点广告）
py watch_ads_v3.py --serial <序列号> --selftest

# 主账号（user 0）跑满当天 21 轮
py watch_ads_v3.py --serial <序列号> --user 0

# 应用分身（荣耀为 user 128）
py watch_ads_v3.py --serial <序列号> --user 128

# 后端选择：auto（默认）/ uiauto（荣耀 WIN 固定用它）/ mobilecli（仅 vivo）
py watch_ads_v3.py --serial <序列号> --user 0 --backend uiauto
```

`watch_ads_launcher.bat [序列号] [分身user]` 是每日调度入口：

```bat
watch_ads_launcher.bat            :: 单设备时自动取 adb get-serialno
watch_ads_launcher.bat XXXXX 128  :: 显式指定
watch_ads_launcher.bat XXXXX none :: 只跑主号
```

任务计划程序建议两个任务：每天 08:00 一次主跑 + 每 30 分钟一次兜底。有完成标记（flag）或设备离线时脚本秒退，重复触发无副作用。

**注意**：不要从会向前台进程发 Ctrl+C 的交互式终端直接跑批处理（计划任务自然触发不受影响）。需要手动补跑时用分离进程：

```powershell
powershell -NoProfile -Command "Start-Process cmd -ArgumentList '/c','watch_ads_launcher.bat' -WorkingDirectory '<目录>' -WindowStyle Hidden"
```

## 完成判定（不要只信「跑完了」）

1. 当天 flag：`claimed_<serial>_u<user>_YYYY-MM-DD.flag`（内容是**本轮**条数，不是当天总数）；
2. `stats_<serial>_u<user>.jsonl` 末尾 `score=21`、`credited=true`；
3. `v3_run.log` 尾部出现 `app says done -> write flag, exit 0`；
4. 时长增量只认白名单 600/1200/1800s，21 条合计 21600s（6 小时）。

中途「NO CTA」也可能写出 credits<21 的假完成 flag：删掉重跑即可，App 自己的阶段计数是权威，续跑只会补没领的。详见 EXPERIENCE.md「已知坑」。

## 环境要点（荣耀 WIN / AAP-AN00）

- 所有 adb 调用带 `ADB_LIBUSB=1`（脚本与 bat 已内置）；枚举为空时在默认/LIBUSB 两个后端间都试一次 `adb kill-server && start-server`，别反复杀。
- USB 模式必须「传输文件(MTP)」；PnP 只剩复合设备+MTP、没有 ADB 子接口时，主机侧无法恢复，需要在手机上重插/重新确认 USB 调试。
- vivo/OriginOS 另需自备 `mobilecli-windows-amd64.exe`（dex+RPC，绕过被 ROM 禁用的 uiautomator），本仓库不含该第三方二进制；荣耀 WIN 用不到。

## 退出码

| code | 含义 |
|---|---|
| 0 | 当天领完（已写 flag） |
| 2 | 未完成 |
| 3 | 首轮就没有 CTA（真失败，看 batch 日志；荣耀上若日志显示 backend=mobilecli，改用 `--backend uiauto`） |
| 4 | 登录页（账号登录失效，需手动登录） |
| 9 | 设备离线 |

## 风险提示

自动化播放激励广告可能违反目标 App 用户协议，有封号风险。仅限本人设备学习研究。
