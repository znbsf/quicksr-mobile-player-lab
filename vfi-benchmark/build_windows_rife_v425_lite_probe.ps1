param(
    [Parameter(Mandatory = $true)] [string] $UpstreamRoot,
    [Parameter(Mandatory = $true)] [string] $VulkanIncludeDir,
    [Parameter(Mandatory = $true)] [string] $VulkanLibrary,
    [string] $Cmake = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\2019\BuildTools\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe"
)

$ErrorActionPreference = "Stop"
$ExpectedCommit = "13338e38debe2e400b3eeecf6792312d01a692f9"
$ExpectedNcnnCommit = "ec19da2b615cc8be438ae3d31fd34fe23df03d52"
$ExpectedWebpCommit = "5abb55823bb6196a918dd87202b2f32bbaff4c18"
$ExpectedParamSha256 = "5bd2ecebc17487798bd421476b44fe4e1730250bd91d402140cdf1ed6e23468f"
$ExpectedWeightSha256 = "350a15e464bea5ad378e06c0fb43996e90a0d35653d5a6ef6bc980d832538fb7"
$ResolvedRoot = (Resolve-Path -LiteralPath $UpstreamRoot).Path
$ResolvedVulkanInclude = (Resolve-Path -LiteralPath $VulkanIncludeDir).Path
$ResolvedVulkanLibrary = (Resolve-Path -LiteralPath $VulkanLibrary).Path

if ((git -C $ResolvedRoot rev-parse HEAD) -ne $ExpectedCommit) { throw "Unexpected rife-ncnn-vulkan commit" }
git -C $ResolvedRoot submodule update --init --recursive --depth 1
if ($LASTEXITCODE -ne 0) { throw "Submodule initialization failed" }
$NcnnRoot = Join-Path $ResolvedRoot "src\ncnn"
if ((git -C $NcnnRoot rev-parse HEAD) -ne $ExpectedNcnnCommit) { throw "Unexpected ncnn submodule commit" }
if ((git -C (Join-Path $ResolvedRoot "src\libwebp") rev-parse HEAD) -ne $ExpectedWebpCommit) { throw "Unexpected libwebp submodule commit" }

$CompatPatch = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "patches\rife-v425-lite-ncnn-pack8-compat.txt")).Path
$GpuSource = Join-Path $NcnnRoot "src\gpu.cpp"
if (-not (Select-String -Quiet -LiteralPath $GpuSource -SimpleMatch 'custom_defines.append("afpvec8", "mat2x4")')) {
    git -C $NcnnRoot apply --check $CompatPatch
    if ($LASTEXITCODE -ne 0) { throw "ncnn pack8 compatibility patch does not apply to the pinned submodule" }
    git -C $NcnnRoot apply $CompatPatch
    if ($LASTEXITCODE -ne 0) { throw "ncnn pack8 compatibility patch failed" }
}

$ModelRoot = Join-Path $ResolvedRoot "models\rife-v4.25-lite"
if ((Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $ModelRoot "flownet.param")).Hash.ToLowerInvariant() -ne $ExpectedParamSha256) {
    throw "Unexpected rife-v4.25-lite parameter file"
}
if ((Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $ModelRoot "flownet.bin")).Hash.ToLowerInvariant() -ne $ExpectedWeightSha256) {
    throw "Unexpected rife-v4.25-lite weight file"
}

$BuildRoot = Join-Path $ResolvedRoot "build-windows-x64"
& $Cmake -S (Join-Path $ResolvedRoot "src") -B $BuildRoot -G "Visual Studio 16 2019" -A x64 `
    "-DVulkan_INCLUDE_DIR=$ResolvedVulkanInclude" "-DVulkan_LIBRARY=$ResolvedVulkanLibrary"
if ($LASTEXITCODE -ne 0) { throw "Windows CMake configure failed" }
# Building only the executable serially avoids MSVC FileTracker path pressure in unrelated ALL targets.
& $Cmake --build $BuildRoot --config Release --target rife-ncnn-vulkan -j 1
if ($LASTEXITCODE -ne 0) { throw "Windows build failed" }

$Binary = Join-Path $BuildRoot "Release\rife-ncnn-vulkan.exe"
[pscustomobject]@{
    Path = $Binary
    Bytes = (Get-Item -LiteralPath $Binary).Length
    Sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $Binary).Hash.ToLowerInvariant()
    SourceCommit = $ExpectedCommit
    NcnnCommit = $ExpectedNcnnCommit
    WebpCommit = $ExpectedWebpCommit
}
