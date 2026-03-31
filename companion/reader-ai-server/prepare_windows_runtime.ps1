param(
    [string]$RuntimeDir = $(Join-Path $env:TEMP "mihon-realesrgan-runtime"),
    [string]$ReleaseUrl = "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/realesrgan-ncnn-vulkan-20220424-windows.zip",
    [switch]$Force
)

$ErrorActionPreference = "Stop"

$runtimeRoot = [System.IO.Path]::GetFullPath($RuntimeDir)
$requiredExe = Join-Path $runtimeRoot "realesrgan-ncnn-vulkan.exe"
$requiredModel = Join-Path $runtimeRoot "models\\realesr-animevideov3-x2.bin"

if (-not $Force -and (Test-Path $requiredExe) -and (Test-Path $requiredModel)) {
    Write-Host "Using existing runtime: $runtimeRoot"
    exit 0
}

$workRoot = Join-Path $env:TEMP ("mihonai-runtime-" + [Guid]::NewGuid().ToString("N"))
$zipPath = Join-Path $workRoot "runtime.zip"
$extractDir = Join-Path $workRoot "extract"

try {
    New-Item -ItemType Directory -Path $extractDir -Force | Out-Null
    Write-Host "Downloading Real-ESRGAN runtime..."
    Invoke-WebRequest -Uri $ReleaseUrl -OutFile $zipPath

    Write-Host "Extracting runtime..."
    Expand-Archive -Path $zipPath -DestinationPath $extractDir -Force

    $exe = Get-ChildItem -Path $extractDir -Recurse -File -Filter "realesrgan-ncnn-vulkan.exe" | Select-Object -First 1
    if (-not $exe) {
        throw "realesrgan-ncnn-vulkan.exe was not found in the downloaded runtime package."
    }

    $sourceRoot = $exe.Directory.FullName
    $modelsDir = Join-Path $sourceRoot "models"
    if (-not (Test-Path $modelsDir)) {
        throw "models directory was not found next to the downloaded runtime executable."
    }

    if (Test-Path $runtimeRoot) {
        Remove-Item -Path $runtimeRoot -Recurse -Force
    }

    New-Item -ItemType Directory -Path $runtimeRoot -Force | Out-Null
    Copy-Item -Path $exe.FullName -Destination $runtimeRoot
    Get-ChildItem -Path $sourceRoot -File -Filter "*.dll" | Copy-Item -Destination $runtimeRoot

    $readmePath = Join-Path $sourceRoot "README_windows.md"
    if (Test-Path $readmePath) {
        Copy-Item -Path $readmePath -Destination $runtimeRoot
    }

    Copy-Item -Path $modelsDir -Destination (Join-Path $runtimeRoot "models") -Recurse

    Write-Host "Prepared runtime: $runtimeRoot"
} finally {
    if (Test-Path $workRoot) {
        Remove-Item -Path $workRoot -Recurse -Force
    }
}
