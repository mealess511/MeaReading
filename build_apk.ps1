# MeiYue (墨阅) - build an APK without Android Studio
#
# Requirements: JDK 8+ (11/17 recommended), Android SDK build-tools 33.0.2
# and platform android-33 installed. Set ANDROID_SDK_ROOT (or pass -SdkDir).
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File build_apk.ps1
#   powershell -ExecutionPolicy Bypass -File build_apk.ps1 -OutApk .\out\app.apk
#
param(
    [string]$SdkDir = $(if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "" }),
    [string]$OutApk = "",
    [string]$VersionName = "1.0.2",
    [int]$VersionCode = 3
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
if ([string]::IsNullOrWhiteSpace($SdkDir)) { throw "Set ANDROID_SDK_ROOT or pass -SdkDir <path to Android SDK>" }
$SdkDir = $SdkDir.TrimEnd('\', '/')
$bt = Join-Path $SdkDir "build-tools\33.0.2"
$androidJar = Join-Path $SdkDir "platforms\android-33\android.jar"
if (-not (Test-Path $androidJar)) { throw "Missing $androidJar" }
if (-not (Test-Path (Join-Path $bt "aapt2.exe"))) { throw "Missing build-tools 33.0.2 in $bt" }

$javaHome = $env:JAVA_HOME
if ([string]::IsNullOrWhiteSpace($javaHome)) {
    $cmd = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($cmd) { $javaHome = Split-Path -Parent (Split-Path -Parent $cmd.Source) } else { throw "Set JAVA_HOME" }
}
$javac = Join-Path $javaHome "bin\javac.exe"
$jarTool = Join-Path $javaHome "bin\jar.exe"
$keytool = Join-Path $javaHome "bin\keytool.exe"

$work = Join-Path $root "build_manual"
$src = Join-Path $root "app\src\main"
$res = Join-Path $src "res"
$manifest = Join-Path $src "AndroidManifest.xml"
$libs = Join-Path $root "app\libs"
$ks = Join-Path $root "keystore\moyue.keystore"
if ([string]::IsNullOrWhiteSpace($OutApk)) { $OutApk = Join-Path $root "MeaReading-$VersionName.apk" }

Write-Host "== 1/6 clean" -ForegroundColor Cyan
if (Test-Path $work) { Remove-Item $work -Recurse -Force }
New-Item -ItemType Directory -Force -Path (Join-Path $work "gen"), (Join-Path $work "classes"), (Join-Path $work "dex") | Out-Null

Write-Host "== 2/6 compile resources" -ForegroundColor Cyan
& (Join-Path $bt "aapt2.exe") compile --dir $res -o (Join-Path $work "res.zip")
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "== 3/6 link resources" -ForegroundColor Cyan
& (Join-Path $bt "aapt2.exe") link -o (Join-Path $work "base.apk") -I $androidJar --manifest $manifest `
    -R (Join-Path $work "res.zip") --java (Join-Path $work "gen") --min-sdk-version 26 --target-sdk-version 33 `
    --version-code $VersionCode --version-name $VersionName --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "== 4/6 javac" -ForegroundColor Cyan
$files = Get-ChildItem -Path (Join-Path $src "java"), (Join-Path $work "gen") -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$sourcesTxt = Join-Path $work "sources.txt"
[System.IO.File]::WriteAllLines($sourcesTxt, [string[]]$files, (New-Object System.Text.UTF8Encoding($false)))
$jars = @(Get-ChildItem $libs -Filter *.jar | ForEach-Object { $_.FullName })
$cpArg = ($jars -join ";")
$lambdaStubs = Join-Path $bt "core-lambda-stubs.jar"
& $javac -encoding UTF-8 -source 8 -target 8 -nowarn `
    -bootclasspath "$androidJar;$lambdaStubs" -cp $cpArg -d (Join-Path $work "classes") "@$sourcesTxt"
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "== 5/6 dex" -ForegroundColor Cyan
Push-Location (Join-Path $work "classes")
& $jarTool cf (Join-Path $work "classes.jar") .
Pop-Location
$d8Jar = Join-Path $bt "lib\d8.jar"
$d8args = @("--min-api", "26", "--lib", $androidJar, "--output", (Join-Path $work "dex"), (Join-Path $work "classes.jar")) + $jars
& (Join-Path $javaHome "bin\java.exe") -cp $d8Jar com.android.tools.r8.D8 @d8args
if ($LASTEXITCODE -ne 0) { throw "d8 failed (use a newer build-tools 34+ d8 if it crashes on junrar)" }

Write-Host "== 6/6 package + sign" -ForegroundColor Cyan
Copy-Item (Join-Path $work "base.apk") (Join-Path $work "unsigned.apk") -Force
Push-Location (Join-Path $work "dex")
& (Join-Path $bt "aapt.exe") add (Join-Path $work "unsigned.apk") "classes.dex"
Pop-Location
& (Join-Path $bt "zipalign.exe") -p -f 4 (Join-Path $work "unsigned.apk") (Join-Path $work "aligned.apk")
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

if (-not (Test-Path $ks)) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ks) | Out-Null
    & $keytool -genkeypair -v -keystore $ks -alias moyue -keyalg RSA -keysize 2048 -validity 10000 `
        -storepass moyue2024 -keypass moyue2024 -dname "CN=MeiYue, OU=Dev, O=MeiYue, C=CN"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}
& (Join-Path $javaHome "bin\java.exe") -jar (Join-Path $bt "lib\apksigner.jar") sign `
    --ks $ks --ks-pass pass:moyue2024 --key-pass pass:moyue2024 --out $OutApk (Join-Path $work "aligned.apk")
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

Write-Host "APK ready: $OutApk" -ForegroundColor Green
Get-Item $OutApk | Select-Object FullName, Length
