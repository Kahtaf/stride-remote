#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
stride_serial="${1:?Usage: verify-emulator.sh emulator-SERIAL [output-label]}"
stride_label="${2:-watch-ui}"
[ "$(adb -s "$stride_serial" shell getprop ro.kernel.qemu | tr -d '\r')" = '1' ] || {
    echo 'This simulated-pad check is for an emulator only.' >&2; exit 1;
}
case "$stride_label" in *[!a-zA-Z0-9_-]*) echo 'Use letters, numbers, hyphens or underscores for the label.' >&2; exit 1;; esac
mkdir -p qa
./gradlew testDebugUnitTest lint assembleDebug assembleDebugAndroidTest > "qa/$stride_label-build.log" 2>&1
adb -s "$stride_serial" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$stride_serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$stride_serial" shell input keyevent 224
adb -s "$stride_serial" shell am instrument -w dev.kahtaf.walkpad.test/dev.kahtaf.walkpad.SmokeInstrumentation > "qa/$stride_label.log" 2>&1
cat "qa/$stride_label.log"
rg -q 'WATCH UI CHECKS PASSED' "qa/$stride_label.log"
mkdir -p "qa/$stride_label-screenshots"
adb -s "$stride_serial" pull /sdcard/Android/data/dev.kahtaf.walkpad/files/screenshots/. "qa/$stride_label-screenshots/"
