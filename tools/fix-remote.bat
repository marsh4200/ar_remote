@echo off
setlocal EnableExtensions
REM ------------------------------------------------------------------
REM  AR Remote - put things right over USB when RosCard (the stock
REM  launcher) has come back. Switches it off again, makes AR Remote the
REM  home screen, turns off the lock screen and the clock screensaver.
REM  Writes fix-report.txt next to this file.
REM ------------------------------------------------------------------
set "HERE=%~dp0"
REM --- find adb, or download it (same as install-usb.bat) ---------------
set "ADB=adb"
where adb >nul 2>nul
if not errorlevel 1 goto :haveadb
set "ADB=%HERE%platform-tools\adb.exe"
if exist "%ADB%" goto :haveadb
echo adb not found - downloading Android platform-tools...
curl -fL -o "%HERE%platform-tools.zip" https://dl.google.com/android/repository/platform-tools-latest-windows.zip || goto :fail
tar -xf "%HERE%platform-tools.zip" -C "%HERE%" || goto :fail
del "%HERE%platform-tools.zip"
:haveadb
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
exit /b 0

:fail
echo.
echo Could not download adb. Check the internet connection, or copy the platform-tools folder next to this file.
pause
exit /b 1
