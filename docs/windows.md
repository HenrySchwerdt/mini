# Windows support status

Windows x64 has two experimental build paths. The C compatibility lane uses the Visual Studio LLVM Clang toolset with the MSVC CRT and Windows SDK. An opt-in source install also builds the LLVM API compiler and native ORC REPL. Neither path uses WSL or an MSYS runtime as a product dependency. Windows packages from 0.9.0 include the LLVM API compiler and REPL; the older 0.8.0 ZIP is C-only.

## CI coverage

The C compatibility lane runs on the explicitly selected `windows-2022` GitHub runner and reports its Windows and Clang versions. It:

- compiles and runs the non-threaded runtime core tests with Clang's GNU-style driver targeting `x86_64-pc-windows-msvc`;
- skips `test_alloc_small` and `test_threads`, which exercise the current pthread path;
- runs the external `check_panic` process test and checks its nonzero exit, flushed stdout, and fatal stderr;
- separately compiles `test_entry_args.c` with `entry.c` and launches it with `café` and `emoji😀`, checking that UTF-16 argv values survive through the atexit callback;
- tests native child-process quoting, Unicode, long arguments, environment/cwd isolation, and large simultaneous stdout/stderr capture;
- verifies the published 0.10.0 C bootstrap seed archive against the release `SHA256SUMS`;
- links the seed compiler against this checkout's runtime, builds three compiler stages, checks stage 2 and stage 3 C/MIR equality, and compiles/runs a hello program;
- compiles and runs a multi-file package/import smoke plus a CLI smoke whose source and output paths contain spaces, BMP Unicode and emoji.
- builds an experimental C-backend `mini.exe` install using the Windows host layer and source stdlib, then exercises `mini new`, `mini run`, `mini test`, `mini check`, and propagation of a failed compiler subprocess from paths containing spaces and Unicode;
- runs the complete source installer across distinct temporary/destination volumes, then checks that an occupied install prefix is refused without changing the installed executable;
- packages the C-only Windows ZIP, extracts it into a fresh directory and repeats the installed CLI smoke tests.

`CHECK_ABORTS` is a no-op in the current Windows test harness because it uses `fork` on POSIX. The separate panic process test does not replace those in-process abort assertions. The C lane does not run ASan, UBSan, TSan, libuv/thread tests, LLVM API bootstrap, ORC REPL, or LLVM release packaging.

The separate LLVM API workflow is configured to validate the official LLVM 18 static SDK, build and run a static-link probe, bootstrap the API compiler from the C compiler, test the installed API compiler and REPL, package an LLVM API ZIP, and exercise the standalone release installer against that package. It requires byte-identical stage 2 and stage 3 output for C, MIR, LLVM IR, and object formats, verifies required PE exports, and runs the session ownership and rollback harness. Installer tests cover wrong or missing checksums, path traversal, and refusal to overwrite an existing prefix.

## Support boundary

Linux and macOS remain the stable LLVM API release platforms. The standalone root `install.ps1` downloads the latest Windows release ZIP, verifies its `SHA256SUMS` entry, and installs into a new prefix without a Git clone or source bootstrap. Version 0.9.0 Windows packages use the LLVM API backend and include the REPL; the older 0.8.0 ZIP is C-only. The PowerShell source installer still defaults to the C backend: it downloads and verifies the published 0.10.0 C seed, bootstraps the current compiler, and stages the native project tool under `%USERPROFILE%\.mini-windows` by default. Both installers refuse to overwrite a prefix and do not modify persistent PATH settings.

Selecting `-Backend llvm-api` opts into the separate Windows LLVM API source build. It requires Windows x64, PowerShell 7, Git, Visual Studio 2022 C++ tools with LLVM Clang and the Windows SDK, CMake 3.24 or newer, Ninja, and 7-Zip. The installer downloads and prepares the official LLVM 18 development archive (about 1 GB). `-LLVMPrefix` can reuse a prefix created by `scripts/windows/prepare-llvm.ps1`, including its asset manifest. The resulting compiler links LLVM statically, so the SDK is not needed to run the installed toolchain. In this install, `mini build`, `mini run`, and `mini test` use the LLVM API by default; `mini run --backend c` selects the C fallback, and `mini repl` starts the native REPL. Windows threads are unsupported.

Neither lane establishes full Windows feature parity. POSIX shell entry points such as `minic.sh` are not supported natively on Windows.

The workflows invoke PowerShell 7 scripts directly and use the Visual Studio developer-shell setup plus `clang.exe --target=x86_64-pc-windows-msvc`. Their runner label is explicitly `windows-2022`; the workflows log the runner image, Windows version, installed Clang version, and check the expected target triple so image updates are visible.

The filesystem and entry layer use UTF-16 Windows APIs and preserve raw UTF-16 argument units. Drive and UNC roots (including extended drive/UNC syntax) have pure parser regression tests; actual remote SMB shares have not been exercised. Device and GLOBALROOT namespaces are rejected by recursive directory creation. The Windows default allocator temporarily uses malloc instead of the thread-local small-object cache until Windows thread-exit cleanup is implemented; public Pool, Arena and Debug allocators remain tested.

`bootstrap-c.ps1` uses a fresh work directory and refuses to delete an existing one. A developer can supply `-WorkDirectory` explicitly; it does not require GitHub's `RUNNER_TEMP` variable.

## The C bootstrap seed and what it restricts in `compiler/**`

The Windows C lane (`windows.yml`, `scripts/windows/bootstrap-c.ps1`, `scripts/windows/install.ps1`) has no stage0 binary: it builds a compiler
from the generated C of a published release, the portable seed `mini-<version>-bootstrap-c.tar.gz` (written by `scripts/package-seed.sh`,
published by `release.yml` together with `SHA256SUMS`), and then lets that compiler build the current sources. Every release since 0.7.0
carries one; the lane used 0.7.0 until it was moved to **0.10.0** (checksum of the archive:
`08ff278311128c79802d494367e0b06d4a7145479af98582b1487145f5f2e578`). The seed is the oldest compiler that has to translate `compiler/**`
and `stdlib/**`, so the oldest seed fixes the dialect those sources may use. Check it locally before pushing compiler changes:
download the seed, build it with `CC=clang ./build.sh stage0`, and run `stage0 -o out.c <compiler/{minic-native,lower,frontend,backend}/src/**.kt>
--stdlib <stdlib/**.kt>` (the same file lists as `bootstrap-c.ps1`). The check must print no error. The seed's C runtime is not linked on
Windows: the generated C of the seed is compiled against this checkout's `runtime/` (checked on Linux: the 0.10.0 seed C built with this checkout's `runtime/src/*.c`
translates the current sources, and `compiler/bootstrap.sh --stage0 <that stage0>` reaches stage2 == stage3 with the native memory check passing).

Restrictions of the 0.7.0 seed (the previous Windows lane) that applied to `compiler/**`, and their status with the 0.10.0 seed:

| restriction | 0.7.0 seed | 0.10.0 seed |
|---|---|---|
| `by lazy { }` (a lambda stored in a delegate) | forbidden | translated correctly (probe) |
| lambdas that capture locals (closures over `val` / `var` of the enclosing function) | forbidden | translated correctly (probe, also a captured `var`) |
| `Outer.Inner()` constructor calls (qualified nested-class constructor calls) | forbidden | translated correctly (probe) |
| overloads whose C names collide after name mangling (`append(String?)` against `append(CharSequence?)`) | collisions | `sb.append(s: String?)` and `sb.append(cs: CharSequence?)` in one program compile and run (probe) |

"Translated correctly" is what a probe program with each construct gave when compiled by the 0.10.0 seed and run (output as the JVM prints it). The restriction is lifted only on the infrastructure side: the Windows lane now bootstraps from 0.10.0. No
code of `compiler/**` was rewritten in this round, and the rules stay in force for code until the Windows CI run on this seed has passed once;
they are then dropped from the contributor instructions. The next step is the same again at every release: when a release is published, the
lane is moved to its seed (`windows.yml` step "Download and verify portable <version> C seed" and the `$SeedVersion` default of
`install.ps1`), so the dialect of `compiler/**` is always bounded by the previous release, not by the oldest one.
