# Mini: Kotlin, nativ und mit prüfbarem Speichermanagement

Mini ist **Kotlin**: gleiche Grammatik, gleiche Semantik, gleiche Standardbibliothek. Der Compiler
`minic` übersetzt aber nicht für die JVM, sondern über C in **native Binaries** und bringt ein eigenes
**Speichermodell** mit:

- **Managed** (Standard): Refcounting. Normaler Kotlin-Code läuft unverändert.
- **Arena / Heap / Pool**: Allocatoren als Kotlin-Context-Parameter (`context(arena) { … }`).
- **Strict** (`@file:MemoryMode(Mode.Strict)`): Jede Allokation braucht einen bewiesenen Besitzer.
  Der Compiler findet Leaks, Double-Free, Use-after-free und Arena-Escapes zur Compile-Zeit.

Jedes Mini-Programm ist gültiges Kotlin. Mini-spezifisches (Allocatoren, `@Owned`, `x.free()`) ist
eine normale Kotlin-Bibliothek, die auch auf der JVM kompiliert.

```kotlin
@file:MemoryMode(Mode.Strict)
import mini.memory.*

context(h: Heap)
fun load(n: Int): @Owned Buffer? {
    val buf = Buffer(n)
    if (!fill(buf)) return null   // error[M0102]: allocation from `Heap` is never released
    return buf
}
```

## Schnellstart

Voraussetzungen: JDK 21, clang (oder gcc), libuv nur für die Runtime-Tests.

```sh
./minic build examples/hello.kt -o build/hello && ./build/hello
./minic build examples/shapes.kt -o build/shapes && ./build/shapes
./minic check examples/memory_leak.kt
```

`./minic` baut den Compiler beim ersten Aufruf (Gradle) und ruft ihn dann auf.

## Aufbau

| Verzeichnis | Inhalt |
|---|---|
| `compiler/` | `minic`: K2-Frontend → Kotlin-IR → MIR → C17 → clang. Dazu der Ownership-Checker. [README](compiler/README.md) |
| `runtime/` | libminirt (C17): Objektmodell, Refcounting, Strings, Allocatoren. [README](runtime/README.md) |
| `stdlib/` | Mini-stdlib als Kotlin-Quelltext (portiert aus der Kotlin-stdlib, Apache-2.0) |
| `stdlib-test/` | stdlib-Tests, die auf der JVM gegen die echte Kotlin-stdlib laufen |
| `examples/` | Beispielprogramme |
| `docs/` | [Plan und Roadmap](docs/plan-kotlin.md), [Speichermodell](docs/memory-model.md), [stdlib](docs/stdlib.md) |

## Tests

```sh
runtime/run_tests.sh                 # Runtime unter ASan/UBSan/TSan (LIBUV_ROOT setzen, falls nötig)
(cd compiler && ./gradlew test)      # Compiler: End-to-End, Leak-Check, Speichermodell-Korpus, stdlib
```

Erwartete Ausgaben stammen immer aus echtem Kotlin auf der JVM.

## Stand

**v1 erreicht:** `minic` kompiliert sein eigenes Backend (reines Kotlin) zu einem nativen Binary
`minic-backend`. Für jedes Testprogramm erzeugt es byte-identisch dasselbe C wie das Backend auf der
JVM (Bootstrap-Stufe B1, geprüft von `SelfHostTest`):

```sh
./minic emit-mir examples/shapes.kt -o build/shapes.mir   # Frontend (K2, noch auf der JVM)
build/minic-backend build/shapes.mir -o build/shapes.c     # Backend, nativ
```

Funktioniert nativ: Klassen, Vererbung, Interfaces, `object`, `data`/`sealed`/`enum class`,
Null-Safety, Lambdas und Closures, `inline`/`reified`, Generics, Exceptions, Extensions, Arrays,
`vararg`, Ranges, Collections und große Teile der stdlib. Der Strict-Checker erkennt den kompletten
Speichermodell-Korpus.

Als Nächstes: eigenes Frontend (B2) für einen komplett nativen, selbst-hostenden Compiler.
Danach Dogfooding des Speichermodells (RC-Elision, Arenas im Compiler), Coroutines und Async-IO auf
libuv, LLVM-Backend und eine Runtime in Kotlin. Details in [docs/plan-kotlin.md](docs/plan-kotlin.md).

Plattformen: Linux und macOS (CI). Windows folgt später.

Der frühere C-Bootstrap-Compiler `mini0` mit eigener Syntax ist entfernt. Sein letzter Stand ist
der Merge-Commit `391270a` auf `main`.
