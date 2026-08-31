[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$AndroidReceipt,
    [string]$AndroidOutput = "",
    [string]$ResultDirectory = "",
    [string]$PythonPath = "python",
    [string]$ModelPath = ""
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
$model = if ([string]::IsNullOrWhiteSpace($ModelPath)) {
    Join-Path $repositoryRoot "models/quicksrnet-small-2x-opset17.onnx"
} else {
    [System.IO.Path]::GetFullPath($ModelPath)
}
$qualificationPlan = Join-Path $repositoryRoot "contracts/p0-cpu-golden-plan.json"

if (-not (Test-Path -LiteralPath $python -PathType Leaf)) {
    throw "Frozen Python environment is missing: $python"
}
if (-not (Test-Path -LiteralPath $model -PathType Leaf)) {
    throw "Locked QuickSR ONNX model is missing: $model"
}
if ([string]::IsNullOrWhiteSpace($ResultDirectory)) {
    $caseId = "cpu-golden-" + (Get-Date -Format "yyyyMMdd-HHmmss-fff")
    $ResultDirectory = Join-Path $repositoryRoot "local-artifacts/golden-correctness/$caseId"
}

$resolvedReceipt = [System.IO.Path]::GetFullPath($AndroidReceipt)
$resolvedResult = [System.IO.Path]::GetFullPath($ResultDirectory)
New-Item -ItemType Directory -Force -Path $resolvedResult | Out-Null

& $python (Join-Path $goldenRoot "generate_pc_golden.py") `
    --model $model `
    --android-receipt $resolvedReceipt `
    --output-dir $resolvedResult `
    --qualification-plan $qualificationPlan
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

$compareArguments = @(
    (Join-Path $goldenRoot "compare_android_output.py"),
    "--manifest", (Join-Path $resolvedResult "pc-golden-manifest.json"),
    "--android-receipt", $resolvedReceipt,
    "--result", (Join-Path $resolvedResult "android-vs-pc-comparison.json")
)
if (-not [string]::IsNullOrWhiteSpace($AndroidOutput)) {
    $compareArguments += @("--android-output", [System.IO.Path]::GetFullPath($AndroidOutput))
}
& $python @compareArguments
$compareExit = $LASTEXITCODE
if ($compareExit -ne 0) {
    exit $compareExit
}
& $python (Join-Path $goldenRoot "validate_golden_case.py") --case-dir $resolvedResult
exit $LASTEXITCODE
