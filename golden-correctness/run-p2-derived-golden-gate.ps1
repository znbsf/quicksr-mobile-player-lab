[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$AndroidReceipt,
    [Parameter(Mandatory = $true)][string]$AndroidOutput,
    [Parameter(Mandatory = $true)][string]$ResultDirectory,
    [Parameter(Mandatory = $true)][string]$PcGoldenManifest,
    [string]$DerivationManifest = "",
    [string]$ExecutionPlan = "",
    [string]$PythonPath = "python"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$goldenRoot = $PSScriptRoot
$repositoryRoot = Split-Path -Parent $goldenRoot
$pythonCommand = Get-Command $PythonPath -ErrorAction SilentlyContinue
$python = if (Test-Path -LiteralPath $PythonPath -PathType Leaf) {
    [System.IO.Path]::GetFullPath($PythonPath)
} elseif ($null -ne $pythonCommand) {
    $pythonCommand.Source
} else {
    throw "Python command is unavailable: $PythonPath"
}
if ([string]::IsNullOrWhiteSpace($DerivationManifest)) {
    $DerivationManifest = Join-Path $repositoryRoot "derived-models/derivation-manifest.json"
}

$resolvedReceipt = [System.IO.Path]::GetFullPath($AndroidReceipt)
$resolvedOutput = [System.IO.Path]::GetFullPath($AndroidOutput)
$resolvedResult = [System.IO.Path]::GetFullPath($ResultDirectory)
$resolvedPcManifest = [System.IO.Path]::GetFullPath($PcGoldenManifest)
$resolvedDerivationManifest = [System.IO.Path]::GetFullPath($DerivationManifest)
$resolvedExecutionPlan = if ([string]::IsNullOrWhiteSpace($ExecutionPlan)) {
    $null
} else {
    [System.IO.Path]::GetFullPath($ExecutionPlan)
}

foreach ($required in @(
    $python,
    $resolvedPcManifest,
    $resolvedDerivationManifest,
    $resolvedReceipt,
    $resolvedOutput
)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Required derived golden input is missing: $required"
    }
}
if ($null -ne $resolvedExecutionPlan -and -not (Test-Path -LiteralPath $resolvedExecutionPlan -PathType Leaf)) {
    throw "Required execution plan is missing: $resolvedExecutionPlan"
}

if (Test-Path -LiteralPath $resolvedResult -PathType Leaf) {
    throw "Derived golden result path is a file, not a directory: $resolvedResult"
}
if (Test-Path -LiteralPath $resolvedResult -PathType Container) {
    $existingResultItems = @(Get-ChildItem -LiteralPath $resolvedResult -Force)
    if ($existingResultItems.Count -gt 0) {
        throw "Derived golden result directory is already populated and frozen; choose a new unique ResultDirectory: $resolvedResult"
    }
}

$resultPath = Join-Path $resolvedResult "android-vs-pc-comparison.json"
$compareArguments = @(
    (Join-Path $goldenRoot "compare_android_output.py"),
    "--manifest", $resolvedPcManifest,
    "--android-receipt", $resolvedReceipt,
    "--android-output", $resolvedOutput,
    "--derivation-manifest", $resolvedDerivationManifest,
    "--result", $resultPath
)
if ($null -ne $resolvedExecutionPlan) {
    $compareArguments += @("--execution-plan", $resolvedExecutionPlan)
}
& $python @compareArguments
$compareExit = $LASTEXITCODE
if ($compareExit -ne 0) {
    exit $compareExit
}

& $python (Join-Path $goldenRoot "validate_golden_case.py") --case-dir $resolvedResult
exit $LASTEXITCODE
