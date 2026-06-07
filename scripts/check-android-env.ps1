$ErrorActionPreference = "SilentlyContinue"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$ProjectTools = Join-Path $Root ".tools"
$ProjectJdk = Join-Path $ProjectTools "jdk"
$ProjectAndroidSdk = Join-Path $ProjectTools "android-sdk"
$ProjectGradle = Join-Path $ProjectTools "gradle"

function Test-Command($Name) {
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($command) {
        Write-Host "[OK] $Name -> $($command.Source)" -ForegroundColor Green
        return $true
    }

    Write-Host "[MISS] $Name not found in PATH" -ForegroundColor Yellow
    return $false
}

Write-Host "Clip Flow Android environment check" -ForegroundColor Cyan
Write-Host ""

$hasJava = Test-Command "java"
$hasGradle = Test-Command "gradle"
$hasAdb = Test-Command "adb"

Write-Host ""
Write-Host "Environment variables" -ForegroundColor Cyan

$androidHome = [Environment]::GetEnvironmentVariable("ANDROID_HOME", "User")
if (-not $androidHome) {
    $androidHome = [Environment]::GetEnvironmentVariable("ANDROID_HOME", "Machine")
}

$androidSdkRoot = [Environment]::GetEnvironmentVariable("ANDROID_SDK_ROOT", "User")
if (-not $androidSdkRoot) {
    $androidSdkRoot = [Environment]::GetEnvironmentVariable("ANDROID_SDK_ROOT", "Machine")
}

if ($androidHome) {
    Write-Host "[OK] ANDROID_HOME=$androidHome" -ForegroundColor Green
} else {
    Write-Host "[MISS] ANDROID_HOME is not set" -ForegroundColor Yellow
}

if ($androidSdkRoot) {
    Write-Host "[OK] ANDROID_SDK_ROOT=$androidSdkRoot" -ForegroundColor Green
} else {
    Write-Host "[INFO] ANDROID_SDK_ROOT is not set" -ForegroundColor Gray
}

Write-Host ""
Write-Host "Common install paths" -ForegroundColor Cyan

$paths = @(
    $ProjectJdk,
    $ProjectAndroidSdk,
    $ProjectGradle,
    "C:\Program Files\Android\Android Studio",
    "$env:LOCALAPPDATA\Android\Sdk",
    "C:\Program Files\Java",
    "C:\Program Files\Eclipse Adoptium"
)

foreach ($path in $paths) {
    if (Test-Path $path) {
        Write-Host "[OK] $path" -ForegroundColor Green
    } else {
        Write-Host "[MISS] $path" -ForegroundColor Yellow
    }
}

Write-Host ""

Write-Host ""
Write-Host "Project-local tools" -ForegroundColor Cyan

$projectJava = Test-Path (Join-Path $ProjectJdk "bin\java.exe")
$projectAdb = Test-Path (Join-Path $ProjectAndroidSdk "platform-tools\adb.exe")
$projectGradleOk = Test-Path (Join-Path $ProjectGradle "bin\gradle.bat")

if ($projectJava) {
    Write-Host "[OK] project java -> $(Join-Path $ProjectJdk 'bin\java.exe')" -ForegroundColor Green
} else {
    Write-Host "[MISS] project java" -ForegroundColor Yellow
}

if ($projectAdb) {
    Write-Host "[OK] project adb -> $(Join-Path $ProjectAndroidSdk 'platform-tools\adb.exe')" -ForegroundColor Green
} else {
    Write-Host "[MISS] project adb" -ForegroundColor Yellow
}

if ($projectGradleOk) {
    Write-Host "[OK] project gradle -> $(Join-Path $ProjectGradle 'bin\gradle.bat')" -ForegroundColor Green
} else {
    Write-Host "[MISS] project gradle" -ForegroundColor Yellow
}

Write-Host ""

if (($hasJava -and ($androidHome -or $androidSdkRoot) -and $hasAdb) -or ($projectJava -and $projectAdb -and $projectGradleOk)) {
    Write-Host "Android environment looks usable." -ForegroundColor Green
} else {
    Write-Host "Android environment is incomplete. See README.md and plan.md." -ForegroundColor Yellow
}
