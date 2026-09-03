param(
    [Parameter(Mandatory = $true)] [string] $UpstreamRoot,
    [string] $AndroidSdk = "$env:LOCALAPPDATA\Android\Sdk",
    [string] $NdkVersion = "25.2.9519653",
    [string] $CmakeVersion = "3.22.1"
)

$ErrorActionPreference = "Stop"
$ExpectedCommit = "13338e38debe2e400b3eeecf6792312d01a692f9"
$ExpectedNcnnCommit = "ec19da2b615cc8be438ae3d31fd34fe23df03d52"
$ExpectedWebpCommit = "5abb55823bb6196a918dd87202b2f32bbaff4c18"
$ExpectedParamSha256 = "5bd2ecebc17487798bd421476b44fe4e1730250bd91d402140cdf1ed6e23468f"
$ExpectedWeightSha256 = "350a15e464bea5ad378e06c0fb43996e90a0d35653d5a6ef6bc980d832538fb7"
$ResolvedRoot = (Resolve-Path -LiteralPath $UpstreamRoot).Path

if ((git -C $ResolvedRoot rev-parse HEAD) -ne $ExpectedCommit) {
    throw "Unexpected rife-ncnn-vulkan commit"
}

git -C $ResolvedRoot submodule update --init --recursive --depth 1
if ($LASTEXITCODE -ne 0) { throw "Submodule initialization failed" }
$NcnnRoot = Join-Path $ResolvedRoot "src\ncnn"
if ((git -C $NcnnRoot rev-parse HEAD) -ne $ExpectedNcnnCommit) {
    throw "Unexpected ncnn submodule commit"
}
if ((git -C (Join-Path $ResolvedRoot "src\libwebp") rev-parse HEAD) -ne $ExpectedWebpCommit) {
    throw "Unexpected libwebp submodule commit"
}

$TimingPatch = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "patches\rife-v425-lite-model-timing.txt")).Path
$CompatPatch = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "patches\rife-v425-lite-ncnn-pack8-compat.txt")).Path
$MainSource = Join-Path $ResolvedRoot "src\main.cpp"
$GpuSource = Join-Path $NcnnRoot "src\gpu.cpp"
if (-not (Select-String -Quiet -LiteralPath $MainSource -SimpleMatch "VFI_VULKAN_INIT_WALL_NS")) {
    git -C $ResolvedRoot apply --check $TimingPatch
    if ($LASTEXITCODE -ne 0) { throw "Timing patch does not apply to the pinned source" }
    git -C $ResolvedRoot apply $TimingPatch
    if ($LASTEXITCODE -ne 0) { throw "Timing patch failed" }
}
if (-not (Select-String -Quiet -LiteralPath $GpuSource -SimpleMatch 'custom_defines.append("afpvec8", "mat2x4")')) {
    git -C $NcnnRoot apply --check $CompatPatch
    if ($LASTEXITCODE -ne 0) { throw "ncnn pack8 compatibility patch does not apply to the pinned submodule" }
    git -C $NcnnRoot apply $CompatPatch
    if ($LASTEXITCODE -ne 0) { throw "ncnn pack8 compatibility patch failed" }
}

$ModelRoot = Join-Path $ResolvedRoot "models\rife-v4.25-lite"
$ParamPath = Join-Path $ModelRoot "flownet.param"
$WeightPath = Join-Path $ModelRoot "flownet.bin"
if ((Get-FileHash -Algorithm SHA256 -LiteralPath $ParamPath).Hash.ToLowerInvariant() -ne $ExpectedParamSha256) {
    throw "Unexpected rife-v4.25-lite parameter file"
}
if ((Get-FileHash -Algorithm SHA256 -LiteralPath $WeightPath).Hash.ToLowerInvariant() -ne $ExpectedWeightSha256) {
    throw "Unexpected rife-v4.25-lite weight file"
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
[pscustomobject]@{
    Path = $Binary
    Bytes = (Get-Item -LiteralPath $Binary).Length
    Sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $Binary).Hash.ToLowerInvariant()
    SourceCommit = $ExpectedCommit
    NcnnCommit = $ExpectedNcnnCommit
    WebpCommit = $ExpectedWebpCommit
}
