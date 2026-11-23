param(
    [string]$OutputApk = (Join-Path $PSScriptRoot 'build/NACE Automation.apk'),
    [string]$SigningKey = (Join-Path $PSScriptRoot 'build/development-signing.jks')
)

$ErrorActionPreference = 'Stop'

function Invoke-BuildTool {
    param([string]$Tool, [string[]]$ToolArguments)
    & $Tool @ToolArguments
    if ($LASTEXITCODE -ne 0) { throw "Build tool failed with exit code ${LASTEXITCODE}: $Tool" }
}

$sdk = $env:ANDROID_HOME
if (!$sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (!$sdk) { throw 'Set ANDROID_HOME to the installed Android SDK.' }
if (!$env:JAVA_HOME) { throw 'Set JAVA_HOME to a JDK 17 or newer.' }

$tools = Join-Path $sdk 'build-tools/36.0.0'
$platform = Join-Path $sdk 'platforms/android-36/android.jar'
$javaBin = Join-Path $env:JAVA_HOME 'bin'
$source = Join-Path $PSScriptRoot 'app/src/main'
$temporary = Join-Path $PSScriptRoot ('build/compile-' + [Guid]::NewGuid().ToString('N'))
$classes = Join-Path $temporary 'classes'
$generated = Join-Path $temporary 'generated'
$dex = Join-Path $temporary 'dex'
$OutputApk = [IO.Path]::GetFullPath($OutputApk)
$SigningKey = [IO.Path]::GetFullPath($SigningKey)

New-Item -ItemType Directory -Force -Path $classes, $generated, $dex, (Split-Path -Parent $OutputApk), (Split-Path -Parent $SigningKey) | Out-Null

$compiledResources = Join-Path $temporary 'resources.zip'
$unsigned = Join-Path $temporary 'unsigned.apk'
$linked = Join-Path $temporary 'aligned.apk'
$jar = Join-Path $temporary 'classes.jar'
$aapt = Join-Path $tools 'aapt2.exe'

Invoke-BuildTool $aapt @('compile', '--dir', (Join-Path $source 'res'), '-o', $compiledResources)
Invoke-BuildTool $aapt @('link', '-I', $platform, '--manifest', (Join-Path $source 'AndroidManifest.xml'), '--java', $generated, '-o', $unsigned, $compiledResources)

$sources = @(
    Get-ChildItem -LiteralPath (Join-Path $source 'java'), $generated -Recurse -File -Filter '*.java' |
        ForEach-Object FullName
)
Invoke-BuildTool (Join-Path $javaBin 'javac.exe') (@('--release', '17', '-encoding', 'UTF-8', '-classpath', $platform, '-d', $classes) + $sources)
Invoke-BuildTool (Join-Path $javaBin 'jar.exe') @('cf', $jar, '-C', $classes, '.')
Invoke-BuildTool (Join-Path $tools 'd8.bat') @('--min-api', '26', '--lib', $platform, '--output', $dex, $jar)
foreach ($bytecode in Get-ChildItem -LiteralPath $dex -File -Filter 'classes*.dex') {
    Invoke-BuildTool (Join-Path $javaBin 'jar.exe') @('uf', $unsigned, '-C', $dex, $bytecode.Name)
}
Invoke-BuildTool (Join-Path $tools 'zipalign.exe') @('-f', '-P', '16', '4', $unsigned, $linked)

if (!(Test-Path -LiteralPath $SigningKey -PathType Leaf)) {
    Invoke-BuildTool (Join-Path $javaBin 'keytool.exe') @(
        '-genkeypair', '-noprompt', '-keystore', $SigningKey, '-storetype', 'JKS',
        '-storepass', 'android', '-keypass', 'android', '-alias', 'androiddebugkey',
        '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000', '-dname', 'CN=NACE Automation Development'
    )
}
Invoke-BuildTool (Join-Path $tools 'apksigner.bat') @(
    'sign', '--v4-signing-enabled', 'false', '--ks', $SigningKey, '--ks-key-alias', 'androiddebugkey',
    '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', $OutputApk, $linked
)
Invoke-BuildTool (Join-Path $tools 'apksigner.bat') @('verify', '--verbose', $OutputApk)
Get-Item -LiteralPath $OutputApk | Select-Object FullName, Length
