# Native Windows LLVM C API stage1/2/3 bootstrap. Requires LLVM 18 static libraries.
# Run from PowerShell 7 after preparing an LLVM prefix with prepare-llvm.ps1.
# The build remains entirely native Windows: clang-cl, CMake and Ninja.
# Example: ./scripts/windows/bootstrap-api.ps1 -Stage0 C:/mini/minic-native.exe -LLVMPrefix C:/llvm18
#
# Stage2 and stage3 must produce byte-identical C, MIR, LLVM IR and object files.
#Requires -Version 7.0
param(
    [Parameter(Mandatory = $true)][string]$Stage0,
    [Parameter(Mandatory = $true)][string]$LLVMPrefix,
    [string]$WorkDirectory = ''
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'setup.ps1')

$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$Stage0 = (Resolve-Path -LiteralPath $Stage0).Path
$LLVMPrefix = (Resolve-Path -LiteralPath $LLVMPrefix).Path
if (-not (Test-Path -LiteralPath $Stage0 -PathType Leaf)) { throw "Stage0 executable not found: $Stage0" }
if ([IO.Path]::GetExtension($Stage0) -ine '.exe') { throw "Stage0 must be a Windows executable: $Stage0" }
$script:clang = Initialize-MiniWindowsToolchain

$cmake = Get-Command cmake.exe -ErrorAction SilentlyContinue
$ninja = Get-Command ninja.exe -ErrorAction SilentlyContinue
if (-not $cmake) { throw 'Required command not found on PATH: cmake.exe' }
if (-not $ninja) { throw 'Required command not found on PATH: ninja.exe' }
$clangCl = Join-Path (Split-Path -Parent $script:clang) 'clang-cl.exe'
$llvmNm = Join-Path $LLVMPrefix 'bin/llvm-nm.exe'
$dumpbin = Get-Command dumpbin.exe -ErrorAction SilentlyContinue
if (-not (Test-Path -LiteralPath $clangCl)) { throw "clang-cl.exe missing beside clang.exe: $clangCl" }
if (-not (Test-Path -LiteralPath $llvmNm)) { throw "LLVM nm missing from prefix: $llvmNm" }
if (-not $dumpbin) { throw 'dumpbin.exe not found; initialize the Visual Studio x64 developer environment.' }
$cmakeLists = Join-Path $PSScriptRoot 'llvm-compiler/CMakeLists.txt'
if (-not (Test-Path -LiteralPath $cmakeLists)) { throw "LLVM API bootstrap CMake project missing: $cmakeLists" }
foreach ($required in @((Join-Path $LLVMPrefix 'lib/cmake/llvm/LLVMConfig.cmake'), (Join-Path $LLVMPrefix 'include/llvm-c/Orc.h'))) {
    if (-not (Test-Path -LiteralPath $required)) { throw "LLVM 18 prefix is incomplete: $required" }
}

if ([string]::IsNullOrWhiteSpace($WorkDirectory)) {
    $base = if ($env:RUNNER_TEMP) { $env:RUNNER_TEMP } else { [IO.Path]::GetTempPath() }
    $WorkDirectory = Join-Path $base 'mini-windows-llvm-api-bootstrap'
}
$WorkDirectory = [IO.Path]::GetFullPath($WorkDirectory)
if (Test-Path -LiteralPath $WorkDirectory) { throw "Work directory already exists; choose a new -WorkDirectory: $WorkDirectory" }
New-Item -ItemType Directory -Path $WorkDirectory | Out-Null
$work = (Resolve-Path -LiteralPath $WorkDirectory).Path

function Invoke-Native([string]$Name, [string]$Exe, [string[]]$Arguments) {
    Write-Host ($Name + ': ' + $Exe + ' ' + ($Arguments -join ' '))
    $nativeOutput = @(& $Exe @Arguments 2>&1)
    $nativeOutput | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "$Name failed with exit code $LASTEXITCODE" }
}
function Invoke-Mini([string]$Exe, [string[]]$Arguments) { Invoke-Native 'compiler' $Exe $Arguments }
function Hash-File([string]$Path) { return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Get-SortedSources([string]$RelativeDirectory) {
    $dir = Join-Path $root $RelativeDirectory
    return [string[]]@((Get-ChildItem -LiteralPath $dir -Filter '*.kt' -File -Recurse | ForEach-Object { $_.FullName.Replace('\', '/') }))
}

$apiSources = @()
$apiSources += @(Get-SortedSources 'compiler/minic-native/src' | Where-Object { [IO.Path]::GetFileName($_) -cne 'Main.kt' })
$apiSources += Get-SortedSources 'compiler/lower/src'
$apiSources += Get-SortedSources 'compiler/frontend/src'
$apiSources += Get-SortedSources 'compiler/backend/src'
$apiSources += Get-SortedSources 'compiler/llvm-c/src'
[string[]]$apiSources = $apiSources
[Array]::Sort($apiSources, [StringComparer]::Ordinal)
[string[]]$stdlibSources = Get-SortedSources 'stdlib'
[Array]::Sort($stdlibSources, [StringComparer]::Ordinal)
if ($apiSources.Count -eq 0 -or $stdlibSources.Count -eq 0) { throw 'API compiler or stdlib Kotlin source list is empty.' }

# Runtime objects are separate, use the same x64 Windows target and dynamic CRT as LLVM/CMake.
$runtimeInclude = Join-Path $root 'runtime/include'
$runtimeSourceFiles = @(Get-ChildItem -LiteralPath (Join-Path $root 'runtime/src') -Filter '*.c' -File | Sort-Object Name)
$runtimeObjects = @()
$runtimeDir = Join-Path $work 'runtime-objects'
New-Item -ItemType Directory -Path $runtimeDir | Out-Null
foreach ($source in $runtimeSourceFiles) {
    $object = Join-Path $runtimeDir ($source.BaseName + '.obj')
    $args = @('--target=x86_64-pc-windows-msvc', '-fms-runtime-lib=dll', '-std=c17', '-O1', '-w', "-I$runtimeInclude", '-c', $source.FullName, '-o', $object)
    Invoke-Native 'runtime clang' $script:clang $args
    $runtimeObjects += $object
}
if ($runtimeObjects.Count -eq 0) { throw 'No runtime C sources were found.' }

# ORC resolves the executable's runtime functions from its own image. Export the
# defined runtime API, plus the LLVM C API bridge entry used by dlsym.
$exports = [Collections.Generic.SortedSet[string]]::new([StringComparer]::Ordinal)
foreach ($object in $runtimeObjects) {
    $nmOutput = @(& $llvmNm --defined-only --extern-only --format=posix $object 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "llvm-nm failed for $object`: $($nmOutput -join "`n")" }
    foreach ($line in $nmOutput) {
        if ($line -match '^([^\s]+)\s+([TDBR])(?:\s|$)') {
            $symbol = $Matches[1]
            # COFF also exposes compiler-generated COMDAT string constants.
            # Only the named Mini runtime ABI is needed by generated modules.
            if ($symbol.StartsWith('mk_', [StringComparison]::Ordinal) -and $symbol -cne 'mk_pending_exception') {
                [void]$exports.Add($symbol)
            }
        }
    }
}
foreach ($requiredExport in @('isatty', 'dlopen', 'dlsym', 'LLVMOrcThreadSafeContextGetContext')) { [void]$exports.Add($requiredExport) }
foreach ($symbol in $exports) {
    if ($symbol -cnotmatch '^[_A-Za-z][_A-Za-z0-9]*$') { throw "Invalid export symbol from runtime scan: $symbol" }
}
$exportsFile = Join-Path $work 'runtime-exports.def'
[IO.File]::WriteAllLines($exportsFile, @('EXPORTS') + @($exports), [Text.UTF8Encoding]::new($false))
Write-Host "Runtime exports: $($exports.Count)"

$runtimeObjectList = ($runtimeObjects | ForEach-Object { $_.Replace('\', '/') }) -join ';'
function Build-ApiStage([string]$Stage, [string]$InputFile) {
    $buildDir = Join-Path $work "stage$Stage-build"
    $configure = @('-S', (Join-Path $PSScriptRoot 'llvm-compiler'), '-B', $buildDir, '-G', 'Ninja',
        "-DCMAKE_C_COMPILER=$clangCl", "-DCMAKE_CXX_COMPILER=$clangCl", "-DCMAKE_MAKE_PROGRAM=$($ninja.Source)",
        '-DCMAKE_BUILD_TYPE=Release', "-DLLVM_DIR=$(Join-Path $LLVMPrefix 'lib/cmake/llvm')",
        "-DMINI_COMPILER_INPUT=$($InputFile.Replace('\', '/'))", "-DMINI_RUNTIME_OBJECTS=$runtimeObjectList",
        "-DMINI_EXPORTS_FILE=$($exportsFile.Replace('\', '/'))", "-DMINI_RUNTIME_INCLUDE=$($runtimeInclude.Replace('\', '/'))")
    Invoke-Native "stage$Stage CMake configure" $cmake.Source $configure
    Invoke-Native "stage$Stage CMake build" $cmake.Source @('--build', $buildDir, '--config', 'Release', '--verbose')
    $exe = Join-Path $buildDir 'minic-api.exe'
    if (-not (Test-Path -LiteralPath $exe -PathType Leaf)) { throw "CMake did not produce expected executable: $exe" }
    $peExports = @(& $dumpbin.Source /nologo /exports $exe 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "Cannot inspect stage$Stage PE exports." }
    foreach ($required in @('LLVMOrcThreadSafeContextGetContext', 'mk_pending_slot', 'mk_string_alloc')) {
        if (($peExports -join "`n") -notmatch "(?m)\s+$([regex]::Escape($required))\s*$") {
            throw "Stage$Stage is missing required PE export $required`: $($peExports -join "`n")"
        }
    }
    Write-Host "Stage$Stage PE exports include the LLVM context and Mini runtime ABI."
    return $exe
}
function Elapsed([Diagnostics.Stopwatch]$Timer) { return ('{0:N1}s' -f $Timer.Elapsed.TotalSeconds) }

$timer = [Diagnostics.Stopwatch]::StartNew()
$stage1C = Join-Path $work 'stage1.c'
$stage1Args = @('-o', $stage1C) + $apiSources + @('--stdlib') + $stdlibSources
Invoke-Mini $Stage0 $stage1Args
$stage1 = Build-ApiStage 1 $stage1C
Write-Host "stage1 (stage0 compiles the API compiler to C; native LLVM link: $(Elapsed $timer))"

$previous = $stage1
$stageFiles = @{}
foreach ($stage in @(2, 3)) {
    $timer.Restart()
    $object = Join-Path $work "stage$stage.obj"
    $cFile = Join-Path $work "stage$stage.c"
    $mirFile = Join-Path $work "stage$stage.mir"
    $llFile = Join-Path $work "stage$stage.ll"
    $compileArgs = @('--backend=llvm-api', '--opt=O1', '-o', $object, '--emit-c', $cFile, '--emit-mir', $mirFile, '--emit-ll', $llFile) + $apiSources + @('--stdlib') + $stdlibSources
    Invoke-Mini $previous $compileArgs
    $compileElapsed = Elapsed $timer
    $timer.Restart()
    $exe = Build-ApiStage $stage $object
    Write-Host "stage$stage (stage$($stage - 1) API compile: $compileElapsed; native LLVM link: $(Elapsed $timer))"
    $stageFiles[$stage] = @{ Exe = $exe; C = $cFile; Mir = $mirFile; Ll = $llFile; Object = $object }
    $previous = $exe
}

foreach ($key in @('C', 'Mir', 'Ll', 'Object')) {
    $left = $stageFiles[2][$key]
    $right = $stageFiles[3][$key]
    if ((Hash-File $left) -cne (Hash-File $right)) { throw "stage2/stage3 $key differs: $left and $right" }
}
Write-Host 'stage2 == stage3 (C, MIR, LLVM module text and object file byte-identical).'

$stage3 = $stageFiles[3].Exe
$dependencies = @(& $dumpbin.Source /nologo /dependents $stage3 2>&1)
if ($LASTEXITCODE -ne 0) { throw "dumpbin /dependents failed: $($dependencies -join "`n")" }
if (($dependencies -join "`n") -match '(?im)^\s*LLVM[^\r\n]*\.dll\s*$') { throw 'stage3 has a runtime dependency on an LLVM DLL.' }
Write-Host 'stage3 has no LLVM DLL dependency.'

# Unicode plus spaces exercise Windows command-line conversion and path handling.
$smokeDir = Join-Path $work 'API smoke Δ 😀 path'
New-Item -ItemType Directory -Path $smokeDir | Out-Null
$hello = Join-Path $smokeDir 'Hello 42 Ω 😀.kt'
[IO.File]::WriteAllText($hello, "fun main() { println(42) }`n", [Text.UTF8Encoding]::new($false))
Invoke-Mini $stage3 (@('--backend=llvm-api', '--check', $hello, '--stdlib') + $stdlibSources)
Invoke-Mini $stage3 (@('--backend=llvm-api', '--run', $hello, '--stdlib') + $stdlibSources)
$helloExe = Join-Path $smokeDir 'hello.exe'
# The compiler's runtime uses the LLVM SDK's DLL CRT. User executables use
# the static CRT, just like the installed libminirt.a built by build-mini.ps1.
# A link-only clang invocation does not apply -fms-runtime-lib to its defaults.
$programRuntimeDir = Join-Path $work 'program-runtime-objects'
New-Item -ItemType Directory -Path $programRuntimeDir | Out-Null
$programRuntimeObjects = @()
foreach ($source in $runtimeSourceFiles) {
    $programObject = Join-Path $programRuntimeDir ($source.BaseName + '.obj')
    Invoke-Native 'program runtime clang' $script:clang @('--target=x86_64-pc-windows-msvc', '-fms-runtime-lib=static', '-std=c17', '-O1', '-w', "-I$runtimeInclude", '-c', $source.FullName, '-o', $programObject)
    $programRuntimeObjects += $programObject
}
$linkArgs = @('--backend=llvm-api', '--opt=O1', '--exe', $helloExe, '--cc', $script:clang)
foreach ($runtimeObject in $programRuntimeObjects) { $linkArgs += @('--link-arg', $runtimeObject) }
$linkArgs += @($hello, '--stdlib') + $stdlibSources
Invoke-Mini $stage3 $linkArgs
if (-not (Test-Path -LiteralPath $helloExe -PathType Leaf)) { throw 'LLVM API executable-link smoke did not produce hello.exe.' }
$helloOut = @(& $helloExe 2>&1)
if ($LASTEXITCODE -ne 0) { throw "Hello executable exited with code $LASTEXITCODE`: $($helloOut -join "`n")" }
if (($helloOut -join "`n") -notmatch '(^|\r?\n)42(\r?\n|$)') { throw "Hello executable output did not contain 42: $($helloOut -join "`n")" }
Write-Host 'LLVM API executable build/run smoke passed.'

$replInput = "val x = 40`nx + 2`n:quit`n"
$replOutput = $replInput | & $stage3 --backend=llvm-api --repl --stdlib @stdlibSources 2>&1
if ($LASTEXITCODE -ne 0) { throw "LLVM API REPL failed with exit code $LASTEXITCODE`: $($replOutput -join "`n")" }
if (($replOutput -join "`n") -notmatch '(?m)^42\r?$') { throw "LLVM API REPL output did not contain 42: $($replOutput -join "`n")" }
Write-Host 'LLVM API piped REPL smoke passed.'

# Reuse the existing session ownership/rollback harness on native Windows,
# outside an outer JIT, with the exact compiler and runtime objects just built.
$sessionSources = @($apiSources | Where-Object { [IO.Path]::GetFileName($_) -cne 'MainApi.kt' })
$sessionSources += (Join-Path $root 'compiler/llvm-c/tests/LlvmJitSessionSmoke.kt').Replace('\', '/')
$sessionObject = Join-Path $work 'session-api-smoke.obj'
Invoke-Mini $stage3 (@('--backend=llvm-api', '--opt=O1', '-o', $sessionObject) + $sessionSources + @('--stdlib') + $stdlibSources)
$sessionExe = Build-ApiStage 'session' $sessionObject
Invoke-Native 'ORC ownership and rollback smoke' $sessionExe @()
Write-Host 'Native Windows ORC session ownership/rollback smoke passed.'

foreach ($key in @('C', 'Mir', 'Ll', 'Object')) {
    Write-Host "stage2 $key SHA256: $(Hash-File $stageFiles[2][$key])"
    Write-Host "stage3 $key SHA256: $(Hash-File $stageFiles[3][$key])"
}
Write-Host "Windows LLVM API bootstrap passed. Work directory: $work"
