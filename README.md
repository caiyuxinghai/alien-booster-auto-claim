# alien-booster-auto-claim

外星仔加速器（`com.etalien.booster`）「看广告领时长」每日任务的自动化方案，包含两套经过真机长期验证的实现：

- **`pc/`** — Windows 电脑 + ADB 的 Python 引擎（主力方案，2026-10 起每日实跑）。手机用数据线连着电脑，计划任务自动完成两个账号各 21 条激励广告（9×20min + 3×30min + 9×10min = 各 +6 小时可暂停时长/天）。
- **`android/`** — 手机本机 App（Kotlin + Shizuku，v0.8.0）。Shizuku 授权后在手机上独立完成同样的任务，是 PC 方案的离线移植，含完整设计文档（`android/SPEC.md`）。

实测主机型：**荣耀 WIN（型号 AAP-AN00，MagicOS）**，另在 vivo iQOO Z5x（OriginOS）上验证过设备抽象层。荣耀「应用分身」对应独立 Android 用户（主号 user 0、分身 user 128），双账号串行执行。

## 目录结构

```
pc/
  watch_ads_v3.py          # 引擎本体（Python 标准库，无第三方依赖）
  watch_ads_launcher.bat   # Windows 每日调度入口（参数化模板）
  run_force_uiauto.bat.example  # 强制 uiautomator 后端的模板
  vivo_cli.py              # mobilecli 后端适配（仅 vivo/OriginOS 需要）
  test_v3_mock.py          # 离线 mock 测试
  EXPERIENCE.md            # 两周真机实战经验全集（SDK 形态/信号/击杀窗口/坑）
android/
  app/                     # claimer v0.8.0，Kotlin + Shizuku，无 INTERNET 权限
  SPEC.md                  # 手机版完整设计规格（安全不变式/状态机/接口）
  README.md                # 手机版构建与使用说明
  deploy.sh.example        # 构建+安装+自检脚本模板
```

## 快速开始（PC 方案）

1. 安装 Android platform-tools，确保 `adb` 在 PATH；手机开启「USB 调试」，USB 模式选「传输文件(MTP)」。
2. `adb devices` 确认能枚举到设备。荣耀 WIN 上若枚举为空，尝试 `adb kill-server` 后用 `set ADB_LIBUSB=1 && adb start-server`（两种 USB 后端随机生效，见 `pc/EXPERIENCE.md`）。
3. 先零消耗探活：
   ```
   py watch_ads_v3.py --serial <序列号> --selftest
   ```
4. 单账号试跑一轮确认链路：
   ```
   py watch_ads_v3.py --serial <序列号> --user 0
   ```
5. 编辑 `watch_ads_launcher.bat` 填入序列号，用 Windows 任务计划程序挂「每天 08:00 主跑 + 每 30 分钟兜底」。有完成标记或设备离线时会秒退。

详细机制、广告 SDK 形态、奖励安全不变式与故障处置见 **[pc/EXPERIENCE.md](pc/EXPERIENCE.md)** 与 [pc/README.md](pc/README.md)。

## 快速开始（手机方案）

见 [android/README.md](android/README.md)。需要 Shizuku 运行并授权；支持设定每日启动时间、双账号串行，无电脑值守。

## 设计原则（两套实现共同遵守）

1. **只做正常 UI 操作**：点击 CTA、等待、返回、`am force-stop` 重开。不调用目标 App 私有 API、不改包、不伪造奖励回调、不用 root；手机版不申请 INTERNET 权限。
2. **奖励安全线不可压缩**：实测发奖点在 CTA 点击后约 18–20s（视频中段），绝不在发奖前退出广告；发奖后 force-stop + 冷启动，以服务端阶段计数与时长增量白名单（600/1200/1800s）验收。
3. **权威信号是焦点组件名**（`dumpsys window` 的 mCurrentFocus），不是屏幕文本——激励视频 Surface 没有可靠文本。

## 风险提示

自动化播放激励广告可能违反外星仔加速器的用户协议，存在封号、追回时长等风险。本项目仅供个人设备上的学习与研究使用，请自行评估后果；不要用于出售代领、批量账号或任何营利场景。

## 许可

[MIT](LICENSE)。
