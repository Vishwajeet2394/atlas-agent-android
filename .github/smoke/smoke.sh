#!/usr/bin/env bash
# Emulator smoke test: install, grant, open every screen, run the agent self-test, fail on crashes or JS errors.
set -u
P=com.atlas.agent
OUT=smoke-out; mkdir -p $OUT
shot() { adb exec-out screencap -p > "$OUT/$1.png"; }
adb install -r -g Atlas.apk || exit 1
adb shell mkdir -p /sdcard/Download /sdcard/DCIM/Camera
adb push .github/smoke/Electricity_Bill_Oct2026.pdf /sdcard/Download/
adb push .github/smoke/IMG_20261101_sunset.jpg /sdcard/DCIM/Camera/
adb push .github/smoke/IMG_20261101_sunset_copy.jpg /sdcard/Download/
adb shell appops set --uid $P MANAGE_EXTERNAL_STORAGE allow
adb shell settings put secure enabled_accessibility_services $P/$P.AgentService
adb shell settings put secure accessibility_enabled 1
adb logcat -c
adb shell am start -W -n $P/.MainActivity; sleep 12; shot 1-assistant
for t in files tasks settings; do adb shell am start -n $P/.MainActivity --es go $t; sleep 5; shot "2-$t"; done
adb shell am start -n $P/.MainActivity --es go assistant; sleep 3
# agent end-to-end: opens the Settings app and taps "Network" using the offline self-test brain
adb shell am start -n $P/.MainActivity --es selftest "TAP:Network" --es app settings
sleep 9; shot 3-agent-running
for i in $(seq 1 30); do adb logcat -d -s AtlasAgent:I | grep -q "Self-test passed\|fail:" && break; sleep 2; done
sleep 2; shot 4-agent-done
adb shell am start -n $P/.MainActivity --es go tasks; sleep 4; shot 5-tasks-log
adb logcat -d > $OUT/logcat.txt
adb logcat -d -s AtlasAgent:I > $OUT/agent.txt
grep -E "chromium.*(Uncaught|CONSOLE.*Error)" $OUT/logcat.txt > $OUT/js-errors.txt
grep -A25 "FATAL EXCEPTION" $OUT/logcat.txt > $OUT/crashes.txt
grep -E "ANR in $P" $OUT/logcat.txt >> $OUT/crashes.txt
echo "--- agent log"; cat $OUT/agent.txt
echo "--- js errors"; cat $OUT/js-errors.txt
echo "--- crashes"; cat $OUT/crashes.txt
fail=0
grep -q "Self-test passed" $OUT/agent.txt || { echo "::error::agent self-test did not pass"; fail=1; }
[ -s $OUT/js-errors.txt ] && { echo "::error::JavaScript errors in the app"; fail=1; }
[ -s $OUT/crashes.txt ] && { echo "::error::app crashed"; fail=1; }
exit $fail
