#Requires -Version 7.0
# Smoke-test a staged Windows toolchain (C-only by default, optionally LLVM API).
param([Parameter(Mandatory = $true)][string]$MiniHome)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'setup.ps1')

$installRoot = (Resolve-Path -LiteralPath $MiniHome).Path
$mini = Join-Path $installRoot 'bin/mini.exe'
$native = Join-Path $installRoot 'bin/minic-native.exe'
$backendFile = Join-Path $installRoot 'TOOLCHAIN-BACKEND'
if (-not (Test-Path -LiteralPath $backendFile -PathType Leaf)) { throw "Missing staged toolchain backend marker: $backendFile" }
$backend = (Get-Content -LiteralPath $backendFile -Raw).Trim()
if ($backend -cne 'c' -and $backend -cne 'llvm-api') { throw "Unknown staged toolchain backend '$backend' (expected c or llvm-api)." }
$cc = Initialize-MiniWindowsToolchain
foreach ($path in @($mini, $native, (Join-Path $installRoot 'lib/libminirt.a'), (Join-Path $installRoot 'include/minirt.h'), (Join-Path $installRoot 'stdlib'))) {
    if (-not (Test-Path -LiteralPath $path)) { throw "Missing staged toolchain file: $path" }
}
if ($backend -eq 'llvm-api') {
    $dumpbin = Get-Command dumpbin.exe -ErrorAction SilentlyContinue
    if (-not $dumpbin) { throw 'dumpbin.exe not found; initialize the Visual Studio x64 developer environment.' }
    $dependencies = @(& $dumpbin.Source /nologo /dependents $native 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "dumpbin /dependents failed: $($dependencies -join "`n")" }
    if (($dependencies -join "`n") -match '(?im)^\s*LLVM[^\r\n]*\.dll\s*$') { throw 'Installed LLVM API compiler depends on an LLVM DLL.' }
    Write-Host 'Installed LLVM API compiler has no LLVM DLL dependency.'
}

$base = if ($env:RUNNER_TEMP) { $env:RUNNER_TEMP } else { [IO.Path]::GetTempPath() }
$work = Join-Path $base ('Mini install Δ 😀 smoke-' + [IO.Path]::GetRandomFileName())
New-Item -ItemType Directory -Force -Path $work | Out-Null
$savedEnv = @{
    MINI_ROOT = $env:MINI_ROOT
    MINIC_NATIVE = $env:MINIC_NATIVE
    MINI_CC = $env:MINI_CC
}
try {
    # Exercise executable-suffix and root discovery from the running mini.exe.
    Remove-Item Env:MINI_ROOT -ErrorAction SilentlyContinue
    Remove-Item Env:MINIC_NATIVE -ErrorAction SilentlyContinue
    $env:MINI_CC = $cc
    Push-Location $work
    try {
        & $mini --version
        if ($LASTEXITCODE -ne 0) { throw "mini --version failed with $LASTEXITCODE" }

        $projectName = 'smoke-app'
        & $mini new $projectName --no-git
        if ($LASTEXITCODE -ne 0) { throw "mini new failed with $LASTEXITCODE" }
        Push-Location $projectName
        try {
            & $mini run
            if ($LASTEXITCODE -ne 0) { throw "mini run failed with $LASTEXITCODE" }
            if ($backend -eq 'llvm-api') {
                & $mini run --backend c
                if ($LASTEXITCODE -ne 0) { throw "mini run --backend c fallback failed with $LASTEXITCODE" }
                Write-Host 'Installed API toolchain explicit C fallback passed.'

                $replInput = @('val x = 40', 'x + 2', ':quit')
                $replOutput = $replInput | & $mini repl 2>&1
                if ($LASTEXITCODE -ne 0) { throw "mini repl failed with exit code $LASTEXITCODE`: $($replOutput -join "`n")" }
                if (($replOutput -join "`n") -notmatch '(?m)^42\r?$') { throw "mini repl output did not contain an exact 42 line: $($replOutput -join "`n")" }
                Write-Host 'Installed API toolchain REPL passed.'
            }
            & $mini test
            if ($LASTEXITCODE -ne 0) { throw "mini test failed with $LASTEXITCODE" }
            & $mini check
            if ($LASTEXITCODE -ne 0) { throw "mini check failed with $LASTEXITCODE" }

            $bad = Join-Path (Get-Location) 'src/main.kt'
            [IO.File]::WriteAllText($bad, "fun main( { println(123) }`n", [Text.UTF8Encoding]::new($false))
            $badErr = Join-Path $work 'invalid-source.stderr'
            & $mini build 2> $badErr
            $badExit = $LASTEXITCODE
            if ($badExit -eq 0) { throw 'mini build unexpectedly accepted invalid Kotlin.' }
            $diagnostic = [IO.File]::ReadAllText($badErr)
            if ($diagnostic -notmatch '(?i)error') { throw "Invalid source failed without a compiler diagnostic: $diagnostic" }
            Write-Host "Invalid-source compiler failure propagated (exit $badExit)."
        } finally { Pop-Location }
    } finally { Pop-Location }


} finally {
    foreach ($name in @('MINI_ROOT', 'MINIC_NATIVE', 'MINI_CC')) {
        if ($null -eq $savedEnv[$name]) { Remove-Item "Env:$name" -ErrorAction SilentlyContinue }
        else { Set-Item "Env:$name" $savedEnv[$name] }
    }
    if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force }
}

Write-Host "Windows $backend mini install smoke passed."
# The compiler-error fixture deliberately returned 1 and was asserted above.
# Report this test script's success rather than leaking that expected child status.
$global:LASTEXITCODE = 0
