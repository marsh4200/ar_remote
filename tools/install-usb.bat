@echo off
setlocal EnableExtensions
REM ------------------------------------------------------------------
REM  AR Remote - first install over USB (Astrion HA100)
REM  1. Turn on USB debugging on the remote, plug it into this PC.
REM  2. Double-click this file. Accept "Allow USB debugging" on the remote.
REM  Put ar-remote.apk next to this file to install a local build,
REM  otherwise the latest GitHub release is downloaded.
REM ------------------------------------------------------------------
set "HERE=%~dp0"
set "PKG=com.arsmarthome.remote"
set "APK=%HERE%ar-remote.apk"
set "URL=https://github.com/marsh4200/ar_remote/releases/latest/download/ar-remote.apk"

REM --- find adb ---------------------------------------------------------
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

REM --- get the apk -----------------------------------------------------
if exist "%APK%" goto :checkapk
echo Downloading latest AR Remote app...
curl -fL -o "%APK%" "%URL%" || goto :nodl
:checkapk
for %%A in ("%APK%") do set "SIZE=%%~zA"
if %SIZE% LSS 100000 (
  echo ar-remote.apk is only %SIZE% bytes - not a real app, deleting it.
  del "%APK%"
  goto :nodl
)

echo.
echo Waiting for the remote on USB (accept the prompt on its screen)...
"%ADB%" wait-for-usb-device || goto :fail

echo Installing...
"%ADB%" -d install -r -g "%APK%" > "%HERE%install.log" 2>&1
type "%HERE%install.log"
findstr /c:"Success" "%HERE%install.log" >nul && goto :installed
findstr /c:"VERSION_DOWNGRADE" "%HERE%install.log" >nul || goto :fail
echo The app on the remote is newer than this file - removing it, then installing this one...
"%ADB%" -d uninstall %PKG%
"%ADB%" -d install -g "%APK%" || goto :fail
:installed

echo Allowing the app to install its own updates...
"%ADB%" -d shell appops set %PKG% REQUEST_INSTALL_PACKAGES allow

echo Switching off the remote's other apps (RosCard, old dashboards, launchers)...
"%ADB%" -d shell "for p in $(pm list packages -3) $(pm list packages | grep -i -e launcher -e roscard -e astrion -e sanytron); do p=${p#package:}; case $p in com.arsmarthome.remote|*inputmethod*|*keyboard*) ;; *) am force-stop $p; pm disable-user --user 0 $p >/dev/null 2>&1 && echo $p ;; esac; done" > "%HERE%disabled-apps.txt"
echo   Disabled:
type "%HERE%disabled-apps.txt"
echo   (run restore-apps.bat to switch them back on)

echo Making AR Remote the home screen...
"%ADB%" -d shell cmd package set-home-activity %PKG%/.MainActivity
echo Letting pick-up detection run while the remote sleeps...
"%ADB%" -d shell dumpsys deviceidle whitelist +%PKG%
"%ADB%" -d shell am start -n %PKG%/.MainActivity

echo.
echo Remote IP address (enter this in Home Assistant):
"%ADB%" -d shell "ip -f inet addr show wlan0 | grep inet"

echo.
echo Optional: trying to open network ADB until the next reboot...
"%ADB%" -d tcpip 5555 >nul 2>nul && echo   network ADB on port 5555, lost on reboot || echo   not supported on this firmware - that is fine.

echo.
echo Done. Add the remote in Home Assistant (AR Remote integration).
echo Future updates: press "Install / update app" in HA and tap Install on the remote.
pause
exit /b 0

:nodl
echo.
echo Could not get the app. On GitHub the latest release must have
echo ar-remote.apk attached - the Build under Actions needs a green tick.
pause
exit /b 1

:fail
echo.
echo Something went wrong - check the cable, USB debugging, and the prompt on the remote.
pause
exit /b 1
