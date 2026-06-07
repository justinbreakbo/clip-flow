$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
. (Join-Path $Root "scripts\use-android-env.ps1")

$Apk = Join-Path $Root "android-native\app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $Apk)) {
    throw "APK not found. Run .\scripts\build-android-debug.ps1 first."
}

Write-Host "Waiting for Android device..."
adb wait-for-device

Write-Host "Waiting for boot completion..."
do {
    Start-Sleep -Seconds 2
    $booted = (adb shell getprop sys.boot_completed 2>$null).Trim()
    Write-Host "sys.boot_completed=$booted"
} while ($booted -ne "1")

adb install -r $Apk
adb shell am start -n com.clipflow.android/.MainActivity
