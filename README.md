# 外星仔加速器领时长

在本人授权调试的 Android 手机上，通过外星仔加速器页面领取广告时长。程序重新读取页面阶段确认到账。再次开始时按实际进度继续。

## 手机端 0.12.0

打开「领取」，先选运行位置和领取方式，再点「开始 / 继续」。要结束本次任务，点「暂停」。

| 运行位置 | 领取方式 | 行为 |
| --- | --- | --- |
| 后台或主屏 | 双号 | 主号和应用分身串行轮换 |
| 后台或主屏 | 全部账号 | 扫描系统允许核对、已安装并启用外星仔的 Android 空间。各账号领满后结束 |
| 后台或主屏 | 持续观看 | 按全部账号的范围领取。领满后释放虚拟屏、恢复商店，等待额度重新开放 |
| 主屏 | 仅主号 | 只选主号，同样启用安装保护，支持只试一条 |

不可访问或不可用的空间会在界面提示。主屏模式需要目标页面留在前台，切出时会暂停。后台模式在单个虚拟屏轮换账号，领取页可看状态和进度。广告声音可能影响主屏媒体。保持手机解锁运行。

### 设置和记录

- 「允许使用移动数据」默认关闭。此时要求当前默认网络是可联网的 Wi-Fi。打开后也允许可联网的移动数据。运行中网络不满足条件会暂停，恢复网络后可点「开始 / 继续」。
- 「定时领取」默认关闭。打开后选择手机当地时间，每天到点尝试「后台 → 全部账号」。若锁屏、网络不符合设置、助手未连接或任务忙，会稍后再试。实际触发时间受 Android 闹钟权限及系统调度影响。手动选项独立保存。
- 「记录」显示开始和结束时间、该轮耗时、已确认新增条数和结束原因。支持逐条「删除」和「清空」。后台记录需要助手连接。删除记录只影响本机列表。

## 安装与启动

1. 从项目 Release 获取同版本 APK 和后台助手恢复包，也可按下文构建。覆盖安装要求签名一致。
2. 所有领取模式都需要配套助手。手机解锁并打开领取助手，通过已授权的 USB 或无线 ADB 连接 Windows 电脑。
3. 完整解压恢复包，双击「启动后台助手.bat」。就绪后可以断开电脑。首次使用、手机重启或助手长时间未连接时，按本步骤启动。更新旧助手前，先暂停任务并等待收尾，再双击启动脚本。

新版使用本机 ADB 助手，日常领取无需 Shizuku 或无障碍授权。助手仅监听本机回环接口，任务指令经双向认证。启动脚本会检查机型和 APK 版本。

所有模式都会在首条广告前启用当轮商店保护，结束后恢复已识别商店的原状态。荣耀桌面重新显示商店图标时可能改变位置。仅核对额度及持续模式的等待期间不启用商店保护。

开始后台任务时，如果系统已有活跃的应用安装会话，程序会等待更新结束。等待期间可随时暂停。

## 在 Windows 构建

需要 JDK 17、Gradle 8.9、Android SDK Platform 35、Build-Tools 35.0.0 和 Platform-Tools。将 Gradle、JDK 和 `adb` 加入 PATH，并将 `ANDROID_HOME` 设为 SDK 目录。在仓库根目录运行以下命令。

```powershell
gradle -p mobile/android :app:assembleDebug :app:lintDebug
if ($LASTEXITCODE -ne 0) { throw 'APK 构建或 lint 失败' }

$sdk = $env:ANDROID_HOME
$androidJar = Join-Path $sdk 'platforms/android-35/android.jar'
$d8 = Join-Path $sdk 'build-tools/35.0.0/d8.bat'
$classesDir = Join-Path ([IO.Path]::GetTempPath()) ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $classesDir | Out-Null
$sources = Get-ChildItem mobile/phonehelper -Filter '*.java' -File
javac --release 8 -encoding UTF-8 -cp $androidJar -d $classesDir $sources.FullName
if ($LASTEXITCODE -ne 0) { throw '后台助手编译失败' }
$classes = Get-ChildItem $classesDir -Filter '*.class' -File
& $d8 --min-api 34 --lib $androidJar --output mobile/recover/dual-helper.jar $classes.FullName
if ($LASTEXITCODE -ne 0) { throw '后台助手 DEX 构建失败' }
Copy-Item mobile/phonehelper/market_lease.sh,mobile/phonehelper/supervise.sh mobile/recover/
```

APK 在 `mobile/android/app/build/outputs/apk/debug/app-debug.apk`。后台助手的 JAR、商店保护脚本和守护脚本与 `mobile/recover/` 下的启动脚本一起使用。恢复脚本通过 Android 的 `run-as` 初始化本机认证，因此使用可调试构建。

## 兼容性

**荣耀 WIN Y（AAP-AN00，Android 17）**为手机端实测基线。其他 ROM 仍需验证分身、虚拟显示、窗口读取和商店保护。设计、实测与限制见 [手机端经验与验证记录](mobile/NOTES.md)。

仓库还保留 [电脑端 ADB/Python](pc/README.md) 和 [旧版 Android/Shizuku](android/README.md)。

## 许可

[MIT](LICENSE)。
