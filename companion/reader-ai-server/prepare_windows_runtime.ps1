param(
    [string]$RuntimeDir = $(Join-Path $env:TEMP "mihon-realesrgan-runtime-ncnn20261020"),
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$runtimeVersion = "ncnn-20261020-mihon1"
$pins = [ordered]@{
    realesrgan = "37026f49824c5cf84062e7c6a5dd71445dcf610f"
    ncnn = "7e36d25cb153092ba87a60530455c7376fedf452"
    glslang = "780b6a209132dc74e99458f62ebe5fa7e57c41cf"
    webp = "4fa21912338357f89e4fd51cf2368325b59e9bd9"
}
$modelUrl = "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/realesrgan-ncnn-vulkan-20220424-windows.zip"
$modelArchiveSha256 = "abc02804e17982a3be33675e4d471e91ea374e65b70167abc09e31acb412802d"
$runtimeRoot = [System.IO.Path]::GetFullPath($RuntimeDir)
$nativeRoot = Join-Path $PSScriptRoot "native"
$manifestPath = Join-Path $runtimeRoot "native-runtime-manifest.json"
$expectedFiles = @("realesrgan-ncnn-vulkan.exe", "licenses/Real-ESRGAN.txt", "licenses/NCNN.txt", "licenses/glslang.txt", "licenses/libwebp.txt")
foreach ($modelName in @("realesr-animevideov3-x2", "realesr-animevideov3-x3", "realesr-animevideov3-x4", "realesrgan-x4plus-anime", "realesrgan-x4plus")) {
    $expectedFiles += "models/$modelName.param", "models/$modelName.bin"
}

function Get-RuntimeRelativePath([string]$Path) {
    $fullPath = [System.IO.Path]::GetFullPath($Path)
    $rootPrefix = $runtimeRoot.TrimEnd('\') + '\'
    if (-not $fullPath.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Runtime file escaped its expected directory"
    }
    return $fullPath.Substring($rootPrefix.Length).Replace('\', '/')
}

# Never package stale DLLs, older aliases or arbitrary files from an explicitly
# supplied old cache directory. Existing unknown files are preserved and rejected.
if (Test-Path -LiteralPath $runtimeRoot) {
    Get-ChildItem -LiteralPath $runtimeRoot -Recurse -File | ForEach-Object {
        $relative = Get-RuntimeRelativePath $_.FullName
        if ($relative -cne "native-runtime-manifest.json" -and $expectedFiles -cnotcontains $relative) {
            throw "Unrecognized runtime file '$relative'. Choose a new empty MIHONAI_RUNTIME_DIR. Existing files were preserved."
        }
    }
}
$buildInputs = [ordered]@{}
foreach ($name in @("CMakeLists.txt", "patch_runtime.py", "cpu_model_smoke.cpp", "native_failure_test.cpp")) {
    $buildInputs[$name] = (Get-FileHash -LiteralPath (Join-Path $nativeRoot $name) -Algorithm SHA256).Hash.ToLowerInvariant()
}

if (-not $Force -and (Test-Path -LiteralPath $manifestPath)) {
    $cached = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    $cacheValid = $cached.format -ceq "mihon-native-runtime" -and $cached.version -eq 1 -and $cached.runtime_version -ceq $runtimeVersion -and $cached.model_archive_sha256 -ceq $modelArchiveSha256
    $cachedFiles = @($cached.files.PSObject.Properties.Name)
    $cacheValid = $cacheValid -and $cachedFiles.Count -eq $expectedFiles.Count
    foreach ($name in $expectedFiles) { $cacheValid = $cacheValid -and ($cachedFiles -ccontains $name) }
    foreach ($pin in $pins.Keys) { $cacheValid = $cacheValid -and ($cached.pins.$pin -ceq $pins[$pin]) }
    foreach ($name in $buildInputs.Keys) { $cacheValid = $cacheValid -and ($cached.build_inputs.$name -ceq $buildInputs[$name]) }
    foreach ($file in $cached.files.PSObject.Properties) {
        if ($expectedFiles -cnotcontains $file.Name) { $cacheValid = $false; continue }
        $path = Join-Path $runtimeRoot $file.Name
        $cacheValid = $cacheValid -and (Test-Path -LiteralPath $path -PathType Leaf)
        if ($cacheValid) { $cacheValid = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -ceq $file.Value }
    }
    if ($cacheValid -and $cached.cpu_smoke.Count -eq 5 -and $cached.native_failure_exit -eq 71) {
        Write-Host "Using verified modern native runtime: $runtimeRoot"
        exit 0
    }
}

# Only this freshly created and verified child of TEMP is removed afterwards.
$tempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
$workRoot = [System.IO.Path]::GetFullPath((Join-Path $tempRoot ("mihonai-native-" + [Guid]::NewGuid().ToString("N"))))
$python = (Get-Command python -ErrorAction Stop).Source

function Invoke-Checked([string]$Program, [string[]]$Arguments) {
    & $Program @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Program failed with exit code $LASTEXITCODE" }
}

function Get-PinnedGit([string]$Url, [string]$Commit, [string]$Destination) {
    Invoke-Checked "git" @("init", "--quiet", $Destination)
    Invoke-Checked "git" @("-C", $Destination, "remote", "add", "origin", $Url)
    Invoke-Checked "git" @("-C", $Destination, "fetch", "--quiet", "--depth", "1", "origin", $Commit)
    Invoke-Checked "git" @("-C", $Destination, "checkout", "--quiet", "--detach", "FETCH_HEAD")
    $actual = (& git -C $Destination rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0 -or $actual -cne $Commit) { throw "Pinned source mismatch: $Url" }
}

try {
    New-Item -ItemType Directory -Path $workRoot -Force | Out-Null
    $realSource = Join-Path $workRoot "realesrgan"
    $ncnnSource = Join-Path $workRoot "ncnn"
    $webpSource = Join-Path $workRoot "webp"
    Write-Host "Fetching pinned native sources (NCNN 20261020)..."
    Get-PinnedGit "https://github.com/xinntao/Real-ESRGAN-ncnn-vulkan.git" $pins.realesrgan $realSource
    Get-PinnedGit "https://github.com/Tencent/ncnn.git" $pins.ncnn $ncnnSource
    Get-PinnedGit "https://github.com/nihui/glslang.git" $pins.glslang (Join-Path $ncnnSource "glslang")
    Get-PinnedGit "https://github.com/webmproject/libwebp.git" $pins.webp $webpSource
    $patched = Join-Path $workRoot "patched"
    Invoke-Checked $python @((Join-Path $nativeRoot "patch_runtime.py"), "--source", $realSource, "--destination", $patched, "--ncnn-source", $ncnnSource)

    $build = Join-Path $workRoot "build"
    Invoke-Checked "cmake" @("-S", $nativeRoot, "-B", $build, "-G", "Visual Studio 17 2022", "-A", "x64",
        "-DMIHON_NCNN_SOURCE=$ncnnSource", "-DMIHON_WEBP_SOURCE=$webpSource", "-DMIHON_APP_SOURCE=$patched")
    Invoke-Checked "cmake" @("--build", $build, "--config", "Release", "--parallel", "2")

    # The 2022 archive supplies model data only; its EXE is never copied.
    $modelZip = Join-Path $workRoot "official-models.zip"
    Invoke-WebRequest -Uri $modelUrl -OutFile $modelZip
    if ((Get-FileHash -LiteralPath $modelZip -Algorithm SHA256).Hash.ToLowerInvariant() -cne $modelArchiveSha256) {
        throw "Official model archive checksum mismatch"
    }
    $modelExtract = Join-Path $workRoot "model-data"
    Expand-Archive -LiteralPath $modelZip -DestinationPath $modelExtract
    $models = Join-Path $modelExtract "models"
    $smokeExe = Join-Path $build "Release\mihon-ncnn-smoke.exe"
    $smoke = @()
    foreach ($model in @("realesr-animevideov3-x2", "realesr-animevideov3-x3", "realesr-animevideov3-x4", "realesrgan-x4plus-anime", "realesrgan-x4plus")) {
        $scale = if ($model -match '-x([234])$') { $Matches[1] } else { "4" }
        $output = & $smokeExe (Join-Path $models "$model.param") (Join-Path $models "$model.bin") $scale
        if ($LASTEXITCODE -ne 0 -or $output -notmatch '^CPU_MODEL_SMOKE_OK .*vulkan_created=false$') {
            throw "Native CPU model smoke failed: $model / $output"
        }
        Write-Host "$model : $output"
        $smoke += @{ model=$model; output=$output; exit_code=0; gpu_used=$false }
    }
    $failureTest = Join-Path $build "Release\mihon-native-failure-test.exe"
    Invoke-Checked $failureTest @("success")
    # Windows PowerShell wraps redirected native stderr in ErrorRecord objects;
    # the expected fail-fast diagnostic must not terminate this script early.
    $ErrorActionPreference = "Continue"
    $failureOutput = & $failureTest 2>&1
    $failureExit = $LASTEXITCODE
    $ErrorActionPreference = "Stop"
    if ($failureExit -ne 71 -or $failureOutput -notmatch 'MIHON_GPU_FAULT' -or $failureOutput -match 'UNREACHABLE_AFTER_GPU_FAULT') {
        throw "Native fail-fast contract did not hold"
    }
    $global:LASTEXITCODE = 0

    New-Item -ItemType Directory -Path $runtimeRoot -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $build "Release\realesrgan-ncnn-vulkan.exe") -Destination $runtimeRoot -Force
    New-Item -ItemType Directory -Path (Join-Path $runtimeRoot "models") -Force | Out-Null
    Get-ChildItem -LiteralPath $models -File | Copy-Item -Destination (Join-Path $runtimeRoot "models") -Force
    $licenses = Join-Path $runtimeRoot "licenses"
    New-Item -ItemType Directory -Path $licenses -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $realSource "LICENSE") -Destination (Join-Path $licenses "Real-ESRGAN.txt")
    Copy-Item -LiteralPath (Join-Path $ncnnSource "LICENSE.txt") -Destination (Join-Path $licenses "NCNN.txt")
    Copy-Item -LiteralPath (Join-Path $ncnnSource "glslang\LICENSE.txt") -Destination (Join-Path $licenses "glslang.txt")
    Copy-Item -LiteralPath (Join-Path $webpSource "COPYING") -Destination (Join-Path $licenses "libwebp.txt")
    $files = [ordered]@{}
    Get-ChildItem -LiteralPath $runtimeRoot -Recurse -File | Where-Object { $_.Name -ne "native-runtime-manifest.json" } | Sort-Object FullName | ForEach-Object {
        $relative = Get-RuntimeRelativePath $_.FullName
        if ($expectedFiles -cnotcontains $relative) { throw "Unexpected new runtime file: $relative" }
        $files[$relative] = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    if ($files.Count -ne $expectedFiles.Count) { throw "Incomplete runtime file inventory" }
    [ordered]@{ format="mihon-native-runtime"; version=1; runtime_version=$runtimeVersion;
        pins=$pins; build_inputs=$buildInputs; model_archive_sha256=$modelArchiveSha256;
        native_patches=(Get-Content -LiteralPath (Join-Path $patched "patch-manifest.json") -Raw | ConvertFrom-Json);
        cpu_smoke=$smoke; native_failure_exit=$failureExit; gpu_smoke_performed=$false;
        files=$files } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $manifestPath -Encoding UTF8
    Write-Host "Prepared modern native runtime: $runtimeRoot"
} finally {
    $resolvedWork = [System.IO.Path]::GetFullPath($workRoot)
    $requiredPrefix = $tempRoot.TrimEnd('\') + '\mihonai-native-'
    if (-not $resolvedWork.StartsWith($requiredPrefix, [StringComparison]::OrdinalIgnoreCase) -or
        [System.IO.Path]::GetDirectoryName($resolvedWork).TrimEnd('\') -ine $tempRoot.TrimEnd('\')) {
        throw "Refusing cleanup outside the verified temporary build directory"
    }
    if (Test-Path -LiteralPath $resolvedWork) { Remove-Item -LiteralPath $resolvedWork -Recurse -Force }
}
