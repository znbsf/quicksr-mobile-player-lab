[CmdletBinding()]
param(
    [string]$ApkPath,
    [string]$AndroidSdkRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ([string]::IsNullOrWhiteSpace($ApkPath)) {
    $ApkPath = Join-Path $repositoryRoot 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
}
if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) {
    throw "AndroidTest APK does not exist: $ApkPath"
}
$resolvedApk = (Resolve-Path -LiteralPath $ApkPath).Path

if ([string]::IsNullOrWhiteSpace($AndroidSdkRoot)) {
    $AndroidSdkRoot = $env:ANDROID_SDK_ROOT
}
if ([string]::IsNullOrWhiteSpace($AndroidSdkRoot)) {
    $localProperties = Join-Path $repositoryRoot 'local.properties'
    if (Test-Path -LiteralPath $localProperties -PathType Leaf) {
        $sdkLine = Get-Content -LiteralPath $localProperties |
            Where-Object { $_ -match '^sdk\.dir=' } |
            Select-Object -First 1
        if ($null -ne $sdkLine) {
            $AndroidSdkRoot = ($sdkLine -replace '^sdk\.dir=', '') -replace '\\:', ':' -replace '\\\\', '\'
        }
    }
}
if ([string]::IsNullOrWhiteSpace($AndroidSdkRoot)) {
    throw 'Set -AndroidSdkRoot or ANDROID_SDK_ROOT so apkanalyzer can be located.'
}
$analyzers = @(
    Get-ChildItem -LiteralPath (Join-Path $AndroidSdkRoot 'cmdline-tools') `
        -Recurse -Filter 'apkanalyzer.bat' -File -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending
)
if ($analyzers.Count -eq 0) {
    throw "apkanalyzer.bat was not found beneath the Android SDK: $AndroidSdkRoot"
}
$apkanalyzer = $analyzers[0].FullName

$manifest = @(& $apkanalyzer manifest print $resolvedApk 2>&1)
if ($LASTEXITCODE -ne 0) {
    throw "apkanalyzer could not read the AndroidTest manifest: $($manifest -join ' ')"
}
$manifestText = $manifest -join "`n"
if ($manifestText -notmatch 'android:name="androidx\.test\.runner\.AndroidJUnitRunner"') {
    throw 'AndroidTest APK does not declare androidx.test.runner.AndroidJUnitRunner.'
}
if ($manifestText -notmatch 'android:targetPackage="dev\.aisystems\.quicksrplayerlab"') {
    throw 'AndroidTest APK targets the wrong application package.'
}

$dex = @(& $apkanalyzer dex packages $resolvedApk 2>&1)
if ($LASTEXITCODE -ne 0) {
    throw "apkanalyzer could not read AndroidTest dex contents: $($dex -join ' ')"
}
$dexText = $dex -join "`n"
$testClass = 'dev.aisystems.quicksrplayerlab.NativeOutputPackerInstrumentedTest'
if ($dexText -notmatch [regex]::Escape($testClass)) {
    throw "AndroidTest APK does not contain $testClass."
}
$requiredTests = @(
    'testMatchesJavaForBoundariesAndRandomValues',
    'testRectangularThreeAndFourScalePreservesEveryNearestAlpha',
    'testCallerOwnsBuffersAcrossRepeatedCallsAndRejectedHeapBuffer'
)
foreach ($testName in $requiredTests) {
    if ($dexText -notmatch [regex]::Escape("$testClass void $testName()")) {
        throw "AndroidTest APK is missing required test method $testName."
    }
}

Write-Host "ANDROID TEST APK CHECK: PASS (AndroidJUnitRunner, $($requiredTests.Count) required tests)"
