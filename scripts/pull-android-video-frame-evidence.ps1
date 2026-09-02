[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9._-]{1,80}$')]
    [string]$RunId,

    [string]$DeviceSerial,
    [string]$AdbPath,
    [string]$PackageName = 'dev.aisystems.quicksrplayerlab',
    [string]$OutputRoot
)

$ErrorActionPreference = 'Stop'
$script:scriptDirectory = $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($script:scriptDirectory)) {
    throw 'Could not resolve the evidence-pull script directory.'
}
$script:repositoryRoot = (Resolve-Path -LiteralPath (Split-Path $script:scriptDirectory -Parent)).Path
if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $script:repositoryRoot 'device-results\android-video-evidence'
}

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

function Require-SafeLeaf([string]$Name, [string]$Description) {
    if ([string]::IsNullOrWhiteSpace($Name) -or [IO.Path]::GetFileName($Name) -ne $Name) {
        throw "$Description must be a non-empty file name without a path"
    }
    return $Name
}

function Resolve-OutputRoot([string]$Candidate) {
    $repositoryRoot = $script:repositoryRoot
    $permittedRoot = Join-Path $repositoryRoot 'device-results'
    $candidateFullPath = [IO.Path]::GetFullPath($Candidate)
    $permittedFullPath = [IO.Path]::GetFullPath($permittedRoot).TrimEnd(
        [IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    $permittedPrefix = $permittedFullPath + [IO.Path]::DirectorySeparatorChar
    if ($candidateFullPath -eq $permittedFullPath -or
            -not $candidateFullPath.StartsWith($permittedPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "OutputRoot must stay under the Git-ignored device-results directory: $permittedRoot"
    }
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

    # Resolve the nearest existing ancestor before creation, so an existing
    # junction cannot cause a child directory to be created outside device-results.
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
        throw "OutputRoot must stay under the Git-ignored device-results directory: $permittedRoot"
    }

    if (-not (Test-Path -LiteralPath $candidateFullPath -PathType Container)) {
        New-Item -ItemType Directory -Path $candidateFullPath -Force | Out-Null
    }
    $resolvedCandidate = (Resolve-Path -LiteralPath $candidateFullPath).Path
    if (-not (($resolvedCandidate.TrimEnd(
                    [IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar) +
                [IO.Path]::DirectorySeparatorChar).StartsWith(
                $resolvedPermittedRoot, [StringComparison]::OrdinalIgnoreCase))) {
        throw "OutputRoot must stay under the Git-ignored device-results directory: $permittedRoot"
    }
    return $resolvedCandidate
}

function Copy-AdbExecOutFile([string[]]$Arguments, [string]$Destination) {
    $info = [System.Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $script:adb
    $info.UseShellExecute = $false
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $processArguments = @($script:deviceArgs + @('exec-out') + $Arguments)
    foreach ($argument in $processArguments) {
        # These adb arguments are deliberately narrow: device serials, package names,
        # and app-private relative paths may not need shell quoting.  This supports
        # Windows PowerShell's .NET Framework ProcessStartInfo, which lacks ArgumentList.
        if ([string]$argument -notmatch '^[A-Za-z0-9._:/=-]+$') {
            throw "Unsafe adb exec-out argument: $argument"
        }
    }
    if ($null -ne $info.ArgumentList) {
        foreach ($argument in $processArguments) {
            [void]$info.ArgumentList.Add($argument)
        }
    } else {
        $info.Arguments = $processArguments -join ' '
    }
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $info
    if (-not $process.Start()) { throw 'Could not start adb exec-out' }
    try {
        $stream = [IO.File]::Open($Destination, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
        try {
            $process.StandardOutput.BaseStream.CopyTo($stream)
            $stream.Flush($true)
        } finally {
            $stream.Dispose()
        }
        $stderr = $process.StandardError.ReadToEnd()
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) {
            throw "adb exec-out failed ($($process.ExitCode)): $stderr"
        }
    } catch {
        if (Test-Path -LiteralPath $Destination -PathType Leaf) {
            Remove-Item -LiteralPath $Destination -Force
        }
        throw
    } finally {
        $process.Dispose()
    }
}

$script:adb = Resolve-Adb
$deviceLines = @(& $script:adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "`tdevice$" })
if ($DeviceSerial) {
    $deviceLines = @($deviceLines | Where-Object { ($_ -split "`t")[0] -eq $DeviceSerial })
}
if ($deviceLines.Count -ne 1) {
    throw "Exactly one authorized target device is required; found $($deviceLines.Count)."
}
$serial = ($deviceLines[0] -split "`t")[0]
$script:deviceArgs = @('-s', $serial)
if (((Invoke-Adb ($script:deviceArgs + @('shell', 'getprop', 'ro.kernel.qemu'))) -join '').Trim() -eq '1') {
    throw 'A physical phone is required; emulator evidence is rejected.'
}
if (((Invoke-Adb ($script:deviceArgs + @('shell', 'getprop', 'ro.product.cpu.abi'))) -join '').Trim() -ne 'arm64-v8a') {
    throw 'The target must report arm64-v8a.'
}
$socManufacturer = ((Invoke-Adb ($script:deviceArgs + @('shell', 'getprop', 'ro.soc.manufacturer'))) -join '').Trim()
if ($socManufacturer -notmatch '^(QTI|Qualcomm)$') {
    throw 'The target must report a Qualcomm SoC manufacturer.'
}

$outputRoot = Resolve-OutputRoot $OutputRoot
$sessionRoot = Join-Path $outputRoot $RunId
if (Test-Path -LiteralPath $sessionRoot) {
    throw "Refusing to overwrite existing pulled evidence: $sessionRoot"
}
New-Item -ItemType Directory -Path $sessionRoot | Out-Null

$relativeDirectory = "video-evaluations/$RunId"
$metadataRemote = "files/$relativeDirectory/metadata.json"
try {
    Invoke-Adb ($script:deviceArgs + @('shell', 'run-as', $PackageName, 'test', '-f', $metadataRemote)) | Out-Null
    $metadataPath = Join-Path $sessionRoot 'metadata.json'
    Copy-AdbExecOutFile -Arguments @('run-as', $PackageName, 'cat', $metadataRemote) -Destination $metadataPath
    $metadata = Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
    if ($metadata.schemaVersion -ne 1 -or $metadata.kind -ne 'quicksr-video-frame-evidence') {
        throw 'Pulled metadata is not a supported video-frame evidence document.'
    }
    if ($metadata.runId -ne $RunId -or $metadata.relativeDirectory -ne $relativeDirectory) {
        throw 'Pulled metadata run identity does not match the requested run.'
    }
    foreach ($tensorName in @('input', 'output')) {
        $tensor = $metadata.tensors.$tensorName
        if ($null -eq $tensor) { throw "Metadata is missing the $tensorName tensor." }
        $leaf = Require-SafeLeaf ([string]$tensor.file) "$tensorName tensor file"
        if ([string]$tensor.relativePath -ne "$relativeDirectory/$leaf") {
            throw "$tensorName tensor relative path does not stay in the evidence directory."
        }
        $localPath = Join-Path $sessionRoot $leaf
        $remotePath = "files/$relativeDirectory/$leaf"
        Invoke-Adb ($script:deviceArgs + @('shell', 'run-as', $PackageName, 'test', '-f', $remotePath)) | Out-Null
        Copy-AdbExecOutFile -Arguments @('run-as', $PackageName, 'cat', $remotePath) -Destination $localPath
        $observedBytes = (Get-Item -LiteralPath $localPath).Length
        if ($observedBytes -ne [Int64]$tensor.bytes) {
            throw "$tensorName tensor byte count mismatch after pull."
        }
        $observedHash = (Get-FileHash -LiteralPath $localPath -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($observedHash -ne ([string]$tensor.sha256LittleEndianFloat32).ToLowerInvariant()) {
            throw "$tensorName tensor SHA-256 mismatch after pull."
        }
    }
    $receipt = [ordered]@{
        schemaVersion = 1
        kind = 'pulled-android-video-frame-evidence'
        runId = $RunId
        metadataSha256 = (Get-FileHash -LiteralPath $metadataPath -Algorithm SHA256).Hash.ToLowerInvariant()
        integrity = 'PASS'
        device = @{ physical = $true; abi = 'arm64-v8a'; serialCaptured = $false }
    }
    $receipt | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $sessionRoot 'pull-receipt.json') -Encoding UTF8
    Write-Host "Pulled and hash-verified app-private evidence: $sessionRoot"
} catch {
    $failure = [ordered]@{
        schemaVersion = 1
        kind = 'pulled-android-video-frame-evidence'
        runId = $RunId
        integrity = 'FAIL'
        failure = $_.Exception.Message
        device = @{ physical = $true; abi = 'arm64-v8a'; serialCaptured = $false }
    }
    $failure | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $sessionRoot 'pull-failure.json') -Encoding UTF8
    throw
}
