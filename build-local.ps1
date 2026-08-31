[CmdletBinding()]
param(
    [string]$ModelPath = ""
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$projectRoot = $PSScriptRoot
$runId = (Get-Date -Format "yyyyMMdd-HHmmss-fff") + "-" + ([Guid]::NewGuid().ToString("N").Substring(0, 8))
$evidenceDirectory = Join-Path $projectRoot "build/evidence/$runId"
New-Item -ItemType Directory -Force -Path $evidenceDirectory | Out-Null

try {
$expectedModelBytes = 93994L
$expectedModelSha256 = "3db92151af52808135024faf6abdec69e75ca13b5112b6521a9681a27c63f6ce"
$expectedPlanSha256 = "44852e9245c46959af438b64dff75db3489f09ac94ac3913277af9d361a00859"
$expectedQnnPlanSha256 = "2b7b888ba95949c92d3ea6df0852bb07fb4ef890227d0ed1842c795aed49af86"
$expectedP4PlanSha256 = "90d05b4cf9837a8a421fc35d144847a4cd727dcf39c8ca50f85aa128104a2fa8"

if ([string]::IsNullOrWhiteSpace($ModelPath)) {
    $ModelPath = Join-Path $projectRoot "models/quicksrnet-small-2x-opset17.onnx"
}
$resolvedModel = [System.IO.Path]::GetFullPath($ModelPath)
$planPath = Join-Path $projectRoot "prototype-plan-p2.json"
$qnnPlanPath = Join-Path $projectRoot "prototype-plan-p3-qnn.json"
$p4PlanPath = Join-Path $projectRoot "contracts/p4-real-image-roi-plan.json"

$sdkCandidates = @(@(
    $env:ANDROID_SDK_ROOT,
    $env:ANDROID_HOME,
    (Join-Path $env:LOCALAPPDATA "Android/Sdk")
) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) -and (Test-Path -LiteralPath $_ -PathType Container) })
if ($sdkCandidates.Count -eq 0) {
    throw "Android SDK was not found. No SDK will be installed automatically."
}
$sdkPath = [System.IO.Path]::GetFullPath($sdkCandidates[0])

$jdkCandidates = @(@(
    $env:JAVA_HOME,
    (Join-Path $env:ProgramFiles "Android/Android Studio/jbr")
) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) -and (Test-Path -LiteralPath (Join-Path $_ "bin/java.exe") -PathType Leaf) })
if ($jdkCandidates.Count -eq 0) {
    throw "A Java 17+ JDK was not found. No JDK will be installed automatically."
}
$jdkPath = [System.IO.Path]::GetFullPath($jdkCandidates[0])

$gradleCommand = Get-Command gradle.bat -ErrorAction SilentlyContinue
if ($null -ne $gradleCommand) {
    $gradlePath = $gradleCommand.Source
} else {
    $gradleCacheRoot = Join-Path $env:USERPROFILE ".gradle/wrapper/dists/gradle-8.14-all"
    $cachedGradle = Get-ChildItem -LiteralPath $gradleCacheRoot -Filter gradle.bat -File -Recurse -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match "gradle-8\.14[\\/]bin[\\/]gradle\.bat$" } |
        Select-Object -First 1
    if ($null -eq $cachedGradle) {
        throw "Gradle 8.14 was not found on PATH or in the existing wrapper cache. No Gradle distribution will be installed automatically."
    }
    $gradlePath = $cachedGradle.FullName
}

$preflight = [ordered]@{
    runId = $runId
    startedAt = (Get-Date).ToString("o")
    projectRoot = $projectRoot
    sdkPath = $sdkPath
    javaHome = $jdkPath
    gradlePath = $gradlePath
    modelPath = $resolvedModel
    modelExpectedBytes = $expectedModelBytes
    modelExpectedSha256 = $expectedModelSha256
    planExpectedSha256 = $expectedPlanSha256
    qnnPlanExpectedSha256 = $expectedQnnPlanSha256
    p4PlanExpectedSha256 = $expectedP4PlanSha256
    environmentMutation = "process-only ANDROID_SDK_ROOT, ANDROID_HOME, and JAVA_HOME"
    dependencyNote = "Maven Central or Google repositories may be contacted if the pinned dependencies are absent from the local Gradle cache."
}

$preflightPath = Join-Path $evidenceDirectory "preflight.json"
$preflight | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $preflightPath -Encoding utf8

if (-not (Test-Path -LiteralPath $resolvedModel -PathType Leaf)) {
    throw "Locked model is missing: $resolvedModel"
}
$modelFile = Get-Item -LiteralPath $resolvedModel
$observedModelSha256 = (Get-FileHash -LiteralPath $resolvedModel -Algorithm SHA256).Hash.ToLowerInvariant()
$observedPlanSha256 = (Get-FileHash -LiteralPath $planPath -Algorithm SHA256).Hash.ToLowerInvariant()
$observedQnnPlanSha256 = (Get-FileHash -LiteralPath $qnnPlanPath -Algorithm SHA256).Hash.ToLowerInvariant()
$observedP4PlanSha256 = (Get-FileHash -LiteralPath $p4PlanPath -Algorithm SHA256).Hash.ToLowerInvariant()
if ($modelFile.Length -ne $expectedModelBytes) {
    throw "Locked model byte mismatch: expected $expectedModelBytes, observed $($modelFile.Length)"
}
if ($observedModelSha256 -ne $expectedModelSha256) {
    throw "Locked model SHA-256 mismatch: expected $expectedModelSha256, observed $observedModelSha256"
}
if ($observedPlanSha256 -ne $expectedPlanSha256) {
    throw "Frozen plan SHA-256 mismatch: expected $expectedPlanSha256, observed $observedPlanSha256"
}
if ($observedQnnPlanSha256 -ne $expectedQnnPlanSha256) {
    throw "Frozen QNN plan SHA-256 mismatch: expected $expectedQnnPlanSha256, observed $observedQnnPlanSha256"
}
if ($observedP4PlanSha256 -ne $expectedP4PlanSha256) {
    throw "Frozen P4 plan SHA-256 mismatch: expected $expectedP4PlanSha256, observed $observedP4PlanSha256"
}

$env:ANDROID_SDK_ROOT = $sdkPath
$env:ANDROID_HOME = $sdkPath
$env:JAVA_HOME = $jdkPath

function Invoke-GradleGate {
    param(
        [Parameter(Mandatory = $true)][string]$Gate,
        [Parameter(Mandatory = $true)][string]$Task
    )

    $logPath = Join-Path $evidenceDirectory "$Gate.log"
    & $gradlePath --no-daemon $Task "-PquickSrModelPath=$resolvedModel" "-PprototypeBuildId=$runId" 2>&1 |
        Tee-Object -FilePath $logPath |
        Out-Host
    $exitCode = $LASTEXITCODE
    return [pscustomobject][ordered]@{
        gate = $Gate
        task = $Task
        exitCode = $exitCode
        status = if ($exitCode -eq 0) { "PASS" } else { "FAIL" }
        log = [System.IO.Path]::GetFileName($logPath)
    }
}

$gateResults = @()
Push-Location $projectRoot
try {
    $gateResults += Invoke-GradleGate -Gate "unit-tests" -Task "testDebugUnitTest"
    $gateResults += Invoke-GradleGate -Gate "lint" -Task "lintDebug"
    $gateResults += Invoke-GradleGate -Gate "assemble" -Task "assembleDebug"
} finally {
    Pop-Location
}

$apkPath = Join-Path $projectRoot "app/build/outputs/apk/debug/app-debug.apk"
$generatedBuildConfigPath = Join-Path $projectRoot "app/build/generated/source/buildConfig/debug/dev/aisystems/quicksrplayerlab/BuildConfig.java"
$appBuildIdentity = $null
if (Test-Path -LiteralPath $generatedBuildConfigPath -PathType Leaf) {
    $generatedBuildConfig = Get-Content -Raw -LiteralPath $generatedBuildConfigPath
    $sourceIdentityMatch = [regex]::Match($generatedBuildConfig, 'APP_SOURCE_SHA256 = "([0-9a-f]{64})";')
    $buildIdMatch = [regex]::Match($generatedBuildConfig, 'PROTOTYPE_BUILD_ID = "([^"]+)";')
    if ($sourceIdentityMatch.Success -and $buildIdMatch.Success) {
        $appBuildIdentity = [ordered]@{
            sourceIdentitySha256 = $sourceIdentityMatch.Groups[1].Value
            prototypeBuildId = $buildIdMatch.Groups[1].Value
            matchesEvidenceRunId = ($buildIdMatch.Groups[1].Value -eq $runId)
        }
    }
}
$allGatesPass = @($gateResults | Where-Object { $_.exitCode -ne 0 }).Count -eq 0
$apkExists = Test-Path -LiteralPath $apkPath -PathType Leaf
$identityLinked = $null -ne $appBuildIdentity -and $appBuildIdentity["matchesEvidenceRunId"] -eq $true
$overallStatus = if ($allGatesPass -and $apkExists -and $identityLinked) { "PASS" } else { "FAIL" }
$summary = [ordered]@{
    schemaVersion = "1.0.0"
    runId = $runId
    status = $overallStatus
    startedAt = $preflight.startedAt
    finishedAt = (Get-Date).ToString("o")
    frozenPlanSha256 = $observedPlanSha256
    frozenQnnPlanSha256 = $observedQnnPlanSha256
    frozenP4PlanSha256 = $observedP4PlanSha256
    model = [ordered]@{
        path = $resolvedModel
        bytes = $modelFile.Length
        sha256 = $observedModelSha256
    }
    gates = $gateResults
    appBuildIdentity = $appBuildIdentity
    apk = if ($apkExists) {
        [ordered]@{
            path = $apkPath
            bytes = (Get-Item -LiteralPath $apkPath).Length
            sha256 = (Get-FileHash -LiteralPath $apkPath -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    } else {
        $null
    }
    deviceExecution = "NOT_RUN"
}
$summaryPath = Join-Path $evidenceDirectory "build-summary.json"
$summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $summaryPath -Encoding utf8

Write-Host "Build evidence: $evidenceDirectory"
Write-Host "Build status: $($summary.status)"
if ($summary.status -ne "PASS") {
    exit 1
}
} catch {
    $failure = [ordered]@{
        schemaVersion = "1.0.0"
        runId = $runId
        status = "FAIL"
        phase = "build-script-or-preflight"
        finishedAt = (Get-Date).ToString("o")
        errorType = $_.Exception.GetType().FullName
        errorMessage = $_.Exception.Message
        scriptStackTrace = $_.ScriptStackTrace
    }
    $failure | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $evidenceDirectory "failure-summary.json") -Encoding utf8
    Write-Error $_
    exit 1
}
