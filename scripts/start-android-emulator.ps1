$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
. (Join-Path $Root "scripts\use-android-env.ps1")

$AvdName = if ($env:CLIP_FLOW_AVD_NAME) { $env:CLIP_FLOW_AVD_NAME } else { "ClipFlowApi35" }
$Emulator = Join-Path $env:ANDROID_HOME "emulator\emulator.exe"

Start-Process -WindowStyle Normal -FilePath $Emulator -ArgumentList @(
    "-avd", $AvdName,
    "-netdelay", "none",
    "-netspeed", "full"
)

Write-Host "Starting emulator $AvdName"
Write-Host "Run .\scripts\install-android-apk.ps1 after boot completes."
