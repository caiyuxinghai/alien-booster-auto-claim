# 外星仔加速器领时长

在本人授权调试的 Android 手机上，通过外星仔加速器页面领取广告时长。程序重新读取页面阶段确认到账。再次开始时按实际进度继续。

## 手机端 0.14.1

打开「领取」顶部先选引擎模式，两者互斥，同一时间只能运行一个：

- **无障碍主屏**：仅用手机自带无障碍权限操作主屏主号，无需电脑或后台助手，不启用商店保护。锁屏、网络不满足或遇到应用商店、安装器时暂停。广告打开后发生外跳或手动切换 App，都可能在约 8 秒后被拉回外星仔，每条最多尝试两次。
- **完整助手**：通过本机 ADB 后台助手运行，支持主屏/虚拟屏、双号/全部账号、持续观看及商店安装保护。

在「完整助手」下先选运行位置和领取方式，再点「开始 / 继续」。要结束本次任务，点「暂停」。

| 模式 / 位置 | 领取方式 | 行为 |
| --- | --- | --- |
| 无障碍主屏 | 仅主号 | 免电脑在主屏领取主号，支持只试一条，广告外跳自动拉回 |
| 完整助手 · 后台或主屏 | 双号 | 主号和应用分身串行轮换 |
| 完整助手 · 后台或主屏 | 全部账号 | 扫描系统允许核对、已安装并启用外星仔的 Android 空间。各账号领满后结束 |
| 完整助手 · 后台或主屏 | 持续观看 | 按全部账号的范围领取。领满后释放虚拟屏、恢复商店，等待额度重新开放 |
| 完整助手 · 后台或主屏 | 仅主号 | 只选主号，后台在虚拟屏运行，支持只试一条 |

不可访问或不可用的空间会在界面提示。主屏模式需要目标页面留在前台，切出时会暂停。后台模式在单个虚拟屏轮换账号，领取页可看状态和进度。广告声音可能影响主屏媒体。保持手机解锁运行。

### 设置和记录

- 「允许使用移动数据」默认关闭。此时要求当前默认网络是可联网的 Wi-Fi。打开后也允许可联网的移动数据。运行中网络不满足条件会暂停，恢复网络后可点「开始 / 继续」。
- 「定时领取」默认关闭。打开后选择手机当地时间，每天到点按当前所选引擎尝试领取（完整助手尝试「后台 → 全部账号」，无障碍主屏需亮屏解锁并停留在桌面或领取页）。若条件不满足或任务忙，会稍后再试。实际触发时间受 Android 闹钟权限及系统调度影响。手动选项独立保存。
- 「记录」显示开始和结束时间、该轮耗时、已确认新增条数和结束原因。支持逐条「删除」和「清空」。后台记录需要助手连接；无障碍主屏记录保存在本机，助手离线也可查看和删除。

## 安装与启动

1. 从项目 Release 获取同版本 APK 和后台助手恢复包，也可按下文构建。覆盖安装要求签名一致。
2. 仅用**无障碍主屏**时，直接在「领取」页点「开启无障碍权限」并在系统设置打开「外星仔一键领时长」，回到 App 即可开始，无需连接电脑。
3. 使用**完整助手**时，在「设置 → 助手维护」点「手机配对」。在系统无线调试页面打开六位配对码，从配对通知输入。成功后回 App 点「启动助手」。Android 12 及以上支持此入口。
4. 「自动启动助手」默认关闭。助手离线时，领取页的「开始 / 继续」会提供「启动并继续」。开启自动启动后，手动开始和每日定时都会按需启动助手。更换网络或重启手机后，可能需要在系统设置重新打开无线调试。

助手在线、守护已启用并且任务已经收尾时，直接在手机点「设置 → 助手维护 → 重启助手」。也可用已授权的 ADB 连接 Windows 电脑，再从恢复包启动或重启助手。

手机配对与 ADB 传输基于 Mika v0.1.37 的开源代码。许可和归属随 APK 分发。 [Mika 源码](https://github.com/wangzan101/Mika/tree/v0.1.37/core/src/main/kotlin/com/wangzan101/mika/core/adb)

「设置 → 助手维护 → 一键恢复图标」按保护记录恢复被临时停用的商店，区分实际恢复和没有待恢复记录。领取任务运行或等待时，先暂停并完成收尾。重启或恢复期间，领取入口暂不可用。恢复后图标的位置由荣耀桌面决定。

完整助手使用本机 ADB 助手，无需 Shizuku 或无障碍授权；助手仅监听本机回环接口，任务指令经双向认证。启动脚本会检查机型和 APK 版本。

完整助手的所有模式都会在首条广告前启用当轮商店保护，结束后恢复已识别商店的原状态。荣耀桌面重新显示商店图标时可能改变位置。仅核对额度及持续模式的等待期间不启用商店保护。无障碍主屏不依赖后台助手，不会停用商店。

开始完整助手后台任务时，如果系统已有活跃的应用安装会话，程序会等待更新结束。等待期间可随时暂停。

## 在 Windows 构建

需要 JDK 17、Gradle 8.9、Android SDK Platform 35、Build-Tools 35.0.0 和 Platform-Tools。将 Gradle、JDK 和 `adb` 加入 PATH，并将 `ANDROID_HOME` 设为 SDK 目录。在仓库根目录运行以下命令。

```powershell
gradle -p mobile/android :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
if ($LASTEXITCODE -ne 0) { throw 'APK 测试、构建或 lint 失败' }

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
