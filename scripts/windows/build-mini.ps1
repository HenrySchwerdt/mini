#Requires -Version 7.0
# Build Windows `mini.exe` through C and stage a source-only toolchain.
param(
    [Parameter(Mandatory = $true)][string]$MinicNative,
    [Parameter(Mandatory = $true)][string]$OutputDirectory,
    [ValidateSet('c', 'llvm-api')][string]$Backend = 'c'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'setup.ps1')

$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$compiler = (Resolve-Path -LiteralPath $MinicNative).Path
if (-not (Test-Path -LiteralPath $compiler -PathType Leaf)) { throw "minic-native not found: $compiler" }
if (Test-Path -LiteralPath $OutputDirectory) { throw "Output directory must be fresh: $OutputDirectory" }
New-Item -ItemType Directory -Path $OutputDirectory | Out-Null
$stage = (Resolve-Path $OutputDirectory).Path
$script:cc = Initialize-MiniWindowsToolchain

$bin = Join-Path $stage 'bin'
$lib = Join-Path $stage 'lib'
$include = Join-Path $stage 'include'
$work = Join-Path $stage 'work'
New-Item -ItemType Directory -Path $bin, $lib, $include, $work | Out-Null

function Invoke-Clang([string[]]$Arguments) {
    Write-Host ('clang ' + ($Arguments -join ' '))
    & $script:cc @Arguments
    if ($LASTEXITCODE -ne 0) { throw "clang failed with exit code $LASTEXITCODE" }
}

$target = @('--target=x86_64-pc-windows-msvc')
$runtime = Join-Path $root 'runtime'
$runtimeSources = @(Get-ChildItem -LiteralPath (Join-Path $runtime 'src') -Filter '*.c' -File | Sort-Object Name)
if ($runtimeSources.Count -eq 0) { throw 'No runtime C sources found.' }
$runtimeObjects = @()
foreach ($source in $runtimeSources) {
    $object = Join-Path $work ($source.BaseName + '.obj')
    Invoke-Clang ($target + @('-std=c17', '-O2', '-w', "-I$(Join-Path $runtime 'include')", '-c', $source.FullName, '-o', $object))
    $runtimeObjects += $object
}

$llvmAr = Join-Path (Split-Path -Parent $script:cc) 'llvm-ar.exe'
$runtimeArchive = Join-Path $lib 'libminirt.a'
if (Test-Path -LiteralPath $llvmAr) {
    & $llvmAr rcs $runtimeArchive @runtimeObjects
    if ($LASTEXITCODE -ne 0) { throw "llvm-ar failed with exit code $LASTEXITCODE" }
} else {
    # VS's librarian accepts COFF objects; keep the repository's existing archive filename.
    & lib.exe "/OUT:$runtimeArchive" @runtimeObjects
    if ($LASTEXITCODE -ne 0) { throw "lib.exe failed with exit code $LASTEXITCODE" }
}

$compilerDirs = @('compiler/mini-tool/src', 'compiler/frontend/src')
$toolSources = @()
foreach ($dir in $compilerDirs) {
    $toolSources += Get-ChildItem -LiteralPath (Join-Path $root $dir) -Filter '*.kt' -File -Recurse | ForEach-Object { $_.FullName.Replace('\', '/') }
}
[string[]]$toolSources = $toolSources
[Array]::Sort($toolSources, [StringComparer]::Ordinal)
$stdlibSources = [string[]]@(Get-ChildItem -LiteralPath (Join-Path $root 'stdlib') -Filter '*.kt' -File -Recurse | ForEach-Object { $_.FullName.Replace('\', '/') })
[Array]::Sort($stdlibSources, [StringComparer]::Ordinal)
if ($toolSources.Count -eq 0 -or $stdlibSources.Count -eq 0) { throw 'Mini tool or stdlib source list is empty.' }

$toolC = Join-Path $work 'mini.c'
$toolExe = Join-Path $bin 'mini.exe'
$toolHost = Join-Path $root 'compiler/mini-tool/host'
$hostObject = Join-Path $work 'mini_host.obj'
$toolHostSource = Join-Path $toolHost 'mini_host.c'

Write-Host ("compiler --backend=c -o $toolC " + ($toolSources -join ' ') + ' --stdlib ' + ($stdlibSources -join ' '))
& $compiler --backend=c -o $toolC @toolSources --stdlib @stdlibSources
if ($LASTEXITCODE -ne 0) { throw "minic-native failed generating mini C with exit code $LASTEXITCODE" }

Invoke-Clang ($target + @('-std=gnu17', '-O2', '-Werror', "-I$(Join-Path $runtime 'include')", "-I$toolHost", '-c', $toolHostSource, '-o', $hostObject))
Invoke-Clang ($target + @('-std=c17', '-O2', '-w', "-I$(Join-Path $runtime 'include')", "-I$toolHost", $toolC, $hostObject, $runtimeArchive, '-Wl,/include:wmain', '-o', $toolExe))

Copy-Item -LiteralPath $compiler -Destination (Join-Path $bin 'minic-native.exe')
Copy-Item -Path (Join-Path $runtime 'include/*.h') -Destination $include
Copy-Item -LiteralPath (Join-Path $root 'stdlib') -Destination (Join-Path $stage 'stdlib') -Recurse
Copy-Item -LiteralPath (Join-Path $root 'VERSION') -Destination $stage
Set-Content -LiteralPath (Join-Path $stage 'TOOLCHAIN-BACKEND') -Value $Backend -NoNewline -Encoding ascii

Write-Host "Staged Windows $Backend toolchain at $stage"
if ($Backend -eq 'c') { Write-Host 'This experimental package uses the C backend by default.' }
else { Write-Host 'The supplied minic-native must include the LLVM C API backend; the staged mini.exe uses C and supports explicit C fallback.' }
