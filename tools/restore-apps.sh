#!/usr/bin/env bash
# Switches back on every app install-usb.sh disabled (listed in disabled-apps.txt).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ADB="$(command -v adb || echo "$HERE/platform-tools/adb")"
[ -f "$HERE/disabled-apps.txt" ] || [ -f "$HERE/hidden-apps.txt" ] || { echo "disabled-apps.txt not found - nothing to restore."; exit 1; }
"$ADB" wait-for-usb-device
if [ -f "$HERE/hidden-apps.txt" ]; then
  "$ADB" -d push "$HERE/hidden-apps.txt" /data/local/tmp/hidden-apps.txt >/dev/null
  "$ADB" -d shell 'for p in $(tr -d "\r" < /data/local/tmp/hidden-apps.txt); do cmd package install-existing $p >/dev/null 2>&1 && echo brought back $p; done; rm /data/local/tmp/hidden-apps.txt'
fi
if [ -f "$HERE/disabled-apps.txt" ]; then
  "$ADB" -d push "$HERE/disabled-apps.txt" /data/local/tmp/disabled-apps.txt >/dev/null
  "$ADB" -d shell 'for p in $(tr -d "\r" < /data/local/tmp/disabled-apps.txt); do pm enable $p >/dev/null 2>&1 && echo re-enabled $p; done; rm /data/local/tmp/disabled-apps.txt'
fi
