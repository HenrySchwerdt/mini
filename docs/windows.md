# Windows support status

The native Windows x64 CI lane is an initial compiler/runtime port check. It uses the Visual Studio LLVM Clang toolset with the MSVC CRT and Windows SDK; it does not use WSL or an MSYS runtime as a product dependency.

## CI coverage

The optional Windows lane runs on the explicitly selected `windows-2022` GitHub runner and reports its Windows and Clang versions. It:

- compiles and runs the non-threaded runtime core tests with Clang's GNU-style driver targeting `x86_64-pc-windows-msvc`;
- skips `test_alloc_small` and `test_threads`, which exercise the current pthread path;
- runs the external `check_panic` process test and checks its nonzero exit, flushed stdout, and fatal stderr;
- verifies the published 0.7.0 C bootstrap seed archive against the release `SHA256SUMS`;
- links the seed compiler against this checkout's runtime, builds three compiler stages, checks stage 2 and stage 3 C/MIR equality, and compiles/runs a hello program.

`CHECK_ABORTS` is a no-op in the current Windows test harness because it uses `fork` on POSIX. The separate panic process test does not replace those in-process abort assertions. This lane does not run ASan, UBSan, TSan, libuv/thread tests, LLVM API bootstrap, ORC REPL, the `mini` project tool, or release packaging.

## Support boundary

Linux and macOS remain the release platforms. Passing this lane is evidence for the native Windows C seed bootstrap and the listed runtime tests only. It does not establish full Windows support, a Windows release asset, or support for `minic.sh` and other POSIX shell entry points.

The workflow invokes PowerShell scripts directly and uses the Visual Studio developer-shell setup plus `clang.exe --target=x86_64-pc-windows-msvc`. The runner label is explicitly `windows-2022`; the workflow logs the runner image, Windows version, installed Clang version, and checks the expected target triple so image updates are visible.
