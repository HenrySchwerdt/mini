#Requires -Version 7.0
param(
    [Parameter(Mandatory = $true)]
    [string]$Prefix
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$assetId = 175624214
$assetName = 'clang+llvm-18.1.8-x86_64-pc-windows-msvc.tar.xz'
$assetSize = 981666720
$releaseTag = 'llvmorg-18.1.8'
$apiUri = "https://api.github.com/repos/llvm/llvm-project/releases/assets/$assetId"

function Require-Command([string]$Name) {
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if (-not $command) { throw "Required command not found on PATH: $Name" }
    return $command.Source
}

$curl = Require-Command 'curl.exe'
$sevenZipCommand = Get-Command '7z.exe' -ErrorAction SilentlyContinue
$sevenZip = if ($sevenZipCommand) { $sevenZipCommand.Source } else { Join-Path $env:ProgramFiles '7-Zip/7z.exe' }
if (-not (Test-Path -LiteralPath $sevenZip -PathType Leaf)) { throw '7-Zip is required to extract the official LLVM development archive.' }
$cmake = Require-Command 'cmake.exe'
$ninja = Require-Command 'ninja.exe'
foreach ($tool in @(@{ Name = 'curl'; Path = $curl }, @{ Name = '7-Zip'; Path = $sevenZip }, @{ Name = 'cmake'; Path = $cmake }, @{ Name = 'ninja'; Path = $ninja })) {
    Write-Host "$($tool.Name): $($tool.Path)"
}

$metadata = Invoke-RestMethod -Uri $apiUri -Headers @{ Accept = 'application/vnd.github+json' }
if ($metadata.id -ne $assetId -or $metadata.name -cne $assetName -or [long]$metadata.size -ne $assetSize) {
    throw "GitHub release asset metadata mismatch: id=$($metadata.id), name=$($metadata.name), size=$($metadata.size)"
}
if ([string]$metadata.browser_download_url -notlike "https://github.com/llvm/llvm-project/releases/download/$releaseTag/*") {
    throw "Unexpected LLVM release asset URL: $($metadata.browser_download_url)"
}

$Prefix = [IO.Path]::GetFullPath($Prefix)
$config = Join-Path $Prefix 'lib/cmake/llvm/LLVMConfig.cmake'
$orcHeader = Join-Path $Prefix 'include/llvm-c/Orc.h'
$manifestPath = Join-Path $Prefix 'asset-manifest.json'

if (-not ((Test-Path -LiteralPath $config) -and (Test-Path -LiteralPath $orcHeader))) {
    if (Test-Path -LiteralPath $Prefix) {
        throw "LLVM prefix exists but is incomplete; choose a fresh prefix or repair the CI cache: $Prefix"
    }

    $prefixParent = Split-Path -Parent $Prefix
    New-Item -ItemType Directory -Force -Path $prefixParent | Out-Null
    $work = Join-Path $prefixParent ("mini-llvm18-" + [IO.Path]::GetRandomFileName())
    New-Item -ItemType Directory -Path $work | Out-Null
    try {
        $archive = Join-Path $work $assetName
        Write-Host "Downloading LLVM development asset $assetId ($assetSize bytes)"
        & $curl --fail --location --connect-timeout 30 --max-time 900 --retry 3 --retry-delay 2 --retry-max-time 1000 --output $archive $metadata.browser_download_url
        if ($LASTEXITCODE -ne 0) { throw "curl failed with exit code $LASTEXITCODE" }
        $downloadedSize = (Get-Item -LiteralPath $archive).Length
        if ($downloadedSize -ne $assetSize) { throw "LLVM asset size mismatch: expected $assetSize, got $downloadedSize" }

        Write-Host "Download complete; hashing $downloadedSize bytes at $([DateTime]::UtcNow.ToString('o'))"
        $sha256 = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
        $extract = Join-Path $work 'extract'
        New-Item -ItemType Directory -Path $extract | Out-Null
        # Use native 7-Zip rather than Windows' bundled libarchive XZ reader.
        # Keep binary data out of the PowerShell text pipeline.
        Write-Host "SHA256 $sha256; decompressing XZ at $([DateTime]::UtcNow.ToString('o'))"
        & $sevenZip x -y -bd $archive "-o$work"
        if ($LASTEXITCODE -ne 0) { throw "7-Zip XZ decompression failed with exit code $LASTEXITCODE" }
        $tarArchive = Join-Path $work ($assetName.Substring(0, $assetName.Length - 3))
        if (-not (Test-Path -LiteralPath $tarArchive -PathType Leaf)) { throw "Decompressed TAR missing: $tarArchive" }
        Write-Host "Extracting TAR at $([DateTime]::UtcNow.ToString('o'))"
        & $sevenZip x -y -bd $tarArchive "-o$extract"
        if ($LASTEXITCODE -ne 0) { throw "7-Zip TAR extraction failed with exit code $LASTEXITCODE" }
        Write-Host "Extraction complete at $([DateTime]::UtcNow.ToString('o'))"

        $roots = @(Get-ChildItem -LiteralPath $extract -Directory)
        if ($roots.Count -ne 1) { throw "Expected one root directory in LLVM archive; found $($roots.Count)" }
        Move-Item -LiteralPath $roots[0].FullName -Destination $Prefix

        $manifest = [ordered]@{
            release_tag = $releaseTag
            asset_id = $assetId
            asset_name = $assetName
            asset_size_bytes = $assetSize
            asset_url = [string]$metadata.browser_download_url
            downloaded_sha256 = $sha256
            sha256_source = 'computed locally; GitHub release metadata did not publish an independent checksum'
        }
        $manifest | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath $manifestPath -Encoding utf8NoBOM
        Write-Host "Downloaded archive SHA256: $sha256 (locally computed; not independently verified)"
    }
    finally {
        Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
    }
}
else {
    if (-not (Test-Path -LiteralPath $manifestPath)) { throw "Cached LLVM prefix has no asset manifest: $manifestPath" }
    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    if ($manifest.asset_id -ne $assetId -or $manifest.asset_name -cne $assetName -or [long]$manifest.asset_size_bytes -ne $assetSize) {
        throw "Cached LLVM prefix manifest does not match asset $assetId"
    }
    Write-Host "Using cached LLVM 18.1.8 prefix for asset $assetId"
    Write-Host "Cached archive SHA256 (computed locally, not independently verified): $($manifest.downloaded_sha256)"
}

foreach ($required in @($config, $orcHeader, (Join-Path $Prefix 'include/llvm/Config/llvm-config.h'))) {
    if (-not (Test-Path -LiteralPath $required)) { throw "LLVM development archive is missing required file: $required" }
}

$staticArchives = @(Get-ChildItem -LiteralPath (Join-Path $Prefix 'lib') -Filter 'LLVMCore.lib' -File -Recurse -ErrorAction SilentlyContinue)
if ($staticArchives.Count -eq 0) {
    throw "LLVM static component archive LLVMCore.lib was not found under $(Join-Path $Prefix 'lib'); refusing to fall back to a DLL or source build."
}

Write-Host "LLVM prefix: $Prefix"
Write-Host "LLVM CMake package: $config"
Write-Host "LLVM component archive found: $($staticArchives[0].FullName)"
