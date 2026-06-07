$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$Tools = Join-Path $Root ".tools"
$Downloads = Join-Path $Tools "downloads"
$JdkHome = Join-Path $Tools "jdk"
$AndroidHome = Join-Path $Tools "android-sdk"
$GradleHome = Join-Path $Tools "gradle"

$JdkUrl = "https://aka.ms/download-jdk/microsoft-jdk-17-windows-x64.zip"
$CmdlineToolsUrl = "https://dl.google.com/android/repository/commandlinetools-win-14742923_latest.zip"
$CmdlineToolsSha256 = "cc610ccbe83faddb58e1aa68e8fc8743bb30aa5e83577eceb4cc168dae95f9ee"
$GradleUrl = "https://services.gradle.org/distributions/gradle-8.7-bin.zip"

function New-CleanDirectory($Path) {
    if (Test-Path $Path) {
        Remove-Item -LiteralPath $Path -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $Path | Out-Null
}

function Download-File($Url, $Output) {
    if (Test-Path $Output) {
        Write-Host "Using cached $Output"
        return
    }

    Write-Host "Downloading $Url"
    Invoke-WebRequest -Uri $Url -OutFile $Output
}

function Assert-Sha256($Path, $Expected) {
    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash.ToLowerInvariant()
    if ($actual -ne $Expected.ToLowerInvariant()) {
        throw "SHA-256 mismatch for $Path. Expected $Expected, got $actual"
    }
}

New-Item -ItemType Directory -Force -Path $Downloads | Out-Null

$jdkZip = Join-Path $Downloads "microsoft-jdk-17-windows-x64.zip"
$cmdlineZip = Join-Path $Downloads "commandlinetools-win-14742923_latest.zip"
$gradleZip = Join-Path $Downloads "gradle-8.7-bin.zip"

Download-File $JdkUrl $jdkZip
Download-File $CmdlineToolsUrl $cmdlineZip
Assert-Sha256 $cmdlineZip $CmdlineToolsSha256
Download-File $GradleUrl $gradleZip

if (-not (Test-Path (Join-Path $JdkHome "bin\java.exe"))) {
    Write-Host "Installing JDK into $JdkHome"
    $tmpJdk = Join-Path $Tools "tmp-jdk"
    New-CleanDirectory $tmpJdk
    Expand-Archive -LiteralPath $jdkZip -DestinationPath $tmpJdk -Force
    $jdkRoot = Get-ChildItem -LiteralPath $tmpJdk -Directory | Select-Object -First 1
    if (-not $jdkRoot) { throw "JDK archive did not contain a root directory" }
    if (Test-Path $JdkHome) { Remove-Item -LiteralPath $JdkHome -Recurse -Force }
    Move-Item -LiteralPath $jdkRoot.FullName -Destination $JdkHome
    Remove-Item -LiteralPath $tmpJdk -Recurse -Force
}

if (-not (Test-Path (Join-Path $AndroidHome "cmdline-tools\latest\bin\sdkmanager.bat"))) {
    Write-Host "Installing Android command-line tools into $AndroidHome"
    $tmpAndroid = Join-Path $Tools "tmp-android"
    New-CleanDirectory $tmpAndroid
    Expand-Archive -LiteralPath $cmdlineZip -DestinationPath $tmpAndroid -Force
    $latest = Join-Path $AndroidHome "cmdline-tools\latest"
    New-CleanDirectory $latest
    Copy-Item -Path (Join-Path $tmpAndroid "cmdline-tools\*") -Destination $latest -Recurse -Force
    Remove-Item -LiteralPath $tmpAndroid -Recurse -Force
}

if (-not (Test-Path (Join-Path $GradleHome "bin\gradle.bat"))) {
    Write-Host "Installing Gradle into $GradleHome"
    $tmpGradle = Join-Path $Tools "tmp-gradle"
    New-CleanDirectory $tmpGradle
    Expand-Archive -LiteralPath $gradleZip -DestinationPath $tmpGradle -Force
    $gradleRoot = Get-ChildItem -LiteralPath $tmpGradle -Directory | Select-Object -First 1
    if (-not $gradleRoot) { throw "Gradle archive did not contain a root directory" }
    if (Test-Path $GradleHome) { Remove-Item -LiteralPath $GradleHome -Recurse -Force }
    Move-Item -LiteralPath $gradleRoot.FullName -Destination $GradleHome
    Remove-Item -LiteralPath $tmpGradle -Recurse -Force
}

. (Join-Path $Root "scripts\use-android-env.ps1")

$sdkManager = Join-Path $AndroidHome "cmdline-tools\latest\bin\sdkmanager.bat"

Write-Host "Accepting Android SDK licenses"
1..100 | ForEach-Object { "y" } | & $sdkManager --sdk_root=$AndroidHome --licenses

Write-Host "Installing Android SDK packages"
& $sdkManager --sdk_root=$AndroidHome "platform-tools" "platforms;android-35" "build-tools;35.0.0"

$localProperties = Join-Path $Root "android-native\local.properties"
$escapedSdk = $AndroidHome.Replace("\", "\\").Replace(":", "\:")
"sdk.dir=$escapedSdk" | Set-Content -Encoding ASCII -LiteralPath $localProperties

Write-Host "Android CLI environment installed."
Write-Host "Run: .\scripts\use-android-env.ps1"
Write-Host "Then: cd android-native; gradle assembleDebug"
