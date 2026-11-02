# Exercise the standalone root installer using a real, packaged Windows ZIP.
#Requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Archive
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$archivePath = (Resolve-Path -LiteralPath $Archive -ErrorAction Stop).Path
$archiveName = [IO.Path]::GetFileName($archivePath)
if ($archiveName -notmatch '^mini-(\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?)-windows-x64\.zip$') {
    throw "Expected a packaged archive named mini-<version>-windows-x64.zip: $archiveName"
}
$version = $Matches[1]
$sourceSums = Join-Path (Split-Path -Parent $archivePath) 'SHA256SUMS'
if (-not (Test-Path -LiteralPath $sourceSums -PathType Leaf)) {
    throw "The real package must have neighboring SHA256SUMS: $sourceSums"
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$installer = Join-Path $repoRoot 'install.ps1'
if (-not (Test-Path -LiteralPath $installer -PathType Leaf)) { throw "Installer not found: $installer" }
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('mini-release-installer-test-' + [IO.Path]::GetRandomFileName())
New-Item -ItemType Directory -Path $testRoot | Out-Null

function Assert-Throws([scriptblock]$Action, [string]$Message, [string]$Expected) {
    $didThrow = $false
    try { & $Action } catch {
        if ($_.Exception.Message -notlike $Expected) { throw }
        $didThrow = $true
    }
    if (-not $didThrow) { throw $Message }
}

function Write-Sums([string]$Directory, [string]$Name, [string]$Digest) {
    Set-Content -LiteralPath (Join-Path $Directory 'SHA256SUMS') -Value "$Digest  $Name" -Encoding ascii
}

function Get-Sha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

try {
    $sourceInstaller = Join-Path $repoRoot 'scripts/windows/install.ps1'
    $invalidSeedPrefix = Join-Path $testRoot 'must-not-install-invalid-seed'
    Assert-Throws { & $sourceInstaller -SeedVersion '../escape' -Prefix $invalidSeedPrefix } 'Source installer accepted a traversal seed version.' 'Invalid seed version:*'
    if (Test-Path -LiteralPath $invalidSeedPrefix) { throw 'Invalid seed version created an install prefix.' }

    $positiveArchive = Join-Path $testRoot $archiveName
    Copy-Item -LiteralPath $archivePath -Destination $positiveArchive
    Copy-Item -LiteralPath $sourceSums -Destination (Join-Path $testRoot 'SHA256SUMS')

    $prefix = Join-Path $testRoot 'unicode-安装-мини'
    & $installer -Archive $positiveArchive -Prefix $prefix

    $versionFile = Join-Path $prefix 'VERSION'
    if (-not (Test-Path -LiteralPath $versionFile -PathType Leaf)) { throw 'Installed VERSION file is missing.' }
    $installedVersion = (Get-Content -LiteralPath $versionFile -Raw).Trim()
    if ($installedVersion -cne $version) { throw "Installed version mismatch: expected $version, got $installedVersion." }
    $backend = (Get-Content -LiteralPath (Join-Path $prefix 'TOOLCHAIN-BACKEND') -Raw).Trim()
    if ($backend -cnotin @('c', 'llvm-api')) { throw "Unexpected installed backend marker: $backend" }
    foreach ($relative in @('bin/mini.exe', 'bin/minic-native.exe', 'lib/libminirt.a', 'include/minirt.h', 'LICENSE', 'NOTICE')) {
        if (-not (Test-Path -LiteralPath (Join-Path $prefix $relative) -PathType Leaf)) {
            throw "Installed package is missing $relative."
        }
    }
    if (-not (Test-Path -LiteralPath (Join-Path $prefix 'stdlib') -PathType Container)) {
        throw 'Installed stdlib directory is missing.'
    }
    if ($backend -eq 'llvm-api' -and -not (Test-Path -LiteralPath (Join-Path $prefix 'LLVM-LICENSE.txt') -PathType Leaf)) {
        throw 'The installed LLVM API package is missing its LLVM license.'
    }
    $miniExe = Join-Path $prefix 'bin/mini.exe'
    $miniHashBefore = Get-Sha256 $miniExe
    & $miniExe --version
    if ($LASTEXITCODE -ne 0) { throw "Installed mini.exe --version exited with code $LASTEXITCODE." }

    Assert-Throws { & $installer -Archive $positiveArchive -Prefix $prefix } 'Installer unexpectedly overwrote an existing prefix.' 'Refusing to overwrite existing install prefix:*'
    if ((Get-Sha256 $miniExe) -cne $miniHashBefore) { throw 'Existing install changed after rejected reinstall.' }

    $badRoot = Join-Path $testRoot 'bad-checksum'
    New-Item -ItemType Directory -Path $badRoot | Out-Null
    $badArchive = Join-Path $badRoot $archiveName
    Copy-Item -LiteralPath $archivePath -Destination $badArchive
    Write-Sums $badRoot $archiveName ('0' * 64)
    $badPrefix = Join-Path $testRoot 'must-not-install-bad-checksum'
    Assert-Throws { & $installer -Archive $badArchive -Prefix $badPrefix } 'Installer accepted a ZIP with a wrong SHA256.' 'SHA256 mismatch*'
    if (Test-Path -LiteralPath $badPrefix) { throw 'Wrong-checksum install left a destination prefix behind.' }

    $missingRoot = Join-Path $testRoot 'missing-checksum'
    New-Item -ItemType Directory -Path $missingRoot | Out-Null
    $missingArchive = Join-Path $missingRoot $archiveName
    Copy-Item -LiteralPath $archivePath -Destination $missingArchive
    $missingPrefix = Join-Path $testRoot 'must-not-install-missing-checksum'
    Assert-Throws { & $installer -Archive $missingArchive -Prefix $missingPrefix } 'Installer accepted an archive without SHA256SUMS.' 'Required neighboring checksum file*'
    if (Test-Path -LiteralPath $missingPrefix) { throw 'Missing-checksum install left a destination prefix behind.' }

    $maliciousRoot = Join-Path $testRoot 'malicious'
    New-Item -ItemType Directory -Path $maliciousRoot | Out-Null
    $maliciousArchive = Join-Path $maliciousRoot $archiveName
    Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.ZipFile
    $zip = [IO.Compression.ZipFile]::Open($maliciousArchive, [IO.Compression.ZipArchiveMode]::Create)
    try {
        $escapeEntry = $zip.CreateEntry("mini-$version-windows-x64/../escape.txt")
        $writer = [IO.StreamWriter]::new($escapeEntry.Open())
        try { $writer.Write('must not escape staging') } finally { $writer.Dispose() }
    } finally { $zip.Dispose() }
    Write-Sums $maliciousRoot $archiveName (Get-Sha256 $maliciousArchive)
    $maliciousPrefix = Join-Path $testRoot 'must-not-install-malicious'
    Assert-Throws { & $installer -Archive $maliciousArchive -Prefix $maliciousPrefix } 'Installer accepted a ZIP path traversal entry.' 'Unsafe ZIP entry path:*'
    if (Test-Path -LiteralPath $maliciousPrefix) { throw 'Malicious ZIP install left a destination prefix behind.' }
    if (Test-Path -LiteralPath (Join-Path $testRoot 'escape.txt')) { throw 'Malicious ZIP wrote outside its staging directory.' }

    Write-Host "Windows release installer checks passed for Mini $version ($backend backend)."
} finally {
    if (Test-Path -LiteralPath $testRoot) { Remove-Item -LiteralPath $testRoot -Recurse -Force }
}

$global:LASTEXITCODE = 0
