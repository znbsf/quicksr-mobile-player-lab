[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateScript({ Test-Path -LiteralPath $_ -PathType Leaf })]
    [string]$MaterializationManifest,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9._-]+$')]
    [string]$ClipId,

    [string]$DeviceSerial,
    [string]$AdbPath,
    [string]$DeviceDirectory = '/sdcard/Movies/QuickSRBenchmark',

    [ValidatePattern('^(|-[A-Za-z0-9._-]+)$')]
    [string]$ReceiptSuffix = ''
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

function Require-SafeLeaf([string]$Value, [string]$Description) {
    if ([string]::IsNullOrWhiteSpace($Value) -or [IO.Path]::GetFileName($Value) -ne $Value -or $Value -notmatch '^[A-Za-z0-9._-]+$') {
        throw "$Description must be a safe file name."
    }
    return $Value
}

function Get-RemoteSha256([string]$RemotePath) {
    $value = (Invoke-Adb ($script:deviceArgs + @('shell', 'sha256sum', $RemotePath))) -join "`n"
    $match = [regex]::Match($value, '^(?<hash>[0-9A-Fa-f]{64})\s+')
    if (-not $match.Success) { throw "Could not parse remote SHA-256 for $RemotePath" }
    return $match.Groups['hash'].Value.ToLowerInvariant()
}

function Invoke-RemoteShellScript([string]$ScriptText) {
    # adb on Windows can remove nested SQL quotes when a command is supplied as
    # one argument.  Base64 encodes the already-validated script so the device
    # shell receives the exact content-query predicate without interpolation.
    $payload = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($ScriptText))
    return Invoke-Adb ($script:deviceArgs + @(
            'shell', "echo $payload | base64 -d | sh"
        ))
}

function Resolve-MediaStoreUri([string]$Name, [Int64]$Bytes) {
    # Name is a safe leaf and Bytes is an Int64 validated from the local manifest.
    $query = "content query --uri content://media/external/video/media --projection _id:_display_name:_size --where `"_display_name='$Name' AND _size=$Bytes`""
    for ($attempt = 1; $attempt -le 5; $attempt++) {
        $rows = (Invoke-RemoteShellScript $query) -join "`n"
        $ids = @([regex]::Matches($rows, '(?:^|\s)_id=(?<id>\d+)(?:,|\s|$)') | ForEach-Object { $_.Groups['id'].Value } | Select-Object -Unique)
        if ($ids.Count -eq 1) {
            return "content://media/external/video/media/$($ids[0])"
        }
        if ($ids.Count -gt 1) {
            throw "MediaStore returned more than one candidate for the controlled test clip $Name."
        }
        Start-Sleep -Seconds 1
    }
    throw "MediaStore did not index the pushed test clip within five seconds."
}

$manifestPath = (Resolve-Path -LiteralPath $MaterializationManifest).Path
$manifestRoot = Split-Path $manifestPath -Parent
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
if ($manifest.schema_version -ne 1 -or $manifest.status -ne 'materialized-local-only' -or $null -eq $manifest.clips) {
    throw 'Materialization manifest is not a supported completed local-only document with clip records.'
}
$clips = @($manifest.clips | Where-Object { $_.id -eq $ClipId })
if ($clips.Count -ne 1) { throw "Expected exactly one clip record for $ClipId." }
$clip = $clips[0]
$fileName = Require-SafeLeaf ([string]$clip.output.file) 'Clip output file'
$expectedHash = ([string]$clip.output.sha256).ToLowerInvariant()
if ($expectedHash -notmatch '^[0-9a-f]{64}$') { throw 'Clip output SHA-256 is invalid.' }
$expectedBytes = [Int64]$clip.output.bytes
if ($expectedBytes -le 0) { throw 'Clip output byte count is invalid.' }
try {
    $expectedFrameCount = [Convert]::ToInt64($clip.output.frame_count)
} catch {
    throw 'Clip output frame count is invalid.'
}
if ($expectedFrameCount -le 0) { throw 'Clip output frame count is invalid.' }
$localPath = Join-Path $manifestRoot $fileName
if (-not (Test-Path -LiteralPath $localPath -PathType Leaf)) {
    throw "The materialized clip is missing beside its manifest: $localPath"
}
$resolvedLocal = (Resolve-Path -LiteralPath $localPath).Path
if (-not $resolvedLocal.StartsWith((Resolve-Path -LiteralPath $manifestRoot).Path + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Clip file escaped the local materialization directory.'
}
if ((Get-Item -LiteralPath $resolvedLocal).Length -ne $expectedBytes) { throw 'Local clip byte count mismatch.' }
if ((Get-FileHash -LiteralPath $resolvedLocal -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expectedHash) {
    throw 'Local clip SHA-256 mismatch; refusing to push unbound media.'
}

$script:adb = Resolve-Adb
$deviceLines = @(& $script:adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "`tdevice$" })
if ($DeviceSerial) {
    $deviceLines = @($deviceLines | Where-Object { ($_ -split "`t")[0] -eq $DeviceSerial })
}
if ($deviceLines.Count -ne 1) { throw "Exactly one authorized target device is required; found $($deviceLines.Count)." }
$serial = ($deviceLines[0] -split "`t")[0]
$script:deviceArgs = @('-s', $serial)
if (((Invoke-Adb ($script:deviceArgs + @('shell', 'getprop', 'ro.kernel.qemu'))) -join '').Trim() -eq '1') {
    throw 'A physical phone is required; emulator media is rejected.'
}
if (((Invoke-Adb ($script:deviceArgs + @('shell', 'getprop', 'ro.product.cpu.abi'))) -join '').Trim() -ne 'arm64-v8a') {
    throw 'The target must report arm64-v8a.'
}
$socManufacturer = ((Invoke-Adb ($script:deviceArgs + @('shell', 'getprop', 'ro.soc.manufacturer'))) -join '').Trim()
if ($socManufacturer -notmatch '^(QTI|Qualcomm)$') { throw 'The target does not report a Qualcomm SoC manufacturer.' }

if ($DeviceDirectory -ne '/sdcard/Movies/QuickSRBenchmark') {
    throw 'DeviceDirectory is fixed to the dedicated QuickSRBenchmark media folder.'
}
$remoteDirectory = "$DeviceDirectory/$expectedHash"
$remotePath = "$remoteDirectory/$fileName"
# Use one remote-shell command so the shell, rather than adb argument handling,
# evaluates the existence test and returns its exit code.
$remoteExists = ((Invoke-Adb ($script:deviceArgs + @('shell', "test -f '$remotePath'; echo `$?"))) -join '').Trim()
if ($remoteExists -eq '0') {
    if ((Get-RemoteSha256 $remotePath) -ne $expectedHash) {
        throw "A different file already occupies the controlled test path; it will not be overwritten: $remotePath"
    }
} elseif ($remoteExists -eq '1') {
    Invoke-Adb ($script:deviceArgs + @('shell', 'mkdir', '-p', $remoteDirectory)) | Out-Null
    Invoke-Adb ($script:deviceArgs + @('push', $resolvedLocal, $remotePath)) | Out-Null
    if ((Get-RemoteSha256 $remotePath) -ne $expectedHash) {
        throw 'Remote clip SHA-256 mismatch after push.'
    }
} else {
    throw "Could not determine whether controlled test path exists: $remotePath"
}

Invoke-Adb ($script:deviceArgs + @('shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', "file://$remotePath")) | Out-Null
$mediaStoreUri = Resolve-MediaStoreUri $fileName $expectedBytes
$receipt = [ordered]@{
    schema_version = 1
    kind = 'android-mobile-subset-media-registration'
    status = 'PASS'
    clip = @{
        id = $clip.id
        tier = $clip.tier
        file = $fileName
        bytes = $expectedBytes
        frameCount = $expectedFrameCount
        sha256 = $expectedHash
        sourceManifestSha256 = (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    mediaStoreUri = $mediaStoreUri
    remote = @{ directory = "QuickSRBenchmark/$expectedHash"; serialCaptured = $false }
}
$receiptPath = Join-Path $manifestRoot "push-receipt-$ClipId$ReceiptSuffix.json"
if (Test-Path -LiteralPath $receiptPath) { throw "Refusing to overwrite existing registration receipt: $receiptPath" }
$receipt | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $receiptPath -Encoding UTF8
Write-Host "Hash-verified test media is registered at $mediaStoreUri"
# A PowerShell script does not reset LASTEXITCODE after its final managed cmdlet.
# Clear any successful internal adb status so a caller can safely sequence receipts.
$global:LASTEXITCODE = 0
