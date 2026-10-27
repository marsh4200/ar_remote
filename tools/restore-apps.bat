@echo off
setlocal EnableExtensions
REM Switches back on every app install-usb.bat disabled (listed in disabled-apps.txt).
set "HERE=%~dp0"
set "ADB=adb"
where adb >nul 2>nul || set "ADB=%HERE%platform-tools\adb.exe"
if not exist "%HERE%disabled-apps.txt" (
  echo disabled-apps.txt not found next to this file - nothing to restore.
  pause
  exit /b 1
)
echo Waiting for the remote on USB...
"%ADB%" wait-for-usb-device
if exist "%HERE%hidden-apps.txt" (
  "%ADB%" -d push "%HERE%hidden-apps.txt" /data/local/tmp/hidden-apps.txt >nul
  "%ADB%" -d shell "for p in $(tr -d '\r' < /data/local/tmp/hidden-apps.txt); do cmd package install-existing $p >/dev/null 2>&1 && echo brought back $p; done; rm /data/local/tmp/hidden-apps.txt"
)
"%ADB%" -d push "%HERE%disabled-apps.txt" /data/local/tmp/disabled-apps.txt >nul
"%ADB%" -d shell "for p in $(tr -d '\r' < /data/local/tmp/disabled-apps.txt); do pm enable $p >/dev/null 2>&1 && echo re-enabled $p; done; rm /data/local/tmp/disabled-apps.txt"
echo.
echo Done. To make the remote's original launcher the home screen again, uninstall
echo AR Remote or pick the launcher when Android asks after pressing Home.
pause
