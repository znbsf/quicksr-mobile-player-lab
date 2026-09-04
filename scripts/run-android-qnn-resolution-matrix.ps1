[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Leaf })]
    [string]$ApkPath,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^(content|file)://')]
    [string]$VideoUri,

    [string]$CaseId,
    [switch]$PrimaryOnly,
    [switch]$SkipInstall,
    [switch]$IncludeExperimental,
    [string]$PrimaryReportPath,
    [string]$MediaRegistrationReceipt,
    [ValidateSet('OFF', 'CONTENT_AWARE_V1')]
    [string]$CadenceMode = 'OFF',
    [ValidateSet('JAVA', 'NATIVE_NEON')]
    [string]$OutputPacker = 'JAVA',
    [int]$CaptureFrame,
    [switch]$CaptureOnly,
    [string]$DeviceSerial,
    [string]$AdbPath,
    [string]$PlanPath,
    [string]$OutputRoot
)

$ErrorActionPreference = 'Stop'
if ($CaptureOnly -and $CadenceMode -ne 'OFF') {
    throw 'CaptureOnly requires CadenceMode OFF so the selected input is inferred.'
}
if ($PrimaryOnly -and ($CaseId -ne '1080p-primary' -or $CaptureOnly)) {
    throw '-PrimaryOnly is restricted to a non-capture -CaseId 1080p-primary run.'
}
$script:scriptDirectory = $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($script:scriptDirectory)) {
    throw 'Could not resolve the runner script directory.'
}
$script:repositoryRoot = (Resolve-Path -LiteralPath (Split-Path $script:scriptDirectory -Parent)).Path
if ([string]::IsNullOrWhiteSpace($PlanPath)) {
    $PlanPath = Join-Path $script:repositoryRoot 'contracts\android-qnn-resolution-plan.json'
}
if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $script:repositoryRoot 'device-results\android-qnn-resolution'
}
$script:videoUriForRedaction = $VideoUri

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

function Resolve-OutputRoot {
    param([Parameter(Mandatory = $true)][string]$Candidate)

    $repositoryRoot = $script:repositoryRoot
    $permittedRoot = Join-Path $repositoryRoot 'device-results'
    $candidateFullPath = [IO.Path]::GetFullPath($Candidate)
    $permittedFullPath = [IO.Path]::GetFullPath($permittedRoot).TrimEnd(
        [IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    $permittedPrefix = $permittedFullPath + [IO.Path]::DirectorySeparatorChar
    if (-not $candidateFullPath.StartsWith($permittedPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "OutputRoot must stay beneath the Git-ignored device-results directory: $permittedRoot"
    }

    # Create only after the lexical containment check, then resolve again to reject
    # an existing junction/symlink that points out of device-results.
    if (-not (Test-Path -LiteralPath $permittedRoot -PathType Container)) {
        New-Item -ItemType Directory -Path $permittedRoot -Force | Out-Null
    }
    $resolvedRepositoryRoot = (Resolve-Path -LiteralPath $repositoryRoot).Path.TrimEnd(
        [IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    $resolvedPermittedRoot = (Resolve-Path -LiteralPath $permittedRoot).Path.TrimEnd(
        [IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (-not $resolvedPermittedRoot.StartsWith($resolvedRepositoryRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "device-results resolved outside the repository root: $permittedRoot"
    }

    # Before creating a descendant, resolve its nearest existing ancestor. This
    # prevents New-Item from following an existing junction out of device-results.
    $existingAncestor = $candidateFullPath
    while (-not (Test-Path -LiteralPath $existingAncestor)) {
        $parent = Split-Path -Path $existingAncestor -Parent
        if ([string]::IsNullOrWhiteSpace($parent) -or $parent -eq $existingAncestor) {
            throw "Could not resolve an existing OutputRoot ancestor: $Candidate"
        }
        $existingAncestor = $parent
    }
    if (-not (Test-Path -LiteralPath $existingAncestor -PathType Container)) {
        throw "OutputRoot ancestor is not a directory: $existingAncestor"
    }
    $resolvedAncestor = (Resolve-Path -LiteralPath $existingAncestor).Path.TrimEnd(
        [IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (-not $resolvedAncestor.StartsWith($resolvedPermittedRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "OutputRoot must stay beneath the Git-ignored device-results directory: $permittedRoot"
    }
    if (-not (Test-Path -LiteralPath $candidateFullPath -PathType Container)) {
        New-Item -ItemType Directory -Path $candidateFullPath -Force | Out-Null
    }
    $resolvedCandidate = (Resolve-Path -LiteralPath $candidateFullPath).Path
    if (-not (($resolvedCandidate.TrimEnd(
                    [IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar) +
                [IO.Path]::DirectorySeparatorChar).StartsWith(
                    $resolvedPermittedRoot, [StringComparison]::OrdinalIgnoreCase))) {
        throw "OutputRoot must stay beneath the Git-ignored device-results directory: $permittedRoot"
    }
    return $resolvedCandidate
}

function Get-FailureMessage([object]$Failure) {
    $message = $null
    if ($Failure -is [System.Management.Automation.ErrorRecord]) {
        $message = $Failure.Exception.Message
    } elseif ($null -eq $Failure) {
        $message = 'Unknown failure'
    } else {
        $message = [string]$Failure
    }
    if (-not [string]::IsNullOrEmpty($script:videoUriForRedaction)) {
        return $message.Replace($script:videoUriForRedaction, '<VIDEO_URI_REDACTED>')
    }
    return $message
}

function Get-FailureType([object]$Failure) {
    if ($Failure -is [System.Management.Automation.ErrorRecord]) {
        return $Failure.Exception.GetType().FullName
    }
    if ($null -eq $Failure) { return 'Unknown' }
    return $Failure.GetType().FullName
}

function Save-CaseRawLog {
    param(
        [Parameter(Mandatory = $true)][string[]]$TargetDeviceArguments,
        [Parameter(Mandatory = $true)][string]$RawLogPath,
        [Parameter(Mandatory = $true)][string]$TelemetryTag,
        [switch]$Append,
        [switch]$ClearAfterCapture
    )

    try {
        $captureArguments = $TargetDeviceArguments + @(
            'logcat', '-d', '-v', 'raw', '-s', "${TelemetryTag}:V", '*:S'
        )
        $lines = & $script:adb @captureArguments 2>&1
        $exitCode = $LASTEXITCODE
        # A valid empty logcat response must still materialize an empty raw-log
        # file so the validator can fail on missing telemetry rather than losing
        # the evidence artifact at the host boundary.
        $rawLogText = (@($lines) -join [Environment]::NewLine)
        $encoding = [Text.UTF8Encoding]::new($false)
        if ($Append) {
            if (-not (Test-Path -LiteralPath $RawLogPath -PathType Leaf)) {
                [IO.File]::WriteAllText($RawLogPath, '', $encoding)
            }
            if (-not [string]::IsNullOrEmpty($rawLogText)) {
                [IO.File]::AppendAllText($RawLogPath, $rawLogText + [Environment]::NewLine, $encoding)
            }
        } else {
            [IO.File]::WriteAllText($RawLogPath, $rawLogText, $encoding)
        }
        if ($exitCode -ne 0) {
            Add-Content -LiteralPath $RawLogPath -Encoding UTF8 -Value (
                "raw log capture failed: adb logcat exited $exitCode"
            )
            return "CAPTURE_FAILED_EXIT_$exitCode"
        }
        if ($ClearAfterCapture) {
            $clearArguments = $TargetDeviceArguments + @('logcat', '-c')
            $clearOutput = & $script:adb @clearArguments 2>&1
            $clearExitCode = $LASTEXITCODE
            if ($clearExitCode -ne 0) {
                @($clearOutput) | Add-Content -LiteralPath $RawLogPath -Encoding UTF8
                Add-Content -LiteralPath $RawLogPath -Encoding UTF8 -Value (
                    "raw log clear failed: adb logcat exited $clearExitCode"
                )
                return "CAPTURE_FAILED_CLEAR_$clearExitCode"
            }
        }
        return 'CAPTURED'
    } catch {
        $message = Get-FailureMessage $_
        try {
            if ($Append) {
                Add-Content -LiteralPath $RawLogPath -Encoding UTF8 -Value (
                    "raw log capture exception: $message"
                )
            } else {
                @("raw log capture exception: $message") |
                    Set-Content -LiteralPath $RawLogPath -Encoding UTF8
            }
        } catch {
            # The caller still writes a failure pointer if the host filesystem is unavailable.
        }
        return 'CAPTURE_FAILED_EXCEPTION'
    }
}

function Initialize-CaseRawLog {
    param([Parameter(Mandatory = $true)][string]$RawLogPath)

    if (Test-Path -LiteralPath $RawLogPath) {
        throw "Refusing to overwrite existing raw log: $RawLogPath"
    }
    [IO.File]::WriteAllText($RawLogPath, '', [Text.UTF8Encoding]::new($false))
}

function Wait-WithTelemetryDrains {
    param(
        [Parameter(Mandatory = $true)][string[]]$TargetDeviceArguments,
        [Parameter(Mandatory = $true)][string]$RawLogPath,
        [Parameter(Mandatory = $true)][string]$TelemetryTag,
        [Parameter(Mandatory = $true)][int]$RunSeconds,
        [int]$DrainIntervalSeconds = 5
    )

    if ($RunSeconds -le 0 -or $DrainIntervalSeconds -le 0) {
        throw 'Telemetry drain duration and interval must be positive.'
    }
    # Stream continuously instead of alternating `logcat -d` and `logcat -c`. The old
    # dump-then-clear sequence had an unavoidable gap where complete frame events written after
    # the dump but before the clear were lost. Start after the case-specific logcat clear; logcat
    # first drains the already-buffered configuration event and then follows new events.
    $stderrPath = "$RawLogPath.stderr.log"
    $captureArguments = $TargetDeviceArguments + @(
        'logcat', '-v', 'raw', '-s', "${TelemetryTag}:V", '*:S'
    )
    try {
        $capture = Start-Process -FilePath $script:adb -ArgumentList $captureArguments `
            -RedirectStandardOutput $RawLogPath -RedirectStandardError $stderrPath `
            -PassThru -WindowStyle Hidden
        $remainingSeconds = $RunSeconds
        while ($remainingSeconds -gt 0) {
            $sleepSeconds = [Math]::Min($DrainIntervalSeconds, $remainingSeconds)
            Start-Sleep -Seconds $sleepSeconds
            if ($capture.HasExited) {
                return "CAPTURE_FAILED_EXIT_$($capture.ExitCode)"
            }
            $remainingSeconds -= $sleepSeconds
        }
    } catch {
        Add-Content -LiteralPath $RawLogPath -Encoding UTF8 -Value (
            "raw log streaming exception: $(Get-FailureMessage $_)"
        )
        return 'CAPTURE_FAILED_EXCEPTION'
    } finally {
        if ($null -ne $capture -and -not $capture.HasExited) {
            $capture.Kill()
            $capture.WaitForExit()
        }
    }
    if ((Test-Path -LiteralPath $stderrPath -PathType Leaf) -and
            (Get-Item -LiteralPath $stderrPath).Length -gt 0) {
        Get-Content -LiteralPath $stderrPath | Add-Content -LiteralPath $RawLogPath -Encoding UTF8
        return 'CAPTURE_FAILED_STDERR'
    }
    return 'CAPTURED'
}

function Stop-AppBestEffort {
    param(
        [Parameter(Mandatory = $true)][string[]]$TargetDeviceArguments,
        [Parameter(Mandatory = $true)][string]$AppPackage
    )

    try {
        $stopArguments = $TargetDeviceArguments + @('shell', 'am', 'force-stop', $AppPackage)
        $null = & $script:adb @stopArguments 2>&1
    } catch {
        # Do not replace the phase failure that triggered cleanup.
    }
}

function New-UnboundMediaRegistration {
    return [pscustomobject]@{
        status = 'UNBOUND'
        clip_id = $null
        clip_file = $null
        clip_sha256 = $null
        clip_frame_count = $null
        receipt_sha256 = $null
        source_manifest_sha256 = $null
    }
}

function New-InvalidMediaRegistration {
    return [pscustomobject]@{
        status = 'INVALID'
        clip_id = $null
        clip_file = $null
        clip_sha256 = $null
        clip_frame_count = $null
        receipt_sha256 = $null
        source_manifest_sha256 = $null
    }
}

function Get-MediaRegistrationBinding {
    param(
        [Parameter(Mandatory = $true)][string]$ReceiptPath,
        [Parameter(Mandatory = $true)][string]$RequestedVideoUri
    )

    if ([string]::IsNullOrWhiteSpace($ReceiptPath)) {
        throw '-MediaRegistrationReceipt cannot be empty when supplied.'
    }
    if (-not (Test-Path -LiteralPath $ReceiptPath -PathType Leaf)) {
        throw 'Media registration receipt does not exist or is not a file.'
    }
    $receiptFile = (Resolve-Path -LiteralPath $ReceiptPath).Path
    $receiptSha256 = (Get-FileHash -LiteralPath $receiptFile -Algorithm SHA256).Hash.ToLowerInvariant()
    try {
        $receipt = Get-Content -LiteralPath $receiptFile -Raw | ConvertFrom-Json
    } catch {
        throw "Media registration receipt is not valid JSON. $(Get-FailureMessage $_)"
    }

    if ([string]$receipt.schema_version -ne '1' -or
            [string]$receipt.kind -ne 'android-mobile-subset-media-registration' -or
            [string]$receipt.status -ne 'PASS') {
        throw 'Media registration receipt does not satisfy the required PASS schema.'
    }
    $clip = $receipt.clip
    if ($null -eq $clip) {
        throw 'Media registration receipt has no clip record.'
    }
    $clipId = [string]$clip.id
    $clipFile = [string]$clip.file
    $clipSha256 = ([string]$clip.sha256).ToLowerInvariant()
    $sourceManifestSha256 = ([string]$clip.sourceManifestSha256).ToLowerInvariant()
    if ([string]::IsNullOrWhiteSpace($clipId) -or $clipId -notmatch '^[A-Za-z0-9._-]+$') {
        throw 'Media registration receipt has an unsafe clip id.'
    }
    if ([string]::IsNullOrWhiteSpace($clipFile) -or [IO.Path]::GetFileName($clipFile) -ne $clipFile -or
            $clipFile -notmatch '^[A-Za-z0-9._-]+$') {
        throw 'Media registration receipt has an unsafe clip file name.'
    }
    if ($clipSha256 -notmatch '^[0-9a-f]{64}$' -or $sourceManifestSha256 -notmatch '^[0-9a-f]{64}$') {
        throw 'Media registration receipt has an invalid clip or source-manifest SHA-256.'
    }
    try {
        $clipBytes = [Convert]::ToInt64($clip.bytes)
    } catch {
        throw 'Media registration receipt has an invalid clip byte count.'
    }
    if ($clipBytes -le 0) {
        throw 'Media registration receipt has a non-positive clip byte count.'
    }
    $clipFrameCount = $null
    if ($clip.PSObject.Properties.Name -contains 'frameCount') {
        try {
            $candidateFrameCount = [Convert]::ToInt64($clip.frameCount)
        } catch {
            throw 'Media registration receipt has an invalid clip frame count.'
        }
        if ($candidateFrameCount -le 0) {
            throw 'Media registration receipt has a non-positive clip frame count.'
        }
        $clipFrameCount = $candidateFrameCount
    }

    $receiptUri = [string]$receipt.mediaStoreUri
    if ($receiptUri -notmatch '^content://media/external/video/media/[0-9]+$') {
        throw 'Media registration receipt has an invalid MediaStore video URI.'
    }
    if (-not [string]::Equals($receiptUri, $RequestedVideoUri, [StringComparison]::Ordinal)) {
        throw 'Media registration receipt MediaStore URI does not exactly match -VideoUri.'
    }
    if ($null -eq $receipt.remote -or
            [string]$receipt.remote.directory -ne "QuickSRBenchmark/$clipSha256" -or
            $receipt.remote.serialCaptured -ne $false) {
        throw 'Media registration receipt remote registration fields are invalid.'
    }

    return [pscustomobject]@{
        status = 'BOUND'
        clip_id = $clipId
        clip_file = $clipFile
        clip_sha256 = $clipSha256
        clip_frame_count = $clipFrameCount
        receipt_sha256 = $receiptSha256
        source_manifest_sha256 = $sourceManifestSha256
    }
}

function Test-BoundRemoteMedia {
    param(
        [Parameter(Mandatory = $true)][string[]]$TargetDeviceArguments,
        [Parameter(Mandatory = $true)][object]$MediaRegistration
    )

    if ([string]$MediaRegistration.status -ne 'BOUND' -or
            [string]::IsNullOrWhiteSpace([string]$MediaRegistration.clip_file) -or
            [string]$MediaRegistration.clip_file -notmatch '^[A-Za-z0-9._-]+$') {
        throw 'Bound media registration lacks a safe controlled clip file name.'
    }
    $remotePath = "/sdcard/Movies/QuickSRBenchmark/$($MediaRegistration.clip_sha256)/$($MediaRegistration.clip_file)"
    $observed = (Invoke-Adb ($TargetDeviceArguments + @('shell', 'sha256sum', $remotePath))) -join "`n"
    $match = [regex]::Match($observed, '^(?<hash>[0-9A-Fa-f]{64})\s+')
    if (-not $match.Success -or
            -not [string]::Equals($match.Groups['hash'].Value, [string]$MediaRegistration.clip_sha256,
                [StringComparison]::OrdinalIgnoreCase)) {
        throw 'The controlled remote media no longer matches the bound registration hash.'
    }
}

function Set-ReportMediaRegistration {
    param(
        [Parameter(Mandatory = $true)][string]$ReportPath,
        [Parameter(Mandatory = $true)][object]$MediaRegistration
    )

    $report = Get-Content -LiteralPath $ReportPath -Raw | ConvertFrom-Json
    # Do not write the MediaStore URI or receipt path into a benchmark report.
    $safeRegistration = [pscustomobject]@{
        status = [string]$MediaRegistration.status
        clip_id = $MediaRegistration.clip_id
        clip_sha256 = $MediaRegistration.clip_sha256
        clip_frame_count = $MediaRegistration.clip_frame_count
        receipt_sha256 = $MediaRegistration.receipt_sha256
        source_manifest_sha256 = $MediaRegistration.source_manifest_sha256
    }
    $report | Add-Member -Force -NotePropertyName 'media_registration' -NotePropertyValue $safeRegistration
    $report | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $ReportPath -Encoding UTF8
    return $report
}

function Write-CaseFailureArtifact {
    param(
        [Parameter(Mandatory = $true)][object]$Plan,
        [Parameter(Mandatory = $true)][object]$Case,
        [Parameter(Mandatory = $true)][string]$RunId,
        [Parameter(Mandatory = $true)][string]$Phase,
        [Parameter(Mandatory = $true)][object]$Failure,
        [Parameter(Mandatory = $true)][string]$RawLogPath,
        [Parameter(Mandatory = $true)][string]$RawLogCaptureStatus,
        [Parameter(Mandatory = $true)][string]$ReportPath,
        [Parameter(Mandatory = $true)][string]$PlanSha256,
        [Parameter(Mandatory = $true)][object]$MediaRegistration
    )

    $reportExists = Test-Path -LiteralPath $ReportPath -PathType Leaf
    $reportMediaRegistrationStatus = if ($reportExists) { 'ATTACHED' } else { 'NOT_APPLICABLE' }
    if ($reportExists) {
        try {
            Set-ReportMediaRegistration -ReportPath $ReportPath -MediaRegistration $MediaRegistration | Out-Null
        } catch {
            # The failure pointer below still carries the redacted binding record.
            $reportMediaRegistrationStatus = 'ATTACH_FAILED'
        }
    }
    $artifactPath = [System.IO.Path]::ChangeExtension($ReportPath, 'failure-pointer.json')
    $artifact = [ordered]@{
        schema_version = 2
        validator_version = 'runner-failure-v1'
        plan_id = [string]$Plan.plan_id
        plan_sha256 = $PlanSha256
        case_id = [string]$Case.id
        run_id = $RunId
        functional_gate = 'FAIL'
        performance_class = 'unclassified'
        failure_phase = $Phase
        failure_type = Get-FailureType $Failure
        failures = @((Get-FailureMessage $Failure))
        raw_log = [System.IO.Path]::GetFileName($RawLogPath)
        raw_log_capture_status = $RawLogCaptureStatus
        report_pointer = if ($reportExists) { [System.IO.Path]::GetFileName($ReportPath) } else { $null }
        media_registration = $MediaRegistration
        report_media_registration_status = $reportMediaRegistrationStatus
        generated_at_utc = (Get-Date).ToUniversalTime().ToString('o')
    }
    $artifact | ConvertTo-Json -Depth 6 |
        Set-Content -LiteralPath $artifactPath -Encoding UTF8
    return $artifactPath
}

function Get-CaptureOnlyObservation {
    param(
        [Parameter(Mandatory = $true)][string]$RawLogPath,
        [Parameter(Mandatory = $true)][string]$RunId,
        [Parameter(Mandatory = $true)][int]$CaptureFrame
    )

    if (-not (Test-Path -LiteralPath $RawLogPath -PathType Leaf)) {
        return [pscustomobject]@{ status = 'FAIL'; failure = 'capture-only raw log is missing' }
    }
    $captureEvents = @()
    foreach ($line in [IO.File]::ReadLines($RawLogPath)) {
        $jsonStart = $line.IndexOf('{')
        if ($jsonStart -lt 0) {
            continue
        }
        try {
            $event = $line.Substring($jsonStart) | ConvertFrom-Json -ErrorAction Stop
        } catch {
            continue
        }
        if ([string]$event.event -eq 'evidence_capture' -and [string]$event.runId -eq $RunId) {
            $captureEvents += $event
        }
    }
    if ($captureEvents.Count -ne 1) {
        return [pscustomobject]@{
            status = 'FAIL'
            failure = "expected exactly one evidence_capture event, found $($captureEvents.Count)"
        }
    }
    $evidence = $captureEvents[0].evidence
    if ($null -eq $evidence -or [string]$evidence.runId -ne $RunId) {
        return [pscustomobject]@{ status = 'FAIL'; failure = 'capture event does not carry the requested evidence run id' }
    }
    if ($null -eq $evidence.capture -or $null -eq $evidence.capture.selector -or
            [string]$evidence.capture.selector.kind -ne 'frame' -or
            [int]$evidence.capture.selector.value -ne $CaptureFrame) {
        return [pscustomobject]@{ status = 'FAIL'; failure = 'capture event selector does not match -CaptureFrame' }
    }
    return [pscustomobject]@{ status = 'CAPTURED'; failure = $null }
}

function Write-CaptureOnlyPointer {
    param(
        [Parameter(Mandatory = $true)][object]$Plan,
        [Parameter(Mandatory = $true)][object]$Case,
        [Parameter(Mandatory = $true)][string]$RunId,
        [Parameter(Mandatory = $true)][int]$CaptureFrame,
        [Parameter(Mandatory = $true)][string]$RawLogPath,
        [Parameter(Mandatory = $true)][string]$RawLogCaptureStatus,
        [Parameter(Mandatory = $true)][object]$MediaRegistration,
        [Parameter(Mandatory = $true)][string]$PlanSha256,
        [Parameter(Mandatory = $true)][string]$PointerPath,
        [Parameter(Mandatory = $true)][string]$CaptureStatus,
        [object]$Failure
    )

    $rawLogSha256 = if (Test-Path -LiteralPath $RawLogPath -PathType Leaf) {
        (Get-FileHash -LiteralPath $RawLogPath -Algorithm SHA256).Hash.ToLowerInvariant()
    } else {
        $null
    }
    $safeRegistration = [ordered]@{
        status = [string]$MediaRegistration.status
        clip_id = $MediaRegistration.clip_id
        clip_sha256 = $MediaRegistration.clip_sha256
        clip_frame_count = $MediaRegistration.clip_frame_count
        receipt_sha256 = $MediaRegistration.receipt_sha256
        source_manifest_sha256 = $MediaRegistration.source_manifest_sha256
    }
    $pointer = [ordered]@{
        schema_version = 1
        kind = 'android-qnn-capture-only-pointer'
        plan_id = [string]$Plan.plan_id
        plan_sha256 = $PlanSha256
        case_id = [string]$Case.id
        run_id = $RunId
        capture_frame = $CaptureFrame
        capture_status = $CaptureStatus
        functional_gate = 'NOT_EVALUATED'
        performance_class = 'NOT_EVALUATED'
        expected_evidence_relative_directory = "video-evaluations/$RunId"
        raw_log = [IO.Path]::GetFileName($RawLogPath)
        raw_log_sha256 = $rawLogSha256
        raw_log_capture_status = $RawLogCaptureStatus
        media_registration = $safeRegistration
        failure = if ($null -eq $Failure) { $null } else { Get-FailureMessage $Failure }
        generated_at_utc = (Get-Date).ToUniversalTime().ToString('o')
    }
    $pointer | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $PointerPath -Encoding UTF8
    return $PointerPath
}

function Test-PrimaryReport {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][object]$Plan,
        [Parameter(Mandatory = $true)][string]$PlanSha256,
        [Parameter(Mandatory = $true)][object]$MediaRegistration
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "1080p prerequisite report is missing: $Path"
    }
    if ([string]$MediaRegistration.status -ne 'BOUND') {
        throw '1080p prerequisite validation requires a BOUND media registration.'
    }
    try {
        $report = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
    } catch {
        throw "1080p prerequisite report is not valid JSON: $Path. $(Get-FailureMessage $_)"
    }
    $required = [ordered]@{
        schema_version = '3'
        validator_version = 'android-qnn-resolution-validator-v3'
        plan_id = [string]$Plan.plan_id
        plan_sha256 = $PlanSha256
        case_id = '1080p-primary'
        functional_gate = 'PASS'
    }
    foreach ($field in $required.Keys) {
        if (-not ($report.PSObject.Properties.Name -contains $field)) {
            throw "1080p prerequisite report is missing required field '$field': $Path"
        }
        if ([string]$report.$field -ne [string]$required[$field]) {
            throw "1080p prerequisite report field '$field' is not valid: expected '$($required[$field])', got '$($report.$field)'"
        }
    }
    if (-not ($report.PSObject.Properties.Name -contains 'raw_log_sha256') -or
            [string]$report.raw_log_sha256 -notmatch '^[0-9a-f]{64}$') {
        throw "1080p prerequisite report lacks a validator-recorded raw log SHA-256: $Path"
    }
    $reportFailures = @($report.failures | Where-Object {
            $null -ne $_ -and -not [string]::IsNullOrWhiteSpace([string]$_)
        })
    if ($reportFailures.Count -ne 0) {
        throw "1080p prerequisite report has recorded failures: $($reportFailures -join '; ')"
    }
    if (-not ($report.PSObject.Properties.Name -contains 'media_registration')) {
        throw "1080p prerequisite report is missing media registration evidence: $Path"
    }
    $reportMediaRegistration = $report.media_registration
    if ($null -eq $reportMediaRegistration -or [string]$reportMediaRegistration.status -ne 'BOUND') {
        throw "1080p prerequisite report does not carry a BOUND media registration: $Path"
    }
    foreach ($field in @('status', 'clip_id', 'clip_sha256', 'receipt_sha256', 'source_manifest_sha256')) {
        if ($null -eq $reportMediaRegistration -or
                -not ($reportMediaRegistration.PSObject.Properties.Name -contains $field) -or
                [string]$reportMediaRegistration.$field -ne [string]$MediaRegistration.$field) {
            throw "1080p prerequisite report media registration '$field' does not match this invocation."
        }
    }
    return $report
}

function Get-RequiredFunctionalPasses([object]$Case) {
    if ($Case.PSObject.Properties.Name -contains 'requires_functional_pass') {
        return @($Case.requires_functional_pass)
    }
    return @()
}

$planFile = (Resolve-Path -LiteralPath $PlanPath).Path
$plan = Get-Content -LiteralPath $planFile -Raw | ConvertFrom-Json
$planSha256 = (Get-FileHash -LiteralPath $planFile -Algorithm SHA256).Hash.ToLowerInvariant()
$outputRoot = Resolve-OutputRoot $OutputRoot
$timestamp = (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss')
$sessionId = "$timestamp-$([guid]::NewGuid().ToString('N').Substring(0, 8))"
$sessionRoot = Join-Path $outputRoot $sessionId
New-Item -ItemType Directory -Path $sessionRoot -ErrorAction Stop | Out-Null

$allCases = @($plan.cases)
$caseById = @{}
foreach ($candidate in $allCases) {
    if ([string]::IsNullOrWhiteSpace([string]$candidate.id) -or $caseById.ContainsKey($candidate.id)) {
        throw "The plan has a missing or duplicate case id: $($candidate.id)"
    }
    $caseById[$candidate.id] = $candidate
}
$baselineId = '720p-baseline'
$primaryId = '1080p-primary'
$experimentalIds = @('1440p-experimental', '4k-display-fallback')
foreach ($requiredCaseId in (@($baselineId, $primaryId) + $experimentalIds)) {
    if (-not $caseById.ContainsKey($requiredCaseId)) {
        throw "The plan is missing required case '$requiredCaseId'"
    }
}

if ($CaptureOnly) {
    if ($IncludeExperimental) {
        throw '-CaptureOnly cannot be combined with -IncludeExperimental.'
    }
    if ($CaseId -ne $primaryId) {
        throw "-CaptureOnly requires -CaseId $primaryId."
    }
    if ([string]::IsNullOrWhiteSpace($PrimaryReportPath)) {
        throw "-CaptureOnly requires -PrimaryReportPath for a verified $primaryId PASS."
    }
    if (-not $PSBoundParameters.ContainsKey('CaptureFrame')) {
        throw '-CaptureOnly requires -CaptureFrame.'
    }
    $cases = @($caseById[$primaryId])
} elseif ($CaseId) {
    if (-not $caseById.ContainsKey($CaseId)) {
        throw "Unknown case id: $CaseId"
    }
    if ($CaseId -eq $baselineId) {
        $cases = @($caseById[$baselineId])
    } elseif ($CaseId -eq $primaryId) {
        if (-not $PrimaryOnly) {
            throw "-CaseId $primaryId requires -PrimaryOnly for an explicit isolated optimization run."
        }
        $cases = @($caseById[$primaryId])
    } elseif ($experimentalIds -contains $CaseId) {
        if (-not $IncludeExperimental) {
            throw "-CaseId $CaseId requires -IncludeExperimental and a verified $primaryId report."
        }
        if ([string]::IsNullOrWhiteSpace($PrimaryReportPath)) {
            throw "-CaseId $CaseId requires -PrimaryReportPath for a verified $primaryId PASS."
        }
        $cases = @($caseById[$CaseId])
    } else {
        throw "-CaseId is permitted only for $baselineId or an explicitly gated experimental case."
    }
} else {
    $cases = @($caseById[$baselineId], $caseById[$primaryId])
    if ($IncludeExperimental) {
        $cases += @($experimentalIds | ForEach-Object { $caseById[$_] })
    }
}

$externalPrerequisiteReports = @{}
$mediaRegistration = New-InvalidMediaRegistration
$captureFrameRequested = $PSBoundParameters.ContainsKey('CaptureFrame')
$deviceArgs = $null
try {
    if (-not $PSBoundParameters.ContainsKey('MediaRegistrationReceipt') -or
            [string]::IsNullOrWhiteSpace($MediaRegistrationReceipt)) {
        throw 'All Android matrix and capture-only invocations require -MediaRegistrationReceipt with a valid BOUND registration.'
    }
    $mediaRegistration = Get-MediaRegistrationBinding -ReceiptPath $MediaRegistrationReceipt `
        -RequestedVideoUri $VideoUri
    if ([string]$mediaRegistration.status -ne 'BOUND') {
        throw 'Media registration did not establish the required BOUND status.'
    }
    if ($captureFrameRequested -and -not $CaptureOnly) {
        throw '-CaptureFrame is permitted only with -CaptureOnly; synchronous evidence capture would contaminate a performance matrix.'
    }
    if ($CaptureOnly) {
        if ($null -eq $mediaRegistration.clip_frame_count) {
            throw '-CaptureOnly requires a media registration receipt with a positive clip.frameCount. Re-register the controlled clip with the current push script.'
        }
        if ($CaptureFrame -lt 1 -or $CaptureFrame -gt [Int64]$mediaRegistration.clip_frame_count) {
            throw "-CaptureFrame must be within the registered clip frames 1..$($mediaRegistration.clip_frame_count) for -CaptureOnly."
        }
    }
    if ($CaptureOnly) {
        $externalPrerequisiteReports[$primaryId] = Test-PrimaryReport -Path $PrimaryReportPath `
            -Plan $plan -PlanSha256 $planSha256 -MediaRegistration $mediaRegistration
    }
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
    $socManufacturer = ((Invoke-Adb ($deviceArgs + @('shell', 'getprop', 'ro.soc.manufacturer'))) -join '').Trim()
    if ($socManufacturer -notmatch '^(QTI|Qualcomm)$') {
        throw 'The plan requires a physical Qualcomm target.'
    }
    Test-BoundRemoteMedia -TargetDeviceArguments $deviceArgs -MediaRegistration $mediaRegistration

    $apk = (Resolve-Path -LiteralPath $ApkPath).Path
    if ($SkipInstall) {
        $installedPath = ((Invoke-Adb ($deviceArgs + @(
                    'shell', 'pm', 'path', $plan.app_package))) -join '').Trim()
        if ($installedPath -notmatch '^package:') {
            throw '-SkipInstall requested but the benchmark package is not installed.'
        }
    } else {
        Invoke-Adb ($deviceArgs + @('install', '-r', $apk)) | Out-Null
    }
    $python = Resolve-Python
    $pythonCommand = $python.Command
    $pythonPrefix = @($python.Prefix)
    $validator = Join-Path $script:scriptDirectory 'validate_android_qnn_resolution_log.py'
    if ($CaseId -and ($experimentalIds -contains $CaseId)) {
        $externalPrerequisiteReports[$primaryId] = Test-PrimaryReport -Path $PrimaryReportPath `
            -Plan $plan -PlanSha256 $planSha256 -MediaRegistration $mediaRegistration
    }
} catch {
    $preflightCase = [pscustomobject]@{ id = 'preflight' }
    $preflightLog = Join-Path $sessionRoot 'preflight.log'
    if ($null -ne $deviceArgs) {
        $captureStatus = Save-CaseRawLog -TargetDeviceArguments $deviceArgs -RawLogPath $preflightLog -TelemetryTag $plan.telemetry_tag
    } else {
        @("preflight raw log unavailable: $(Get-FailureMessage $_)") |
            Set-Content -LiteralPath $preflightLog -Encoding UTF8
        $captureStatus = 'UNAVAILABLE_NO_TARGET_DEVICE'
    }
    if ($CaptureOnly) {
        $preflightPointer = Join-Path $sessionRoot 'preflight.capture-only.json'
        Write-CaptureOnlyPointer -Plan $plan -Case $preflightCase -RunId "preflight_$sessionId" `
            -CaptureFrame $CaptureFrame -RawLogPath $preflightLog -RawLogCaptureStatus $captureStatus `
            -MediaRegistration $mediaRegistration -PlanSha256 $planSha256 -PointerPath $preflightPointer `
            -CaptureStatus 'FAIL' -Failure $_ | Out-Null
    } else {
        $preflightReport = Join-Path $sessionRoot 'preflight.json'
        Write-CaseFailureArtifact -Plan $plan -Case $preflightCase -RunId "preflight_$sessionId" `
            -Phase 'preflight' -Failure $_ -RawLogPath $preflightLog -RawLogCaptureStatus $captureStatus `
            -ReportPath $preflightReport -PlanSha256 $planSha256 -MediaRegistration $mediaRegistration | Out-Null
    }
    throw
}

$completedReports = @{}
$failed = @()
foreach ($case in $cases) {
    $runId = "$($case.id)_$sessionId"
    $rawLog = Join-Path $sessionRoot "$($case.id).log"
    $report = Join-Path $sessionRoot "$($case.id).json"
    $capturePointer = Join-Path $sessionRoot "$($case.id).capture-only.json"
    $phase = 'prerequisite'
    $rawLogCaptureStatus = 'NOT_ATTEMPTED'
    $periodicDrainStatus = 'NOT_STARTED'
    $caseSucceeded = $false
    $caseFailure = $null

    try {
        foreach ($requiredCaseId in (Get-RequiredFunctionalPasses $case)) {
            if ($completedReports.ContainsKey($requiredCaseId)) {
                Test-PrimaryReport -Path $completedReports[$requiredCaseId] -Plan $plan -PlanSha256 $planSha256 `
                    -MediaRegistration $mediaRegistration | Out-Null
            } elseif ($externalPrerequisiteReports.ContainsKey($requiredCaseId)) {
                # It was parsed and checked before any experimental case starts.
                $null = $externalPrerequisiteReports[$requiredCaseId]
            } else {
                throw "Case $($case.id) requires a verified functional PASS report for $requiredCaseId."
            }
        }

        $phase = 'logcat-clear'
        Invoke-Adb ($deviceArgs + @('logcat', '-c')) | Out-Null
        Initialize-CaseRawLog -RawLogPath $rawLog
        $phase = 'force-stop-before-start'
        Invoke-Adb ($deviceArgs + @('shell', 'am', 'force-stop', $plan.app_package)) | Out-Null
        $phase = 'start'
        $startArguments = $deviceArgs + @(
            'shell', 'am', 'start', '-W', '-a', 'android.intent.action.VIEW',
            '-d', $VideoUri, '-t', 'video/*', '-f', '0x1', '-n', $plan.activity,
            '--es', $plan.intent_extras.run_id, $runId,
            '--es', $plan.intent_extras.video_mode, 'QUICKSR_QNN',
            '--es', $plan.intent_extras.video_profile, $case.profile,
            '--es', $plan.intent_extras.video_tuning, 'SUSTAINED',
            '--es', 'dev.aisystems.quicksrplayerlab.extra.CADENCE_MODE', $CadenceMode,
            '--es', 'dev.aisystems.quicksrplayerlab.extra.OUTPUT_PACKER', $OutputPacker
        )
        if ($captureFrameRequested) {
            # Capture-only has a separate non-performance contract and may select any
            # registered frame after a matching primary functional PASS.
            $startArguments += @(
                '--ei', $plan.intent_extras.capture_frame, [string]$CaptureFrame
            )
        }
        $startOutput = Invoke-Adb $startArguments
        if ((@($startOutput) -join [Environment]::NewLine) -notmatch '(?m)^Status:\s*ok\s*$') {
            throw 'am start did not report Status: ok for the benchmark Activity.'
        }
        $phase = 'wait-and-periodic-telemetry-drain'
        Write-Host "Running $($case.id) on $serial for $($case.run_seconds) seconds..."
        $periodicDrainStatus = Wait-WithTelemetryDrains -TargetDeviceArguments $deviceArgs -RawLogPath $rawLog `
            -TelemetryTag $plan.telemetry_tag -RunSeconds ([int]$case.run_seconds)
        if ($periodicDrainStatus -ne 'CAPTURED') {
            $rawLogCaptureStatus = $periodicDrainStatus
            throw "Periodic telemetry drain did not complete: $periodicDrainStatus"
        }
        $phase = 'force-stop-after-wait'
        Invoke-Adb ($deviceArgs + @('shell', 'am', 'force-stop', $plan.app_package)) | Out-Null
        $phase = 'extract'
        $rawLogCaptureStatus = $periodicDrainStatus
        if ($CaptureOnly) {
            $phase = 'capture-only-pointer'
            $captureObservation = if ($rawLogCaptureStatus -eq 'CAPTURED') {
                Get-CaptureOnlyObservation -RawLogPath $rawLog -RunId $runId -CaptureFrame $CaptureFrame
            } else {
                [pscustomobject]@{
                    status = 'FAIL'
                    failure = "Raw log extraction did not complete: $rawLogCaptureStatus"
                }
            }
            Write-CaptureOnlyPointer -Plan $plan -Case $case -RunId $runId -CaptureFrame $CaptureFrame `
                -RawLogPath $rawLog -RawLogCaptureStatus $rawLogCaptureStatus -MediaRegistration $mediaRegistration `
                -PlanSha256 $planSha256 -PointerPath $capturePointer -CaptureStatus $captureObservation.status `
                -Failure $captureObservation.failure | Out-Null
            if ($captureObservation.status -ne 'CAPTURED') {
                throw $captureObservation.failure
            }
            $caseSucceeded = $true
        } else {
            if ($rawLogCaptureStatus -ne 'CAPTURED') {
                throw "Raw log extraction did not complete: $rawLogCaptureStatus"
            }
            $phase = 'validate'
            & $pythonCommand @pythonPrefix $validator --plan $planFile --case $case.id --run-id $runId `
                --log $rawLog --output $report
            if ($LASTEXITCODE -ne 0) {
                throw "Validator exited $LASTEXITCODE for $($case.id); its report and raw log were preserved."
            }
            if (-not (Test-Path -LiteralPath $report -PathType Leaf)) {
                throw "Validator reported success but did not create $report"
            }
            $validatedReport = Set-ReportMediaRegistration -ReportPath $report -MediaRegistration $mediaRegistration
            if ([string]$validatedReport.functional_gate -ne 'PASS') {
                throw "Validator report for $($case.id) is not a functional PASS."
            }
            $completedReports[$case.id] = $report
            $caseSucceeded = $true
        }
    } catch {
        $caseFailure = $_
    } finally {
        if (-not $caseSucceeded) {
            Stop-AppBestEffort -TargetDeviceArguments $deviceArgs -AppPackage $plan.app_package
            if ($rawLogCaptureStatus -eq 'NOT_ATTEMPTED') {
                $rawLogCaptureStatus = Save-CaseRawLog -TargetDeviceArguments $deviceArgs -RawLogPath $rawLog `
                    -TelemetryTag $plan.telemetry_tag -Append
            }
            if ($CaptureOnly) {
                if (-not (Test-Path -LiteralPath $capturePointer -PathType Leaf)) {
                    Write-CaptureOnlyPointer -Plan $plan -Case $case -RunId $runId -CaptureFrame $CaptureFrame `
                        -RawLogPath $rawLog -RawLogCaptureStatus $rawLogCaptureStatus -MediaRegistration $mediaRegistration `
                        -PlanSha256 $planSha256 -PointerPath $capturePointer -CaptureStatus 'FAIL' `
                        -Failure $caseFailure | Out-Null
                }
            } else {
                Write-CaseFailureArtifact -Plan $plan -Case $case -RunId $runId -Phase $phase `
                    -Failure $caseFailure -RawLogPath $rawLog -RawLogCaptureStatus $rawLogCaptureStatus `
                    -ReportPath $report -PlanSha256 $planSha256 -MediaRegistration $mediaRegistration | Out-Null
            }
        }
    }

    if (-not $caseSucceeded) {
        $failed += $case.id
        break
    }
}

Write-Host "Raw logs and local artifacts: $sessionRoot"
if ($failed.Count -gt 0) {
    if ($CaptureOnly) {
        throw "Capture-only evidence collection failed for: $($failed -join ', '). The raw log and capture-only pointer were preserved; no performance claim was generated."
    }
    throw "Functional gate failed for: $($failed -join ', '). Execution stopped before later cases. Performance class is reported separately."
}
