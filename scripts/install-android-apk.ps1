$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
. (Join-Path $Root "scripts\use-android-env.ps1")

$Apk = Join-Path $Root "android-native\app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $Apk)) {
    throw "APK not found. Run .\scripts\build-android-debug.ps1 first."
}

adb devices
adb install -r $Apk
