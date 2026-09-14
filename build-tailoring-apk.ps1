$ErrorActionPreference = 'Stop'
$workspaceRoot = $PSScriptRoot
$projectRoot = Join-Path $workspaceRoot 'android'
$toolingCandidates = @(
    (Join-Path $workspaceRoot '.tooling'),
    'E:\ChatGPT\WMS MOBILE APP\.tooling'
)
$tooling = $toolingCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $tooling) { throw 'Android build toolchain not found.' }

$jdk = Get-ChildItem (Join-Path $tooling 'jdk') -Directory | Select-Object -First 1
$buildTools = Get-ChildItem (Join-Path $tooling 'sdk\build-tools') -Directory | Sort-Object Name -Descending | Select-Object -First 1
$androidJar = Join-Path $tooling 'sdk\platforms\android-35\android.jar'
if (-not $jdk -or -not $buildTools -or -not (Test-Path -LiteralPath $androidJar)) {
    throw 'JDK, Android platform 35, or build-tools are missing.'
}
$env:JAVA_HOME = $jdk.FullName

$buildRoot = Join-Path $projectRoot 'direct-build'
$resolvedProject = [IO.Path]::GetFullPath($projectRoot).TrimEnd('\') + '\'
$resolvedBuild = [IO.Path]::GetFullPath($buildRoot)
if (-not $resolvedBuild.StartsWith($resolvedProject, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Refusing to clean a build path outside the Android project.'
}
if (Test-Path -LiteralPath $resolvedBuild) { Remove-Item -LiteralPath $resolvedBuild -Recurse -Force }

$gen = New-Item -ItemType Directory -Force (Join-Path $buildRoot 'gen')
$classes = New-Item -ItemType Directory -Force (Join-Path $buildRoot 'classes')
$dex = New-Item -ItemType Directory -Force (Join-Path $buildRoot 'dex')
$compiled = Join-Path $buildRoot 'resources.zip'
$unsigned = Join-Path $buildRoot 'unsigned.apk'
$aligned = Join-Path $buildRoot 'aligned.apk'
$classJar = Join-Path $buildRoot 'classes.jar'
$manifest = Join-Path $projectRoot 'app\src\main\AndroidManifest.xml'
$resources = Join-Path $projectRoot 'app\src\main\res'
$javaRoot = Join-Path $projectRoot 'app\src\main\java'

$aapt2 = Join-Path $buildTools.FullName 'aapt2.exe'
$aapt = Join-Path $buildTools.FullName 'aapt.exe'
$d8 = Join-Path $buildTools.FullName 'd8.bat'
$zipalign = Join-Path $buildTools.FullName 'zipalign.exe'
$apksigner = Join-Path $buildTools.FullName 'apksigner.bat'
$javac = Join-Path $jdk.FullName 'bin\javac.exe'
$jar = Join-Path $jdk.FullName 'bin\jar.exe'

& $aapt2 compile --dir $resources -o $compiled
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $aapt2 link -o $unsigned -I $androidJar --manifest $manifest --java $gen.FullName --min-sdk-version 23 --target-sdk-version 35 --version-code 5 --version-name 2.0.0 --auto-add-overlay $compiled
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$sources = @(
    Get-ChildItem -LiteralPath $javaRoot -Filter '*.java' -Recurse | ForEach-Object FullName
    Get-ChildItem -LiteralPath $gen.FullName -Filter '*.java' -Recurse | ForEach-Object FullName
)
& $javac -encoding UTF-8 -source 8 -target 8 -classpath $androidJar -d $classes.FullName $sources
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $jar cf $classJar -C $classes.FullName .
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $d8 --lib $androidJar --min-api 23 --output $dex.FullName $classJar
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Push-Location $dex.FullName
try { & $aapt add $unsigned 'classes.dex' } finally { Pop-Location }
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $zipalign -f 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$debugKey = 'C:\Users\Administrator\.android\debug.keystore'
if (-not (Test-Path -LiteralPath $debugKey)) { throw 'Android debug keystore not found.' }
$targetApk = Join-Path $workspaceRoot 'Tailoring-Staff-v2.0.apk'
& $apksigner sign --ks $debugKey --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out $targetApk $aligned
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $apksigner verify --verbose $targetApk
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host "APK: $targetApk"
