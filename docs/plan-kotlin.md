# Plan: Mini wird natives Kotlin mit prüfbarem Speichermodell

Status: **Entwurf zur Entscheidung**. Dieses Dokument beschreibt den Umbau von Mini
(v0.3, eigene Kotlin-inspirierte Syntax) zu einer Sprache, die **1:1 Kotlin-Grammatik
und -Semantik** spricht, die **Kotlin-Standardbibliothek** mitbringt, **Kotlin-Libs**
nutzen kann, über `external` mit **C-Bibliotheken** redet, **libuv** für Threads/IO
verwendet und deren Besonderheit ein **Allocator-Trait-System** ist, mit dem der
Compiler prüft, *ob* und *wo* sinnvoll alloziert wird und *was nicht freigegeben* wurde.

---

## 1. Leitprinzipien

1. **Jedes Mini-Programm ist ein gültiges Kotlin-Programm.** Keine eigene Syntax mehr.
   Alles Mini-Spezifische (Allocator, Ownership, C-Interop) wird über Mittel
   ausgedrückt, die Kotlin schon hat: Annotationen (auch Typ-Annotationen),
   Context-Parameter (`context(alloc: Allocator)`), `external`, `expect`/`actual`,
   Interfaces. Damit funktionieren IntelliJ/kotlin-lsp, Formatter und Syntax-Highlighting
   sofort, und derselbe Code kompiliert (mit No-op-Allocator-Lib) auch auf der JVM.
2. **Dateien heißen `.kt`.** Die `.mini`-Endung entfällt.
3. **Kompatibilität vor Kontrolle, aber Kontrolle ist abrufbar.** Fremder Kotlin-Code
   geht davon aus, dass es einen GC gibt. Der läuft im Modus `Managed`. Eigener Code
   kann pro Datei oder Modul auf `Strict` schalten. Dann **muss** jede Allokation
   statisch einem Besitzer zugeordnet und freigegeben sein, sonst gibt es einen
   Compile-Fehler.
4. **Eine Analyse, zwei Nutzen.** Dieselbe Ownership- und Escape-Analyse, die im
   `Strict`-Modus Diagnosen liefert, eliminiert im `Managed`-Modus Refcount-Operationen
   und verschiebt nicht entkommende Objekte auf den Stack.
5. **Konformität wird gemessen, nicht behauptet.** Ziel-Testsuite sind die
   `codegen/box`-Tests aus dem Kotlin-Repo (`compiler/testData/codegen/box`, jeder
   Test liefert `"OK"`). Die Pass-Rate ist die zentrale Fortschrittsmetrik.

## 2. Die Weichenstellung: Frontend selbst bauen oder K2 nutzen?

Das ist die wichtigste Entscheidung, denn sie bestimmt den Aufwand um Größenordnungen.

| | **A: Eigenes Frontend in C** (Fortsetzung von mini0) | **B: Eigenes Frontend in Kotlin** (selbst-hostend) | **C: Kotlin-K2-Frontend + eigenes Backend** *(Empfehlung)* |
|---|---|---|---|
| Parser | handgeschrieben nach kotlin-spec-Grammatik | handgeschrieben | geschenkt (K2/FIR) |
| Typsystem | selbst: Overload-Resolution, Inferenz, Smart Casts, Varianz, Builder-Inferenz … | selbst | geschenkt, exakt Kotlin |
| Kotlin-Libs nutzen | nur Quellcode, nur was unser Frontend versteht | dito | Quellcode **und** klibs (die IR enthalten) |
| Gemeinsame Lowerings (Coroutines, Inline, Data-/Enum-Klassen, lokale Klassen) | alles selbst | alles selbst | großteils aus `backend.common` wiederverwendbar |
| Risiko | Jahre bis zu echter Lib-Kompatibilität | dito | interne Compiler-APIs ändern sich zwischen Kotlin-Versionen, daher Version pinnen |
| Compiler läuft auf | nativ | JVM, später nativ (sich selbst kompilieren) | JVM |

**Empfehlung: C.** Das Ziel ist „1:1 Kotlin, gleiche std, Kotlin-Libs nutzen“. Das
Einzigartige an Mini ist das Speichermodell, nicht der Parser. Kotlin/Native, Kotlin/Wasm
und Kotlin/JS sind genau so gebaut: gemeinsames Frontend, eigenes IR-Backend. Wir bauen
ein weiteres Backend („Kotlin/Mini“), das IR nach C (später LLVM) übersetzt und unsere
C/libuv-Runtime linkt.

Die Ownership-Diagnosen laufen zweigleisig:
- **FIR-Checker** (`FirAdditionalCheckersExtension`, stabile Plugin-API) für Fehler, die
  man schon am Quelltext sieht. Diese erscheinen damit auch in der IDE.
- **IR-Pass** nach dem Inlining für die flusssensitive Analyse (Leaks, Double-Free,
  Escape). Diese Fehler erscheinen beim Build.

Phase 0 ist ein **Spike**, der Variante C validiert. Scheitert er, fallen wir auf B
zurück. Alle Teile ab Abschnitt 4 (Runtime, Speichermodell, Interop, std-Actuals) sind
in beiden Varianten dieselben.

## 3. Architektur (Variante C)

```
 .kt Quellen ─┐
 klibs (Libs) ┼─► K2 Frontend (FIR) ──► FIR-Checker: mini.memory-Regeln (IDE + CLI)
 mini-stdlib ─┘        │
                       ▼
                  Fir2Ir ──► Kotlin IR (IrModuleFragment)
                       │
                       ▼  gemeinsame Lowerings aus backend.common
                  Inline, Coroutines→State-Machines, lokale Klassen, Enums, Data Classes …
                       │
                       ▼  Mini-Lowerings
                  1. Escape-Analyse      → Stack-Promotion
                  2. Allocator-Auflösung → welcher Allocator pro Allokationsstelle
                  3. Ownership-Analyse   → Strict: Diagnosen / Managed: RC-Elision (Perceus-artig)
                  4. Exceptions          → explizite Fehler-Rückgabe (Swift-Error-ABI-artig)
                  5. Generics            → Primitive monomorphisieren, Referenztypen geteilt
                       │
                       ▼
                  C-Emitter (später: LLVM)  ──► clang ──► + libminirt (C, libuv) ──► Binary
```

Der Compiler ist ein Gradle-Projekt in Kotlin unter `compiler/` mit gepinnter
Kotlin-Version. Die Runtime bleibt C17 mit xmake unter `runtime/`.

## 4. Das Speichermodell

### 4.1 Allocator-Traits

Allocatoren sind normale Kotlin-Interfaces in `mini.memory`. Der Compiler kennt die
**Fähigkeits-Traits** und leitet daraus ab, welche Prüfungen gelten:

```kotlin
package mini.memory

interface Allocator {
    fun allocate(size: Long, align: Int): NativePtr?
}

/** Einzelne Objekte werden einzeln freigegeben → Must-Free-Analyse. */
interface Deallocating : Allocator {
    fun free(ptr: NativePtr)
}

/** Alles stirbt gemeinsam am Scope-Ende → Escape/Lifetime-Analyse statt Free-Pflicht. */
interface Scoped : Allocator, AutoCloseable

/** Refcount (+ Zyklensammler). Keine Pflichten, Default für fremden Kotlin-Code. */
interface Managed : Allocator

/** Marker: darf von mehreren Threads benutzt werden. */
interface ThreadSafe
```

Mitgelieferte Implementierungen: `Heap : Deallocating, ThreadSafe` (malloc/free),
`Arena : Scoped`, `Pool<T> : Deallocating`, `Rc : Managed, ThreadSafe` (Default),
`DebugAllocator`, der Leaks zur Laufzeit mit Allokationsstelle meldet.

| Trait des Allocators | Was der Compiler prüft |
|---|---|
| `Deallocating` | Jede Allokation wird auf **jedem** Pfad genau einmal freigegeben oder an einen `@Owned`-Slot übergeben. Kein Use-after-free, kein Double-free. |
| `Scoped` | Kein Objekt aus der Arena entkommt dem Scope (Rückgabe, Feld eines langlebigeren Objekts, Capture in einem entkommenden Lambda). |
| `Managed` | nichts, nur Optimierung |
| `Scoped` (zusätzlich) | Eine Arena ruft beim Freigeben **kein** `dispose` auf, damit sie pro Objekt nichts kostet. Deshalb darf ein Arena-Objekt keine Referenzen auf Objekte anderer Allocatoren besitzen (z. B. refcounted Strings). Der Compiler meldet das als Fehler. *(entschieden)* |
| ohne `ThreadSafe` | Objekte daraus dürfen nicht an einen anderen Thread übergeben werden. |

### 4.2 Wo wird alloziert? Platzierung pro Konstruktoraufruf

Jedes `Foo(...)` ist eine Allokationsstelle. Der Compiler entscheidet in dieser Reihenfolge:

1. **Entkommt nicht** (Escape-Analyse) → **Stack**, kein Allocator nötig. Das gilt in
   jedem Modus und ist die wichtigste Optimierung.
2. Ein `context(alloc: …)` ist im Scope → dieser Allocator.
3. Die Klasse legt einen Allocator fest (`@AllocatedBy(Pool::class)`) → dieser.
4. Sonst der Modul-Default (`Rc`, bzw. ein Fehler im `Strict`-Modus).

Kotlin-Context-Parameter (seit 2.2) sind genau das richtige Werkzeug, weil sie
Standard-Kotlin sind:

```kotlin
import mini.memory.*

class Node(val value: Int, var next: Node? = null)

context(alloc: Arena)
fun buildList(n: Int): Node? {
    var head: Node? = null
    for (i in n downTo 1) head = Node(i, head)   // → Arena
    return head
}

fun main() {
    Arena().use { arena ->
        context(arena) {
            val list = buildList(1000)
            println(list?.value)
        }
    }   // Arena wird freigegeben; der Compiler hat bewiesen, dass `list` nicht entkommt
}
```

### 4.3 Ownership in Kotlin-Syntax

Annotationen, auch auf Typen (gültiges Kotlin):

- `@Owned` auf Rückgabe, Parameter oder Feld: Besitz wird übertragen.
- `@Borrowed` (Default für Parameter): nur geliehen, darf nicht gespeichert werden.
- `x.free()` / `alloc.free(x)` / `x.use { }` beenden den Besitz.
- Default-Inferenz: Parameter sind geliehen, Rückgaben besitzend, Felder besitzend.
  Annotationen braucht man nur an Modulgrenzen und in Spezialfällen.

```kotlin
@file:MemoryMode(Strict)

class Buffer(val size: Int) : AutoCloseable { /* … */ }

context(alloc: Heap)
fun load(): @Owned Buffer {
    val buf = Buffer(4096)
    if (!fill(buf)) return Buffer(0)   // ← Fehler
    return buf
}
```

```
error[M0102]: allocation from `Heap` (Deallocating) is never released
  --> src/load.kt:5:15   `val buf = Buffer(4096)` allocated here
  --> src/load.kt:6:24   path: early return without `buf.free()` / `buf.close()`
  help: call `buf.close()` before returning, or wrap in `buf.use { … }`
```

### 4.4 Ist es sinnvoll, hier zu allozieren? Lints

| Code | Meldung |
|---|---|
| M0201 (info) | Heap-Allokation entkommt nicht und wurde auf den Stack verschoben |
| M0202 (warn) | Allokation in Schleife in einer `Scoped`-Arena: unbeschränktes Wachstum |
| M0203 (warn) | großes Objekt (> N KB) auf dem Stack |
| M0204 (error, Strict) | Allokation braucht `Managed`-Fallback, Ownership nicht beweisbar |
| M0205 (warn) | Allokation in `@NoAlloc`-Funktion oder Hot-Path |
| M0206 (error) | Objekt aus nicht `ThreadSafe`-Allocator verlässt den Thread |

### 4.5 Modi

- `@file:MemoryMode(Managed)`: **Default**, für alle fremden Kotlin-Libs. Refcounting
  mit Elision. Das Refcounting ist nicht-atomar, solange ein Objekt beim Thread bleibt,
  und wird beim Teilen zwischen Threads atomar („biased RC“). Ein Zyklensammler kommt in
  einer späteren Phase dazu.
- `@file:MemoryMode(Strict)`: kein RC-Fallback, jede Allokation braucht einen bewiesenen
  Besitzer.
- Gemischt: Strict-Code darf Managed-Code aufrufen. Managed-Objekte sind im Strict-Code
  „geliehen, Lebensdauer extern“. Umgekehrt dürfen Strict-Objekte nicht in Managed-Felder
  gespeichert werden, ohne den Besitz zu übertragen.

## 5. Kotlin-Semantik, die Runtime-Arbeit braucht

| Kotlin-Feature | Umsetzung |
|---|---|
| `Any`, `equals`/`hashCode`/`toString`, `is`/`as` | Objekt-Header (TypeInfo-Pointer + RC/Flags), vtables und Interface-Tabellen |
| `T?` / `null` | Nullpointer bei Referenztypen, Tag+Wert bei Primitiven (ersetzt Minis `Optional`) |
| `kotlin.Result`, `when` | ersetzen Minis `Result`/`match` 1:1 |
| Exceptions | Fehler-Rückgabe-ABI im C-Backend (Flag nach jedem werfenden Aufruf, `@NoThrow`-Inferenz entfernt Checks). Im LLVM-Backend später echtes Unwinding. `finally` und `use` rufen auch im Fehlerpfad `free` auf. Die Ownership-Analyse kennt diese Pfade. |
| Generics (erased in Kotlin) | Referenztypen teilen sich Code (ein Pointer-Layout), Primitive werden monomorphisiert. `reified` geht über Inlining. |
| Lambdas/Closures | Closure-Objekt geht über die normale Allokationsplatzierung, nicht entkommende Lambdas liegen auf dem Stack |
| `String`, `StringBuilder` | UTF-16 wie Kotlin (Kompatibilität bei `Char`/Indexen), aus der std mit `actual`s |
| Coroutines/`suspend` | State-Machine-Lowering aus `backend.common`. Dispatcher läuft auf der libuv-Loop. `Dispatchers.IO`/`Default` nutzen den libuv-Threadpool (`uv_queue_work`). |
| `object`/Companion, `lateinit`, `lazy` | Runtime-Init-Guards, threadsicher über atomics |

## 6. Standardbibliothek

- **Quelle:** die `common`-Quellen der offiziellen Kotlin-stdlib (`libraries/stdlib/src`,
  Apache-2.0) plus **unsere `actual`s** in `stdlib-mini/`. Kotlin/Native hat das gleiche
  Problem gelöst. Seine `actual`s (`kotlin-native/runtime/src/main/kotlin`, ebenfalls
  Apache-2.0) dienen als Vorlage. Deren `@SymbolName`/C++-Runtime-Aufrufe ersetzen wir
  durch unsere C-Runtime.
- **Abkürzung, früh prüfen:** die fertige stdlib-klib von Kotlin/Native direkt durch
  unser Backend schicken und nur deren Runtime-Symbole in C nachbauen.
- **Mini-Erweiterungen** in eigenen Paketen: `mini.memory`, `mini.concurrent` (Thread,
  Mutex, Condition auf libuv), `mini.io` (Dateien, TCP, UDP, DNS auf libuv),
  `mini.interop`.
- **kotlinx.coroutines** als erste große Fremd-Lib: deren Native-Sourceset auf unsere
  Runtime portieren, mit einem libuv-Dispatcher.

## 7. C-Interop mit `extern`

Alles in Kotlin-Syntax und **kompatibel zur `kotlinx.cinterop`-API von Kotlin/Native**,
damit Kotlin/Native-Code mit cinterop bei uns funktioniert:

```kotlin
@file:CInclude("uv.h")
@file:CLink("uv")

import kotlinx.cinterop.*

@CName("uv_version_string")
external fun uvVersionString(): CPointer<ByteVar>?

@CStruct("uv_loop_t")
external class UvLoop : CStructVar

fun main() {
    println(uvVersionString()?.toKString())
    memScoped {                       // = Scoped-Allocator, gleiche Analyse wie Arena
        val n = alloc<IntVar>()
        n.value = 42
    }
}
```

- `external fun` + `@CName` werden zu einem direkten C-Aufruf ohne Wrapper.
- `CPointer<T>`, `CValue<T>`, `memScoped`, `alloc<T>()`, `staticCFunction` (Callbacks)
  wie in Kotlin/Native.
- **`mini-bindgen`**: liest C-Header (libclang) und erzeugt die `external`-Deklarationen
  als `.kt`, wie `cinterop` mit `.def`-Dateien.
- Export in die andere Richtung: `@CName("my_fn") fun …` erzeugt C-ABI-Symbole und einen
  generierten Header, damit C Mini-Code aufrufen kann.
- Ownership über die Grenze: Pointer aus C sind „extern verwaltet“. `@Owned` an einer
  `external fun` überträgt die Free-Pflicht, z. B. `@Owned(free = "uv_loop_close")`.

## 8. Kotlin-Libs nutzen: Stufen

1. **Quelltext von KMP-Libs** (`commonMain`): kompiliert direkt. Für ihre `expect`s
   schreiben wir einen „mini“-Satz `actual`s, oder nehmen die `nativeMain`-Variante,
   wenn sie nur cinterop/posix nutzt.
2. **klibs** (Kotlin/Native-Bibliotheken enthalten serialisiertes IR): mit Variante C
   kann unser Backend sie deserialisieren und kompilieren. Damit funktionieren viele
   veröffentlichte KMP-Libs ohne Quelltext. Grenze: Libs, die Kotlin/Native-interne
   Intrinsics nutzen, brauchen passende Runtime-Symbole.
3. **JVM-only-Libs** (`.jar`): bewusst **nicht unterstützt**, weil sie das JDK brauchen.

Gradle-Integration: ein Gradle-Plugin mit eigenem KMP-Target `mini()`, damit man in
bestehenden KMP-Projekten einfach ein Target hinzufügen kann (spätere Phase).

## 9. Was mit dem Bestand passiert

| Bestand | Zukunft |
|---|---|
| `bootstrap/` (mini0, C) | wird als `v0.3-legacy` getaggt und eingefroren, später entfernt |
| `runtime/` + libuv-Anbindung aus `bootstrap/src/lowering/threading.c`, `std/mini/net` | wird zur Basis von `libminirt` (C17, xmake) |
| `std/mini/*.mini` | als `.kt` neu geschrieben: Kotlin-Namen gibt es in der echten stdlib, Mini-Spezifisches kommt nach `mini.*` |
| `examples/*.mini` | nach `.kt` portiert und Teil der Testsuite |
| CI-Matrix, xmake | bleibt für die Runtime, Gradle kommt für den Compiler dazu |
| Docs v0.2/v0.3 | Archiv. Neue Referenz: „Kotlin + Mini-Speichermodell“ |

## 10. Phasen und Abnahmekriterien

**Priorität (Stand jetzt):** Linux und macOS zuerst. Windows läuft in der CI mit, blockiert
aber nicht. Manuelles Speichermanagement ist das Kernfeature. Deshalb kommen die
Grundlagen von `mini.memory` (`Heap`, `Arena`, `free`/`use`, `@file:MemoryMode(Strict)` und
die ersten Leak- und Double-Free-Diagnosen) schon in P1, parallel zu den Klassen. P3 baut
die Analyse dann aus (Escape-Analyse, Lints, Thread-Regeln).

| Phase | Inhalt | Fertig, wenn … |
|---|---|---|
| **P0 Spike** (Weichenstellung) | Gradle-Projekt mit gepinnter Kotlin-Version, K2 → IR → minimaler C-Emitter | `fun main() { println(1 + 2) }` aus `.kt` läuft nativ. Entscheidung C oder B ist getroffen. |
| **P1 Kern-Backend** | Klassen, Vererbung, Interfaces, `when`, Nullability, Strings, Primitive, Arrays, Lambdas. Box-Test-Runner. | Runner läuft in CI, Pass-Rate wird reportet |
| **P2 Runtime + std** | Objekt-Header, RC (`Rc`), Exceptions, stdlib-`common` + `actual`s, Collections | `println(listOf(1,2,3).map { it * 2 })`, ≥ 30 % Box-Tests |
| **P3 Speichermodell** | `mini.memory`, Escape-Analyse, Allocator-Auflösung, Strict-Ownership-Analyse, FIR-Checker, Lints M01xx/M02xx, DebugAllocator | Negativ-Tests für jede Diagnose, alle portierten Beispiele laufen unter ASan leckfrei |
| **P4 Interop** | `external`/`@CName`, cinterop-API, `mini-bindgen`, C-Export | libuv-Bindings werden **generiert** statt handgeschrieben |
| **P5 Concurrency/IO** | `mini.concurrent`, `mini.io` auf libuv, Coroutines-Lowering, kotlinx.coroutines-Port | Echo-Server mit Coroutines, TSan sauber |
| **P6 Libs** | klib-Import, Gradle-Plugin/KMP-Target | eine echte KMP-Lib (z. B. kotlinx-datetime oder okio) läuft unverändert |
| **P7** | LLVM-Backend, Zyklensammler, Performance | Box-Tests ≥ 80 %, Benchmarks gegen Kotlin/Native |
| **P8 Self-Hosting** | siehe Abschnitt 10a | Mini-Compiler baut sich selbst, Stage 2 == Stage 3 |

### 10b. v1 und Bootstrap: konkrete Meilensteine

**v1 ist fertig, wenn `minic` sein eigenes Backend kompiliert** (`compiler/backend`, reines
Kotlin, Regel aus 10a) und das native Ergebnis für alle Testprogramme dieselbe C-Ausgabe
erzeugt wie das JVM-Backend. Das ist ein harter, automatisch prüfbarer Maßstab. Er zwingt
genau die Features herbei, die echter Kotlin-Code braucht:

| Bereich | Für v1 nötig |
|---|---|
| Sprache | Klassen, `sealed`/`data`/`enum`/`object`, Interfaces, Generics (Klassen + Funktionen), Lambdas und Closures, Extension-Funktionen, `when`, Nullability, Exceptions (`error()`, `require`, `try/catch/finally`), Default- und Named-Args, `vararg`, Properties mit Gettern, `lazy` |
| stdlib | `String`/`StringBuilder`, `List`/`MutableList`/`Map`/`Set` (+ `ArrayList`, `HashMap`, `LinkedHashMap`, `HashSet`), `map`/`filter`/`joinToString`/`forEach`/`associate`…, `Pair`, Ranges, `buildString`, `println` |
| Speicher | Managed (RC) überall. Strict-Prüfung läuft als eigener Strang parallel. |

**Bootstrap-Stufen danach:**
1. **B1 (= v1):** Backend self-hosted. Native `minic-backend` liest serialisiertes MIR
   (vom JVM-Frontend als Datei geschrieben) und erzeugt C. Damit läuft der halbe Compiler nativ.
2. **B2:** Eigenes Frontend in `common`-Kotlin (Lexer → Parser → Resolver → MIR), K2 als
   Orakel. Wachstum Datei für Datei: Jede Testdatei muss dasselbe MIR liefern wie der K2-Pfad.
3. **B3:** Komplett nativ: `minic` kompiliert sich selbst ohne JVM, Stage 2 == Stage 3.

Der stdlib-Weg für v1: Kotlin-stdlib-Quellen (`common`, Apache-2.0) **zusammen mit dem
Programm** als Quelltext durch K2 schicken, mit `-Xallow-kotlin-package` und eigenen `actual`s.
So haben auch `inline`-Funktionen wie `map`/`filter` IR-Bodies, die unser Backend
kompilieren kann. Aus der JVM-stdlib-JAR gibt es nur Bytecode, kein IR.

### 10a. Self-Hosting

Ziel: Der Compiler wird von sich selbst kompiliert und läuft als natives Binary ohne JVM.
Unser eigener Code wird dabei mit dem Speichermodell schneller gemacht: `Arena` pro
Compiler-Phase und `Strict` in den heißen Pfaden.

**Designregel ab sofort:** Das Backend (`compiler/backend`, alles nach Fir2Ir) und die
Mini-Analysen sind **reines `common`-Kotlin**: kein `java.*`, kein `kotlin.jvm.*`, keine
JVM-Reflection. Der JVM-Kontakt sitzt in genau einem dünnen Modul `compiler/jvm-bridge`
(K2 aufrufen, Dateien lesen, clang starten). Damit kann sich das Backend schon früh
selbst kompilieren (Meilenstein „Backend self-hosted“).

Das Frontend ist der schwierige Teil. K2 hängt an Java-Code (intellij-core, der per JFlex
generierte Lexer, `java.util`). Zwei Wege:

1. **Eigenes Frontend in `common`-Kotlin**, mit K2 als **Orakel**: Für jede Datei der
   Testsuite und für den Compiler selbst muss unser Frontend dasselbe IR liefern wie K2
   (differentielles Testen). Das ist viel leichter als „Frontend ohne Referenz“ (Weg A
   aus Abschnitt 2), weil jede Abweichung automatisch auffällt.
2. **Java-Teile von K2 automatisch nach Kotlin konvertieren** (J2K aus IntelliJ, bzw.
   das Kommandozeilen-J2K aus dem Kotlin-Repo) und dann das echte K2-Frontend mit
   unserem Backend kompilieren. Schritte:
   1. **Inventur:** Welche Java-Klassen erreicht K2 im LightTree-Modus (ohne PSI-Editor-
      Features) tatsächlich? Aufrufgraph ab `FirSession`/LightTree-Parser messen. Ziel ist
      ein möglichst kleines intellij-core-Subset.
   2. **J2K + Nacharbeit:** Konvertierter Code kompiliert selten sofort. Nullability,
      statische Member, Generics-Wildcards und Raw Types brauchen Handarbeit. Der Lexer
      wird aus der `.flex`-Grammatik neu erzeugt (JFlex kann auch anderes ausgeben, sonst
      schreiben wir einen eigenen Generator), statt generierten Java-Code zu konvertieren.
   3. **`java.*`-Shim:** Was übrig bleibt (`java.util`-Collections, `ConcurrentHashMap`,
      `ThreadLocal`, `WeakReference`/`SoftReference`, `ServiceLoader`, Datei-IO, einfache
      Reflection), bekommt eine eigene `mini.jdk`-Bibliothek in Kotlin auf libuv. Nur das,
      was die Inventur verlangt, nicht das ganze JDK.
   4. **Speichermodell anpassen:** Ein Compiler ist der Idealfall für Arenas. Fast alles
      (FIR-Bäume, IR, Symboltabellen) lebt genau eine Kompilierung oder eine Phase lang
      und hat viele Zyklen (Parent-Pointer). Daher bekommt jede Phase eine `Arena` über
      `context(arena)`. Nur, was über Phasen hinweg lebt, wird explizit herauskopiert.
      Damit entfallen die Zyklen-Probleme des Refcountings, und der Speicher wird pro Phase
      in einem Rutsch freigegeben. `WeakReference`/`SoftReference`-Caches werden zu
      Phasen-Caches.
   5. **Pflegeproblem:** Jeder Kotlin-Versionssprung bringt neuen Java-Code mit. Daher läuft
      die Konvertierung als reproduzierbare Pipeline (Skript + Patch-Serie), nicht als
      Einmal-Fork.

   Bewertung: Weg 2 liefert 100 % K2-Semantik, kostet aber dauerhaft Pflege. Weg 1 ist mehr
   Arbeit am Anfang, danach aber unabhängig. Die Entscheidung fällt nach der Inventur
   (Schritt 1). Die liefert die harte Zahl: wie viele Zeilen Java.

Bootstrap-Kette: JVM-Compiler (K2 + unser Backend) → kompiliert unser Frontend und
Backend → `minic` Stage 1 (nativ) → Stage 2 → Stage 3 und Vergleich Stage 2 == Stage 3.

## 11. Arbeitsweise: Manager und Sonnet-Agenten

- **Manager** (dieser Agent): Architektur, Schnittstellen zwischen den Paketen,
  Arbeitspakete mit klaren Abnahmetests schreiben, Diffs reviewen, Tests selbst
  ausführen, mergen, Plan nachführen. Der Manager schreibt keinen Feature-Code.
- **Worker**: Agenten auf **Sonnet 5.5**, je Arbeitspaket einer, jeder im **eigenen
  Git-Worktree**. Pakete, die sich nicht gegenseitig blockieren, laufen parallel.
- Jedes Paket enthält: Ziel, betroffene Verzeichnisse, Schnittstelle (Signaturen/Header),
  Abnahmetests, „nicht anfassen“-Liste. Ein Paket ist fertig erst nach grüner lokaler
  Suite und Manager-Review.
- Parallelisierbar ab P1:
  1. Runtime (C: Objekt-Header, RC, Strings, Exceptions-Support)
  2. IR→C-Emitter
  3. Box-Test-Runner und CI
  4. stdlib-`actual`s
  5. `mini.memory`-API und Diagnose-Testfälle (vor der Analyse geschrieben: Test-first)
  6. cinterop/bindgen

## 12. Risiken und offene Fragen

- **Interne Compiler-API (Variante C):** IR- und Fir2Ir-APIs sind nicht stabil. Gegenmittel:
  Kotlin-Version pinnen, Upgrades als eigenes Arbeitspaket, Kontaktfläche klein halten.
- **Ownership-Inferenz vs. echter Kotlin-Code:** Strict wird nie für beliebigen Fremdcode
  funktionieren. Das ist Absicht, dafür gibt es `Managed`.
- **Exceptions im C-Backend** kosten einen Branch pro Aufruf. Akzeptiert bis zum
  LLVM-Backend.
- **UTF-16-Strings** sind speicherhungriger, aber nötig für 1:1-Semantik.
- **Lizenz:** Kotlin-stdlib und K/N-Runtime sind Apache-2.0. Übernommene Dateien behalten
  ihren Header, und eine NOTICE-Datei kommt dazu.
