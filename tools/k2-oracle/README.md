# K2 oracle (optional, JVM)

Cross-checks of the Mini compiler against the **official Kotlin compiler (K2) on the JVM**. This is the only place in the
repository that uses Gradle or a JDK. The main build (`compiler/`, `mini.toml`, `scripts/`, CI per pull request) is native and
never needs them; this tool runs nightly and on manual dispatch (`.github/workflows/k2-oracle.yml`), and locally when you want
a second opinion from K2.

## What is here

| Path | Role |
|---|---|
| `build.gradle.kts`, `settings.gradle.kts`, `modules/*.gradle.kts` | One Gradle build. The pure Mini packages (`compiler/backend`, `frontend`, `lower`, `minic-native`, `native-driver`) are compiled for the JVM straight from their `src/` directories; their build scripts live in `modules/`. |
| `jvm-bridge/` | The original JVM compiler: K2 frontend (`FrontendDriver`), Kotlin IR to MIR, CLI (`minic`). Its tests (`EndToEndTest`, `LlvmEndToEndTest`, `LlvmApiEndToEndTest`, `MemoryCheckTest`, `MiniMemoryCheckTest`, `SelfHostTest`, `StdlibJvmTest`, ...) run `compiler/testdata` through the K2 path, compare K2 and the own frontend (same MIR, same diagnostics) and run the Mini stdlib tests against the real kotlin-stdlib. |
| `frontend-oracle/` | The own parser against the K2 PSI parser, the semantic analysis against K2's FIR (fuzz, mutation, snippet and self-host tests). |
| `memory-spec/` | The memory-model corpus (`compiler/testdata/memory`) must be valid Kotlin for K2 and give the expected output on the JVM. |
| `selfcompare.py`, `selfcompare-baseline.txt` | MIR of the compiler sources from K2 vs from the own frontend; the difference may not grow. |
| `bootstrap-k2.sh` | The K2/JVM-started bootstrap (JVM stage1, then stage2 == stage3 natively); compares with the native chain. |
| `selfhost/` | The self-hosting driver compiled for the JVM by `SelfHostTest`. |

## Run

```sh
cd tools/k2-oracle
./gradlew :jvm-bridge:installDist                       # the JVM minic: jvm-bridge/build/install/minic/bin/minic
./gradlew :jvm-bridge:test :frontend-oracle:test :memory-spec:test
python3 selfcompare.py
./bootstrap-k2.sh /tmp/k2-boot
```

Needs JDK 21 and a C compiler (clang; gcc is used for sanitizers where clang cannot link them). The Kotlin version is pinned in
`gradle.properties` (`kotlinVersion`).
