[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$temporaryRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
$caseRoot = [System.IO.Path]::GetFullPath(
    (Join-Path $temporaryRoot ("quicksr-publication-test-" + [Guid]::NewGuid().ToString("N")))
)
$temporaryPrefix = $temporaryRoot.TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
if (-not $caseRoot.StartsWith($temporaryPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to create a publication test outside the operating-system temp directory."
}

function Invoke-ExpectedExit {
    param(
        [Parameter(Mandatory = $true)][int]$Expected,
        [Parameter(Mandatory = $true)][string]$Label
    )

    & (Get-Process -Id $PID).Path -NoProfile -File (Join-Path $caseRoot "scripts/verify-publication.ps1") | Out-Host
    $observed = $LASTEXITCODE
    if ($observed -ne $Expected) {
        throw "$Label expected exit $Expected but observed $observed"
    }
}

try {
    New-Item -ItemType Directory -Path (Join-Path $caseRoot "scripts") -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot "verify-publication.ps1") `
        -Destination (Join-Path $caseRoot "scripts/verify-publication.ps1")
    Set-Content -LiteralPath (Join-Path $caseRoot "README.md") `
        -Value "# Synthetic publication-safety fixture" -Encoding utf8
    New-Item -ItemType Directory -Path (Join-Path $caseRoot "docs/diagrams") -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $caseRoot "docs/diagrams/edge-safe.puml") `
        -Value "@startuml`nAlice -> Bob: safe`n@enduml" -Encoding utf8
    Set-Content -LiteralPath (Join-Path $caseRoot "docs/diagrams/edge-safe.svg") `
        -Value '<svg xmlns="http://www.w3.org/2000/svg"><text>safe</text></svg>' -Encoding utf8
    git -C $caseRoot init -b main | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Could not initialize the synthetic Git repository."
    }

    Invoke-ExpectedExit -Expected 0 -Label "safe source-only fixture"

    Set-Content -LiteralPath (Join-Path $caseRoot "docs/unscoped.svg") `
        -Value '<svg xmlns="http://www.w3.org/2000/svg"><text>not allowlisted</text></svg>' -Encoding utf8
    Invoke-ExpectedExit -Expected 1 -Label "unscoped SVG negative fixture"
    Remove-Item -LiteralPath (Join-Path $caseRoot "docs/unscoped.svg")

    Set-Content -LiteralPath (Join-Path $caseRoot "docs/diagrams/edge-active.svg") `
        -Value '<svg xmlns="http://www.w3.org/2000/svg"><script>void 0</script></svg>' -Encoding utf8
    Invoke-ExpectedExit -Expected 1 -Label "active SVG negative fixture"
    Remove-Item -LiteralPath (Join-Path $caseRoot "docs/diagrams/edge-active.svg")

    Set-Content -LiteralPath (Join-Path $caseRoot "docs/diagrams/edge-include.puml") `
        -Value "@startuml`n!includeurl https://example.invalid/remote.puml`n@enduml" -Encoding utf8
    Invoke-ExpectedExit -Expected 1 -Label "PlantUML include negative fixture"
    Remove-Item -LiteralPath (Join-Path $caseRoot "docs/diagrams/edge-include.puml")

    New-Item -ItemType Directory -Path (Join-Path $caseRoot "models") -Force | Out-Null
    [System.IO.File]::WriteAllBytes(
        (Join-Path $caseRoot "models/forbidden-model.onnx"),
        [byte[]](1, 2, 3, 4)
    )
    Invoke-ExpectedExit -Expected 1 -Label "model payload negative fixture"
    Remove-Item -LiteralPath (Join-Path $caseRoot "models/forbidden-model.onnx")

    $secretShape = "github_" + "pat_" + ("A" * 24)
    Set-Content -LiteralPath (Join-Path $caseRoot "notes.txt") `
        -Value ("synthetic=" + $secretShape) -Encoding utf8
    Invoke-ExpectedExit -Expected 1 -Label "credential-shape negative fixture"

    Write-Host "Publication safety tests: PASS"
} finally {
    $resolvedCase = [System.IO.Path]::GetFullPath($caseRoot)
    if ($resolvedCase.StartsWith($temporaryPrefix, [System.StringComparison]::OrdinalIgnoreCase) -and
        [System.IO.Path]::GetFileName($resolvedCase).StartsWith(
            "quicksr-publication-test-",
            [System.StringComparison]::OrdinalIgnoreCase
        ) -and
        (Test-Path -LiteralPath $resolvedCase -PathType Container)) {
        Remove-Item -LiteralPath $resolvedCase -Recurse -Force
    }
}

# The final negative fixture intentionally leaves a child process exit code of 1.
# Reset it only after every assertion and cleanup completed successfully.
$global:LASTEXITCODE = 0
