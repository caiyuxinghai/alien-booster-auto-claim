param(
    [string]$Serial,
    [switch]$ReplaceIdleHelper
)

Set-StrictMode -Version 2
$ErrorActionPreference = 'Stop'
$pkg = 'com.alienbooster.claimer'
$remote = '/data/local/tmp/codex-dual'
$stageId = [Guid]::NewGuid().ToString('N')

function Find-Adb {
    $cmd = Get-Command adb.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($cmd) { return $cmd.Source }
    foreach ($folder in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk")) {
        if (-not $folder) { continue }
        $candidate = Join-Path $folder 'platform-tools\adb.exe'
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
    }
    throw '找不到 adb.exe，请先安装 Android SDK Platform-Tools，或将它加入 PATH。'
}

function Invoke-DeviceAdb([string[]]$Command) {
    $result = & $adb -s $Serial @Command
    if ($LASTEXITCODE -ne 0) { throw ('手机命令未完成：' + $Command[0] + '。请检查 USB 或无线调试连接。') }
    return $result
}

# Use a byte stream for the shell program to avoid cmd.exe quoting and PS 5.1 CRLF conversion.
# The app token travels only through an on-device pipe and is never returned to the PC.
function Invoke-DeviceShell([string]$Program) {
    $start = New-Object System.Diagnostics.ProcessStartInfo
    $start.FileName = $adb
    $start.Arguments = "-s $Serial shell -T /system/bin/sh -s"
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $start
    try {
        $null = $process.Start()
        $readOut = $process.StandardOutput.ReadToEndAsync()
        $readErr = $process.StandardError.ReadToEndAsync()
        $bytes = [Text.Encoding]::ASCII.GetBytes($Program.Replace("`r", '') + "`n")
        $process.StandardInput.BaseStream.Write($bytes, 0, $bytes.Length)
        $process.StandardInput.BaseStream.Flush()
        $process.StandardInput.Close()
        if (-not $process.WaitForExit(42000)) {
            $process.Kill()
            throw '手机响应超时，请重新检查连接。'
        }
        $outputText = $readOut.GetAwaiter().GetResult().Trim()
        $null = $readErr.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) {
            # Only expose our own fixed status names from the phone.
            $errorName = 'DEVICE_ERROR'
            if ($outputText -match '(?m)^FAIL_([A-Z_]+)\s*$') { $errorName = $Matches[1] }
            if ($errorName -in @('HELPER_ACTIVE','MARKET_ACTIVE')) {
                throw '任务或商店保护仍在运行。请先在手机点「暂停」，等收尾后再重启或更新。'
            }
            if ($errorName -eq 'HELPER_STATE_UNKNOWN') {
                throw '无法确认现有后台助手已停止领取。请检查手机状态后重试。'
            }
            if ($errorName -eq 'HELPER_REPLACED') {
                throw '另一处正在重新配置后台助手。请回到手机领取页查看状态。'
            }
            throw ("配置失败（$errorName）。请保持手机解锁，打开「外星仔领时长」后重试。")
        }
        return $outputText
    } finally { $process.Dispose() }
}

function Get-Devices {
    $rows = & $adb devices
    if ($LASTEXITCODE -ne 0) { throw '无法读取 ADB 设备列表。' }
    foreach ($row in $rows) {
        if ($row -match '^([^\s]+)\s+device\s*$') { $Matches[1] }
    }
}

try {
    $adb = Find-Adb
    $devices = @(Get-Devices)
    if (-not $Serial) {
        if ($devices.Count -eq 1) { $Serial = $devices[0] }
        elseif ($devices.Count -gt 1) {
            Write-Host ('已连接设备：' + ($devices -join '，'))
            $Serial = Read-Host '请输入荣耀手机对应的设备地址或序列号'
        } else {
            $Serial = Read-Host '请输入手机「无线调试」页面的 IP 地址和端口（或插好 USB 后直接回车）'
            if (-not $Serial) {
                $devices = @(Get-Devices)
                if ($devices.Count -ne 1) { throw '请只连接一台已授权调试的荣耀手机后重试。' }
                $Serial = $devices[0]
            }
        }
    }
    $Serial = $Serial.Trim()
    if ($Serial -notmatch '^[a-zA-Z0-9][a-zA-Z0-9_.:-]{0,150}$') { throw '设备地址或序列号格式不正确。' }
    if ($devices -notcontains $Serial -and $Serial -match '^\d{1,3}(?:\.\d{1,3}){3}:\d{1,5}$') {
        $null = & $adb connect $Serial
        if ($LASTEXITCODE -ne 0) { throw '无线调试连接失败。请核对手机当前 IP 和端口。' }
    }
    $state = (Invoke-DeviceAdb @('get-state') | Out-String).Trim()
    if ($state -ne 'device') { throw '手机尚未允许本电脑调试。' }
    $model = (Invoke-DeviceAdb @('shell','getprop','ro.product.model') | Out-String).Trim()
    if ($model -notmatch '^AAP[-_]AN00$') { throw ('连接设备型号为 ' + $model + '。这个恢复包只用于荣耀 WIN Y。') }
    $package = Invoke-DeviceAdb @('shell','dumpsys','package',$pkg)
    if (-not ($package | Where-Object { $_ -match '^\s*versionName=0\.12\.3\s*$' })) {
        throw '手机需要先安装「外星仔领时长」0.12.3。'
    }
    foreach ($name in @('dual-helper.jar','market_lease.sh','supervise.sh')) {
        if (-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot $name) -PathType Leaf)) {
            throw ('恢复包缺少 ' + $name + '，请先完整解压。')
        }
    }
    $helperDigest = [Security.Cryptography.SHA256]::Create()
    try {
        $helperStream = [IO.File]::OpenRead((Join-Path $PSScriptRoot 'dual-helper.jar'))
        try {
            $helperHash = [BitConverter]::ToString($helperDigest.ComputeHash($helperStream)).Replace('-', '').ToLowerInvariant()
        } finally {
            $helperStream.Dispose()
        }
    } finally {
        $helperDigest.Dispose()
    }
    $replace = if ($ReplaceIdleHelper) { '1' } else { '0' }
    $initialScript = @'
set -eu
umask 077
d=/data/local/tmp/codex-dual
if [ -L "$d" ] || { [ -e "$d" ] && [ ! -d "$d" ]; }; then echo FAIL_PRIVATE_DIR; exit 1; fi
mkdir -p "$d"
[ "$(stat -c %u "$d")" = 2000 ] || { echo FAIL_PRIVATE_DIR; exit 1; }
chmod 700 "$d"
ps -A -o USER,PID,ARGS | grep -qE '^[[:space:]]*shell[[:space:]]+[0-9]+[[:space:]]+([^ ]*/)?app_process(32|64)?[[:space:]]+/[[:space:]]+DualDaemon[[:space:]]*$' && {
  if [ REPLACEIDLEHELPER -eq 0 ] &&
     grep -q '^DUAL_DAEMON_READY$' "$d/daemon.log" 2>/dev/null &&
     [ "$(sha256sum "$d/dual-helper.jar" 2>/dev/null | cut -d ' ' -f 1)" = 'HELPERHASH' ]; then
    echo ALREADY_RUNNING
    exit 0
  fi
}
echo NEED_START
'@
    $initial = Invoke-DeviceShell ($initialScript.Replace('REPLACEIDLEHELPER', $replace).Replace('HELPERHASH', $helperHash))
    if ($initial -eq 'ALREADY_RUNNING') {
        Write-Host '后台助手已运行。打开手机「外星仔领时长」，选择「后台」「双号」，再点「开始 / 继续」。'
        exit 0
    }
    if ($initial -ne 'NEED_START') { throw '无法确认手机后台助手状态。' }
    Write-Host '正在向这台荣耀手机配置后台助手…'
    $null = Invoke-DeviceAdb @('shell','am','start','--user','0','-n',"$pkg/.MainActivity")
    try {
        $null = Invoke-DeviceAdb @('push',(Join-Path $PSScriptRoot 'dual-helper.jar'),"$remote/helper-$stageId.new")
        $null = Invoke-DeviceAdb @('push',(Join-Path $PSScriptRoot 'market_lease.sh'),"$remote/lease-$stageId.new")
        $null = Invoke-DeviceAdb @('push',(Join-Path $PSScriptRoot 'supervise.sh'),"$remote/supervisor-$stageId.new")
        $setupScript = @'
set -eu
umask 077
d=/data/local/tmp/codex-dual
cd "$d"
[ "$(stat -c '%a %u' "$d")" = '700 2000' ] || { echo FAIL_PRIVATE_DIR; exit 1; }
exec 9>>setup.lock
toybox flock -n 0 <&9 || { echo FAIL_BUSY; exit 1; }
helper_pids() (
  set -o pipefail
  ps -A -o USER,PID,ARGS | awk '$1 == "shell" && $2 ~ /^[0-9]+$/ &&
    $3 ~ /(^|\/)app_process(32|64)?$/ && $4 == "/" && $5 == "DualDaemon" && NF == 5 {print $2}'
)
alive() {
  current=$(helper_pids) || return 1
  [ -n "$current" ]
}
replace_idle() {
  # Exclude a running market guard and any interrupted session awaiting recovery.
  exec 8>>market.lock
  toybox flock -n 0 <&8 || { echo FAIL_MARKET_ACTIVE; exit 1; }
  for marker in session-*.market-ready session-*.market-journal session-*.market-journal.tmp; do
    if [ -e "$marker" ] || [ -L "$marker" ]; then echo FAIL_MARKET_ACTIVE; exit 1; fi
  done
  old=$(helper_pids) || { echo FAIL_HELPER_STATE_UNKNOWN; exit 1; }
  if [ -z "$old" ]; then exec 8>&-; return; fi
  case "$old" in *[!0-9]*) echo FAIL_HELPER_STATE_UNKNOWN; exit 1;; esac
  if ! grep -q '^DUAL_DAEMON_READY$' daemon.log; then echo FAIL_HELPER_STATE_UNKNOWN; exit 1; fi
  last=$(awk '$1 == "DUAL_STATUS" { found = 1; state = $2 } END {
    if (found) { if (state == "") exit 1; print state }
  }' daemon.log) || { echo FAIL_HELPER_STATE_UNKNOWN; exit 1; }
  case "$last" in
    RUNNING|WAITING|RECOVERING|RESTARTING) echo FAIL_HELPER_ACTIVE; exit 1;;
    ''|IDLE|DONE|STOPPED|ERROR|RECOVERED|RECOVERY_FAILED) ;;
    *) echo FAIL_HELPER_STATE_UNKNOWN; exit 1;;
  esac
  [ "$(helper_pids)" = "$old" ] || { echo FAIL_HELPER_STATE_UNKNOWN; exit 1; }
  # Invalidate the old generation while setup.lock is held so its supervisor cannot restart it.
  echo invalid-STAGEID >generation.tmp && mv -f generation.tmp generation
  kill -TERM "$old" || { echo FAIL_HELPER_STATE_UNKNOWN; exit 1; }
  i=0
  while kill -0 "$old" 2>/dev/null; do
    i=$((i+1))
    if [ "$i" -ge 20 ]; then echo FAIL_HELPER_STATE_UNKNOWN; exit 1; fi
    sleep 0.2
  done
  current=$(helper_pids) || { echo FAIL_HELPER_STATE_UNKNOWN; exit 1; }
  [ -z "$current" ] || { echo FAIL_HELPER_STATE_UNKNOWN; exit 1; }
  exec 8>&-
}
if alive && [ REPLACEIDLEHELPER -eq 0 ] &&
   grep -q '^DUAL_DAEMON_READY$' daemon.log 2>/dev/null &&
   [ "$(sha256sum dual-helper.jar 2>/dev/null | cut -d ' ' -f 1)" = 'HELPERHASH' ]; then
  echo ALREADY_RUNNING
  exit 0
fi
t=token-STAGEID.new
trap 'rm -f "$t"' EXIT
i=0
while :; do
  if run-as com.alienbooster.claimer cat files/dual.token 2>/dev/null | cat > "$t" &&
     [ "$(wc -c < "$t" | tr -d ' ')" = 64 ] && grep -Eq '^[0-9a-f]{64}$' "$t"; then
    break
  fi
  i=$((i+1))
  if [ "$i" -ge 16 ]; then echo FAIL_APP_AUTH; exit 1; fi
  sleep 0.4
done
chmod 600 "$t" lease-STAGEID.new
chmod 400 helper-STAGEID.new
if alive; then replace_idle; fi
mv -f helper-STAGEID.new dual-helper.jar
mv -f lease-STAGEID.new market_lease.sh
chmod 600 supervisor-STAGEID.new
mv -f supervisor-STAGEID.new supervise.sh
mv -f "$t" token
echo STAGEID >generation.tmp && mv -f generation.tmp generation
if [ -s daemon.log ]; then mv -f daemon.log daemon.previous.log; fi
setsid /system/bin/sh "$d/supervise.sh" STAGEID 9>&- </dev/null >"$d/supervisor.log" 2>&1 &
exec 9>&-
i=0
while [ "$i" -lt 60 ]; do
  if [ "$(cat generation 2>/dev/null)" != STAGEID ]; then echo FAIL_HELPER_REPLACED; exit 1; fi
  if grep -q '^DUAL_DAEMON_READY$' daemon.log && alive; then echo READY; exit 0; fi
  i=$((i+1))
  sleep 0.4
done
echo FAIL_START
exit 1
'@
        $outcome = Invoke-DeviceShell ($setupScript.Replace('STAGEID', $stageId).Replace('REPLACEIDLEHELPER', $replace).Replace('HELPERHASH', $helperHash))
        if ($outcome -notin @('READY','ALREADY_RUNNING')) { throw '后台助手启动结果未确认。' }
        Write-Host '后台助手已就绪。可以断开电脑，在手机「外星仔领时长」选择「后台」「双号」，再点「开始 / 继续」。'
    } finally {
        # Clean only this run's randomly named staging files. No user files are touched.
        $null = & $adb -s $Serial shell rm -f "$remote/helper-$stageId.new" "$remote/lease-$stageId.new" "$remote/supervisor-$stageId.new" "$remote/token-$stageId.new"
    }
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit 1
}
