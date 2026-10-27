# Runs ON the remote (pushed by fix-remote.sh / fix-remote.bat). Plain Android sh.
PKG=com.arsmarthome.remote
home() { cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN 2>/dev/null | tail -n 1; }
stock() { ps -A 2>/dev/null | grep -i -E 'roscard|astrion|sanytron|launcher' | grep -v arsmarthome | awk '{print $NF}'; }

echo "=== Before ==="
echo "Home screen app : $(home)"
echo "Stock apps running:"; stock | sed 's/^/  /'
echo "Lock screen off : $(locksettings get-disabled 2>/dev/null)"
echo "Screensaver     : $(settings get secure screensaver_enabled)"

echo
echo "=== Switching off RosCard and the other stock apps ==="
for p in $(pm list packages -e -3) $(pm list packages -e | grep -i -E 'launcher|roscard|astrion|sanytron'); do
  p=${p#package:}
  case $p in $PKG|*inputmethod*|*keyboard*) continue ;; esac
  am force-stop "$p"
  if pm disable-user --user 0 "$p" >/dev/null 2>&1; then
    echo "  switched off  $p"; echo "$p" >> /data/local/tmp/ar_disabled.txt
  elif pm uninstall -k --user 0 "$p" >/dev/null 2>&1; then
    echo "  hidden        $p   (firmware would not switch it off)"; echo "$p" >> /data/local/tmp/ar_hidden.txt
  else
    echo "  COULD NOT stop $p"
  fi
done

echo
echo "=== Home screen, lock screen, screensaver ==="
cmd package set-home-activity "$PKG/.MainActivity" >/dev/null 2>&1 && echo "  AR Remote is the home screen"
locksettings set-disabled true >/dev/null 2>&1 && echo "  lock screen off (no swipe to unlock)"
settings put secure screensaver_enabled 0
settings put secure screensaver_activate_on_dock 0
settings put secure screensaver_activate_on_sleep 0
echo "  screensaver / clock off"
dumpsys deviceidle whitelist +"$PKG" >/dev/null 2>&1
appops set "$PKG" SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1
appops set "$PKG" REQUEST_INSTALL_PACKAGES allow >/dev/null 2>&1
am start -n "$PKG/.MainActivity" >/dev/null 2>&1
sleep 2

echo
echo "=== After ==="
echo "Home screen app : $(home)"
echo "On screen now   : $(dumpsys activity activities 2>/dev/null | grep -m1 -E 'mResumedActivity|topResumedActivity' | sed 's/^ *//')"
echo "Stock apps running:"; stock | sed 's/^/  /'
echo "Lock screen off : $(locksettings get-disabled 2>/dev/null)"
echo "Android version : $(getprop ro.build.version.release)"
