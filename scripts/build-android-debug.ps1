$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
. (Join-Path $Root "scripts\use-android-env.ps1")
Push-Location (Join-Path $Root "android-native")
try {
    gradle --no-daemon assembleDebug
} finally {
    Pop-Location
}
