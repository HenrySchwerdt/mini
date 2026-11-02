# Native Windows x64 runtime smoke suite. The Bash sanitizer/TSan runner remains
# authoritative on Linux/macOS; this checks the supported non-threaded Windows core.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'setup.ps1')

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$script:cc = Initialize-MiniWindowsToolchain
$runtime = Join-Path $root 'runtime'
if ($env:RUNNER_TEMP) { $out = Join-Path $env:RUNNER_TEMP 'mini-windows-runtime' }
else { $out = Join-Path $env:TEMP 'mini-windows-runtime' }
if (Test-Path $out) { Remove-Item -LiteralPath $out -Recurse -Force }
New-Item -ItemType Directory -Path $out | Out-Null

$targetArgs = @('--target=x86_64-pc-windows-msvc', '-std=c17', '-O1', '-g', '-Wall', '-Wextra', '-Werror', '-D_CRT_SECURE_NO_WARNINGS', "-I$(Join-Path $runtime 'include')", "-I$(Join-Path $runtime 'src')")
$runtimeSources = @(Get-ChildItem -LiteralPath (Join-Path $runtime 'src') -Filter '*.c' -File | Sort-Object Name | ForEach-Object FullName | Where-Object { [IO.Path]::GetFileName($_) -ne 'entry.c' })
$excluded = @('test_alloc_small', 'test_threads')
$tests = @(Get-ChildItem -LiteralPath (Join-Path $runtime 'tests') -Filter 'test_*.c' -File | Sort-Object Name)

function Invoke-Clang([string[]]$Arguments) {
    Write-Host ('clang ' + ($Arguments -join ' '))
    & $script:cc @Arguments
    if ($LASTEXITCODE -ne 0) { throw "clang failed with exit code $LASTEXITCODE" }
}

function Invoke-Program([string]$Exe, [string]$StdoutPath, [string]$StderrPath) {
    $p = Start-Process -FilePath $Exe -NoNewWindow -Wait -PassThru -RedirectStandardOutput $StdoutPath -RedirectStandardError $StderrPath
    return $p.ExitCode
}

Write-Host 'Coverage scope: core runtime tests except test_alloc_small and test_threads (threading); no ASan/UBSan/TSan on this lane.'
Write-Host 'CHECK_ABORTS is compiled as a no-op on Windows; external panic behavior is checked separately below.'
foreach ($test in $tests) {
    $name = [IO.Path]::GetFileNameWithoutExtension($test.Name)
    if ($excluded -contains $name) {
        Write-Host "SKIP $name (threading test outside this lane)"
        continue
    }

    $extra = @()
    if ($name -eq 'test_entry') { $extra += (Join-Path $runtime 'src/entry.c') }
    $testFlags = @($targetArgs)
    if ($name -eq 'test_stats') { $testFlags += '-DMK_STATS' }
    $exe = Join-Path $out "$name.exe"
    $stdout = Join-Path $out "$name.stdout"
    $stderr = Join-Path $out "$name.stderr"
    $clangArgs = @($testFlags) + @($test.FullName) + $runtimeSources + $extra + @('-o', $exe)
    Invoke-Clang $clangArgs
    $exit = Invoke-Program $exe $stdout $stderr
    if ($exit -ne 0) { Get-Content $stderr -ErrorAction SilentlyContinue; throw "$name exited with $exit" }

    $expectedPath = Join-Path (Join-Path $runtime 'tests/expected') "$name.out"
    if (Test-Path -LiteralPath $expectedPath) {
        $actualText = [IO.File]::ReadAllText($stdout) -replace "`r`n", "`n"
        $expectedText = [IO.File]::ReadAllText($expectedPath) -replace "`r`n", "`n"
        if ($actualText -cne $expectedText) { throw "$name stdout differs from $expectedPath" }
    }
    Write-Host "PASS $name"
}

# CHECK_ABORTS uses fork on POSIX and is intentionally disabled in harness.h on
# Windows. Keep the process-level panic contract checked with a real exit status.
$panicExe = Join-Path $out 'check_panic.exe'
$panicArgs = @($targetArgs) + @((Join-Path $runtime 'tests/check_panic.c')) + $runtimeSources + @((Join-Path $runtime 'src/entry.c'), '-o', $panicExe)
Invoke-Clang $panicArgs
$panicOut = Join-Path $out 'panic.stdout'
$panicErr = Join-Path $out 'panic.stderr'
$panicExit = Invoke-Program $panicExe $panicOut $panicErr
$actualPanicOut = [IO.File]::ReadAllText($panicOut) -replace "`r`n", "`n"
$actualPanicErr = [IO.File]::ReadAllText($panicErr)
if ($panicExit -eq 0) { throw 'check_panic unexpectedly exited successfully' }
if ($actualPanicOut -cne "null`n") { throw "check_panic stdout mismatch: $actualPanicOut" }
if ($actualPanicErr -notmatch 'fatal: boom') { throw "check_panic stderr missing fatal message: $actualPanicErr" }
Write-Host "PASS check_panic (exit $panicExit, stdout flushed, fatal stderr observed)"
Write-Host 'Windows core runtime tests passed.'
