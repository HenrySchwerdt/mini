# Install the published native Windows x64 Mini release.
#Requires -Version 7.0
[CmdletBinding()]
param(
    [string]$Version = '',
    [string]$Archive = '',
    [string]$Prefix = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-ValidatedVersion([string]$Value) {
    $normalized = $Value.Trim()
    if ($normalized.StartsWith('v', [StringComparison]::OrdinalIgnoreCase)) {
        $normalized = $normalized.Substring(1)
    }
    if ($normalized -notmatch '^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$') {
        throw "Invalid Mini version: $Value"
    }
    return $normalized
}

function Get-Checksum([string]$ChecksumFile, [string]$FileName) {
    $checksumValues = @()
    foreach ($line in Get-Content -LiteralPath $ChecksumFile) {
        if ($line -match '^([0-9A-Fa-f]{64})\s+\*?(.+?)\s*$' -and $Matches[2] -ceq $FileName) {
            $checksumValues += $Matches[1].ToLowerInvariant()
        }
    }
    if ($checksumValues.Count -ne 1) {
        throw "Expected exactly one SHA256SUMS entry for $FileName; found $($checksumValues.Count)."
    }
    return $checksumValues[0]
}

function Assert-SafeArchive([string]$ZipPath, [string]$RootName) {
    Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.ZipFile
    $zip = [IO.Compression.ZipFile]::OpenRead($ZipPath)
    try {
        $rootPrefix = "$RootName/"
        $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
        $hasPayload = $false
        foreach ($entry in $zip.Entries) {
            $name = $entry.FullName
            if ([string]::IsNullOrWhiteSpace($name) -or $name.Contains('\') -or $name.StartsWith('/') -or $name -match '^[A-Za-z]:') {
                throw "Unsafe ZIP entry path: $name"
            }
            $segments = $name.TrimEnd('/').Split('/')
            if ($segments | Where-Object { $_ -in @('', '.', '..') -or $_.Contains(':') }) {
                throw "Unsafe ZIP entry path: $name"
            }
            if ($name -cne "$RootName/" -and -not $name.StartsWith($rootPrefix, [StringComparison]::Ordinal)) {
                throw "ZIP entry is outside the expected $RootName/ directory: $name"
            }
            if (-not $seen.Add($name)) { throw "Duplicate ZIP entry path: $name" }
            # Reject Unix symlink entries; extracting them can redirect later writes.
            if ((($entry.ExternalAttributes -shr 16) -band 0xF000) -eq 0xA000) {
                throw "ZIP contains an unsupported symbolic link: $name"
            }
            if ($name.StartsWith($rootPrefix, [StringComparison]::Ordinal) -and -not $name.EndsWith('/')) {
                $hasPayload = $true
            }
        }
        if (-not $hasPayload) { throw "ZIP does not contain payload files under $RootName/." }
    } finally {
        $zip.Dispose()
    }
}

if (-not [Runtime.InteropServices.RuntimeInformation]::IsOSPlatform([Runtime.InteropServices.OSPlatform]::Windows)) {
    throw 'The Mini Windows installer runs only on Windows x64.'
}
if ([Runtime.InteropServices.RuntimeInformation]::OSArchitecture -ne [Runtime.InteropServices.Architecture]::X64) {
    throw 'The Mini Windows installer requires Windows x64.'
}

if ([string]::IsNullOrWhiteSpace($Prefix)) {
    if (-not [string]::IsNullOrWhiteSpace($env:MINI_HOME)) { $Prefix = $env:MINI_HOME }
    else {
        if ([string]::IsNullOrWhiteSpace($env:USERPROFILE)) { throw 'USERPROFILE is not set; pass -Prefix explicitly.' }
        $Prefix = Join-Path $env:USERPROFILE '.mini-windows'
    }
}
$prefixPath = [IO.Path]::GetFullPath($Prefix)
if (Test-Path -LiteralPath $prefixPath) { throw "Refusing to overwrite existing install prefix: $prefixPath" }
$parent = Split-Path -Parent $prefixPath
if ([string]::IsNullOrWhiteSpace($parent)) { throw "Install prefix must have a parent directory: $prefixPath" }
if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Path $parent | Out-Null }

$versionInput = if ([string]::IsNullOrWhiteSpace($Version)) { $env:MINI_VERSION } else { $Version }
$localArchive = -not [string]::IsNullOrWhiteSpace($Archive)
if ($localArchive) {
    $archivePath = (Resolve-Path -LiteralPath $Archive -ErrorAction Stop).Path
    $archiveName = [IO.Path]::GetFileName($archivePath)
    if ($archiveName -notmatch '^mini-(.+)-windows-x64\.zip$') { throw "Unexpected Windows archive filename: $archiveName" }
    $archiveVersion = Get-ValidatedVersion $Matches[1]
    if ([string]::IsNullOrWhiteSpace($versionInput)) { $version = $archiveVersion }
    else {
        $version = Get-ValidatedVersion $versionInput
        if ($version -cne $archiveVersion) { throw "Archive version $archiveVersion does not match requested version $version." }
    }
    $checksumPath = Join-Path (Split-Path -Parent $archivePath) 'SHA256SUMS'
    if (-not (Test-Path -LiteralPath $checksumPath -PathType Leaf)) { throw "Required neighboring checksum file is missing: $checksumPath" }
} else {
    if ([string]::IsNullOrWhiteSpace($versionInput)) {
        $release = Invoke-RestMethod -Uri 'https://api.github.com/repos/HenrySchwerdt/mini/releases/latest' -Headers @{ Accept = 'application/vnd.github+json'; 'User-Agent' = 'Mini-Windows-Installer' }
        if ($release.tag_name -notmatch '^v(.+)$') { throw "Unexpected latest release tag: $($release.tag_name)" }
        $version = Get-ValidatedVersion $Matches[1]
    } else { $version = Get-ValidatedVersion $versionInput }
    $archiveName = "mini-$version-windows-x64.zip"
}

$stage = Join-Path $parent ('.mini-install-stage-' + [IO.Path]::GetRandomFileName())
New-Item -ItemType Directory -Path $stage | Out-Null
try {
    $stagedArchive = Join-Path $stage $archiveName
    $stagedSums = Join-Path $stage 'SHA256SUMS'
    if ($localArchive) {
        Copy-Item -LiteralPath $archivePath -Destination $stagedArchive
        Copy-Item -LiteralPath $checksumPath -Destination $stagedSums
    } else {
        $baseUrl = "https://github.com/HenrySchwerdt/mini/releases/download/v$version"
        Invoke-WebRequest -Uri "$baseUrl/$archiveName" -OutFile $stagedArchive
        Invoke-WebRequest -Uri "$baseUrl/SHA256SUMS" -OutFile $stagedSums
    }

    $expected = Get-Checksum -ChecksumFile $stagedSums -FileName $archiveName
    $actual = (Get-FileHash -LiteralPath $stagedArchive -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -cne $expected) { throw "SHA256 mismatch for ${archiveName}: expected $expected, got $actual." }

    $rootName = "mini-$version-windows-x64"
    Assert-SafeArchive -ZipPath $stagedArchive -RootName $rootName
    $extractRoot = Join-Path $stage 'extracted'
    New-Item -ItemType Directory -Path $extractRoot | Out-Null
    [IO.Compression.ZipFile]::ExtractToDirectory($stagedArchive, $extractRoot)
    $installRoot = Join-Path $extractRoot $rootName

    $requiredFiles = @(
        'bin/mini.exe',
        'bin/minic-native.exe',
        'lib/libminirt.a',
        'include/minirt.h',
        'VERSION',
        'TOOLCHAIN-BACKEND',
        'LICENSE',
        'NOTICE'
    )
    foreach ($relative in $requiredFiles) {
        if (-not (Test-Path -LiteralPath (Join-Path $installRoot $relative) -PathType Leaf)) {
            throw "Required Windows package file is missing: $relative"
        }
    }
    if (-not (Test-Path -LiteralPath (Join-Path $installRoot 'stdlib') -PathType Container)) {
        throw 'Required Windows package directory is missing: stdlib'
    }
    $installedVersion = (Get-Content -LiteralPath (Join-Path $installRoot 'VERSION' -ErrorAction Stop) -Raw).Trim()
    if ($installedVersion -cne $version) { throw "Archive VERSION mismatch: expected $version, got $installedVersion." }
    $backend = (Get-Content -LiteralPath (Join-Path $installRoot 'TOOLCHAIN-BACKEND' -ErrorAction Stop) -Raw).Trim()
    if ($backend -cnotin @('c', 'llvm-api')) { throw "Unsupported TOOLCHAIN-BACKEND value: $backend" }

    if (Test-Path -LiteralPath $prefixPath) { throw "Refusing to overwrite existing install prefix: $prefixPath" }
    [IO.Directory]::Move($installRoot, $prefixPath)

    Write-Host "Installed Mini $version ($backend backend) under $prefixPath"
    Write-Host 'No persistent PATH settings were changed. To use it in this PowerShell session:'
    $binLiteral = (Join-Path $prefixPath 'bin').Replace("'", "''")
    Write-Host ('$env:PATH = ''' + $binLiteral + ';'' + $env:PATH')
    if ($backend -eq 'c') { Write-Host 'This C-backend package does not include the LLVM API backend or REPL.' }
    else { Write-Host 'This LLVM API package includes the native REPL.' }
} finally {
    if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
}
