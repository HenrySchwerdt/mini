#Requires -Version 7.0
# Parse installer/build scripts before starting a costly native bootstrap.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$files = @(Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.ps1' -File)
$files += Get-Item -LiteralPath (Join-Path $root 'install.ps1')
$failed = $false
foreach ($file in $files) {
    $tokens = $null
    $parseErrors = $null
    $null = [Management.Automation.Language.Parser]::ParseFile($file.FullName, [ref]$tokens, [ref]$parseErrors)
    foreach ($problem in $parseErrors) {
        Write-Host "$($file.Name):$($problem.Extent.StartLineNumber): $($problem.Message)"
        $failed = $true
    }
}
if ($failed) { throw 'PowerShell script syntax validation failed.' }
Write-Host "PowerShell syntax validated for $($files.Count) scripts."
