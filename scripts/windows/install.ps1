#Requires -Version 7.0
# Experimental C-only Windows source install. Run from a cloned Mini checkout.
param(
    [string]$Prefix = '',
    [string]$SeedVersion = '0.7.0'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'setup.ps1')
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if ([string]::IsNullOrWhiteSpace($Prefix)) {
    if ([string]::IsNullOrWhiteSpace($env:USERPROFILE)) { throw 'USERPROFILE is not set; pass -Prefix explicitly.' }
    $Prefix = Join-Path $env:USERPROFILE '.mini-windows'
}
$prefixPath = [IO.Path]::GetFullPath($Prefix)
if (Test-Path -LiteralPath $prefixPath) { throw "Refusing to overwrite existing install prefix: $prefixPath" }
$seedName = "mini-$SeedVersion-bootstrap-c"
$archiveName = "$seedName.tar.gz"
$baseUrl = "https://github.com/HenrySchwerdt/mini/releases/download/v$SeedVersion"
$tempBase = if ($env:TEMP) { $env:TEMP } else { [IO.Path]::GetTempPath() }
$parent = Split-Path -Parent $prefixPath
if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Path $parent | Out-Null }
# Stage beside the destination: directory moves across Windows volumes are not supported.
$package = Join-Path $parent ('.mini-install-stage-' + [IO.Path]::GetRandomFileName())
if (Test-Path -LiteralPath $package) { throw "Staging path already exists: $package" }
$work = Join-Path $tempBase ("mini-windows-install-" + [IO.Path]::GetRandomFileName())
New-Item -ItemType Directory -Path $work | Out-Null

try {
    $archive = Join-Path $work $archiveName
    $sums = Join-Path $work 'SHA256SUMS'
    Write-Host "Downloading published Mini $SeedVersion C seed..."
    Invoke-WebRequest -Uri "$baseUrl/$archiveName" -OutFile $archive
    Invoke-WebRequest -Uri "$baseUrl/SHA256SUMS" -OutFile $sums

    $line = Get-Content -LiteralPath $sums | Where-Object { $_ -match "\s+$([regex]::Escape($archiveName))$" } | Select-Object -First 1
    if (-not $line) { throw "No checksum entry for $archiveName in SHA256SUMS." }
    $expected = ($line -split '\s+')[0].ToLowerInvariant()
    $actual = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $expected) { throw "Seed SHA256 mismatch: expected $expected, got $actual" }

    $seedExtract = Join-Path $work 'seed'
    New-Item -ItemType Directory -Path $seedExtract | Out-Null
    & tar.exe -xzf $archive -C $seedExtract
    if ($LASTEXITCODE -ne 0) { throw "Seed extraction failed with exit code $LASTEXITCODE" }
    $seedDirectory = Join-Path $seedExtract $seedName
    if (-not (Test-Path -LiteralPath (Join-Path $seedDirectory 'minic.c'))) { throw "Extracted C seed is missing: $seedDirectory" }

    $bootstrapWork = Join-Path $work 'bootstrap'
    & (Join-Path $PSScriptRoot 'bootstrap-c.ps1') -SeedDirectory $seedDirectory -WorkDirectory $bootstrapWork
    & (Join-Path $PSScriptRoot 'build-mini.ps1') -MinicNative (Join-Path $bootstrapWork 'stage3.exe') -OutputDirectory $package

    # The build directory contains intermediate C and object files, which are not part of the install.
    Remove-Item -LiteralPath (Join-Path $package 'work') -Recurse -Force
    if (Test-Path -LiteralPath $prefixPath) { throw "Refusing to overwrite existing install prefix: $prefixPath" }
    [IO.Directory]::Move($package, $prefixPath)

    Write-Host "Installed experimental C-only Mini under $prefixPath"
    Write-Host 'No system or user PATH settings were changed. To add it to this PowerShell session:'
    Write-Host ('$env:PATH = "' + (Join-Path $prefixPath 'bin') + ';$env:PATH"')
    Write-Host 'This source install uses the C backend and does not include LLVM API or REPL support.'
} finally {
    if (Test-Path -LiteralPath $package) { Remove-Item -LiteralPath $package -Recurse -Force }
    if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force }
}
