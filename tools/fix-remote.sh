#!/usr/bin/env bash
# AR Remote - put things right over USB when RosCard (the stock launcher) has
# come back: switches it off again, makes AR Remote the home screen, turns off
# the lock screen and the clock screensaver. Writes fix-report.txt.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ADB="$(command -v adb || echo "$HERE/platform-tools/adb")"
[ -x "$ADB" ] || { echo "adb not found - run install-usb.sh once first (it downloads it)."; exit 1; }

echo "Waiting for the remote on USB (accept the prompt on its screen)..."
"$ADB" wait-for-usb-device
"$ADB" -d push "$HERE/arfix.sh" /data/local/tmp/arfix.sh >/dev/null
"$ADB" -d shell 'rm -f /data/local/tmp/ar_disabled.txt /data/local/tmp/ar_hidden.txt; tr -d "\r" < /data/local/tmp/arfix.sh > /data/local/tmp/arfix2.sh; sh /data/local/tmp/arfix2.sh' | tr -d '\r' | tee "$HERE/fix-report.txt"
# Remember what was switched off so restore-apps can switch it back on.
"$ADB" -d shell 'cat /data/local/tmp/ar_disabled.txt 2>/dev/null' | tr -d '\r' >> "$HERE/disabled-apps.txt"
"$ADB" -d shell 'cat /data/local/tmp/ar_hidden.txt 2>/dev/null' | tr -d '\r' >> "$HERE/hidden-apps.txt"
sort -u -o "$HERE/disabled-apps.txt" "$HERE/disabled-apps.txt" 2>/dev/null || true
[ -s "$HERE/hidden-apps.txt" ] && sort -u -o "$HERE/hidden-apps.txt" "$HERE/hidden-apps.txt" || rm -f "$HERE/hidden-apps.txt"
echo
echo "Done. The report is in fix-report.txt - send it over if anything still looks wrong."
