[CmdletBinding()]
param(
    [string]$DeviceSerial,
    [string]$AndroidSdkRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
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
    throw 'Set -AndroidSdkRoot or ANDROID_SDK_ROOT so adb can be located.'
}
$adb = Join-Path $AndroidSdkRoot 'platform-tools/adb.exe'
if (-not (Test-Path -LiteralPath $adb -PathType Leaf)) {
    throw "adb.exe was not found beneath the Android SDK: $AndroidSdkRoot"
}

if ([string]::IsNullOrWhiteSpace($DeviceSerial)) {
    $devices = @(
        & $adb devices |
            Select-String '\tdevice$' |
            ForEach-Object { ($_ -split '\t')[0] }
    )
    if ($devices.Count -ne 1) {
        throw "Expected exactly one authorized device, found $($devices.Count)."
    }
    $DeviceSerial = $devices[0]
}
$deviceArguments = @('-s', $DeviceSerial)
$abi = ((& $adb @deviceArguments shell getprop ro.product.cpu.abi) -join '').Trim()
$qemu = ((& $adb @deviceArguments shell getprop ro.kernel.qemu) -join '').Trim()
if ($abi -ne 'arm64-v8a' -or $qemu -eq '1') {
    throw "Native output-packer tests require a physical arm64-v8a device; got ABI=$abi qemu=$qemu."
}

$appPackage = 'dev.aisystems.quicksrplayerlab'
$testPackage = 'dev.aisystems.quicksrplayerlab.test'
$runner = 'androidx.test.runner.AndroidJUnitRunner'
$component = "$testPackage/$runner"
$className = 'dev.aisystems.quicksrplayerlab.NativeOutputPackerInstrumentedTest'

$mainPath = ((& $adb @deviceArguments shell pm path $appPackage) -join '').Trim()
$testPath = ((& $adb @deviceArguments shell pm path $testPackage) -join '').Trim()
if ($mainPath -notmatch '^package:' -or $testPath -notmatch '^package:') {
    throw 'Install the matching app and corrected AndroidTest APK before running this gate.'
}
$instrumentation = (& $adb @deviceArguments shell pm list instrumentation) -join "`n"
$expectedRegistration =
        "instrumentation:$([regex]::Escape($component)) \(target=$([regex]::Escape($appPackage))\)"
if ($instrumentation -notmatch $expectedRegistration) {
    throw "Corrected AndroidTest APK is not registered with $runner; install it before running this gate."
}

$output = @(
    & $adb @deviceArguments shell am instrument -w -r -e class $className $component 2>&1
)
$exitCode = $LASTEXITCODE
$text = $output -join "`n"
$output | ForEach-Object { Write-Host $_ }
if (($exitCode -ne 0) -or
        ($text -match 'INSTRUMENTATION_FAILED|FAILURES!!!|OK \(0 tests?\)') -or
        ($text -notmatch 'OK \(3 tests\)') -or
        ($text -notmatch 'INSTRUMENTATION_CODE:\s*-1')) {
    throw 'Native output-packer device instrumentation did not complete exactly three passing tests.'
}

Write-Host 'NATIVE OUTPUT PACKER DEVICE TESTS: PASS (3 tests)'
