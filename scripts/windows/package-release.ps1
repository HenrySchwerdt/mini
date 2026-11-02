# Package the tested experimental C-only Windows x64 install as a ZIP asset.
# The archive contains one root directory: mini-<version>-windows-x64.
# No LLVM API or REPL binaries are included.
#Requires -Version 7.0
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [Parameter(Mandatory = $true)][string]$MiniHome,
    [Parameter(Mandatory = $true)][string]$OutDirectory
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($Version -notmatch '^[0-9]+\.[0-9]+\.[0-9]+(?:[-+.][0-9A-Za-z.-]+)?$') {
    throw "Invalid release version: $Version"
}
$installRoot = (Resolve-Path -LiteralPath $MiniHome).Path
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$installedVersion = (Get-Content -LiteralPath (Join-Path $installRoot 'VERSION' -ErrorAction Stop) -Raw).Trim()
if ($installedVersion -cne $Version) { throw "Install version mismatch: expected $Version, got $installedVersion" }
$backendFile = Join-Path $installRoot 'TOOLCHAIN-BACKEND'
if (-not (Test-Path -LiteralPath $backendFile) -or (Get-Content -LiteralPath $backendFile -Raw).Trim() -cne 'c') {
    throw 'Refusing to package a toolchain that is not explicitly marked TOOLCHAIN-BACKEND=c.'
}

$required = @(
    'bin/mini.exe',
    'bin/minic-native.exe',
    'lib/libminirt.a',
    'include/minirt.h',
    'stdlib',
    'VERSION',
    'TOOLCHAIN-BACKEND'
)
foreach ($relative in $required) {
    if (-not (Test-Path -LiteralPath (Join-Path $installRoot $relative))) {
        throw "Required Windows package input is missing: $relative"
    }
}
foreach ($relative in @('LICENSE', 'NOTICE')) {
    if (-not (Test-Path -LiteralPath (Join-Path $repositoryRoot $relative))) {
        throw "Required repository license file is missing: $relative"
    }
}

$out = [IO.Path]::GetFullPath($OutDirectory)
if (-not (Test-Path -LiteralPath $out)) { New-Item -ItemType Directory -Path $out | Out-Null }
$zipName = "mini-$Version-windows-x64.zip"
$zipPath = Join-Path $out $zipName
if (Test-Path -LiteralPath $zipPath) { throw "Refusing to overwrite existing package: $zipPath" }

$stageParent = Join-Path ([IO.Path]::GetTempPath()) ("mini-windows-package-" + [IO.Path]::GetRandomFileName())
$stageRoot = Join-Path $stageParent "mini-$Version-windows-x64"
New-Item -ItemType Directory -Path $stageParent | Out-Null
try {
    New-Item -ItemType Directory -Path $stageRoot | Out-Null
    foreach ($relative in @('bin/mini.exe', 'bin/minic-native.exe', 'lib/libminirt.a', 'include/minirt.h', 'VERSION', 'TOOLCHAIN-BACKEND')) {
        $source = Join-Path $installRoot $relative
        $destination = Join-Path $stageRoot $relative
        $destinationParent = Split-Path -Parent $destination
        if (-not (Test-Path -LiteralPath $destinationParent)) { New-Item -ItemType Directory -Path $destinationParent | Out-Null }
        Copy-Item -LiteralPath $source -Destination $destination
    }
    Copy-Item -LiteralPath (Join-Path $installRoot 'stdlib') -Destination (Join-Path $stageRoot 'stdlib') -Recurse
    Copy-Item -LiteralPath (Join-Path $repositoryRoot 'LICENSE') -Destination $stageRoot
    Copy-Item -LiteralPath (Join-Path $repositoryRoot 'NOTICE') -Destination $stageRoot

    # Point Compress-Archive at the one named root folder (not its contents).
    Compress-Archive -Path $stageRoot -DestinationPath $zipPath -CompressionLevel Optimal
    if (-not (Test-Path -LiteralPath $zipPath -PathType Leaf)) { throw "ZIP creation did not produce $zipPath" }
    Write-Host "Created $zipPath"
    Write-Host "Archive root: mini-$Version-windows-x64/"
    Write-Host 'Package contains the experimental C backend only; no LLVM API or REPL.'
} finally {
    if (Test-Path -LiteralPath $stageParent) { Remove-Item -LiteralPath $stageParent -Recurse -Force }
}
