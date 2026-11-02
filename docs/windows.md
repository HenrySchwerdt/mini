# Windows support status

Windows x64 has two experimental build paths. The C compatibility lane uses the Visual Studio LLVM Clang toolset with the MSVC CRT and Windows SDK. An opt-in source install also builds the LLVM API compiler and native ORC REPL. Neither path uses WSL or an MSYS runtime as a product dependency. The published 0.8.0 Windows ZIP remains C-only; the LLVM API build is not yet a published Windows release.

## CI coverage

The C compatibility lane runs on the explicitly selected `windows-2022` GitHub runner and reports its Windows and Clang versions. It:

- compiles and runs the non-threaded runtime core tests with Clang's GNU-style driver targeting `x86_64-pc-windows-msvc`;
- skips `test_alloc_small` and `test_threads`, which exercise the current pthread path;
- runs the external `check_panic` process test and checks its nonzero exit, flushed stdout, and fatal stderr;
- separately compiles `test_entry_args.c` with `entry.c` and launches it with `café` and `emoji😀`, checking that UTF-16 argv values survive through the atexit callback;
- tests native child-process quoting, Unicode, long arguments, environment/cwd isolation, and large simultaneous stdout/stderr capture;
- verifies the published 0.7.0 C bootstrap seed archive against the release `SHA256SUMS`;
- links the seed compiler against this checkout's runtime, builds three compiler stages, checks stage 2 and stage 3 C/MIR equality, and compiles/runs a hello program;
- compiles and runs a multi-file package/import smoke plus a CLI smoke whose source and output paths contain spaces, BMP Unicode and emoji.
- builds an experimental C-backend `mini.exe` install using the Windows host layer and source stdlib, then exercises `mini new`, `mini run`, `mini test`, `mini check`, and propagation of a failed compiler subprocess from paths containing spaces and Unicode;
- runs the complete source installer across distinct temporary/destination volumes, then checks that an occupied install prefix is refused without changing the installed executable;
- packages the C-only Windows ZIP, extracts it into a fresh directory and repeats the installed CLI smoke tests.

`CHECK_ABORTS` is a no-op in the current Windows test harness because it uses `fork` on POSIX. The separate panic process test does not replace those in-process abort assertions. The C lane does not run ASan, UBSan, TSan, libuv/thread tests, LLVM API bootstrap, ORC REPL, or LLVM release packaging.

The separate LLVM API workflow is configured to validate the official LLVM 18 static SDK, build and run a static-link probe, bootstrap the API compiler from the C compiler, test the installed API compiler and REPL, package an LLVM API ZIP, and exercise the standalone release installer against that package. It requires byte-identical stage 2 and stage 3 output for C, MIR, LLVM IR, and object formats, verifies required PE exports, and runs the session ownership and rollback harness. Installer tests cover wrong or missing checksums, path traversal, and refusal to overwrite an existing prefix.

## Support boundary

Linux and macOS remain the stable LLVM API release platforms. The standalone root `install.ps1` downloads the latest Windows release ZIP, verifies its `SHA256SUMS` entry, and installs into a new prefix without a Git clone or source bootstrap. The currently published 0.8.0 Windows ZIP is C-only and was built and smoke-tested with the C compatibility lane. The PowerShell source installer still defaults to the C backend: it downloads and verifies the published 0.7.0 C seed, bootstraps the current compiler, and stages the native project tool under `%USERPROFILE%\.mini-windows` by default. Both installers refuse to overwrite a prefix and do not modify persistent PATH settings.

Selecting `-Backend llvm-api` opts into the separate Windows LLVM API source build. It requires Windows x64, PowerShell 7, Git, Visual Studio 2022 C++ tools with LLVM Clang and the Windows SDK, CMake 3.24 or newer, Ninja, and 7-Zip. The installer downloads and prepares the official LLVM 18 development archive (about 1 GB). `-LLVMPrefix` can reuse a prefix created by `scripts/windows/prepare-llvm.ps1`, including its asset manifest. The resulting compiler links LLVM statically, so the SDK is not needed to run the installed toolchain. In this install, `mini build`, `mini run`, and `mini test` use the LLVM API by default; `mini run --backend c` selects the C fallback, and `mini repl` starts the native REPL. Windows threads are unsupported.

Neither lane establishes full Windows feature parity. POSIX shell entry points such as `minic.sh` are not supported natively on Windows.

The workflows invoke PowerShell 7 scripts directly and use the Visual Studio developer-shell setup plus `clang.exe --target=x86_64-pc-windows-msvc`. Their runner label is explicitly `windows-2022`; the workflows log the runner image, Windows version, installed Clang version, and check the expected target triple so image updates are visible.

The filesystem and entry layer use UTF-16 Windows APIs and preserve raw UTF-16 argument units. Drive and UNC roots (including extended drive/UNC syntax) have pure parser regression tests; actual remote SMB shares have not been exercised. Device and GLOBALROOT namespaces are rejected by recursive directory creation. The Windows default allocator temporarily uses malloc instead of the thread-local small-object cache until Windows thread-exit cleanup is implemented; public Pool, Arena and Debug allocators remain tested.

`bootstrap-c.ps1` uses a fresh work directory and refuses to delete an existing one. A developer can supply `-WorkDirectory` explicitly; it does not require GitHub's `RUNNER_TEMP` variable.
