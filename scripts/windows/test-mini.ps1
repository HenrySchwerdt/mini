# Smoke-test the experimental Windows C-only staged toolchain.
param([Parameter(Mandatory = $true)][string]$MiniHome)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'setup.ps1')

$installRoot = (Resolve-Path -LiteralPath $MiniHome).Path
$mini = Join-Path $installRoot 'bin/mini.exe'
$native = Join-Path $installRoot 'bin/minic-native.exe'
$env:MINI_CC = Initialize-MiniWindowsToolchain
foreach ($path in @($mini, $native, (Join-Path $installRoot 'lib/libminirt.a'), (Join-Path $installRoot 'include/minirt.h'), (Join-Path $installRoot 'stdlib'))) {
    if (-not (Test-Path -LiteralPath $path)) { throw "Missing staged toolchain file: $path" }
}

$env:MINI_ROOT = $installRoot
$env:MINIC_NATIVE = $native
$base = if ($env:RUNNER_TEMP) { $env:RUNNER_TEMP } else { [IO.Path]::GetTempPath() }
$work = Join-Path $base 'Mini install Δ 😀 smoke'
New-Item -ItemType Directory -Force -Path $work | Out-Null
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
        & $mini test
        if ($LASTEXITCODE -ne 0) { throw "mini test failed with $LASTEXITCODE" }
        & $mini check
        if ($LASTEXITCODE -ne 0) { throw "mini check failed with $LASTEXITCODE" }

        $bad = Join-Path (Get-Location) 'bad.kt'
        [IO.File]::WriteAllText($bad, "fun main( { println(123) }`n", [Text.UTF8Encoding]::new($false))
        & $mini build $bad
        if ($LASTEXITCODE -eq 0) { throw 'mini build unexpectedly accepted invalid Kotlin; child failure was not propagated.' }
        Write-Host "Invalid-source child failure propagated (exit $LASTEXITCODE)."
    } finally { Pop-Location }
} finally { Pop-Location }

Write-Host 'Windows C-only mini install smoke passed.'
