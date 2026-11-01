#!/usr/bin/env bash
# Usage: ANDROID_SERIAL=<serial> ./adb-check.sh [apk] [--offline]
APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"; PKG=com.ridepack.app
adb devices -l | grep -w device >/dev/null || { echo "No authorised device. Enable USB debugging and accept the prompt."; exit 1; }
adb install -r "$APK" || exit 1
for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION BLUETOOTH_SCAN BLUETOOTH_ADVERTISE BLUETOOTH_CONNECT NEARBY_WIFI_DEVICES POST_NOTIFICATIONS; do adb shell pm grant $PKG android.permission.$p 2>/dev/null; done
if [ "$2" = "--offline" ]; then adb shell svc wifi disable; adb shell svc data disable; echo "Wi-Fi + mobile data OFF (undo: adb shell svc wifi enable; adb shell svc data enable)"; fi
adb logcat -c; adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null; sleep 10
adb exec-out screencap -p > ridepack-screen.png
adb logcat -d --pid="$(adb shell pidof $PKG | tr -d '\r')" > ridepack-log.txt
adb logcat -d -s AndroidRuntime:E >> ridepack-log.txt
echo "Saved ridepack-screen.png and ridepack-log.txt"
