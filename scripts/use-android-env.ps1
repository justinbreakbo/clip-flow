$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$Tools = Join-Path $Root ".tools"
$JdkHome = Join-Path $Tools "jdk"
$AndroidHome = Join-Path $Tools "android-sdk"
$GradleHome = Join-Path $Tools "gradle"

$env:JAVA_HOME = $JdkHome
$env:ANDROID_HOME = $AndroidHome
$env:ANDROID_SDK_ROOT = $AndroidHome
$env:Path = @(
    (Join-Path $JdkHome "bin"),
    (Join-Path $AndroidHome "platform-tools"),
    (Join-Path $AndroidHome "cmdline-tools\latest\bin"),
    (Join-Path $GradleHome "bin"),
    $env:Path
) -join ";"

Write-Host "JAVA_HOME=$env:JAVA_HOME"
Write-Host "ANDROID_HOME=$env:ANDROID_HOME"
Write-Host "Gradle=$(Join-Path $GradleHome 'bin')"
