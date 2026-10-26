#!/usr/bin/env bash
# Switches back on every app install-usb.sh disabled (listed in disabled-apps.txt).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ADB="$(command -v adb || echo "$HERE/platform-tools/adb")"
[ -f "$HERE/disabled-apps.txt" ] || { echo "disabled-apps.txt not found - nothing to restore."; exit 1; }
"$ADB" wait-for-usb-device
"$ADB" -d push "$HERE/disabled-apps.txt" /data/local/tmp/disabled-apps.txt >/dev/null
"$ADB" -d shell 'for p in $(tr -d "\r" < /data/local/tmp/disabled-apps.txt); do pm enable $p >/dev/null 2>&1 && echo re-enabled $p; done; rm /data/local/tmp/disabled-apps.txt'
