# Builds the LSPosed module APK with the portable JDK + Android SDK under work\android-tools.
# Nothing is installed system-wide; everything stays inside this project folder.
$ErrorActionPreference = 'Stop'

$root      = Split-Path -Parent $PSScriptRoot
$tools     = Join-Path $root 'work\android-tools'
$jdk       = Join-Path $tools 'jdk-17.0.21+9'
$platform  = Join-Path $tools 'android-35'
$buildTools = Join-Path $tools 'android-15'
$apiJar    = Join-Path $tools 'api-82.jar'
$module    = Join-Path $root 'lsp-module\app'
$buildDir  = Join-Path $root 'build\lsp'
$keystore  = Join-Path $tools 'g2lyrics-debug.keystore'
$outputs   = Join-Path $root 'outputs'
$finalApk  = Join-Path $outputs 'g2-lyrics-bridge.apk'

$androidJar = Join-Path $platform 'android.jar'
$manifest   = Join-Path $module 'src\main\AndroidManifest.xml'
$aapt2      = Join-Path $buildTools 'aapt2.exe'
$d8         = Join-Path $buildTools 'd8.bat'
$zipalign   = Join-Path $buildTools 'zipalign.exe'
$apksigner  = Join-Path $buildTools 'apksigner.bat'

foreach ($required in @($jdk, $androidJar, $aapt2, $d8, $zipalign, $apksigner, $apiJar, $manifest)) {
    if (-not (Test-Path -LiteralPath $required)) { throw "missing build prerequisite: $required" }
}

$env:JAVA_HOME = $jdk
$env:PATH = "$jdk\bin;$env:PATH"

if (Test-Path -LiteralPath $buildDir) { Remove-Item -LiteralPath $buildDir -Recurse -Force }
New-Item -ItemType Directory -Path (Join-Path $buildDir 'classes') -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $buildDir 'dex') -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $buildDir 'res') -Force | Out-Null
New-Item -ItemType Directory -Path $outputs -Force | Out-Null

# 1. Compile the Xposed entry point against android.jar + the Xposed API (compile-only).
$sources = Get-ChildItem -LiteralPath (Join-Path $module 'src\main\java') -Recurse -Filter *.java | Select-Object -ExpandProperty FullName
if (-not $sources) { throw 'no java sources found' }
& (Join-Path $jdk 'bin\javac.exe') -encoding UTF-8 -source 11 -target 11 -Xlint:-options `
    -classpath "$androidJar;$apiJar" -d (Join-Path $buildDir 'classes') $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

# 2. Compile and link resources (manifest + theme + xposed scope array).
& $aapt2 compile --dir (Join-Path $module 'src\main\res') -o (Join-Path $buildDir 'res')
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed with exit code $LASTEXITCODE" }

$flats = Get-ChildItem -LiteralPath (Join-Path $buildDir 'res') -Recurse -Filter *.flat | Select-Object -ExpandProperty FullName
$baseApk = Join-Path $buildDir 'base.apk'
& $aapt2 link -o $baseApk -I $androidJar --manifest $manifest `
    --min-sdk-version 26 --target-sdk-version 35 `
    -A (Join-Path $module 'src\main\assets') $flats
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed with exit code $LASTEXITCODE" }

# 3. Dex the classes and pack them next to resources.arsc.
$classFiles = Get-ChildItem -LiteralPath (Join-Path $buildDir 'classes') -Recurse -Filter *.class | Select-Object -ExpandProperty FullName
& $d8 --lib $androidJar --min-api 26 --output (Join-Path $buildDir 'dex') $classFiles
if ($LASTEXITCODE -ne 0) { throw "d8 failed with exit code $LASTEXITCODE" }

$dexFile = Join-Path $buildDir 'dex\classes.dex'
if (-not (Test-Path -LiteralPath $dexFile)) { throw 'd8 produced no classes.dex' }

$packScript = @'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
print('classes.dex packed')
'@
$packScript | Set-Content -LiteralPath (Join-Path $buildDir 'pack.py') -Encoding utf8
& python (Join-Path $buildDir 'pack.py') $baseApk $dexFile
if ($LASTEXITCODE -ne 0) { throw "packing classes.dex failed with exit code $LASTEXITCODE" }

# 4. Align + sign so the module installs on Android 11+.
$alignedApk = Join-Path $buildDir 'aligned.apk'
& $zipalign -f -p 4 $baseApk $alignedApk
if ($LASTEXITCODE -ne 0) { throw "zipalign failed with exit code $LASTEXITCODE" }

if (-not (Test-Path -LiteralPath $keystore)) {
    & (Join-Path $jdk 'bin\keytool.exe') -genkeypair -keystore $keystore `
        -storepass android -keypass android -alias androiddebugkey `
        -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=G2 Lyrics Bridge, O=Personal, C=CN"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed with exit code $LASTEXITCODE" }
}

if (Test-Path -LiteralPath $finalApk) { Remove-Item -LiteralPath $finalApk -Force }
& $apksigner sign --ks $keystore --ks-pass pass:android --key-pass pass:android `
    --ks-key-alias androiddebugkey --v4-signing-enabled false --out $finalApk $alignedApk
if ($LASTEXITCODE -ne 0) { throw "apksigner failed with exit code $LASTEXITCODE" }

& $apksigner verify --print-certs $finalApk
if ($LASTEXITCODE -ne 0) { throw "apksigner verify failed with exit code $LASTEXITCODE" }

$size = (Get-Item -LiteralPath $finalApk).Length
Write-Output ("APK ready: {0} ({1:N0} bytes)" -f $finalApk, $size)
