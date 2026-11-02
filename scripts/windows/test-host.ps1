# Native Windows host process contract tests (independent of compiler bootstrap).
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'setup.ps1')
$cc = Initialize-MiniWindowsToolchain
$base = if ($env:RUNNER_TEMP) { $env:RUNNER_TEMP } else { [IO.Path]::GetTempPath() }
$work = Join-Path $base ('mini-windows-host-' + [IO.Path]::GetRandomFileName())
New-Item -ItemType Directory -Path $work | Out-Null
try {
    $root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
    $runtime = Join-Path $root 'runtime'
    $hostDir = Join-Path $root 'compiler/mini-tool/host'
    $fixture = Join-Path $hostDir 'tests/windows_host_test.c'
    if (-not (Test-Path -LiteralPath $fixture)) { throw "Windows host fixture missing: $fixture" }
    $runtimeSources = @(Get-ChildItem -LiteralPath (Join-Path $runtime 'src') -Filter '*.c' -File |
        Where-Object { $_.Name -ne 'entry.c' } | Sort-Object Name | ForEach-Object FullName)
    $hostWork = Join-Path $work 'host fixture Ω 😀'
    New-Item -ItemType Directory -Path $hostWork | Out-Null
    $hostExe = Join-Path $hostWork 'windows_host_test.exe'
    $hostArgs = @('--target=x86_64-pc-windows-msvc', '-std=gnu17', '-Wall', '-Wextra', '-Werror',
        "-I$(Join-Path $runtime 'include')", "-I$hostDir", (Join-Path $hostDir 'mini_host.c'), $fixture) + $runtimeSources + @('-o', $hostExe)
    Write-Host ('clang ' + ($hostArgs -join ' '))
    & $cc @hostArgs
    if ($LASTEXITCODE -ne 0) { throw "Windows host fixture compile failed with exit code $LASTEXITCODE" }
    $hostOut = Join-Path $hostWork 'host.stdout'
    $hostErr = Join-Path $hostWork 'host.stderr'
    $hostProcess = Start-Process -FilePath $hostExe -WorkingDirectory $hostWork -NoNewWindow -Wait -PassThru -RedirectStandardOutput $hostOut -RedirectStandardError $hostErr
    if ($hostProcess.ExitCode -ne 0) { throw "Windows host fixture exited with $($hostProcess.ExitCode): $([IO.File]::ReadAllText($hostErr))" }
    if (([IO.File]::ReadAllText($hostOut) -replace "`r`n", "`n") -notmatch 'Windows host argv/env/cwd/dual-stream/exit/error tests passed\.') {
        throw 'Windows host fixture success output was missing.'
    }
    Write-Host 'Windows native host contract tests passed.'
} finally {
    if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force }
}
