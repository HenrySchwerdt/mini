# Windows support status

The native Windows x64 CI lane is an experimental C compiler/runtime and project-tool port check. It uses the Visual Studio LLVM Clang toolset with the MSVC CRT and Windows SDK; it does not use WSL or an MSYS runtime as a product dependency.

## CI coverage

The optional Windows lane runs on the explicitly selected `windows-2022` GitHub runner and reports its Windows and Clang versions. It:

- compiles and runs the non-threaded runtime core tests with Clang's GNU-style driver targeting `x86_64-pc-windows-msvc`;
- skips `test_alloc_small` and `test_threads`, which exercise the current pthread path;
- runs the external `check_panic` process test and checks its nonzero exit, flushed stdout, and fatal stderr;
- separately compiles `test_entry_args.c` with `entry.c` and launches it with `café` and `emoji😀`, checking that UTF-16 argv values survive through the atexit callback;
- tests native child-process quoting, Unicode, long arguments, environment/cwd isolation, and large simultaneous stdout/stderr capture;
- verifies the published 0.7.0 C bootstrap seed archive against the release `SHA256SUMS`;
- links the seed compiler against this checkout's runtime, builds three compiler stages, checks stage 2 and stage 3 C/MIR equality, and compiles/runs a hello program;
- compiles and runs a multi-file package/import smoke plus a CLI smoke whose source and output paths contain spaces, BMP Unicode and emoji.
- builds an experimental C-only `mini.exe` install using the Windows host layer and source stdlib, then exercises `mini new`, `mini run`, `mini test`, `mini check`, and propagation of a failed compiler subprocess from paths containing spaces and Unicode;
- runs the complete source installer across distinct temporary/destination volumes, then checks that an occupied install prefix is refused without changing the installed executable.

`CHECK_ABORTS` is a no-op in the current Windows test harness because it uses `fork` on POSIX. The separate panic process test does not replace those in-process abort assertions. This lane does not run ASan, UBSan, TSan, libuv/thread tests, LLVM API bootstrap, ORC REPL, or release packaging.

## Support boundary

Linux and macOS remain the release platforms. The PowerShell source installer downloads and verifies the published 0.7.0 C seed, bootstraps the current compiler, and stages an experimental C-only native project tool under `%USERPROFILE%\.mini-windows` by default. It refuses to overwrite a prefix and does not modify persistent PATH settings. Passing this lane covers the bootstrap, listed runtime tests, and the specific `mini.exe` commands above; it does not establish a Windows release asset or full Windows support. The install is not an LLVM API build and does not provide the LLVM REPL. POSIX shell entry points such as `minic.sh` are not supported natively on Windows.

The workflow invokes PowerShell 7 scripts directly and uses the Visual Studio developer-shell setup plus `clang.exe --target=x86_64-pc-windows-msvc`. The runner label is explicitly `windows-2022`; the workflow logs the runner image, Windows version, installed Clang version, and checks the expected target triple so image updates are visible.

The filesystem and entry layer use UTF-16 Windows APIs and preserve raw UTF-16 argument units. Drive and UNC roots (including extended drive/UNC syntax) have pure parser regression tests; actual remote SMB shares have not been exercised. Device and GLOBALROOT namespaces are rejected by recursive directory creation. The Windows default allocator temporarily uses malloc instead of the thread-local small-object cache until Windows thread-exit cleanup is implemented; public Pool, Arena and Debug allocators remain tested.

`bootstrap-c.ps1` uses a fresh work directory and refuses to delete an existing one. A developer can supply `-WorkDirectory` explicitly; it does not require GitHub's `RUNNER_TEMP` variable.
