# The repository without Gradle

The main build of Mini needs **no JVM and no Gradle**. A C compiler and the latest release (or the C bootstrap seed) are enough for the C bootstrap; release packaging also needs LLVM 18 development libraries to produce the static LLVM API compiler.
The cross-checks against the official Kotlin compiler (K2 on the JVM) live in the separate, optional tool
[`tools/k2-oracle`](../tools/k2-oracle/README.md), run nightly and on manual dispatch, not per pull request.

## Layout

Every compiler component is a flat Mini project (`docs/mini-tool.md`): `mini.toml`, sources in `src/*.kt`, tests next to them in
`tests/*.kt`. Kotlin does not need package directories, so there is no `src/main/kotlin/<package path>/`.

```text
compiler/
  mini.toml            workspace: backend, frontend, lower, minic-native, native-driver, mini-tool
  backend/             MIR, C and LLVM emitters, RcOpt, memory checker      (lib)   src/ tests/
  frontend/            lexer, CST parser, AST, semantic analysis             (lib)   src/ tests/ driver/
  lower/               typed AST -> IR -> MIR                                (lib)   src/
  minic-native/        the compiler (frontend + lower + backend + driver)    (app)   src/
  native-driver/       .mir -> .c/.ll driver (B1)                            (app)   src/ tests/
  mini-tool/           the `mini` tool: pure core + NativePlatform           (app)   src/ tests/ host/mini_host.c
  llvm-c/              LLVM C API binding, built by bootstrap.sh --backend=llvm-api
  testdata/            file-driven end-to-end programs (.kt .out .err .cerr .diag .nok2 .noleakcheck)
  bootstrap.sh         stage0 (native) -> stage1 -> stage2 -> stage3, stage2 == stage3 (C, LLVM, LLVM API)
scripts/               build-mini.sh, test-e2e.sh, check-purity.sh, fetch-stage0.sh, ci-*.sh, wasm/
tools/k2-oracle/       the only Gradle build: K2 cross-checks on the JVM
```

`mini` learned two things for this: `@Test` methods of classes and objects are found next to top-level `@Test` functions
(JUnit/`kotlin.test` style), and `[package] c-sources = ["host/mini_host.c"]` compiles and links C files of a package (the POSIX
layer of `mini` itself). The Mini standard library gained `mini.io` file-system calls (`fileExists`, `isDirectory`, `listDirectory`,
`walkFiles`, `createDirectories`, `deleteRecursively`; runtime `b1.c`) so that tests need no JVM `File`.

## Build and test

```sh
./mini --version                            # builds build/native (compiler, tool, runtime) from the latest release's compiler
scripts/check-purity.sh                     # the compiler packages use no JVM API
(cd compiler && ../mini test -p backend -p frontend -p native-driver)   # unit tests; mini-tool: -p mini-tool (slower, real builds)
scripts/test-e2e.sh                         # compiler/testdata: C backend, ASan/LSan/UBSan, memory corpus; --suite llvm ... for LLVM
compiler/bootstrap.sh                       # stage2 == stage3; --backend=llvm | llvm-api
```

Release builds use `compiler/bootstrap.sh --backend=llvm-api` and link LLVM 18 static archives into `bin/minic-native` for
linux-x64, linux-arm64 and macOS arm64. The shipped compiler needs no LLVM installation at runtime. It still uses the platform
C linker to build programs; `minic` and `mini` choose the LLVM API path by default, and `--backend=c` selects the C fallback.

## Inventory: what was Gradle/JVM, and where it went

| Former module | Pure Kotlin (compiled by Mini)? | Now |
|---|---|---|
| `backend`, `frontend`, `lower`, `minic-native`, `native-driver`, `llvm-c` | yes | `compiler/<name>` flat Mini projects (`llvm-c`: built by `bootstrap.sh`) |
| `mini-tool` core + `native` platform | yes | `compiler/mini-tool` (`src/`, `host/`); the JVM platform (`jvm/`) is deleted |
| `jvm-bridge` (K2 frontend, Ir2Mir, CLI) | no, JVM | `tools/k2-oracle/jvm-bridge` |
| `frontend-oracle` | no, JVM (K2 PSI/FIR) | `tools/k2-oracle/frontend-oracle` |
| `memory-spec` | no, JVM (K2) | `tools/k2-oracle/memory-spec` |

| Test class (JUnit) | Tests | Now |
|---|---:|---|
| backend: `ConventionsTest` 13, `EmitterTest` 7, `LlvmEmitterTest` 7, `MirSerialUnitTest` 6, `RcOptTest` 32, `StrictEmitTest` 8 | 73 | `mini test -p backend` (79 with the 6 `DiagFormatTest` that arrived from main) |
| backend `PurityTest`, frontend `FrontendPurityTest`, `LowerPurityTest`, `MinicNativePurityTest`, `DriverPurityTest`, `MiniToolPurityTest` | 18 | `scripts/check-purity.sh` (same forbidden patterns, dependency rule from `mini.toml`, self-test of the checker) |
| frontend: `LosslessTest` 2, `MatmulParseTest` 9, `NegativeTest` 5, `SemaTest` 12 | 28 | `mini test -p frontend` (28) |
| native-driver `NativeDriverTest` | 2 | `mini test -p native-driver` (2) |
| mini-tool: `CoreTest` 21, `ManifestTest` 12, `TomlTest` 13, `ProjectTest`+`ScaffoldTest` 17, `GitDependencyTest` 4, `EndToEndTest` 5 | 72 | `mini test -p mini-tool` (72) |
| jvm-bridge `EndToEndTest` (66 programs + 89 leak checks) | 155 | `scripts/test-e2e.sh --suite c --suite leak` (66 + 89) |
| jvm-bridge `LlvmEndToEndTest` (66 + 89 + 4 variants) | 159 | `--suite llvm --suite llvm-leak --suite llvm-variants` (66 + 89 + 4) |
| jvm-bridge `LlvmApiEndToEndTest` | 66 | `--suite llvm-api` (66, needs the API compiler from `bootstrap.sh --backend=llvm-api`) |
| jvm-bridge `MemoryCheckTest` / `MiniMemoryCheckTest` (`.diag` expectations) | 123 | `--suite memcheck` (123); the K2-equals-own-frontend comparison stays in the oracle |
| jvm-bridge `MemoryRunTest` | 34 | `--suite memrun` (34 run, 1 skipped: threads) |
| jvm-bridge `MultiFileTest`, `MiniMemoryCheckTest.allUserFilesAreChecked`, `MemoryCheckTest.checkerCliRejectsAndAccepts` | 4 | `--suite multifile` (2 scripts covering the 4 checks) |
| jvm-bridge `MirSerialTest`, `MiniFrontendTest`, `SelfHostTest`, `StdlibJvmTest`, `B1IntrinsicsSyncTest`, K2 halves of the memory tests | rest | stay in `tools/k2-oracle` (they compare with K2 or the real kotlin-stdlib) |
| all `frontend-oracle` tests, `memory-spec` | all | stay in `tools/k2-oracle` |

The JUnit tests of the K2 path (`EndToEndTest`, `LlvmEndToEndTest`, ...) also stay in the oracle, so the nightly run still checks
that the K2 path produces the same results on the same programs.

## Known differences

* `mini test` has no per-test timeout (the JUnit `NegativeTest` limited each parse to 20 s): the CI job timeout catches a hang.
* The Mini standard library has no `Regex`; the emitter tests use plain string scans (`backend/tests/Support.kt`).
* The `java.util.Random` sequence of the fuzz tests is reproduced exactly by `frontend/tests/JavaRandom.kt`.
