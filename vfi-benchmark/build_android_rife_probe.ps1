param(
    [Parameter(Mandatory = $true)] [string] $UpstreamRoot,
    [string] $AndroidSdk = "$env:LOCALAPPDATA\Android\Sdk",
    [string] $NdkVersion = "25.2.9519653",
    [string] $CmakeVersion = "3.22.1"
)

$ErrorActionPreference = "Stop"
$ExpectedCommit = "a7532fc3f9f8f008cd6eecd6f2ffe2a9698e0cf7"
$ExpectedNcnnCommit = "b4ba207c18d3103d6df890c0e3a97b469b196b26"
$ExpectedWebpCommit = "5abb55823bb6196a918dd87202b2f32bbaff4c18"
$ResolvedRoot = (Resolve-Path -LiteralPath $UpstreamRoot).Path

if ((git -C $ResolvedRoot rev-parse HEAD) -ne $ExpectedCommit) {
    throw "Unexpected rife-ncnn-vulkan commit"
}
git -C $ResolvedRoot submodule update --init --recursive --depth 1
if ((git -C (Join-Path $ResolvedRoot "src\ncnn") rev-parse HEAD) -ne $ExpectedNcnnCommit) {
    throw "Unexpected ncnn submodule commit"
}
if ((git -C (Join-Path $ResolvedRoot "src\libwebp") rev-parse HEAD) -ne $ExpectedWebpCommit) {
    throw "Unexpected libwebp submodule commit"
}

$PatchPath = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "patches\rife-ncnn-vulkan-model-timing.txt")).Path
$MainSource = Join-Path $ResolvedRoot "src\main.cpp"
if (-not (Select-String -Quiet -LiteralPath $MainSource -Pattern "VFI_MODEL_WALL_NS")) {
    git -C $ResolvedRoot apply --check $PatchPath
    if ($LASTEXITCODE -ne 0) { throw "Timing patch does not apply" }
    git -C $ResolvedRoot apply $PatchPath
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
& $Cmake --build $BuildRoot --target rife-ncnn-vulkan -j 8
if ($LASTEXITCODE -ne 0) { throw "Android build failed" }

$Binary = Join-Path $BuildRoot "rife-ncnn-vulkan"
$Hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $Binary).Hash.ToLowerInvariant()
[pscustomobject]@{
    Path = $Binary
    Bytes = (Get-Item -LiteralPath $Binary).Length
    Sha256 = $Hash
    SourceCommit = $ExpectedCommit
    NcnnCommit = $ExpectedNcnnCommit
    WebpCommit = $ExpectedWebpCommit
}
