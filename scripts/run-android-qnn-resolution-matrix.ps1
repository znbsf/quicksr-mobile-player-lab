[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Leaf })]
    [string]$ApkPath,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^(content|file)://')]
    [string]$VideoUri,

    [string]$CaseId,
    [string]$DeviceSerial,
    [string]$AdbPath,
    [string]$PlanPath = (Join-Path (Split-Path $PSScriptRoot -Parent) 'contracts\android-qnn-resolution-plan.json'),
    [string]$OutputRoot = (Join-Path (Split-Path $PSScriptRoot -Parent) 'device-results\android-qnn-resolution')
)

$ErrorActionPreference = 'Stop'

function Resolve-Adb {
    if ($AdbPath) {
        if (-not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) {
            throw "adb not found at explicit path: $AdbPath"
        }
        return (Resolve-Path -LiteralPath $AdbPath).Path
    }
    $sdkRoot = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { $env:ANDROID_HOME }
    if ($sdkRoot) {
        $sdkAdb = Join-Path $sdkRoot 'platform-tools\adb.exe'
        if (Test-Path -LiteralPath $sdkAdb -PathType Leaf) { return $sdkAdb }
    }
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    throw 'adb was not found; set -AdbPath, ANDROID_SDK_ROOT, or ANDROID_HOME'
}

function Invoke-Adb([string[]]$Arguments) {
    $output = & $script:adb @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed ($LASTEXITCODE): $($Arguments -join ' ')`n$($output -join "`n")"
    }
    return $output
}

function Resolve-Python {
    $python = Get-Command python -ErrorAction SilentlyContinue
    if ($python) { return [pscustomobject]@{ Command = $python.Source; Prefix = @() } }
    $launcher = Get-Command py -ErrorAction SilentlyContinue
    if ($launcher) { return [pscustomobject]@{ Command = $launcher.Source; Prefix = @('-3') } }
    throw 'Python 3 was not found'
}

$planFile = (Resolve-Path -LiteralPath $PlanPath).Path
$plan = Get-Content -LiteralPath $planFile -Raw | ConvertFrom-Json
$script:adb = Resolve-Adb
$deviceLines = @(& $script:adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "`tdevice$" })
if ($DeviceSerial) {
    $deviceLines = @($deviceLines | Where-Object { ($_ -split "`t")[0] -eq $DeviceSerial })
}
if ($deviceLines.Count -ne 1) {
    throw "Exactly one authorized target device is required; found $($deviceLines.Count). Connect the phone or pass -DeviceSerial."
}
$serial = ($deviceLines[0] -split "`t")[0]
$deviceArgs = @('-s', $serial)
$isEmulator = ((Invoke-Adb ($deviceArgs + @('shell', 'getprop', 'ro.kernel.qemu'))) -join '').Trim()
if ($plan.device_requirements.reject_emulator -and $isEmulator -eq '1') {
    throw "The plan requires a physical phone; $serial reports ro.kernel.qemu=1"
}
$abi = ((Invoke-Adb ($deviceArgs + @('shell', 'getprop', 'ro.product.cpu.abi'))) -join '').Trim()
if ($abi -ne $plan.device_requirements.abi) {
    throw "The plan requires ABI $($plan.device_requirements.abi); $serial reports $abi"
}

$apk = (Resolve-Path -LiteralPath $ApkPath).Path
Invoke-Adb ($deviceArgs + @('install', '-r', $apk)) | Out-Null
$cases = @($plan.cases)
if ($CaseId) {
    $cases = @($cases | Where-Object { $_.id -eq $CaseId })
    if ($cases.Count -ne 1) { throw "Unknown or duplicate case id: $CaseId" }
}
$python = Resolve-Python
$pythonCommand = $python.Command
$pythonPrefix = @($python.Prefix)
$validator = Join-Path $PSScriptRoot 'validate_android_qnn_resolution_log.py'
$timestamp = (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss')
$sessionRoot = Join-Path $OutputRoot $timestamp
New-Item -ItemType Directory -Path $sessionRoot -Force | Out-Null
$failed = @()

foreach ($case in $cases) {
    $runId = "$($case.id)_$timestamp"
    $rawLog = Join-Path $sessionRoot "$($case.id).log"
    $report = Join-Path $sessionRoot "$($case.id).json"
    Invoke-Adb ($deviceArgs + @('logcat', '-c')) | Out-Null
    Invoke-Adb ($deviceArgs + @('shell', 'am', 'force-stop', $plan.app_package)) | Out-Null
    $startArguments = $deviceArgs + @(
        'shell', 'am', 'start', '-W', '-a', 'android.intent.action.VIEW',
        '-d', $VideoUri, '-t', 'video/*', '-f', '0x1', '-n', $plan.activity,
        '--es', $plan.intent_extras.run_id, $runId,
        '--es', $plan.intent_extras.video_mode, 'QUICKSR_QNN',
        '--es', $plan.intent_extras.video_profile, $case.profile,
        '--es', $plan.intent_extras.video_tuning, 'SUSTAINED'
    )
    Invoke-Adb $startArguments | Out-Null
    Write-Host "Running $($case.id) on $serial for $($case.run_seconds) seconds..."
    Start-Sleep -Seconds ([int]$case.run_seconds)
    Invoke-Adb ($deviceArgs + @('shell', 'am', 'force-stop', $plan.app_package)) | Out-Null
    $logLines = Invoke-Adb ($deviceArgs + @('logcat', '-d', '-v', 'raw', '-s', "$($plan.telemetry_tag):V", '*:S'))
    $logLines | Set-Content -LiteralPath $rawLog -Encoding UTF8
    & $pythonCommand @pythonPrefix $validator --plan $planFile --case $case.id --run-id $runId --log $rawLog --output $report
    if ($LASTEXITCODE -ne 0) { $failed += $case.id }
}

Write-Host "Raw logs and reports: $sessionRoot"
if ($failed.Count -gt 0) {
    throw "Functional gate failed for: $($failed -join ', '). Performance class is reported separately."
}
