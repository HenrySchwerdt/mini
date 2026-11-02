# Finds the VS LLVM toolset and initializes a native x64 MSVC developer environment.
# Dot-source this file, then call Initialize-MiniWindowsToolchain in scripts that need it.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Initialize-MiniWindowsToolchain {
    $vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
    if (-not (Test-Path -LiteralPath $vswhere)) {
        throw "vswhere.exe not found: $vswhere"
    }

    $vsInstallOutput = @(& $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Llvm.ClangToolset -property installationPath)
    if ($LASTEXITCODE -ne 0 -or $vsInstallOutput.Count -eq 0 -or [string]::IsNullOrWhiteSpace([string]$vsInstallOutput[0])) {
        throw 'Could not locate a Visual Studio installation with the LLVM Clang toolset.'
    }
    $vsInstall = ([string]$vsInstallOutput[0]).Trim()

    $devShell = Join-Path $vsInstall 'Common7\Tools\Launch-VsDevShell.ps1'
    $clang = Join-Path $vsInstall 'VC\Tools\Llvm\bin\clang.exe'
    if (-not (Test-Path -LiteralPath $devShell)) { throw "VS developer shell script missing: $devShell" }
    if (-not (Test-Path -LiteralPath $clang)) { throw "VS clang.exe missing: $clang" }

    # This is the documented VS setup entry point; it configures the MSVC linker,
    # CRT and Windows SDK for clang's GNU-style driver.
    & $devShell -Arch amd64 -HostArch amd64 -SkipAutomaticLocation

    $version = & $clang --version 2>&1 | Select-Object -First 1
    if ($LASTEXITCODE -ne 0) { throw "clang.exe --version failed with exit code $LASTEXITCODE" }
    $target = & $clang --target=x86_64-pc-windows-msvc -print-target-triple 2>&1 | Select-Object -First 1
    if ($LASTEXITCODE -ne 0 -or $target.Trim() -ne 'x86_64-pc-windows-msvc') {
        throw "Unexpected clang target triple: $target"
    }

    Write-Host "Windows host: $([Environment]::OSVersion.VersionString)"
    Write-Host "Runner image: $($env:ImageOS) $($env:ImageVersion)"
    Write-Host "Visual Studio: $vsInstall"
    Write-Host "Compiler: $version"
    Write-Host "Target: $target"
    return $clang
}

if ($MyInvocation.InvocationName -ne '.') {
    $null = Initialize-MiniWindowsToolchain
}
