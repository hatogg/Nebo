#!/usr/bin/env bash
# Перевірка в емуляторі: малюємо зразки циферблатів і знімаємо екран застосунку.
set -x
OUT=ci-out
mkdir -p $OUT
adb install -r -g app/build/outputs/apk/debug/app-debug.apk > $OUT/install.txt 2>&1
adb logcat -c
adb shell am start -n ua.nebo.watch/.MainActivity --ez renderTest true
for i in $(seq 1 90); do
  adb shell "test -f /sdcard/Android/data/ua.nebo.watch/files/faces/done" && break
  sleep 2
done
adb pull /sdcard/Android/data/ua.nebo.watch/files/faces $OUT/ || true
sleep 8
adb exec-out screencap -p > $OUT/ui_top.png
adb shell input swipe 540 1900 540 500 400
sleep 2
adb exec-out screencap -p > $OUT/ui_scrolled.png
adb shell dumpsys activity services ua.nebo.watch > $OUT/services.txt 2>&1 || true
adb logcat -d > $OUT/logcat_all.txt 2>&1 || true
grep -iE "nebo|AndroidRuntime|FATAL|FcSDK|chromium|alerts|watch" $OUT/logcat_all.txt | tail -400 > $OUT/logcat.txt || true
rm -f $OUT/logcat_all.txt
exit 0
