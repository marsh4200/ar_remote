#!/usr/bin/env bash
# AR Remote - first install over USB (Astrion HA100), macOS / Linux.
# Turn on USB debugging on the remote, plug it in, run: ./install-usb.sh
# Put ar-remote.apk next to this script to install a local build.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
PKG="com.arsmarthome.remote"
APK="$HERE/ar-remote.apk"
URL="https://github.com/marsh4200/ar_remote/releases/latest/download/ar-remote.apk"

ADB="$(command -v adb || true)"
if [ -z "$ADB" ]; then
  if [ ! -x "$HERE/platform-tools/adb" ]; then
    case "$(uname -s)" in
      Darwin) OS=darwin ;;
      *) OS=linux ;;
    esac
    echo "adb not found - downloading Android platform-tools..."
    curl -L -o "$HERE/platform-tools.zip" "https://dl.google.com/android/repository/platform-tools-latest-$OS.zip"
    unzip -q -o "$HERE/platform-tools.zip" -d "$HERE"
    rm "$HERE/platform-tools.zip"
  fi
  ADB="$HERE/platform-tools/adb"
fi

if [ ! -f "$APK" ]; then
  echo "Downloading latest AR Remote app..."
  curl -fL -o "$APK" "$URL" || { echo "No ar-remote.apk on the latest GitHub release yet."; rm -f "$APK"; exit 1; }
fi
if [ "$(wc -c < "$APK")" -lt 100000 ]; then
  echo "ar-remote.apk is too small to be a real app - deleting it. Check the GitHub release."
  rm -f "$APK"; exit 1
fi

echo "Waiting for the remote on USB (accept the prompt on its screen)..."
"$ADB" wait-for-usb-device

echo "Installing..."
OUT="$("$ADB" -d install -r -g "$APK" 2>&1 || true)"
echo "$OUT"
if echo "$OUT" | grep -q "VERSION_DOWNGRADE"; then
  echo "The app on the remote is newer than this file - removing it, then installing this one..."
  "$ADB" -d uninstall "$PKG" || true
  "$ADB" -d install -g "$APK"
elif ! echo "$OUT" | grep -q "Success"; then
  echo "Install failed."; exit 1
fi

echo "Allowing the app to install its own updates..."
"$ADB" -d shell appops set "$PKG" REQUEST_INSTALL_PACKAGES allow

echo "Switching off the remote's other apps (RosCard, old dashboards, launchers)..."
"$ADB" -d shell 'for p in $(pm list packages -3) $(pm list packages | grep -i -e launcher -e roscard -e astrion -e sanytron); do p=${p#package:}; case $p in com.arsmarthome.remote|*inputmethod*|*keyboard*) ;; *) am force-stop $p; pm disable-user --user 0 $p >/dev/null 2>&1 && echo $p ;; esac; done' | tr -d '\r' > "$HERE/disabled-apps.txt"
echo "  Disabled:"; cat "$HERE/disabled-apps.txt"
echo "  (run ./restore-apps.sh to switch them back on)"

echo "Making AR Remote the home screen..."
"$ADB" -d shell cmd package set-home-activity "$PKG/.MainActivity" || true
echo "Letting pick-up detection run while the remote sleeps..."
"$ADB" -d shell dumpsys deviceidle whitelist +"$PKG" || true
"$ADB" -d shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow || true
"$ADB" -d shell am start -n "$PKG/.MainActivity"

echo
echo "Remote IP address (enter this in Home Assistant):"
"$ADB" -d shell "ip -f inet addr show wlan0 | grep inet" || true

echo
echo "Optional: trying to open network ADB until the next reboot..."
if "$ADB" -d tcpip 5555 >/dev/null 2>&1; then
  echo "  network ADB on port 5555 (lost on reboot)"
else
  echo "  not supported on this firmware - that's fine."
fi

echo
echo "Done. Add the remote in Home Assistant (AR Remote integration)."
echo "Future updates: press 'Install / update app' in HA and tap Install on the remote."
