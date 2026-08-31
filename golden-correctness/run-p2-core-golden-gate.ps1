[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$AndroidReceipt,
    [Parameter(Mandatory = $true)][string]$AndroidOutput,
    [Parameter(Mandatory = $true)][string]$PcGoldenManifest,
    [string]$ResultDirectory = "",
    [string]$PythonPath = "python"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$goldenRoot = $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($ResultDirectory)) {
    $repositoryRoot = Split-Path -Parent $goldenRoot
    $caseId = "derived-golden-" + (Get-Date -Format "yyyyMMdd-HHmmss-fff")
    $ResultDirectory = Join-Path $repositoryRoot "local-artifacts/golden-correctness/$caseId"
}

& (Join-Path $goldenRoot "run-p2-derived-golden-gate.ps1") `
    -AndroidReceipt $AndroidReceipt `
    -AndroidOutput $AndroidOutput `
    -ResultDirectory $ResultDirectory `
    -PcGoldenManifest $PcGoldenManifest `
    -PythonPath $PythonPath
exit $LASTEXITCODE
