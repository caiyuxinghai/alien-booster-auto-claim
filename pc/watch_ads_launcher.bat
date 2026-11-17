@echo off
REM ==========================================================================
REM watch_ads_launcher.bat - daily runner for watch_ads_v3.py (Windows)
REM
REM Usage:
REM   watch_ads_launcher.bat [DEVICE_SERIAL] [CLONE_USER_ID]
REM
REM   DEVICE_SERIAL  adb serial of your phone. Defaults to the SERIAL value
REM                  below, or the single connected device from adb get-serialno.
REM   CLONE_USER_ID  Android user id of the cloned/twin app (Honor twin apps
REM                  use 128). Pass "none" to skip the clone account.
REM
REM Schedule this .bat with Task Scheduler: one daily run + one retry every
REM 30 minutes. Runs with flags are no-ops, and an offline phone exits 9.
REM ==========================================================================
setlocal enableextensions
set ADB_LIBUSB=1
set SCRIPT=%~dp0watch_ads_v3.py
set LOG=%~dp0v3_run.log

REM === CONFIG: edit your device serial here, or pass it as argument 1 ===
set SERIAL=%~1
if "%SERIAL%"=="" set SERIAL=YOUR_DEVICE_SERIAL
if "%SERIAL%"=="YOUR_DEVICE_SERIAL" (
  for /f "delims=" %%s in ('adb get-serialno 2^>nul') do set SERIAL=%%s
)
REM === Honor twin-app user id (set to "none" to skip the clone account) ===
set CLONE_USER=%~2
if "%CLONE_USER%"=="" set CLONE_USER=128

echo ===== %DATE% %TIME% =====>>"%LOG%"
where adb >nul 2>nul
if errorlevel 1 (
  echo ADB NOT IN PATH - install platform-tools>>"%LOG%"
  echo EXIT 9 %TIME%>>"%LOG%"
  endlocal
  exit /b 9
)
set STATE=
for /f "delims=" %%s in ('adb -s %SERIAL% get-state 2^>nul') do set STATE=%%s
if not "%STATE%"=="device" (
  echo DEVICE OFFLINE - plug the phone in and enable USB debugging>>"%LOG%"
  echo EXIT 9 %TIME%>>"%LOG%"
  endlocal
  exit /b 9
)
REM main account (user 0)
py -u "%SCRIPT%" --serial %SERIAL% --user 0 >>"%LOG%" 2>&1
echo MAIN EXIT %ERRORLEVEL% %TIME%>>"%LOG%"
if /i "%CLONE_USER%"=="none" goto done

REM clone account, only if the booster is installed for that user
set HASCLONE=
for /f "delims=" %%p in ('adb -s %SERIAL% shell pm list packages --user %CLONE_USER% com.etalien.booster 2^>nul') do set HASCLONE=1
if defined HASCLONE (
  echo --- clone user %CLONE_USER% start --->>"%LOG%"
  py -u "%SCRIPT%" --serial %SERIAL% --user %CLONE_USER% >>"%LOG%" 2>&1
  echo CLONE EXIT %ERRORLEVEL% %TIME%>>"%LOG%"
) else (
  echo no clone booster installed, skip>>"%LOG%"
)
:done
echo EXIT 0 %TIME%>>"%LOG%"
endlocal
