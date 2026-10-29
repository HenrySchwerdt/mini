# Standardbibliothek aus Quelltext (v1-S)

Stand: Meilenstein v1-S. Zugehörig: `docs/plan-kotlin.md` (Abschnitte 6 und 10b), `compiler/README.md`,
Code in `compiler/jvm-bridge` (`FrontendDriver.kt`, `Stdlib.kt`, `Reachability.kt`, `Ir2MirIntrinsics.kt`),
Quellen in `stdlib/`, Tests in `stdlib-test/` und `compiler/jvm-bridge/src/test/kotlin/StdlibJvmTest.kt`.

## 1. Entscheidung

`minic build a.kt [b.kt ...]` schickt die Benutzerdateien **zusammen mit allen Quellen aus `stdlib/`**
durch den K2-Frontend-Lauf. Es gibt **keine** JVM-kotlin-stdlib-JAR und **kein JDK** im Spiel. Dadurch hat jede
Funktion der Standardbibliothek (auch `inline`-Funktionen wie `map`/`filter`) einen IR-Body, den `Ir2Mir` nach
MIR übersetzen kann. Vor der Übersetzung schneidet `Reachability` alles ab, was vom Benutzercode aus nicht
erreichbar ist, sodass das erzeugte C klein bleibt (`println("x")` → 66 Zeilen C, ein `StringBuilder`-Programm → ~220 Zeilen).

Konfiguration (`FrontendDriver.runFrontend`):

| Einstellung | Wert | Grund |
|---|---|---|
| Compiler | `K2JVMCompiler` wie bisher, Plugin `IrGenerationExtension` nach fir2ir | IR nach fir2ir, vor den JVM-Lowerings |
| `-no-stdlib -no-reflect` | an | keine kotlin-stdlib-JAR; `kotlin.*` kommt aus unseren Quellen |
| `-no-jdk` (`args.noJdk`) | an | kein `java.lang.*`; `kotlin.collections.List` bleibt ein normales Kotlin-Interface (keine JVM-Abbildung auf `java.util.List`) |
| `-Xallow-kotlin-package` | an | Quellen dürfen `package kotlin...` deklarieren |
| `-Xstdlib-compilation` | an | wie beim Bau der echten stdlib; für Frontend und fir2ir nicht zwingend (Versuch ohne das Flag: gleiches Ergebnis, nur die JVM-Lowerings stürzen ab, die wir ohnehin überspringen), bleibt als Absicherung gesetzt |
| Eingabe | Benutzerdateien + `stdlib/**/*.kt` | `--stdlib <dir>` überschreibt, `--no-stdlib` schaltet auf das alte Verhalten (JVM-JAR) zurück |
| nach dem Consumer | `module.files.clear()` | die JVM-Backend-Phase hätte sonst unsere Quellen „lowern“ wollen und stürzt ab (z.B. `kotlin/arrayOfNulls`) |

Pro Build kostet das einen K2-Lauf über rund 5700 Zeilen Stdlib-Quellcode (rund 3 s zusätzlich zum Start).
Eine Cache-Stufe (FIR/Klib der Stdlib) ist als Optimierung vorgesehen, aber nicht Teil von v1-S.

## 2. Spike: was funktioniert hat, was nicht

Alle Versuche mit kotlin-compiler-embeddable 2.4.21, Werkzeug: `K2JVMCompiler` auf der Kommandozeile, Skripte im Scratch-Verzeichnis.

* **`-no-stdlib` allein:** `Unresolved reference 'Boolean'/'Int'/'String'`, `missing stdlib class`. Die Builtins kommen auf der JVM
  aus `.kotlin_builtins`-Dateien der stdlib-JAR; ohne JAR gibt es sie nicht.
* **Eigene `package kotlin` ohne `-Xallow-kotlin-package`:** Fehler. Mit dem Flag und einer eigenen `open class Any` im Quelltext
  löst K2 `kotlin.Any` aus der Quelle auf. **Builtins aus Quelltext funktionieren.**
* **Rumpflose Deklarationen** (`fun plus(other: Int): Int` in `class Int`): gehen nur mit `@file:Suppress("NON_ABSTRACT_FUNCTION_WITH_NO_BODY",
  "MUST_BE_INITIALIZED_OR_BE_ABSTRACT", "NON_MEMBER_FUNCTION_NO_BODY")`; K2 beachtet Fehler-Suppression in Quelldateien. Dafür muss es
  `kotlin.Suppress` mit `@Target(.. FILE, EXPRESSION ..)` und `@Retention(SOURCE)` geben, also auch `kotlin.annotation.Target/Retention/AnnotationTarget`.
* **Original-Quellen der stdlib 2.4.21** (`kotlin-stdlib-2.4.21-sources.jar`, `commonMain`/`jvmMain`): enthalten die Builtins als `expect`/`actual`
  mit Annotationen (`@JvmBuiltin`, `@SuppressBytecodeGeneration`); als Einzel-Dateien kompiliert hängen sie an `-Xmulti-platform`.
  Wir haben stattdessen **eigene, nicht-expect Builtins** geschrieben (abgeleitet aus `jvmMain/*.kt`, Primitives per Generator).
* **`expect`/`actual` + `-Xcommon-sources`:** ohne `-Xmulti-platform` Fehler „can be used only in multiplatform projects“; mit dem Flag
  „'expect' and corresponding 'actual' are declared in the same module“. Es funktioniert nur mit echten Fragmenten
  (`-Xfragments=common,jvm -Xfragment-sources=… -Xfragment-refines=jvm:common`), und dann muss **jede** Quelldatei einem Fragment
  zugeordnet sein. Möglich, aber ohne Nutzen: wir haben genau eine Plattform. Nicht gewählt.
* **Metadata/Common-Pipeline** (`K2MetadataCompiler`): nicht weiter verfolgt, weil sie keinen fir2ir/IR-Lauf (und damit keine `IrGenerationExtension`)
  anbietet; die JVM-Pipeline ohne JDK liefert dasselbe IR mit der API, die wir schon benutzen.
* **Fir2Ir verlangt feste Klassenmenge:** Nach dem FIR-Lauf bricht fir2ir mit `IllegalStateException: Class kotlin/X is not found` ab, bis *alle*
  Klassen existieren, die `IrBuiltInsOverSymbolFinder`/`Fir2IrBuiltinSymbolsContainer` über `StandardClassIds` laden. Die Menge, die wir
  liefern müssen: `Any, Nothing, Unit, Boolean, Char, Byte/Short/Int/Long/Float/Double, Number, String, CharSequence, Comparable, Enum, Array,
  {Boolean,Byte,Char,Double,Float,Int,Long,Short}Array, {…}Iterator (kotlin.collections), Iterable, Collection, List, Set, Map, Map.Entry,
  Iterator, Throwable, Annotation, DeprecationLevel, KCallable/KClass/KFunction/KProperty*` (siehe `stdlib/kotlin/*.kt`).
* **`enum class`** gegen ein `Enum(name: String, ordinal: Int)`: K2 meldet `no value passed for parameter 'name'`. Lösung: `protected constructor()`
  (nur eine Deklaration; `name`/`ordinal` sind für Ir2Mir ohnehin Sonderfälle).
* **JVM-Abbildung (`List` ↔ `java.util.List`, `StringBuilder` = Typalias auf `java.lang.StringBuilder`):** entsteht ausschließlich durch das JDK
  im Klassenpfad. Mit `-no-jdk` und eigenen Quellen gibt es das nicht; eigene `kotlin.collections.List`, `kotlin.collections.ArrayList`,
  `kotlin.text.StringBuilder` sind gewöhnliche Klassen. Die kleine Konsequenz: `java.lang.Thread` (von `mini.memory.Spawn` unqualifiziert
  benutzt) existiert nicht und wird durch einen Platzhalter `stdlib/java/lang/Thread.kt` ersetzt.
* **Standard-Auflösung in JVM-Tests** (siehe 7): Typen wie `List` aus den Default-Imports gewinnen gegen Stern-Importe; nur explizite
  Einzel-Importe gewinnen. Betrifft nur den JVM-Test, nicht den Compiler.

Entscheidungsgrund in einem Satz: **Eigene Quellen, JVM-Pipeline ohne JDK und ohne stdlib-JAR, ein Plugin, kein zusätzlicher Compiler-Hack** —
die kleinste Änderung am bestehenden Aufbau, und `-no-jdk` verhindert genau die Abbildungen, die uns stören würden.

## 3. Aufbau von `stdlib/`

```
kotlin/                 Builtins (Builtin-markiert) und Kern: Any, Int, String, Enum, Array ..., Throwable/Exceptions,
                        Preconditions (require/check/error/TODO), Standard (let/run/apply/also/with/takeIf/repeat),
                        Tuples (Pair/Triple/to), Lazy, Comparator, Annotations, Platform (AutoCloseable/use)
kotlin/collections/     Collections.kt (Interfaces, aus der stdlib), AbstractCollections, ArrayList, HashMap/LinkedHashMap,
                        HashSet/LinkedHashSet, Iterables (map/filter/...), Builders (listOf/mapOf/..., Map-Funktionen)
kotlin/text/            Strings, StringBuilder, Char, StringNumberConversions
kotlin/ranges, math, comparisons, io, jvm, reflect, annotation, internal
mini/internal/          CSymbol.kt (Annotation), Intrinsics.kt (die gesamte native Schnittstelle)
mini/memory/            vom Speicher-Strang (unverändert)
java/lang/Thread.kt     Platzhalter für mini.memory.Spawn
```

### Builtins

Dateien mit `@file:kotlin.internal.Builtin` enthalten die Deklarationen, die der Compiler selbst kennt (`kotlin.Int`, `String`, `Array`, Ranges ...).
Sie haben keine Rümpfe. `minic` analysiert sie mit, **konvertiert sie aber nie nach C**: `Reachability.prune` entfernt diese Dateien vor `Ir2Mir`;
Aufrufe wie `Int.plus`, `String.length`, `println` erkennt `Ir2Mir` weiterhin am Namen (wie bisher mit der JAR-stdlib).
Alle anderen Quellen sind normaler Kotlin-Code und werden übersetzt, soweit erreichbar.

`kotlin.io.println/print` sind `external` ohne `@CSymbol`; `Ir2Mir` kennt sie als Sonderfälle (`mk_println_*`).

### Erreichbarkeit (`Reachability.kt`)

* Wurzeln: **alle** Deklarationen der Benutzerdateien (nicht nur `main`, damit Fehlermeldungen für unerreichbaren, nicht unterstützten Benutzercode
  gleich bleiben).
* Gefolgt wird: Aufrufe, Konstruktoraufrufe, Objekt-/Enum-Referenzen, Typoperatoren, Callable-Referenzen und jeder Typ, der in Deklarationen und
  Ausdrücken vorkommt. Eine erreichte Klasse behält alle Konstruktoren, Initialisierer, Properties und alle *dispatch-fähigen* Funktionen
  (offen, `override`, abstrakt, Interface-Mitglieder). **Finale, nicht überschreibende Methoden werden nur behalten, wenn sie aufgerufen werden.**
* Top-Level-Funktionen werden einzeln behalten.
* Aufrufe an Mitglieder von Builtin-Typen (z.B. `Iterator.hasNext` in `for`-Schleifen) zählen nicht.
* Die Funktion ist rein bridge-seitig (arbeitet auf Kotlin-IR). Eine Variante auf MIR-Ebene wäre möglich, hätte aber schon die
  Konvertierung aller Stdlib-Funktionen bezahlt.

## 4. Native Primitive („Intrinsics“) — Konvention

Alles Native läuft über **`mini.internal.CSymbol("mk_name")`** (`stdlib/mini/internal/CSymbol.kt`):

```kotlin
@CSymbol("mk_string_substring")
public external fun stringSubstring(s: String, start: Int, end: Int): String
```

Regeln (gilt für Top-Level-Funktionen, Memberfunktionen, Properties-Getter):

1. Der Aufruf wird zu einem **gewöhnlichen C-Aufruf** `mk_name(receiver?, arg1, ...)` (`MirExpr.RuntimeCall`, siehe `Ir2MirIntrinsics.kt`;
   Hook in `Ir2MirCalls.kt: call()`).
2. Typabbildung: `Int/Long/Boolean/Double/Char` → `MkInt/MkLong/MkBoolean/MkDouble/MkChar`; `String` → `const MkString *`; jeder andere
   Referenztyp (auch `Any`) → `MkObject *`. Nullable Referenzen sind dieselben Zeiger (`NULL`).
3. **Argumente sind geliehen**; ein **Referenz-Ergebnis ist besessen (+1)** und muss retained zurückgegeben werden (`mk_ref_array_get` retained).
4. Ergebnistyp `Nothing`: die C-Funktion ist `_Noreturn`; der Aufruf wird als Anweisung ausgegeben.
5. Die Deklaration ist `external` (ohne Rumpf) oder steht in einer Builtin-Datei. Top-Level-`external`/`@CSymbol`-Funktionen entfernt
   `Reachability` aus den Dateien; der Aufruf wird am Symbol des Callee erkannt.
6. Jedes Symbol steht in `runtime/include/minirt.h` im Block `/* ---- v1-S ---- */` (Implementierung `runtime/src/v1s.c`,
   Test `runtime/tests/test_v1s.c`). `StdlibJvmTest.intrinsicsAreInSync` prüft, dass jedes `@CSymbol` im Header steht und in der
   JVM-Implementierung (`stdlib-test/jvm/mini/internal/Intrinsics.kt`) dieselbe Signatur hat.

Die gesamte native Fläche von v1-S (außer Builtin-Members von `String` und der Konsole):

| Gruppe | Funktionen (Kotlin → C) |
|---|---|
| String | `String.get` → `mk_string_char_at`, `String.compareTo` → `mk_string_compare`, `String.length` → `mk_string_length`, `stringSubstring`, `stringParseDouble`, `stringIsDouble` |
| StringBuilder | `sbNew`, `sbAppendString`, `sbAppendChar`, `sbLength`, `sbCharAt`, `sbSetCharAt`, `sbSetLength`, `sbToString` (Handle ist ein `Any`) |
| Referenz-Arrays | `refArrayNew`, `refArrayGet`, `refArraySet`, `refArrayLength` (Speicher von `ArrayList`, `HashMap`) |
| Sonstiges | `typeName` (`MkTypeInfo.name`), `identityHashCode` |
| Fehler | `throwIllegalArgument`, `throwIllegalState`, `throwIndexOutOfBounds`, `throwNoSuchElement`, `throwNumberFormat`, `throwUnsupportedOperation`, `throwNotImplemented` |

Entwurfsentscheidung: Strings, `StringBuilder`, `ArrayList` usw. brauchen **keine** Compilerkenntnis über Arrays oder Generics:
Handles sind opake `Any`-Objekte der Runtime.

### Fehler (`throw`) — die einzige Naht

Das Backend kennt noch keine Exceptions. **Jede** `throw`-Stelle der Stdlib steht deshalb als Aufruf von `mini.internal.throwXxx(message)`.
Nativ bricht `mk_throw_*` mit `fatal: <Kind>: <Message>` auf stderr ab (wie `mk_panic`), im JVM-Test werfen die Funktionen echte Exceptions.
Sobald das Backend `throw`/`try` kann, wird *nur der Rumpf* in `mini/internal/Intrinsics.kt` zu `throw IllegalArgumentException(message)` etc.
Die Exception-Klassen selbst (`kotlin/Throwable.kt`) existieren bereits. Hinweis: `Throwable.toString()` liefert `kotlin.IllegalStateException: msg`
(JVM: `java.lang.IllegalStateException: msg`).

## 5. Umfang und Stand der nativen Übersetzung

Spalte „nativ“: Ein Test unter `compiler/testdata/stdlib_*.kt` baut und läuft mit identischer Ausgabe wie auf der JVM.

| Bereich | Quelle | nativ heute | Hindernis |
|---|---|---|---|
| `String`: substring/take/drop/startsWith/endsWith/indexOf/lastIndexOf/contains/trim*/padStart/padEnd/repeat/reversed/lowercase/uppercase/replace/substringBefore/After(Last)/removePrefix/Suffix/isBlank/isEmpty/first/last/compareTo | `kotlin/text/Strings.kt` | **ja** (`stdlib_strings`) | |
| `String.split/lines/toList` | Strings.kt | nein | brauchen `ArrayList<String>` (Generics) |
| `String.count/forEach/filter/any/all/trim{}` | Strings.kt | nein | Lambdas |
| `StringBuilder` (append/insert/delete/reverse/setLength/get/set/substring/appendLine/clear/…) | `text/StringBuilder.kt` | **ja** (`stdlib_stringbuilder`) | |
| `buildString { }` | StringBuilder.kt | nein | Lambdas |
| `toInt/toIntOrNull/toLong/toLongOrNull/toDouble/toDoubleOrNull/toBoolean`, `Int/Long.toString(radix)` | `text/StringNumberConversions.kt` | **ja** (`stdlib_numbers`, `stdlib_panic`) | |
| `Char.isDigit/isLetter/isWhitespace/isUpperCase/uppercaseChar/digitToInt/…` | `text/Char.kt` | **ja** | nur ASCII/Latin-1 |
| `maxOf/minOf` (Int/Long/Double/Char), `coerceIn/AtLeast/AtMost`, `abs` | `comparisons`, `ranges/Coerce.kt`, `math/Math.kt` | **ja** | |
| `Pair/Triple/to`, `Comparator`, `compareBy`, `lazy`, `let/run/also/apply/with/takeIf/repeat`, `require/check/requireNotNull/error/TODO` | `kotlin/*.kt` | nein | Generics, Lambdas |
| `Iterable/Iterator/Collection/List/MutableList/Set/MutableSet/Map/MutableMap` (+ `Abstract*`) | `kotlin/collections/*` | nein | Generics (Interfaces/Klassen) |
| `ArrayList`, `HashMap`, `LinkedHashMap`, `HashSet`, `LinkedHashSet` | `ArrayList.kt`, `HashMap.kt`, `HashSet.kt` | nein | Generics |
| `listOf/mutableListOf/arrayListOf/emptyList/setOf/mapOf/hashMapOf/...`, `buildList`, `List(n){}` | `Builders.kt` | nein | Generics, `vararg`, Lambdas |
| `map/filter/forEach/joinToString/associate*/groupBy/first/last/any/all/count/sum/sumOf/min/max*/sortedBy/sortedWith/sorted/reversed/take/drop/zip/fold/reduce/distinct/partition/flatMap/…` | `Iterables.kt` | nein | Generics, Lambdas, Erweiterungsfunktionen auf Interfaces |

Alles in der Tabelle mit „nein“ ist als Kotlin geschrieben, durchläuft K2 und wird **auf der JVM gegen die Tests geprüft** (siehe 7).
Sobald Generics/Lambdas/Exceptions im Backend vorhanden sind, ist hier idealerweise nichts mehr zu portieren.

### Bekannte Abweichungen von Kotlin

* `StringBuilder` ist (noch) keine `CharSequence`/`Appendable`: `CharSequence` ist ein Builtin-Interface und für das Backend kein
  übersetzbarer Typ. Entsprechend sind `isEmpty/isNotEmpty/isBlank/isNotBlank/indices/lastIndex` auf `String` statt `CharSequence` deklariert.
* Zeichenklassifikation und Groß-/Kleinschreibung nur ASCII und Latin-1. Kein Regex (`split(Regex)`, `replace(Regex)`), kein `format`.
* `HashMap`/`HashSet` replizieren die Iterationsreihenfolge von `java.util.HashMap` (Tabellengröße, Spreizung, Resize, `mapCapacity` wie die JVM-stdlib),
  damit Programme dieselbe Ausgabe haben; keine Tree-Bins.
* Schreibgeschützte Listen/Mengen/Maps sind die veränderlichen Implementierungen hinter dem Interface (wie auf Kotlin/Native); `emptyList()` ist ein Singleton.
* `Double.toString`, `Float`: Float-Operationen fehlen im Backend; die Quellen vermeiden `Float`, wo es geht (nur `mapCapacity` nutzt `Double`).
* Alles Einthread (`lazy` ist `UnsafeLazy`), `java.lang.Thread` ist ein Platzhalter, der beim Aufruf mit Fehler abbricht.
* `Throwable` hat kein Stacktrace-API.
* `vararg`-Funktionen (`listOf(a, b)`) benutzen `Array<T>`; ein natives `Array` gibt es noch nicht (Backend-Aufgabe, siehe Risiken).

## 6. Erweiterungs-Hinweise für das Backend

* Erweiterungsfunktionen: `Ir2Mir` lehnte sie ab; v1-S hat die Prüfung für `ExtensionReceiver` entfernt (zwei Zeilen in `Ir2MirClasses.kt`/`Ir2MirExpressions.kt`),
  der Rest funktioniert unverändert (der Receiver ist einfach der erste Parameter).
* Überladungen, die sich nur durch Typargumente unterscheiden (`Iterable<Int>.sum()` / `Iterable<Long>.sum()`, `sumOf` mit Lambda-Rückgabetyp),
  tragen `@JvmName`. Die Namens-Mangelung des Backends muss das berücksichtigen (z.B. `@JvmName` oder Typargumente in den Namen aufnehmen),
  sonst kollidieren die C-Namen.
* `String`-Member mit `@CSymbol` werden vom Hook in `call()` abgefangen, bevor die alten Sonderfälle (`<get-length>`, `hashCode` ...) greifen.

## 7. Tests

* `StdlibJvmTest.stdlibGivesIrForEverything`: alle Stdlib-Quellen laufen mit der Compiler-Konfiguration aus Abschnitt 1 durch K2; jede Funktion
  (nicht abstrakt, nicht `external`, nicht Builtin) hat einen IR-Body.
* `StdlibJvmTest.stdlibUnitTestsOnJvm`: die Stdlib-Quellen (ohne Builtins, `package kotlin.x` → `mkt.x`, `mini.internal` durch
  `stdlib-test/jvm/mini/internal/Intrinsics.kt` ersetzt, alle Klassen explizit importiert) werden für die JVM kompiliert und mit den
  Unit-Tests aus `stdlib-test/tests/` ausgeführt (rund 40 Tests: Strings, StringBuilder, Listen, Mengen, Maps, HashMap-Reihenfolge,
  Sortierung, Aggregate, Scope-Funktionen, Preconditions). `UsesMiniStdlib` stellt sicher, dass wirklich *unsere* Klassen getestet werden.
* `StdlibJvmTest.testsAgreeWithRealStdlib`: dieselben Tests laufen gegen die echte kotlin-stdlib — die Erwartungswerte sind echtes Kotlin-Verhalten.
* `StdlibJvmTest.intrinsicsAreInSync`: `@CSymbol` ↔ `minirt.h` ↔ JVM-Implementierung.
* `EndToEndTest` mit `compiler/testdata/stdlib_{strings,stringbuilder,numbers,panic}.kt` (`.out` stammt von der echten JVM).
* `runtime/tests/test_v1s.c` (ASan/UBSan) für die C-Funktionen.

## 8. Risiken und offene Punkte

1. **Generics/Lambdas im Backend** sind Voraussetzung für Collections und `map`/`filter`. Die Quellen sind dafür geschrieben, aber nativ ungetestet;
   Backend-Lücken werden erst dort sichtbar (Namens-Mangelung bei Überladungen, `Array<T>` für `vararg`, `Map.Entry` als Interface in Interface,
   `object`-Ausdrücke in `HashMap` (anonyme Iteratoren), `private class` in Dateien).
2. **Build-Zeit**: jeder `minic build` analysiert die gesamte Stdlib (rund 3 s). EndToEndTest ist dadurch auf ~110 s gewachsen. Gegenmaßnahme: Stdlib-IR/FIR einmal
   vorübersetzen oder Teilmengen nach Import laden.
3. **Statische Reichweiten** sind konservativ (Klassen behalten alle dispatchfähigen Methoden): ein erreichtes `ArrayList` zieht z.B. `AbstractMutableList` mit
   `subList`, `equals`, `hashCode`. Eine feinere Analyse braucht Typflussinformationen (RTA).
4. **Speichermodell**: Referenzzyklen (z.B. `LinkedHashMap`-Knoten `before`/`after`) leaken unter RC. `HashNode.before/after` bilden Zyklen, das Backend
   braucht schwache Referenzen oder einen Zyklensammler, bevor `LinkedHashMap` leak-frei ist.
5. **Interface-Typen aus Builtin-Dateien** (`CharSequence`, `Comparable`, `Iterator` ...) sind für `Ir2Mir` unbekannt; solange `CharSequence` Builtin ist, gibt es
   keine String-/StringBuilder-Gleichbehandlung.
6. **Unicode**: nur ASCII/Latin-1; das Verhalten bei anderen Zeichen weicht von der JVM ab.
7. **`Throwable.toString`/`typeName`**: der Paketname ist `kotlin.…` statt `java.lang.…`.
8. **Lizenz**: abgeleitete Dateien tragen den Apache-2.0-Kopf und `stdlib/NOTICE`.
