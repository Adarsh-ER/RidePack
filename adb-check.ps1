# Windows equivalent of adb-check.sh
# Usage: .\adb-check.ps1 [-Apk <path>] [-Offline]
param(
    [string]$Apk = "app\build\outputs\apk\debug\app-debug.apk",
    [switch]$Offline
)
$ErrorActionPreference = "Continue"
$pkg = "com.ridepack.app"

if (-not (adb devices | Select-String -Pattern "\sdevice$")) {
    Write-Host "No authorised device. Enable USB debugging and accept the prompt."
    exit 1
}

adb install -r $Apk
if ($LASTEXITCODE -ne 0) { exit 1 }

$perms = @(
    "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION",
    "BLUETOOTH_SCAN", "BLUETOOTH_ADVERTISE", "BLUETOOTH_CONNECT",
    "NEARBY_WIFI_DEVICES", "POST_NOTIFICATIONS"
)
foreach ($p in $perms) { adb shell pm grant $pkg "android.permission.$p" 2>$null | Out-Null }

if ($Offline) {
    adb shell svc wifi disable
    adb shell svc data disable
    Write-Host "Wi-Fi + mobile data OFF (undo: adb shell svc wifi enable; adb shell svc data enable)"
}

adb logcat -c
adb shell monkey -p $pkg -c android.intent.category.LAUNCHER 1 | Out-Null
Start-Sleep -Seconds 10

# Pull via /sdcard: PowerShell's ">" would re-encode the binary PNG as UTF-16.
adb shell screencap -p /sdcard/ridepack-screen.png
adb pull /sdcard/ridepack-screen.png ridepack-screen.png | Out-Null
adb shell rm /sdcard/ridepack-screen.png
$pid0 = (adb shell pidof $pkg) -replace "`r", ""
if ($pid0) { adb logcat -d --pid=$pid0 > ridepack-log.txt } else { adb logcat -d > ridepack-log.txt }
adb logcat -d -s AndroidRuntime:E >> ridepack-log.txt

Write-Host "Saved ridepack-screen.png and ridepack-log.txt"
