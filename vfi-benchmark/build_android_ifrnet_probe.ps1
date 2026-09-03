param(
    [Parameter(Mandatory = $true)] [string] $UpstreamRoot,
    [string] $AndroidSdk = "$env:LOCALAPPDATA\Android\Sdk",
    [string] $NdkVersion = "25.2.9519653",
    [string] $CmakeVersion = "3.22.1"
)

$ErrorActionPreference = "Stop"
$ExpectedCommit = "3592a70355ec011fe7cefb3a9ba08b63d82a2b6d"
$ExpectedNcnnCommit = "30ab31cc4194f57866ba48753aeceae40e823d81"
$ExpectedWebpCommit = "5a2d929cd8a627d7a342e78ce4603167022b76af"
$ResolvedRoot = (Resolve-Path -LiteralPath $UpstreamRoot).Path

if ((git -C $ResolvedRoot rev-parse HEAD) -ne $ExpectedCommit) {
    throw "Unexpected ifrnet-ncnn-vulkan commit"
}

$PatchPath = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "patches\ifrnet-ncnn-vulkan-model-timing.txt")).Path
$MainSource = Join-Path $ResolvedRoot "src\main.cpp"
$HasCurrentTimingPatch = Select-String -Quiet -LiteralPath $MainSource -Pattern "VFI_VULKAN_INIT_WALL_NS"
$HasLegacyTimingPatch = Select-String -Quiet -LiteralPath $MainSource -SimpleMatch "VFI_MODEL_WALL_NS="
if (-not $HasCurrentTimingPatch -and $HasLegacyTimingPatch) {
    throw "Legacy VFI timing patch detected. Use a clean pinned ifrnet-ncnn-vulkan clone; this script will not reset or rewrite an existing upstream tree."
}
if (-not $HasCurrentTimingPatch) {
    git -C $ResolvedRoot apply --check $PatchPath
    if ($LASTEXITCODE -ne 0) { throw "Timing patch does not apply; use a clean pinned ifrnet-ncnn-vulkan clone" }
    git -C $ResolvedRoot apply $PatchPath
}

git -C $ResolvedRoot submodule update --init --recursive --depth 1
if ((git -C (Join-Path $ResolvedRoot "src\ncnn") rev-parse HEAD) -ne $ExpectedNcnnCommit) {
    throw "Unexpected ncnn submodule commit"
}
if ((git -C (Join-Path $ResolvedRoot "src\libwebp") rev-parse HEAD) -ne $ExpectedWebpCommit) {
    throw "Unexpected libwebp submodule commit"
}

$Cmake = Join-Path $AndroidSdk "cmake\$CmakeVersion\bin\cmake.exe"
$Ninja = Join-Path $AndroidSdk "cmake\$CmakeVersion\bin\ninja.exe"
$Toolchain = Join-Path $AndroidSdk "ndk\$NdkVersion\build\cmake\android.toolchain.cmake"
$BuildRoot = Join-Path $ResolvedRoot "build-android-arm64"
& $Cmake -S (Join-Path $ResolvedRoot "src") -B $BuildRoot -G Ninja `
    "-DCMAKE_MAKE_PROGRAM=$Ninja" "-DCMAKE_TOOLCHAIN_FILE=$Toolchain" `
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-27 -DANDROID_STL=c++_static `
    -DCMAKE_BUILD_TYPE=Release
if ($LASTEXITCODE -ne 0) { throw "Android CMake configure failed" }
& $Cmake --build $BuildRoot --target ifrnet-ncnn-vulkan -j 8
if ($LASTEXITCODE -ne 0) { throw "Android build failed" }

$Binary = Join-Path $BuildRoot "ifrnet-ncnn-vulkan"
$Hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $Binary).Hash.ToLowerInvariant()
[pscustomobject]@{
    Path = $Binary
    Bytes = (Get-Item -LiteralPath $Binary).Length
    Sha256 = $Hash
    SourceCommit = $ExpectedCommit
    NcnnCommit = $ExpectedNcnnCommit
    WebpCommit = $ExpectedWebpCommit
}
