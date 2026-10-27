@echo off
setlocal EnableExtensions
REM ------------------------------------------------------------------
REM  AR Remote - put things right over USB when RosCard (the stock
REM  launcher) has come back. Switches it off again, makes AR Remote the
REM  home screen, turns off the lock screen and the clock screensaver.
REM  Writes fix-report.txt next to this file.
REM ------------------------------------------------------------------
set "HERE=%~dp0"
set "ADB=adb"
where adb >nul 2>nul || set "ADB=%HERE%platform-tools\adb.exe"
if not "%ADB%"=="adb" if not exist "%ADB%" (
  echo adb not found - run install-usb.bat once first ^(it downloads it^).
  pause
  exit /b 1
)
echo Waiting for the remote on USB (accept the prompt on its screen)...
"%ADB%" wait-for-usb-device
"%ADB%" -d push "%HERE%arfix.sh" /data/local/tmp/arfix.sh >nul
"%ADB%" -d shell "rm -f /data/local/tmp/ar_disabled.txt /data/local/tmp/ar_hidden.txt; tr -d '\r' < /data/local/tmp/arfix.sh > /data/local/tmp/arfix2.sh; sh /data/local/tmp/arfix2.sh" > "%HERE%fix-report.txt"
type "%HERE%fix-report.txt"
"%ADB%" -d shell "cat /data/local/tmp/ar_disabled.txt 2>/dev/null" >> "%HERE%disabled-apps.txt"
"%ADB%" -d shell "cat /data/local/tmp/ar_hidden.txt 2>/dev/null" >> "%HERE%hidden-apps.txt"
echo.
echo Done. The report is in fix-report.txt - send it over if anything still looks wrong.
pause
