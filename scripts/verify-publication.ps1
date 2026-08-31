[CmdletBinding()]
param(
    [switch]$ScanAllFiles
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$script:Findings = [System.Collections.Generic.List[object]]::new()
$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$repositoryPrefix = $repositoryRoot.TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar

function Add-Finding {
    param(
        [Parameter(Mandatory = $true)][string]$Category,
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Detail,
        [int]$Line = 0
    )

    $script:Findings.Add([pscustomobject]@{
        Category = $Category
        Path = $Path
        Line = $Line
        Detail = $Detail
    }) | Out-Null
}

function Normalize-RelativePath {
    param([Parameter(Mandatory = $true)][string]$Path)
    return $Path.Replace('\', '/').TrimStart('/')
}

function Get-PublicationCandidates {
    if ($ScanAllFiles) {
        return @(
            Get-ChildItem -LiteralPath $repositoryRoot -Recurse -Force -File |
                Where-Object {
                    -not $_.FullName.StartsWith(
                        (Join-Path $repositoryRoot ".git") + [System.IO.Path]::DirectorySeparatorChar,
                        [System.StringComparison]::OrdinalIgnoreCase
                    )
                } |
                ForEach-Object {
                    Normalize-RelativePath $_.FullName.Substring($repositoryPrefix.Length)
                }
        )
    }

    $gitCommand = Get-Command git -ErrorAction SilentlyContinue
    if ($null -eq $gitCommand) {
        throw "Git is required to determine the publication set. Use -ScanAllFiles only for a deliberate local audit."
    }
    if (-not (Test-Path -LiteralPath (Join-Path $repositoryRoot ".git"))) {
        throw "The project is not an independent Git worktree. Initialize Git here before publication verification."
    }

    $gitRootOutput = @(& $gitCommand.Source -C $repositoryRoot rev-parse --show-toplevel 2>&1)
    if ($LASTEXITCODE -ne 0 -or $gitRootOutput.Count -ne 1) {
        throw "The project is not an independent Git worktree. Initialize Git here before publication verification."
    }

    $observedGitRoot = [System.IO.Path]::GetFullPath($gitRootOutput[0].Trim())
    if (-not $observedGitRoot.Equals($repositoryRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Git top-level mismatch. Expected '$repositoryRoot' but observed '$observedGitRoot'."
    }

    $files = @(& $gitCommand.Source -C $repositoryRoot ls-files --cached --others --exclude-standard)
    if ($LASTEXITCODE -ne 0) {
        throw "git ls-files failed; the publication set cannot be verified."
    }

    return @($files | ForEach-Object { Normalize-RelativePath $_ } | Sort-Object -Unique)
}

$forbiddenDirectoryPatterns = @(
    '(?i)(^|/)(?:\.gradle|build|out|cmake-build[^/]*|CMakeFiles|node_modules|__pycache__|\.idea|\.vscode)(?:/|$)',
    '(?i)(^|/)(?:local-artifacts|device-results|receipts|profiles|qnn-profiles|qnn-traces)(?:/|$)',
    '(?i)^golden-correctness/results(?:/|$)',
    '(?i)(^|/)(?:artifacts|checkpoints?)(?:/|$)',
    '(?i)(^|/)evidence/(?:raw|private)(?:/|$)'
)

$forbiddenExtensions = [System.Collections.Generic.HashSet[string]]::new(
    [System.StringComparer]::OrdinalIgnoreCase
)
@(
    '.onnx', '.pth', '.pt', '.ckpt', '.safetensors', '.tflite', '.dlc',
    '.engine', '.gguf', '.bin', '.apk', '.aab', '.aar', '.so', '.dll',
    '.dylib', '.exe', '.dex', '.class', '.jar', '.o', '.obj', '.lib',
    '.raw', '.f32le', '.npy', '.npz', '.trace', '.nsys-rep', '.ncu-rep',
    '.qdrep', '.log', '.csv', '.mp4', '.mkv', '.webm', '.mov', '.avi',
    '.wav', '.flac', '.mp3', '.png', '.jpg', '.jpeg', '.webp'
) | ForEach-Object { [void]$forbiddenExtensions.Add($_) }

$forbiddenNames = [System.Collections.Generic.HashSet[string]]::new(
    [System.StringComparer]::OrdinalIgnoreCase
)
@(
    'local.properties', 'google-services.json', 'id_rsa', 'id_ed25519'
) | ForEach-Object { [void]$forbiddenNames.Add($_) }

$secretExtensions = [System.Collections.Generic.HashSet[string]]::new(
    [System.StringComparer]::OrdinalIgnoreCase
)
@('.jks', '.keystore', '.p12', '.pfx', '.pem', '.key') |
    ForEach-Object { [void]$secretExtensions.Add($_) }

$textExtensions = [System.Collections.Generic.HashSet[string]]::new(
    [System.StringComparer]::OrdinalIgnoreCase
)
@(
    '.java', '.kt', '.kts', '.gradle', '.xml', '.json', '.md', '.txt',
    '.ps1', '.psm1', '.py', '.mjs', '.js', '.ts', '.yml', '.yaml',
    '.properties', '.toml', '.ini', '.cfg', '.conf', '.sh', '.bat', '.cmd',
    '.c', '.cc', '.cpp', '.h', '.hpp', '.cmake', '.pro'
) | ForEach-Object { [void]$textExtensions.Add($_) }

$textNames = [System.Collections.Generic.HashSet[string]]::new(
    [System.StringComparer]::OrdinalIgnoreCase
)
@(
    '.gitignore', '.gitattributes', 'LICENSE', 'LICENSE.md', 'NOTICE',
    'NOTICE.md', 'COPYING', 'gradlew'
) | ForEach-Object { [void]$textNames.Add($_) }

$contentRules = @(
    @{ Category = 'absolute-path'; Detail = 'Windows absolute path'; Pattern = '(?i)(?<![A-Za-z0-9_])[A-Z]:[\\/]' },
    @{ Category = 'absolute-path'; Detail = 'UNC path'; Pattern = '(?<![\\])\\\\[A-Za-z0-9._$-]+[\\/]' },
    @{ Category = 'absolute-path'; Detail = 'Unix user-home path'; Pattern = '(?<![A-Za-z0-9_])/(?:home|Users)/[^/\s]+' },
    @{ Category = 'device-private-path'; Detail = 'Android app-private or emulated-storage path'; Pattern = '(?i)(?<![A-Za-z0-9_])/(?:data/(?:user|data)|storage/emulated)/' },
    @{ Category = 'external-path'; Detail = 'Parent-directory traversal reference'; Pattern = '(?<!\.)\.\.[\\/]' },
    @{ Category = 'credential'; Detail = 'Private-key material'; Pattern = '-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----' },
    @{ Category = 'credential'; Detail = 'AWS access-key shape'; Pattern = '\bAKIA[0-9A-Z]{16}\b' },
    @{ Category = 'credential'; Detail = 'GitHub token shape'; Pattern = '\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})\b' },
    @{ Category = 'credential'; Detail = 'OpenAI-style secret-key shape'; Pattern = '\bsk-[A-Za-z0-9_-]{20,}\b' },
    @{ Category = 'credential'; Detail = 'Google API-key shape'; Pattern = '\bAIza[0-9A-Za-z_-]{30,}\b' },
    @{ Category = 'credential'; Detail = 'Bearer credential'; Pattern = '(?i)\bBearer\s+[A-Za-z0-9._~+/=-]{16,}' },
    @{ Category = 'credential'; Detail = 'Assigned secret-like value'; Pattern = '(?i)\b(?:api[_-]?key|access[_-]?token|auth[_-]?token|client[_-]?secret|password|passwd)\b\s*[:=]\s*["'']?(?!REDACTED|CHANGEME|YOUR_|<)[A-Za-z0-9+/=_\-.]{8,}' }
)

$contentScanExemptions = [System.Collections.Generic.HashSet[string]]::new(
    [System.StringComparer]::OrdinalIgnoreCase
)
[void]$contentScanExemptions.Add('scripts/verify-publication.ps1')

$wrapperJar = 'gradle/wrapper/gradle-wrapper.jar'
$wrapperProperties = 'gradle/wrapper/gradle-wrapper.properties'

try {
    $candidatePaths = @(Get-PublicationCandidates)
} catch {
    Write-Host "PUBLICATION CHECK: FAIL" -ForegroundColor Red
    Write-Host $_.Exception.Message
    exit 2
}

foreach ($relativePath in $candidatePaths) {
    if ([string]::IsNullOrWhiteSpace($relativePath)) {
        Add-Finding 'path' '<empty>' 'Empty publication path returned by the file enumerator.'
        continue
    }

    $relativePath = Normalize-RelativePath $relativePath
    if ($relativePath -match '(^|/)\.\.(?:/|$)') {
        Add-Finding 'path' $relativePath 'Publication path contains parent-directory traversal.'
        continue
    }

    $fullPath = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot $relativePath))
    if (-not $fullPath.StartsWith($repositoryPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        Add-Finding 'path' $relativePath 'Publication path resolves outside the independent repository.'
        continue
    }
    if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
        Add-Finding 'path' $relativePath 'Publication candidate is missing or is not a regular file.'
        continue
    }

    $item = Get-Item -LiteralPath $fullPath -Force
    if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        Add-Finding 'path' $relativePath 'Symlinks and reparse points are not accepted in the publication set.'
        continue
    }

    foreach ($pattern in $forbiddenDirectoryPatterns) {
        if ($relativePath -match $pattern) {
            Add-Finding 'generated-or-raw-path' $relativePath 'Path belongs to generated, raw-evidence, cache, artifact, or private-output storage.'
            break
        }
    }

    if ($relativePath -match '(?i)^models/' -and
        $relativePath -notmatch '(?i)^models/(?:README\.md|\.gitkeep|[^/]+\.example\.json)$') {
        Add-Finding 'model' $relativePath 'The models directory may publish only instructions or explicitly labelled example metadata.'
    }

    $name = [System.IO.Path]::GetFileName($relativePath)
    $extension = [System.IO.Path]::GetExtension($relativePath)

    if ($name -match '(?i)^\.env(?:\..+)?$' -or $forbiddenNames.Contains($name) -or
        $secretExtensions.Contains($extension)) {
        Add-Finding 'secret-or-machine-config' $relativePath 'Secret, signing, service, or machine-local configuration filename is forbidden.'
    }

    if ($relativePath -match '(?i)\.raw\.b64$' -or
        $relativePath -match '(?i)\.trace\.json$') {
        Add-Finding 'raw-evidence' $relativePath 'Raw/Base64 tensor or trace preservation file is forbidden.'
    }

    if ($forbiddenExtensions.Contains($extension) -and
        -not $relativePath.Equals($wrapperJar, [System.StringComparison]::OrdinalIgnoreCase)) {
        Add-Finding 'binary-model-media-or-log' $relativePath "Forbidden publication extension '$extension'."
        continue
    }

    $isText = $textExtensions.Contains($extension) -or $textNames.Contains($name)
    if ($relativePath.Equals($wrapperJar, [System.StringComparison]::OrdinalIgnoreCase)) {
        continue
    }
    if (-not $isText) {
        Add-Finding 'unclassified-file' $relativePath 'File is not in the explicit text-source allowlist.'
        continue
    }
    if ($contentScanExemptions.Contains($relativePath)) {
        continue
    }

    try {
        $lineNumber = 0
        foreach ($line in Get-Content -LiteralPath $fullPath) {
            $lineNumber += 1
            foreach ($rule in $contentRules) {
                if ([regex]::IsMatch($line, $rule.Pattern)) {
                    Add-Finding $rule.Category $relativePath $rule.Detail $lineNumber
                }
            }
        }
    } catch {
        Add-Finding 'read-failure' $relativePath 'Text content could not be read and therefore could not be verified.'
    }
}

if ($candidatePaths -contains $wrapperJar) {
    $propertiesPath = Join-Path $repositoryRoot $wrapperProperties
    if (-not ($candidatePaths -contains $wrapperProperties) -or
        -not (Test-Path -LiteralPath $propertiesPath -PathType Leaf)) {
        Add-Finding 'wrapper' $wrapperJar 'Gradle wrapper JAR requires published gradle-wrapper.properties.'
    } else {
        try {
            $propertiesText = Get-Content -LiteralPath $propertiesPath -Raw
            if ($propertiesText -notmatch '(?m)^distributionUrl=https\\://services\.gradle\.org/distributions/gradle-[^\r\n]+-bin\.zip\s*$') {
                Add-Finding 'wrapper' $wrapperProperties 'Gradle distributionUrl must use the official HTTPS -bin.zip endpoint.'
            }
            if ($propertiesText -notmatch '(?m)^distributionSha256Sum=[0-9a-fA-F]{64}\s*$') {
                Add-Finding 'wrapper' $wrapperProperties 'A 64-hex distributionSha256Sum is required.'
            }
        } catch {
            Add-Finding 'wrapper' $wrapperProperties 'Gradle wrapper properties could not be verified.'
        }
    }
}

$orderedFindings = @($script:Findings | Sort-Object Category, Path, Line, Detail -Unique)
if ($orderedFindings.Count -gt 0) {
    Write-Host "PUBLICATION CHECK: FAIL ($($orderedFindings.Count) finding(s))" -ForegroundColor Red
    foreach ($finding in $orderedFindings) {
        $location = $finding.Path
        if ($finding.Line -gt 0) {
            $location = "$location`:$($finding.Line)"
        }
        Write-Host "[$($finding.Category)] $location - $($finding.Detail)"
    }
    exit 1
}

Write-Host "PUBLICATION CHECK: PASS ($($candidatePaths.Count) candidate file(s))" -ForegroundColor Green
Write-Host "Automated PASS does not grant model, dataset, media, vendor-binary, or APK redistribution rights."
exit 0
