# Build current compiler stages from a verified portable C-seed source archive
# using native Windows clang/MSVC CRT; no Bash, WSL, or MSYS runtime is used.
param(
    [Parameter(Mandatory = $true)][string]$SeedDirectory,
    [string]$WorkDirectory = ''
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'setup.ps1')

$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$seed = (Resolve-Path $SeedDirectory).Path
$script:cc = Initialize-MiniWindowsToolchain
if (-not (Test-Path (Join-Path $seed 'minic.c'))) { throw "C seed source missing in $seed" }
if (-not (Test-Path (Join-Path $seed 'runtime/include/minirt.h'))) { throw "C seed runtime headers missing in $seed" }

if ([string]::IsNullOrWhiteSpace($WorkDirectory)) {
    $base = if ($env:RUNNER_TEMP) { $env:RUNNER_TEMP } else { [IO.Path]::GetTempPath() }
    $WorkDirectory = Join-Path $base 'mini-windows-bootstrap'
}
if (Test-Path -LiteralPath $WorkDirectory) {
    throw "Work directory already exists; choose a new -WorkDirectory: $WorkDirectory"
}
New-Item -ItemType Directory -Path $WorkDirectory | Out-Null
$work = (Resolve-Path $WorkDirectory).Path
$runtimeSources = @(Get-ChildItem -LiteralPath (Join-Path $root 'runtime/src') -Filter '*.c' -File | Sort-Object Name | ForEach-Object FullName)
$compilerDirs = @('compiler/minic-native/src', 'compiler/lower/src', 'compiler/frontend/src', 'compiler/backend/src')
$compilerSources = @()
foreach ($dir in $compilerDirs) {
    $compilerSources += Get-ChildItem -LiteralPath (Join-Path $root $dir) -Filter '*.kt' -File -Recurse | ForEach-Object FullName
}
$compilerSources = [string[]]$compilerSources
[Array]::Sort($compilerSources, [StringComparer]::Ordinal)
$stdlibSources = [string[]]@(Get-ChildItem -LiteralPath (Join-Path $root 'stdlib') -Filter '*.kt' -File -Recurse | ForEach-Object FullName)
[Array]::Sort($stdlibSources, [StringComparer]::Ordinal)
if ($compilerSources.Count -eq 0 -or $stdlibSources.Count -eq 0) { throw 'Compiler or stdlib Kotlin source list is empty.' }

function Invoke-Clang([string[]]$Arguments) {
    Write-Host ('clang ' + ($Arguments -join ' '))
    & $script:cc @Arguments
    if ($LASTEXITCODE -ne 0) { throw "clang failed with exit code $LASTEXITCODE" }
}
function Invoke-Mini([string]$Exe, [string[]]$Arguments) {
    Write-Host ("$Exe " + ($Arguments -join ' '))
    & $Exe @Arguments
    if ($LASTEXITCODE -ne 0) { throw "compiler $Exe failed with exit code $LASTEXITCODE" }
}
function Build-Stage([string]$CFile, [string]$ExeFile) {
    $clangArgs = @('--target=x86_64-pc-windows-msvc', '-std=c17', '-O1', '-w', "-I$(Join-Path $root 'runtime/include')", $CFile) + $runtimeSources + @('-o', $ExeFile)
    Invoke-Clang $clangArgs
}
function Hash-File([string]$Path) { return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }

$stage0 = Join-Path $work 'stage0.exe'
$stage1C = Join-Path $work 'stage1.c'
$stage1 = Join-Path $work 'stage1.exe'
$stage2C = Join-Path $work 'stage2.c'
$stage2Mir = Join-Path $work 'stage2.mir'
$stage2 = Join-Path $work 'stage2.exe'
$stage3C = Join-Path $work 'stage3.c'
$stage3Mir = Join-Path $work 'stage3.mir'
$stage3 = Join-Path $work 'stage3.exe'

# The seed's historical POSIX runtime is intentionally not linked. Use its
# generated compiler C with this checkout's Windows-capable runtime sources.
$seedC = Join-Path $seed 'minic.c'
$currentRuntimeIncludes = Join-Path $root 'runtime/include'
$stage0Args = @('--target=x86_64-pc-windows-msvc', '-std=c17', '-O1', '-w', "-I$currentRuntimeIncludes", $seedC) + $runtimeSources + @('-o', $stage0)
Invoke-Clang $stage0Args
Invoke-Mini $stage0 (@('--check') + $compilerSources + @('--stdlib') + $stdlibSources)

Invoke-Mini $stage0 (@('-o', $stage1C) + $compilerSources + @('--stdlib') + $stdlibSources)
Build-Stage $stage1C $stage1
Invoke-Mini $stage1 (@('-o', $stage2C, '--emit-mir', $stage2Mir) + $compilerSources + @('--stdlib') + $stdlibSources)
Build-Stage $stage2C $stage2
Invoke-Mini $stage2 (@('-o', $stage3C, '--emit-mir', $stage3Mir) + $compilerSources + @('--stdlib') + $stdlibSources)
Build-Stage $stage3C $stage3

if ((Hash-File $stage2C) -ne (Hash-File $stage3C)) { throw 'stage2/stage3 generated C differs.' }
if ((Hash-File $stage2Mir) -ne (Hash-File $stage3Mir)) { throw 'stage2/stage3 MIR differs.' }

# Smoke the resulting compiler with a tiny program and the current stdlib.
$hello = Join-Path $work 'hello.kt'
$helloC = Join-Path $work 'hello.c'
$helloExe = Join-Path $work 'hello.exe'
[IO.File]::WriteAllText($hello, "fun main() { println(42) }`n", [Text.UTF8Encoding]::new($false))
Invoke-Mini $stage3 (@('-o', $helloC, $hello, '--stdlib') + $stdlibSources)
Build-Stage $helloC $helloExe
$stdout = Join-Path $work 'hello.stdout'
$stderr = Join-Path $work 'hello.stderr'
$p = Start-Process -FilePath $helloExe -NoNewWindow -Wait -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
if ($p.ExitCode -ne 0) { throw "Hello smoke exited with $($p.ExitCode): $([IO.File]::ReadAllText($stderr))" }
if (([IO.File]::ReadAllText($stdout) -replace "`r`n", "`n") -cne "42`n") { throw 'Hello smoke output mismatch.' }

$moduleLib = Join-Path $work 'answer.kt'
$moduleMain = Join-Path $work 'module-main.kt'
$moduleC = Join-Path $work 'module.c'
$moduleExe = Join-Path $work 'module.exe'
[IO.File]::WriteAllText($moduleLib, "package winprobe`nfun answer(): Int = 42`n", [Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText($moduleMain, "import winprobe.answer`nfun main() { println(answer()) }`n", [Text.UTF8Encoding]::new($false))
Invoke-Mini $stage3 (@('-o', $moduleC, $moduleLib, $moduleMain, '--stdlib') + $stdlibSources)
Build-Stage $moduleC $moduleExe
$moduleOut = Join-Path $work 'module.stdout'
$moduleErr = Join-Path $work 'module.stderr'
$moduleProcess = Start-Process -FilePath $moduleExe -NoNewWindow -Wait -PassThru -RedirectStandardOutput $moduleOut -RedirectStandardError $moduleErr
if ($moduleProcess.ExitCode -ne 0) { throw "Module smoke exited with $($moduleProcess.ExitCode): $([IO.File]::ReadAllText($moduleErr))" }
if (([IO.File]::ReadAllText($moduleOut) -replace "`r`n", "`n") -cne "42`n") { throw 'Module smoke output mismatch.' }
Write-Host 'Module source/import smoke passed.'

# Exercise the compiler CLI's Windows argument conversion and runtime file IO
# with non-ASCII source and output paths, including spaces, BMP text and emoji.
$unicodeInputDir = Join-Path $work 'Input Δ 😀 path'
$unicodeOutputDir = Join-Path $work 'Output Ω 😀 path'
New-Item -ItemType Directory -Force -Path $unicodeInputDir, $unicodeOutputDir | Out-Null
$unicodeSource = Join-Path $unicodeInputDir 'hello λ 😀.kt'
$unicodeC = Join-Path $unicodeOutputDir 'hello.c'
$unicodeExe = Join-Path $unicodeOutputDir 'hello Ω 😀.exe'
[IO.File]::WriteAllText($unicodeSource, 'fun main() { println("ok Ω 😀") }' + "`n", [Text.UTF8Encoding]::new($false))
Invoke-Mini $stage3 (@('-o', $unicodeC, $unicodeSource, '--stdlib') + $stdlibSources)
Build-Stage $unicodeC $unicodeExe
$unicodeOut = Join-Path $unicodeOutputDir 'hello.stdout'
$unicodeErr = Join-Path $unicodeOutputDir 'hello.stderr'
$unicodeProcess = Start-Process -FilePath $unicodeExe -NoNewWindow -Wait -PassThru -RedirectStandardOutput $unicodeOut -RedirectStandardError $unicodeErr
if ($unicodeProcess.ExitCode -ne 0) { throw "Unicode-path smoke exited with $($unicodeProcess.ExitCode): $([IO.File]::ReadAllText($unicodeErr))" }
if (([IO.File]::ReadAllText($unicodeOut) -replace "`r`n", "`n") -cne "ok Ω 😀`n") { throw 'Unicode-path smoke output mismatch.' }
Write-Host 'Unicode CLI source/output path smoke passed.'

Write-Host "stage2 C SHA256: $(Hash-File $stage2C)"
Write-Host "stage3 C SHA256: $(Hash-File $stage3C)"
Write-Host "stage2 MIR SHA256: $(Hash-File $stage2Mir)"
Write-Host "stage3 MIR SHA256: $(Hash-File $stage3Mir)"
Write-Host 'Windows C-seed bootstrap fixpoint and hello smoke passed.'
