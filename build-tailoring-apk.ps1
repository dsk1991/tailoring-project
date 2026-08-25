$ErrorActionPreference = 'Stop'
$workspaceRoot = $PSScriptRoot
$projectRoot = Join-Path $workspaceRoot 'android'
$toolingCandidates = @(
    (Join-Path $workspaceRoot '.tooling'),
    'E:\ChatGPT\WMS MOBILE APP\.tooling'
)
$tooling = $toolingCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $tooling) { throw 'Android build toolchain not found. Expected .tooling locally or under E:\ChatGPT\WMS MOBILE APP.' }
$jdk = Get-ChildItem (Join-Path $tooling 'jdk') -Directory | Select-Object -First 1
$gradle = Get-ChildItem (Join-Path $tooling 'gradle') -Directory | Select-Object -First 1
if (-not $jdk -or -not $gradle) { throw 'JDK or Gradle directory is missing from the Android toolchain.' }

$env:JAVA_HOME = $jdk.FullName
$env:ANDROID_HOME = Join-Path $tooling 'sdk'
$env:GRADLE_USER_HOME = Join-Path $tooling 'gradle-home'
& (Join-Path $gradle.FullName 'bin\gradle.bat') --no-daemon -p $projectRoot assembleDebug
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$sourceApk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
$targetApk = Join-Path $workspaceRoot 'Tailoring-Staff-v1.0.apk'
Copy-Item -LiteralPath $sourceApk -Destination $targetApk -Force
Write-Host "APK: $targetApk"
