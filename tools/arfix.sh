# Runs ON the remote (pushed by fix-remote.sh / fix-remote.bat). Plain Android sh.
PKG=com.arsmarthome.remote
home() { cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN 2>/dev/null | tail -n 1; }
stock() { ps -A 2>/dev/null | grep -i -E 'roscard|astrion|sanytron|launcher|aiks|haremote' | grep -v arsmarthome | sed 's/.* //'; }

echo "=== Before ==="
echo "Home screen app : $(home)"
echo "Stock apps running:"; stock | sed 's/^/  /'
echo "Lock screen off : $(locksettings get-disabled 2>/dev/null)"
echo "Screensaver     : $(settings get secure screensaver_enabled)"

echo
echo "=== Switching off RosCard and the other stock apps ==="
done=""
# Every other app that can be the home screen (whatever it's called, e.g.
# com.aiks.HaRemote = RosCard), every downloaded app, and anything with a
# stock-launcher name.
homes=$(cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null | grep '/' | sed 's/ //g' | cut -d/ -f1)
for p in $homes $(pm list packages -e -3) $(pm list packages -e | grep -i -E 'launcher|roscard|astrion|sanytron|aiks|haremote'); do
  p=${p#package:}
  case $p in $PKG|com.android.settings|android|*inputmethod*|*keyboard*) continue ;; esac
  case " $done " in *" $p "*) continue ;; esac
  done="$done $p"
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
# Settings panel: Wi-Fi list needs location, screen timeout needs WRITE_SETTINGS.
pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION >/dev/null 2>&1
pm grant "$PKG" android.permission.ACCESS_COARSE_LOCATION >/dev/null 2>&1
appops set "$PKG" WRITE_SETTINGS allow >/dev/null 2>&1
settings put secure location_mode 3 >/dev/null 2>&1
cmd location set-location-enabled true >/dev/null 2>&1
echo "  settings panel allowed (Wi-Fi list, screen timeout)"
am start -n "$PKG/.MainActivity" >/dev/null 2>&1
sleep 2

echo
echo "=== After ==="
echo "Home screen app : $(home)"
echo "On screen now   : $(dumpsys activity activities 2>/dev/null | grep -m1 -E 'mResumedActivity|topResumedActivity' | sed 's/^ *//')"
echo "Stock apps running:"; stock | sed 's/^/  /'
echo "Lock screen off : $(locksettings get-disabled 2>/dev/null)"
echo "Android version : $(getprop ro.build.version.release)"
