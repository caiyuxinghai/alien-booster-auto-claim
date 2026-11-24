@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1" %*
set "recovery_rc=%errorlevel%"
pause
exit /b %recovery_rc%
