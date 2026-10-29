# Kotlin/Mini: Das Speichermodell (Spezifikation P1)

Status: **normativ für P1, Test-first.** Die Analyse existiert noch nicht. Dieses Dokument,
die API `stdlib/mini/memory/*.kt` und der Korpus `compiler/testdata/memory/` legen
fest, was der Compiler später akzeptieren und ablehnen muss. Der Entwurf steht in
`docs/plan-kotlin.md` (Abschnitt 4). Wo dieses Dokument davon abweicht, gilt dieses Dokument;
die Abweichungen stehen in Abschnitt 10.

Schlüsselwörter MUSS, DARF NICHT, SOLL sind im Sinn von RFC 2119 gemeint.

---

## 1. Grundsätze

1. **Jedes Mini-Programm ist ein gültiges Kotlin-Programm.** Das Speichermodell besteht nur
   aus Annotationen, Interfaces, gewöhnlichen Funktionen und Context-Parametern
   (`context(alloc: Arena)`, Kotlin 2.2+, in 2.4.21 ohne Zusatzflag). Der gesamte Korpus
   wird mit dem echten `kotlinc` (K2) übersetzt, siehe Abschnitt 11. Auf der JVM sind
   `free`, Allocatoren und Annotationen wirkungslos beziehungsweise naiv (`free` ruft `close()`
   auf, wenn das Objekt `AutoCloseable` ist). Die Programme verhalten sich dort genauso wie
   unter Mini, solange kein Fehler vorliegt.
2. **Zwei Modi, pro Datei oder Klasse.** `Managed` (Default) = Refcounting, keine Pflichten.
   `Strict` = jede Allokation hat einen bewiesenen Besitzer, sonst Compile-Fehler.
3. **Die Analyse ist unabhängig von der Platzierung.** Ob ein Objekt am Ende auf dem Stack,
   in einer Arena oder auf dem Heap liegt, ändert nie, ob ein Programm akzeptiert wird.
   Die Escape-Analyse (Stack-Verschiebung) ist eine reine Optimierung (Abschnitt 2.1).
   Damit sind Diagnosen stabil, auch wenn sich Optimierungen ändern.
4. **Eine Schreibweise pro Konzept.** Wo Kotlin mehrere Wege bietet, nennt dieses Dokument
   den Hauptweg; Nebenwege sind ausdrücklich als gleichwertig markiert.
5. **Lokal prüfbar.** Jede Funktion wird allein anhand ihrer Signatur und der Signaturen der
   Aufgerufenen geprüft. Es gibt keine globale Inferenz über Funktionsgrenzen (nur das
   Standard-Ownership der Signatur, Abschnitt 4.2).

---

## 2. Allokation

### 2.1 Jeder Konstruktoraufruf ist eine Allokationsstelle

`Foo(...)` für eine Klasse `Foo` ist eine Allokationsstelle, ebenso Aufrufe, die ein solches
Objekt als Rückgabewert erzeugen (Factory-Funktion mit `@Owned`-Rückgabe, siehe 4.2).
Arrays und lokale Klassen/Lambdas verhalten sich wie Klassen.

Zwei Dinge werden strikt getrennt:

* **Nomineller Allocator** (statisch, bestimmt die Pflichten): Regeln 2.2.
* **Tatsächliche Platzierung** (Codegen): Ein Objekt, das nicht entkommt, liegt auf dem
  **Stack**, unabhängig vom nominellen Allocator. Erst wenn es entkommt, wird tatsächlich
  über den nominellen Allocator alloziert. Das meldet die Info M0201 (nicht im `.diag`).

**Entkommen** heißt: der Wert fließt in eine Rückgabe, in einen `@Owned`-Parameter, in ein
Feld oder eine globale Variable, in die Captures eines entkommenden Lambdas (5.5) oder über
eine Thread-Grenze (5.9). Das Übergeben an einen geliehenen Parameter, Methodenaufrufe und
Feldzugriffe lassen einen Wert nicht entkommen.

```kotlin
class Vec(val x: Int, val y: Int)
fun len2(v: Vec): Int = v.x * v.x + v.y * v.y

fun main() {
    val v = Vec(3, 4)        // entkommt nicht: Stack, kein Allocator nötig, kein free
    println(len2(v))
}
```

### 2.2 Wie eine Allokationsstelle ihren Allocator wählt

Reihenfolge, die erste zutreffende Regel gilt:

1. **Context-Allocator.** Im Scope ist mindestens ein Context-Parameter eines
   `Allocator`-Typs sichtbar (`context(alloc: Arena)` an der Funktion oder
   `context(arena) { ... }` als Block). Der lexikalisch **innerste** gewinnt.
   Sind auf derselben Ebene mehrere Allocator-Parameter sichtbar
   (`context(a: Arena, h: Heap)`), ist die Wahl **mehrdeutig**: M0114. Lösung:
   verschachtelte `context(x) { ... }`-Blöcke (der innere gewinnt).
2. **Klassen-Annotation** `@AllocatedBy(X::class)` an der Klasse. Sie ist eine
   **Einschränkung**: gilt sie, kommen in Regel 1 nur Context-Allocatoren in Frage, die
   Instanzen von `X` sind. Gibt es keinen, benutzt der Compiler die globale Standardinstanz
   von `X`, falls es eine gibt. Das ist nur bei `Heap` und `Rc` der Fall (zustandslos);
   `Arena` und `Pool` brauchen immer einen Context.
3. **Modul-Default.** In **Managed**-Dateien ist der Default `Rc`. In **Strict**-Dateien
   gibt es keinen Default.
4. Kein Allocator gefunden:
   * Der Wert entkommt nicht: er liegt auf dem Stack, ohne Pflichten (2.1).
   * Der Wert entkommt: Fehler **M0204**.

Abgeleitet daraus, welche Klasse welches Verhalten hat (bestimmt durch die Datei oder
Klasse, in der die **Klasse deklariert** ist, nicht den Aufrufer):

| Klasse deklariert in | nomineller Allocator ohne Context |
|---|---|
| `Managed`-Datei/Klasse | `Rc` (keine Pflichten) |
| `Strict`-Datei/Klasse | keiner: Stack oder M0204 |

Eine Strict-Klasse in Managed-Code zu konstruieren, ist erlaubt und benutzt Regel 1 und 2,
sonst `Rc` (siehe 6.2 für die Grenze).

```kotlin
class Node(val value: Int, var next: Node?)

context(alloc: Arena)
fun buildList(n: Int): Node? {
    var head: Node? = null
    for (i in n downTo 1) head = Node(i, head)    // Regel 1: alloc (Arena)
    return head                                   // Region des Aufrufers, siehe 2.4
}
```

```kotlin
fun make(): @Owned Box = Box(1)     // Strict, entkommt, kein Allocator: M0204
```

Ein nomineller Allocator mit dem Trait `Deallocating` erzeugt die Pflicht „genau einmal
freigeben oder weiterreichen“ (Abschnitt 3 und 5). Ein `Scoped`-Allocator erzeugt die Pflicht
„darf den Scope nicht verlassen“ (2.4). Ein `Managed`-Allocator (`Rc`) erzeugt keine Pflicht.

### 2.3 Die mitgelieferten Allocatoren (`mini.memory`)

Alle als Kotlin-Quelltext in `stdlib/mini/memory/`.

| Typ | Traits | Ressource? | Bemerkung |
|---|---|---|---|
| `Heap` | `Deallocating`, `ThreadSafe` | nein | malloc/free. Zustandslos: `Heap()` zu erzeugen kostet nichts und erzeugt keine Pflicht. |
| `Arena` | `Scoped` (also `AutoCloseable`) | **ja** | Region. Muss geschlossen werden (`use {}` oder `close()`): sonst M0102. Nicht `ThreadSafe`. |
| `Pool<T>` | `Deallocating`, `AutoCloseable` | **ja** | Feste Kapazität, Objekte einzeln freigegeben. Muss geschlossen werden. Nicht `ThreadSafe`. |
| `Rc` | `Managed`, `ThreadSafe` | nein | Refcounting, Default von Managed-Code. |
| `DebugAllocator(inner)` | `Deallocating`, `ThreadSafe` | nein | Umhüllt einen `Deallocating`-Allocator und meldet unter Mini beim Programmende Lecks mit Allokationsstelle. |

Interfaces: `Allocator { allocate(size, align): NativePtr? }`,
`Deallocating : Allocator { free(ptr); free(obj) }`, `Scoped : Allocator, AutoCloseable`,
`Managed : Allocator`, `ThreadSafe` (Marker).

**Regel: Allocator-Instanzen sind selbst keine verfolgten Werte**, mit Ausnahme der
Ressourcen der Tabelle (`Arena`, `Pool`): diese sind `AutoCloseable`-Besitzer wie jedes
andere Strict-Objekt und folgen 3.2. Sie leben im Normalfall auf dem Stack.

### 2.4 Arena und `Scoped`

Alle Objekte einer Arena sterben gemeinsam, wenn die Arena geschlossen wird. Für sie gibt
es **keine Free-Pflicht**, aber eine **Lebensdauerbeschränkung**:

> Ein Wert, der in der Arena `a` alloziert wurde (Regel 2.2/1 mit `a`), darf den Scope von
> `a` nicht verlassen. Der Scope von `a` ist bei `a = Arena().use { a -> ... }` der
> Lambda-Körper, bei `val a = Arena()` der Bereich bis zu `a.close()`.

Verboten (M0105) sind insbesondere:

* `return` aus dem Scope, Wert des `use`-Blocks, Wert eines `context(a) { ... }`-Blocks,
  der außerhalb des Scopes von `a` benutzt wird,
* Zuweisung an ein Feld eines Objekts, das länger lebt, oder an eine globale Variable,
* Capture in einem Lambda, das den Scope verlässt (5.5).

Erlaubt ist das Weiterreichen **nach innen** (Aufrufargumente an geliehene Parameter) und
das Herauskopieren von Werten ohne Allokation (`Int`, `Boolean`, ...). Eine Funktion mit
`context(a: Arena)` darf Arena-Objekte an ihren Aufrufer zurückgeben: Die Region ist die
des Aufrufers, und dort wird der Scope-Test fortgesetzt.

```kotlin
fun main() {
    val total = Arena().use { arena ->
        context(arena) {
            val list = buildList(1000)        // Arena-Objekte
            sum(list)                         // Int verlässt den Scope: ok
        }
    }                                         // Arena freigegeben
    println(total)
}
```

**Arena-Objekte besitzen keine fremden Referenzen (M0115).** Eine Arena führt beim
`close()`/Reset **nie** `dispose` (weder `close()` noch Feld-Glue) der Objekte aus; sie
führt keine Einzelbuchführung (so arbeitet die Runtime, siehe `runtime/README.md`).
Deshalb darf ein Objekt, das in einer Arena liegt, in seinen **besitzenden** Feldern
(auch Property-Parametern des Konstruktors) nur enthalten: Primitive, konstante String-Literale
(statisch, ohne Refcount), und Objekte **derselben Arena**. Der Compiler meldet M0115, wenn ein
Wert eines anderen Allocators in ein besitzendes Feld eines Arena-Objekts fließt (Konstruktor-
argument oder Zuweisung): refcounted `String`, Managed-Objekte (`Rc`), Heap- und Pool-Objekte.
Nicht besitzende Felder (`@Borrowed`) sind erlaubt, z. B. `@Borrowed val name: String`;
dann ist der Aufrufer für die Lebensdauer verantwortlich (Lücke wie bei 5.3).

Ein einzelnes Arena-Objekt kann nicht freigegeben und nicht an einen `@Owned`-Parameter
übergeben werden (M0106). Hat ein Arena-Objekt `close()` (Ressourcen, die ein Ende brauchen),
darf `close()` direkt aufgerufen werden (es gibt den Speicher nicht frei, der Speicher
gehört der Arena); `free()` bleibt verboten.

### 2.5 `@AllocatedBy`

```kotlin
@AllocatedBy(Pool::class)
class Item(val id: Int)

Pool<Item>(64).use { pool ->
    context(pool) {
        val i = Item(1)          // Pool<Item> aus dem Context (Regel 2)
        i.free()
    }
}
```

---

## 3. Freigabe

### 3.1 Hauptschreibweise: `x.free()`

Es gibt **eine** Hauptschreibweise für das Beenden des Besitzes: die Erweiterungsfunktion

```kotlin
fun Any?.free()          // package mini.memory
```

Begründung:

* Sie ist gültiges Kotlin und kompiliert mit dem echten `kotlinc` ohne Sonderstatus.
* Sie braucht den Allocator nicht zu nennen. Das Objekt weiß zur Laufzeit, woher es kommt;
  der Compiler weiß es statisch aus dem Datenfluss und prüft es (M0106, M0107).
  Das ist wichtig für `@Owned`-Parameter, deren Allocator der Aufrufer wählt.
* Sie ist auf `Any?` definiert. `x.free()` und `x?.free()` sind für `T?` beide korrekt
  (5.10), und `free` auf `null` ist ein No-op.
* Sie liest sich in Ketten (`make().free()`) und im `finally` kurz.

Bedeutung: `x.free()` (1) ruft `close()` auf, wenn `x` `AutoCloseable` ist; (2) gibt dann alle
**Felder frei, die `x` besitzt** (rekursiv, 5.3); (3) gibt den Speicher von `x` an den
Allocator zurück, der ihn geliefert hat (bei Stack-Objekten No-op).

Gleichwertige Nebenwege (keine zusätzliche Pflicht, dieselbe Analyse):

* `alloc.free(x)` (3.3), wenn man den Allocator ausdrücklich nennen will.
* `x.close()` auf einem `AutoCloseable`-Besitzer und `x.use { ... }` (3.2).
* Das Ende des Scopes einer Arena (3.4).

### 3.2 `use {}` und `close()`

Für `AutoCloseable`-Klassen ist `close()` der Destruktor. `x.use { ... }` ruft `close()` am
Ende des Blocks auf **jedem** Pfad auf (auch bei `throw`, `return`, `break`) und beendet
damit den Besitz von `x`. Im Block ist `it` (oder der Lambda-Parameter) **geliehen**. Der
Aufruf `x.close()` auf einem Besitzer zählt wie `x.free()`: ein zweiter Aufruf von `close()`
oder `free()` ist M0103.

`use` ist `inline`. Die Analyse sieht in den Block hinein (5.5).

```kotlin
class Res(val name: String) : AutoCloseable {
    override fun close() { println("close " + name) }
}

context(h: Heap)
fun run() {
    Res("a").use { println("using " + it.name) }   // close am Ende
    val b = Res("b")
    b.free()                                       // ruft close() auf
}
```

Nach `close()`/`free()` laufen die generierten Felderfreigaben (5.3). Ein
`close()`-Körper darf **keine** besessenen Felder selbst freigeben: dafür sorgt der Compiler.

### 3.3 `alloc.free(x)`

`Deallocating.free(obj: Any?)` ist `x.free()` mit ausdrücklich genanntem Allocator. Kennt
der Compiler den Allocator von `x`, wird verglichen: `pool.free(x)` für ein Heap-Objekt ist
**M0108**. Ist der Allocator nicht statisch bekannt (z. B. `@Owned`-Parameter), wird die
Prüfung in `DebugAllocator`/Debug-Builds zur Laufzeit gemacht. Der Aufruf von
`scopedAlloc.free(x)` compiliert nicht (Kotlin-Fehler), denn `Scoped` hat kein `free`.

### 3.4 Arena-Ende

`Arena.close()` (auch durch `use`) gibt alle Arena-Objekte frei, **ohne** `close()` ihrer
Klassen und ohne Feld-Glue aufzurufen (Entscheidung des Projekteigners, passend zur Runtime).
Deshalb M0115 (2.4). Ressourcen in Arenen müssen einzeln mit `close()` beendet werden (2.4).

---

## 4. Ownership: Grundbegriffe

### 4.1 Was verfolgt wird

Die Analyse verfolgt **Referenzen auf Instanzen von Klassen, die in einer Strict-Datei
(oder Strict-Klasse) deklariert sind**, sowie `AutoCloseable`-Allocatoren (`Arena`, `Pool`).
Alles andere ist **nicht verfolgt** und unterliegt keinen Pflichten: Primitive, `String`,
Objekte von Managed-Klassen (Refcount, „Lebensdauer extern“), Funktionstypen ohne
verfolgte Captures, `Heap`/`Rc`/`DebugAllocator` (Allocatoren ohne Ressource).

Eine verfolgte Referenz ist in einem Moment in genau einem **Zustand** (5.2). Verfolgte
Referenzen haben einen **Besitzer**: eine lokale Variable, ein Parameter, ein Feld.

### 4.2 Owned und Borrowed: Defaults

| Position | Default | Annotation |
|---|---|---|
| Parameter einer Funktion | **geliehen** | `p: @Owned T` übernimmt den Besitz |
| Rückgabe einer Funktion | **besitzend** | `: @Borrowed T` gibt eine Leihgabe zurück (4.3) |
| Property-Parameter des Konstruktors (`class C(var x: T)`) | **besitzend** (= Feld) | `@Borrowed` |
| Feld | **besitzend** | `@Borrowed var f: T?` (ungeprüfte Rückverweise, 5.3) |
| lokale Variable | **besitzend**, wenn mit einem frischen Wert (`Foo(...)`, Aufruf mit besitzender Rückgabe) oder `a.move()` initialisiert; **Alias (Leihgabe)**, wenn mit einem bestehenden Besitzer initialisiert/zugewiesen (`val b = a`) | `a.move()` oder `val b: @Owned T = a` macht `b` zum Besitzer; `@Borrowed val b = a` ist redundant (erlaubt, ohne Wirkung) |
| `this` und Extension-Receiver | **geliehen** | – |
| Parameter von Managed-Funktionen | **extern verwaltet** (6.2) | `@Borrowed` |

Es gibt in P1 **keine Inferenz über Funktionsgrenzen**: Eine Funktion, die ihren Parameter
speichert, freigibt oder als besessen zurückgibt, braucht `@Owned`, sonst M0107/M0109. Die
Hilfe der Diagnose nennt die Annotation.

Als Annotation steht in Typposition die Hauptform (`b: @Owned Buffer`, `: @Owned Buffer?`,
`List<@Owned T>` ist P3, siehe 5.4). Deklarationsposition (`@Owned fun f()`, `@Owned val x`)
ist gleichwertig.

### 4.3 Geliehene Ergebnisse

Eine Funktion mit `: @Borrowed T` liefert eine Leihgabe. Die Leihgabe ist gültig, **bis
einer der Besitzer, aus denen sie hervorging (Receiver und alle geliehenen Argumente), frei-
gegeben, verschoben oder überschrieben wird.** Jede Verwendung danach ist M0101. Eine
Leihgabe darf weder in einem besitzenden Feld gespeichert noch als besessen zurückgegeben
werden: M0109.

```kotlin
fun first(h: Holder): @Borrowed Box? = h.item

val holder = Holder(Box(1))
@Borrowed val peek = first(holder)
holder.free()
println(peek?.v)               // M0101
```

### 4.4 Gültigkeit der Annotationen

`@Owned`/`@Borrowed` sind nur auf Typen verfolgter Klassen (4.1, auch nullable) sinnvoll.
`@Owned` auf Primitiven, `String` oder Managed-Klassen ist ein Fehler: **M0111**.
`@Borrowed` ist zusätzlich auf Feldern und Parametern beliebiger Referenztypen (auch `String`,
Managed) erlaubt und bedeutet dort „nicht besitzend“ (nötig für Felder von Arena-Objekten, 2.4). Beide
zugleich an derselben Stelle: ebenfalls M0111. `@Borrowed` auf einer `@Owned`-Rückgabe
widerspricht sich: M0111.

---

## 5. Flussregeln (Strict)

### 5.1 Moves und Borrows

**Einfache Zuweisung leiht.** `val b = a`, `var b = a` und `b = a` mit einem bestehenden
Besitzer `a` (lokale Variable, besessener Parameter, besessenes Feld) auf der rechten Seite
machen `b` zu einem **Alias** (Leihgabe): `a` bleibt Besitzer, `b` darf lesen, aber nicht
freigeben oder besitzen. Ein Alias lebt höchstens so lange wie sein Besitzer (siehe 4.3).

Ein **Move** überträgt den Besitz. Er geschieht **implizit** genau an diesen Stellen:

1. `return a` aus einer Funktion mit besitzender Rückgabe,
2. Argument an einen `@Owned`-Parameter (auch Property-Parameter des Konstruktors),
3. Zuweisung an ein besitzendes **Feld** (`s.item = a`),
4. Capture in einem Thread-Lambda (5.9); ferner `a.use { }` / `a.close()` (3.2),

und **ausdrücklich** durch die Hauptschreibweise `a.move()`:

```kotlin
fun <T> T.move(): T = this      // package mini.memory, vom Compiler erkannt
```

`val b = a.move()` verschiebt `a` in die neue lokale Variable `b`; `b` ist Besitzer.
Gleichwertig ist die Typannotation `val b: @Owned Box = a`. Begründung für `move()` als
Hauptform: Es ist ein gewöhnlicher Ausdruck und funktioniert an jeder Stelle (Argument,
`return`, rechte Seite, Teil-Move aus Feldern: `s.item.move()`), braucht keine Deklaration und
ist beim Lesen auffällig. Frische Werte (`val b = Box(1)`, `val b = make()`) sind ohnehin
Besitzer; eine Variable, die einen Besitzer bekommt, wird mit `move()` neu gesetzt
(`b = a.move()`).

**Regeln für Aliase**

* Verwendung eines Alias, nachdem sein Besitzer freigegeben wurde: **M0101**; nachdem er
  verschoben wurde: **M0104**; nachdem der Scope des Besitzers endete: ebenso (Besitzer
  `Freed`/`Moved`, je nachdem, wie er den Scope verließ). Gemeldet wird an der Verwendung des Alias.
* `alias.free()`, `alias.close()`, `alias.use { }`, Übergabe an einen `@Owned`-Parameter,
  Zuweisung an ein besitzendes Feld und `return alias` aus einer besitzenden Funktion:
  **M0107** (Freigabe) beziehungsweise **M0109** (speichern/zurückgeben). Das ändert den
  Zustand des Besitzers nicht.
* Ein Alias kann neu zugewiesen werden (`var cur = n; cur = cur.next`): er leiht dann vom
  neuen Besitzer.

**Nutzung nach Move** (M0104): Jeder Zugriff auf einen verschobenen Slot (außer einer
neuen Zuweisung, die ihn wieder initialisiert). Ebenso ein Move in einer Schleife, der im
nächsten Durchlauf wieder trifft (5.7).

```kotlin
val a = Box(1)
val b = a               // Alias: a bleibt Besitzer
println(b.v)            // ok
a.free()
println(b.v)            // M0101: b leiht von a, a ist freigegeben

val c = Box(2)
val d = c.move()        // Besitz wandert nach d
println(c.v)            // M0104
d.free()

var list: Node? = null
for (i in 1..5) list = push(list, i)   // ok: list wird an @Owned-Parameter verschoben und neu gesetzt
```

Herausnehmen aus einem Feld ist ein **Teil-Move** und braucht `move()`: siehe 5.3.

### 5.2 Zustände (Modell der Analyse)

Für jeden besitzenden Slot gilt an jedem Programmpunkt ein Zustand:

| Zustand | Bedeutung |
|---|---|
| `Owned` | gültig, Pflicht offen |
| `Null` | bei `T?` bekannt `null`, keine Pflicht |
| `Freed` | freigegeben (3) |
| `Moved` | verschoben (5.1) |
| `Maybe(a, b)` | Vereinigung zweier Pfade; Verbund an Zusammenführungen |

Regeln:

* **Verwendung** (Lesen, Methodenaufruf, Borrow) in `Freed`/`Moved`/`Maybe` mit solchem
  Anteil: M0101 bzw. M0104. Die Meldung sagt „may have been freed“, wenn nur ein Pfad
  betroffen ist.
* **Freigabe** (`free`, `close`, `use`-Ende) in `Freed`/`Maybe` mit `Freed`-Anteil: M0103.
  In `Moved`: M0107 (nicht mehr besessen).
* **Scope-Ende, Return, Break, Continue, Throw-Kante** eines Slots in `Owned` oder in
  `Maybe` mit `Owned`-Anteil: M0102 mit Pfadart (7.2).
* **Überschreiben** eines Slots in `Owned`: M0102, Pfadart `overwritten`.
* **Kaskadenunterdrückung:** Nach einer Meldung zu einem Slot setzt die Analyse ihn auf
  den Zustand, der den nächsten Schritt konsistent macht (nach M0101/M0104: `Owned`; nach
  M0103: `Freed`; nach M0108: `Freed`). So erzeugt eine Ursache genau eine Meldung.
* Zwei Pfade mit `Null` und `Owned` ergeben einen Zustand, bei dem `free()` erlaubt ist und
  `x == null` die Pflicht beseitigt (5.10).

### 5.3 Felder

* Felder verfolgter Typen sind **besitzend**. Ein Objekt besitzt seine Felder; `x.free()`
  gibt sie rekursiv frei (Feld-Glue), nachdem `close()` gelaufen ist. Ein Objekt, das
  Teil eines besessenen Baumes ist, wird nie einzeln freigegeben, sondern mit der Wurzel.
* Property-Parameter des Konstruktors besitzen (= Feld): `Node(i, head)` **verschiebt** `head`.
* **Überschreiben** eines besitzenden Feldes mit unbekanntem altem Inhalt: M0102 (Pfadart
  `overwritten`, Position: die Zuweisung). Vorher `f.free()` oder aus dem Feld herausverschieben.
* **Teil-Moves:** `val old = s.item.move()` (ohne `move()` wäre `old` nur ein Alias; rechte Seite ein besitzendes Feld einer lokalen Wurzel `s`,
  auch wenn `s` ein geliehener Parameter ist) verschiebt das Feld heraus. Das Feld ist
  danach `Moved`. Vor **jedem Aufruf** (außer Methoden von lokalen Besitzern mit Borrow) und
  vor dem **Funktionsende** muss jedes verschobene Feld wieder gültig sein (neue
  Zuweisung, auch `null`), sonst **M0113**. Die Verfolgung ist **eine Ebene tief** (`local.field`).
* `@Borrowed var parent: Node?` an einem Feld ist ein **ungeprüfter Rückverweis**: Zuweisung
  erlaubt, keine Lebensdauerprüfung (Lücke, in P3 geschlossen).
* Felder in `Managed`-Klassen: siehe 6.2.

```kotlin
fun swapOut(s: Slot): @Owned Box? {
    val old = s.item.move() // Teil-Move
    s.item = null           // wiederhergestellt
    return old
}
```

### 5.4 Collections besessener Werte

**In P1 nicht unterstützt.** Verfolgte Werte dürfen nicht in Kotlin-Collections
(`List`, `MutableList`, `Map`, ...) abgelegt werden: M0110 (Strict → Managed). Typ-Argumente
wie `List<@Owned T>` und Arrays besessener Elemente sind Teil von P3 (Abschnitt 9). In P1
baut man eigene Container-Klassen aus besitzenden Feldern (siehe `ok_linked_list`).
Managed-Klassen dürfen weiterhin in Collections liegen.

### 5.5 Lambdas und Captures

* **Inline-Lambdas** (Parameter einer `inline`-Funktion, nicht `crossinline`/`noinline`:
  `use`, `repeat`, `run`, `let`, `forEach`, `context(x) { }` ...) sind **durchsichtig**: Die
  Analyse liest den Körper wie Code an dieser Stelle. Besitzer dürfen darin freigegeben oder
  verschoben werden.
* **Thread-Lambdas** (Parameter mit `@ThreadBoundary`, siehe 5.9): Captures verschiebender
  Wert, das Lambda läuft genau einmal und besitzt danach.
* **Alle anderen Lambdas** sind **entkommend**. Für sie gilt:
  * Capture eines **besitzenden** Werts: **M0112** (in P1 nicht unterstützt).
  * Capture eines **geliehenen** Werts: M0109 (die Leihgabe überlebt ihren Verleiher).
  * Capture eines **Arena-Objekts**, wenn das Lambda den Arena-Scope verlässt: M0105
    (Position: das Lambda).
  * Capture eines nicht verfolgten Werts (Primitiv, Managed): immer erlaubt.
* Ein Lambda, das nicht entkommt (sofort aufgerufen, lokal gehalten und nur lokal
  benutzt), darf Arena-Objekte und Leihgaben capturen.

### 5.6 `try`, `finally`, frühe `return`, `throw`

Die Analyse arbeitet auf dem Kontrollflussgraphen mit diesen Kanten:

* **Normale Kanten.** `return`, `break`, `continue` verlassen Scopes. Jeder Besitzer, der den
  Scope verlässt und nicht `Freed`/`Moved`/`Null` ist, ist ein Leck: M0102 (Pfadart
  `early return`, `break`, `continue`).
* **`finally`** wird in jede ausgehende Kante des `try` eingefügt. Eine Freigabe im
  `finally` deckt daher alle Pfade ab. Der Zustand nach dem `try` ist der Zustand nach dem
  `finally`: Wurde im `finally` freigegeben, ist der Wert danach `Freed`.
* **Exception-Kanten (P1-Näherung).** Eine Exception-Kante entsteht nur an `throw`-Ausdrücken
  und an Aufrufen von `error`, `require`, `check`, `requireNotNull`, `checkNotNull`,
  `TODO` sowie an Aufrufen von Funktionen derselben Strict-Module, die (transitiv, laut
  Zusammenfassung) ein `throw` enthalten. Beliebige andere Aufrufe, Division durch null,
  Array-Index usw. erzeugen **keine** Exception-Kante (sonst wäre jeder Code ein Leck).
  Eine `catch`-Klausel fängt die Kante und setzt den Zustand dort fort; ohne passendes
  `catch` verlässt die Kante die Funktion: Leck M0102 (Pfadart `throw`).
  Siehe Abschnitt 10, Frage 3.
* **`use`** ist ein `try/finally` mit `close()`.

```kotlin
context(h: Heap)
fun risky(n: Int): Int {
    val b = Box(n)
    try {
        if (n < 0) throw IllegalStateException("neg")
        return b.v * 2
    } finally {
        b.free()           // deckt return und throw
    }
}
```

### 5.7 Schleifen

Der Zustand am Schleifenkopf ist die Vereinigung aus Eintritt und Rückkante (Fixpunkt).
Konsequenzen:

* **Pro-Iteration-Besitzer** (in der Schleife deklariert) müssen in jedem Durchlauf und auf
  jedem Ausgang (`break`, `continue`, Ende) freigegeben oder verschoben sein: M0102.
* **Außenbesitzer** dürfen im Schleifenkörper weder verschoben noch freigegeben werden,
  außer der Körper stellt sie vor der Rückkante wieder her (`x = ...`). Sonst: M0104
  (Move) beziehungsweise M0103 (Free) beim zweiten Durchlauf, Position der Stelle.
* In Arena-Scopes ist eine Allokation in einer Schleife erlaubt, aber unbeschränkt
  wachsend: Lint M0202.

### 5.8 `if`, `when`

An jeder Zusammenführung wird der Zustand pro Slot vereinigt. Eine Verzweigung, die einen
Besitzer in `Owned` lässt, während eine andere ihn freigibt oder verschiebt, führt zu
`Maybe` und damit zu M0102 am Scope-Ende (oder M0103/M0101 bei weiterer Verwendung).
Ein `when` ohne `else`, das als Statement benutzt wird, hat eine implizite leere
Verzweigung. Smart-Casts auf `null` bestimmen `Null`-Zustände (5.10).

### 5.9 Threads und `ThreadSafe`

Eine **Thread-Grenze** ist der Aufruf einer Funktion, deren Lambda-Parameter mit
`@ThreadBoundary` markiert ist (`mini.memory.spawn`), sowie die vom Compiler gelisteten
Standard-Stellen (`kotlin.concurrent.thread`, `Thread(Runnable)`). Für das Lambda gilt:

* Captures **besitzender** Werte sind Moves (5.1.4). Das Lambda besitzt sie und muss sie
  freigeben oder weiterreichen. Danach im Aufrufer Zugriff: M0104.
* Ein Wert, dessen nomineller Allocator **nicht** `ThreadSafe` ist (`Arena`, `Pool`), darf
  die Grenze nicht überqueren, und der Allocator selbst auch nicht: **M0206** (Position: das
  Lambda). Heap-Objekte dürfen.
* Geliehene Werte und Arena-Objekte dürfen nicht in ein Thread-Lambda (M0109 / M0105).

```kotlin
context(h: Heap)
fun run() {
    val b = Box(41)
    val t = spawn { println(b.v + 1); b.free() }   // b wandert in den Thread
    t.join()
}
```

### 5.10 Nullability

* `T?` ist besitzend wie `T`. `null` trägt keine Pflicht.
* `free()` auf `T?` ist erlaubt (`x.free()` und `x?.free()`); auf `null` ein No-op. Nach
  `x.free()` ist `x` `Freed` (nicht `Null`).
* `var b: Box? = null; if (flag) b = Box(1)` ergibt `Maybe(Null, Owned)`. Das ist
  vollständig freigegeben durch `b.free()` (oder `b?.free()`) oder durch `if (b != null) {
  b.free() }` (der Else-Zweig ist `Null`).
* Das Ergebnis einer Funktion mit `@Owned T?`, das verworfen wird, ist ein Leck (M0102,
  Pfadart `result discarded`): auch wenn es zur Laufzeit `null` sein könnte.
* Zuweisung von `null` an einen Slot in `Owned` ist ein Überschreiben (M0102).

---

## 6. Modi und Managed-Code

### 6.1 Moduswahl

* `@file:MemoryMode(Mode.Strict)` oder `Mode.Managed` an der Datei.
* `@MemoryMode(Mode.Managed)` an einer Klasse **überschreibt** die Datei für diese Klasse
  (Zusatz gegenüber dem Plan, damit gemischter Code in einer Datei geht und testbar ist).
* Ohne Annotation: **Managed**. Managed-Code unterliegt keiner Analyse (keine M01xx,
  außer M0110 an der Grenze). Managed-Klassen sind `Rc` und brauchen kein `free`.

Hinweis zur Schreibweise: Der Plan zeigt `@file:MemoryMode(Strict)`. In Kotlin muss der
Enum-Eintrag qualifiziert oder importiert werden; wir schreiben `Mode.Strict`.

### 6.2 Grenze Strict <-> Managed

| Richtung | Regel |
|---|---|
| Strict ruft Managed, Argument **nicht verfolgt** (Int, String, Managed-Objekt) | erlaubt. |
| Strict ruft Managed, Argument **verfolgt** | nur, wenn der Parameter der Managed-Funktion `@Borrowed` ist (Versprechen des Autors, ungeprüft in P1). Sonst **M0110**. Standard-Bibliotheksfunktionen (`println`, `toString`, ...) sind als `@Borrowed` bekannt. |
| Strict speichert verfolgten Wert in Feld oder Global einer Managed-Klasse | **M0110**. |
| Strict legt verfolgten Wert in eine Kotlin-Collection | **M0110** (5.4). |
| Strict benutzt Managed-Objekte | sie sind **geliehen mit externer Lebensdauer**: kein `free` (M0107). |
| Managed ruft Strict-Funktion mit geliehenen Parametern/ohne besitzende Rückgabe | erlaubt. |
| Managed ruft Strict-Funktion mit `@Owned`-Parameter oder besitzender Rückgabe | **M0110**: Managed-Code kann die Pflicht nicht erfüllen. Lösung: Strict-Wrapper, der intern freigibt. |

Der Check am Parameter ist eine bewusste Unsicherheit: Die Annahme „`@Borrowed` an einer
Managed-Funktion stimmt“ wird in P3 durch Zusammenfassungen der Managed-Funktion geprüft.

---

## 7. Diagnosekatalog

### 7.0 Format und Positionen

Das Ausgabeformat ist rustc-ähnlich (`plan-kotlin.md` 4.3):

```
error[M0102]: allocation from `Heap` (Deallocating) is never released
  --> src/load.kt:5:15   `val buf = Buffer(4096)` allocated here
  --> src/load.kt:6:24   path: early return without `buf.free()` / `buf.close()`
  help: call `buf.close()` before returning, or wrap in `buf.use { … }`
```

Genauer:

```
<severity>[<code>]: <message>
  --> <file>:<line>:<col>   <label>        (erste Zeile: Primärposition, genau eine)
  --> <file>:<line>:<col>   <label>        (null oder mehr Sekundärpositionen)
  help: <text>                             (null oder mehr)
```

* `severity` ist `error`, `warning` oder `info`. Spalten und Zeilen zählen ab 1; die Spalte
  zählt UTF-16-Einheiten wie Kotlin.
* Meldungstext, Labels und `help` sind **nicht stabil**. Stabil sind der **Code** und die
  **Primärposition**. Tests vergleichen `line:col: code`.
* Pro Ursache **genau eine** Meldung (Kaskadenunterdrückung, 5.2). Reihenfolge: nach
  Datei, Zeile, Spalte.
* Kotlin-eigene Fehler gehen den Mini-Diagnosen voraus. Gibt es einen, laufen die Mini-Analysen nicht.
* Mini-Diagnosen mit `info`-Schwere (M0201) stehen nur mit `-Minfo` in der Ausgabe und
  nicht in `.diag`-Dateien. Warnungen stehen in `.diag`; `error` lässt den Build
  scheitern (Exit 1), `warning` nicht.

Die Primärposition ist je Code der Anfang des genannten Ausdrucks (Spalte des ersten Zeichens):

| Code | Primärposition |
|---|---|
| M0101, M0104 | der verwendende Ausdruck (Anfang von `b.v`, `peek?.v`, `b` im Argument) |
| M0102 | die Allokationsstelle (Anfang von `Buffer(n)`, `make(3)`, `Arena()`); bei `@Owned`-Parametern der Parametername; bei Überschreiben eines Felds mit unbekanntem Inhalt die Zuweisung (Anfang des Ziels) |
| M0103, M0106 (free), M0107, M0108 | der `free`-Aufruf, Anfang des Ausdrucks einschließlich Receiver (`b.free()`, `pool.free(b)`) |
| M0106 (`@Owned`-Argument), M0109, M0110, M0105 (Wert) | der Ausdruck, der den Wert transportiert (Argument, rechte Seite, zurückgegebener Ausdruck) |
| M0105 (Lambda-Capture), M0112, M0206 | der Anfang des Lambdas (`{`) |
| M0111 | die Annotation (`@Owned`) |
| M0113 | der Teil-Move (Anfang von `s.item`) |
| M0114, M0204, M0205 | die Allokationsstelle |
| M0202 | die Allokationsstelle |

### 7.1 M0101 Nutzung nach Freigabe

**Regel:** Ein Slot in Zustand `Freed` (oder `Maybe` mit `Freed`-Anteil) wird gelesen, aufgerufen oder
geliehen. Gilt auch für Leihgaben, deren Besitzer freigegeben wurde (4.3).

```kotlin
val b = Box(1)
b.free()
println(b.v)          // error[M0101]: use of `b` after it was freed
```
Behoben:
```kotlin
val b = Box(1)
println(b.v)
b.free()
```
Korpus: `borrow_use_after_free`, `uaf_simple`, `uaf_borrowed_result`, `uaf_finally`, `uaf_loop`.

### 7.2 M0102 Leck: Allokation wird nie freigegeben

**Regel:** Ein besitzender Slot verlässt seinen Gültigkeitsbereich in `Owned`. Pfadarten
(im Label): `end of scope`, `early return`, `break`, `continue`, `throw`,
`not released on all branches`, `overwritten`, `result discarded`, `parameter`. Gilt auch für
`Arena`/`Pool`, die nicht geschlossen werden.

```kotlin
context(h: Heap)
fun load(n: Int): @Owned Buffer? {
    val buf = Buffer(n)             // error[M0102]: allocation from `Heap` (Deallocating) is never released
    if (!fill(buf)) return null     //   path: early return
    return buf
}
```
Behoben:
```kotlin
    if (!fill(buf)) { buf.free(); return null }
```
Korpus: `leak_end_of_scope`, `leak_early_return`, `leak_branch`, `leak_when_branch`,
`leak_loop_break`, `leak_loop_body`, `leak_overwrite`, `leak_overwrite_field`, `leak_throw`,
`leak_owned_param`, `leak_arena_not_closed`, `leak_ignored_result`, `leak_pool`.

### 7.3 M0103 Doppelte Freigabe

**Regel:** `free`/`close`/`use`-Ende auf einem Slot in `Freed` (oder `Maybe` mit `Freed`-Anteil).

```kotlin
b.free()
b.free()          // error[M0103]: `b` is freed twice
```
Behoben: den zweiten Aufruf löschen. Korpus: `dfree_simple`, `dfree_branch`, `dfree_loop`.

### 7.4 M0104 Nutzung nach Move

**Regel:** Zugriff auf einen Slot in `Moved` (5.1).

```kotlin
val a = Box(1)
val b = a.move()
println(a.v)      // error[M0104]: use of moved value `a`
```
Behoben: `b` statt `a` benutzen, oder `val b = a` (Alias) schreiben, wenn kein Move gewollt ist.
Korpus: `move_explicit`, `borrow_use_after_move`, `borrow_scope_moved`, `move_param`, `move_loop`, `move_into_field`, `move_thread`.

### 7.5 M0105 Entkommen aus einem Scoped-Allocator

**Regel:** Ein Arena-Objekt verlässt den Scope seiner Arena (2.4, 5.5).

```kotlin
fun build(): Box = Arena().use { a -> context(a) { Box(1) } }
//                                                 ^ error[M0105]: value allocated in arena `a` escapes its scope
```
Behoben: einen Wert ohne Allokation (Int) zurückgeben, oder die Arena in den Aufrufer
verlegen (`context(a: Arena) fun build(): Box`).
Korpus: `arena_escape_return`, `arena_escape_use_result`, `arena_escape_field`,
`arena_escape_global`, `arena_escape_lambda`.

### 7.6 M0106 Einzelfreigabe eines Scoped-Objekts

**Regel:** `free()` auf ein Arena-Objekt, oder Übergabe eines Arena-Objekts an einen
`@Owned`-Parameter (der Aufgerufene dürfte es freigeben).

```kotlin
Arena().use { a -> context(a) { val b = Box(1); b.free() } }   // error[M0106]
```
Behoben: `free()` weglassen, die Arena räumt auf. Korpus: `free_scoped`, `scoped_to_owned_param`.

### 7.7 M0107 Freigabe eines nicht besessenen Werts

**Regel:** `free()` auf einen geliehenen Wert (Parameter ohne `@Owned`, `@Borrowed`-Slot,
Receiver), auf einen Managed-Wert oder auf einen bereits verschobenen Slot.

```kotlin
fun drop(b: Box) { b.free() }          // error[M0107]: `b` is borrowed and cannot be freed
```
Behoben: `fun drop(b: @Owned Box)`. Korpus: `borrow_free_via_alias`, `free_borrowed_param`, `free_managed_object`.

### 7.8 M0108 Falscher Allocator beim Freigeben

**Regel:** `alloc.free(x)`, wobei `x` statisch nachweislich von einem anderen Allocator stammt.

```kotlin
context(heap) { val b = Box(1); pool.free(b) }   // error[M0108]: `b` was allocated by `Heap`, not `Pool`
```
Behoben: `b.free()` oder `heap.free(b)`. Korpus: `free_wrong_allocator`.

### 7.9 M0109 Leihgabe wird gespeichert oder als besessen zurückgegeben

**Regel:** Ein geliehener Wert fließt in ein besitzendes Feld, in ein besitzendes Rückgabe-
oder ein `@Owned`-Argument, oder wird von einem entkommenden Lambda gecaptured (5.5).

```kotlin
fun keep(h: Holder, i: Item) { h.item = i }   // error[M0109]: borrowed `i` is stored in an owned field
fun pick(a: Item): Item = a                   // error[M0109]: borrowed `a` is returned as owned
```
Behoben: `i: @Owned Item` (und der Aufrufer verschiebt), oder `: @Borrowed Item`.
Korpus: `borrow_alias_stored`, `borrow_stored_field`, `borrow_returned_owned`.

### 7.10 M0110 Verletzung der Strict/Managed-Grenze

**Regel:** Siehe 6.2.

```kotlin
val list = ArrayList<Box>()
list.add(b)            // error[M0110]: Strict value `b` handed to Managed code
```
Behoben: eigene Container-Klasse mit besitzenden Feldern; für den Managed-Aufruf einen
Strict-Wrapper. Korpus: `boundary_store_managed`, `boundary_collection`,
`boundary_managed_calls_owned`.

### 7.11 M0111 Ungültige Ownership-Annotation

**Regel:** 4.4.

```kotlin
fun twice(x: @Owned Int): Int = x * 2         // error[M0111]: `@Owned` on `Int`, which has no ownership
```
Behoben: Annotation entfernen. Korpus: `annotation_misuse`.

### 7.12 M0112 Besitzender Wert in entkommendem Lambda

**Regel:** 5.5 (P1 unterstützt kein Move-Capture in entkommenden Lambdas).

```kotlin
return { b.v }         // error[M0112]: owned `b` captured by a lambda that escapes
```
Behoben: den Wert als Parameter durchreichen, oder `b.v` vor dem Lambda auslesen.
Korpus: `capture_escaping_lambda`.

### 7.13 M0113 Besessenes Feld bleibt verschoben

**Regel:** 5.3, Teil-Moves.

```kotlin
fun steal(s: Slot): @Owned Box? { val b = s.item.move(); return b }   // error[M0113]: field `item` of `s` is moved out and not restored
```
Behoben: `s.item = null` vor dem `return`. Korpus: `field_left_moved`.

### 7.14 M0114 Mehrdeutiger Allocator

**Regel:** 2.2, Regel 1.

```kotlin
context(a: Arena, h: Heap)
fun both() { val b = Box(1) }    // error[M0114]: ambiguous allocator: `a: Arena` and `h: Heap`
```
Behoben: `context(h) { Box(1) }` (der innere Block gewinnt). Korpus: `ambiguous_allocator`.

### 7.14a M0115 Arena-Objekt besitzt fremde Referenz

**Regel:** 2.4. Ein Wert, der nicht aus derselben Arena stammt und kein Primitiv/String-
Literal ist, fließt in ein besitzendes Feld eines Arena-Objekts. Primärposition: der Wert.

```kotlin
val s = 5.toString()
val l = Label(5, s)       // error[M0115]: arena object `Label` would own refcounted `s`; the arena never releases it
```
Behoben: `@Borrowed val text: String` im Label, oder das Label und der String leben beide ohne
Arena (Heap + `free`). Korpus: `arena_owns_string`, `arena_owns_heap`, `arena_owns_managed`,
`ok_arena_plain_data`, `ok_arena_borrowed_ref`.

### 7.15 M0204 Strict: Allokation ohne wählbaren Allocator (Fehler)

**Regel:** 2.2, Regel 4 (Plan: „braucht Managed-Fallback“). Ein Strict-Wert entkommt, und
kein Context und keine `@AllocatedBy`-Standardinstanz ist verfügbar.

```kotlin
fun make(): @Owned Box = Box(1)      // error[M0204]: no allocator for an escaping Strict value
```
Behoben: `context(h: Heap)` an `make` oder `@AllocatedBy(Heap::class)` an `Box`. Korpus:
`no_allocator_escape`.

### 7.16 M0205 Allokation in `@NoAlloc`

`@NoAlloc` an einer Funktion: Jede Allokationsstelle der Funktion, die **tatsächlich nicht
auf dem Stack liegt** (sie entkommt) und jeder Aufruf `alloc.allocate(...)` ist ein Fehler.
Stack-Objekte sind erlaubt. P1 prüft **nur den Körper selbst**; Aufrufe anderer Funktionen
werden erst in P3 transitiv geprüft (Zusammenfassungen). Außerhalb von `@NoAlloc`
(Hot-Path-Heuristik) ist M0205 eine Warnung (P3).

```kotlin
@NoAlloc context(h: Heap)
fun make(): @Owned Box = Box(1)      // error[M0205]: allocation in `@NoAlloc` function
```
Korpus: `noalloc_alloc`, `ok_noalloc`.

### 7.17 M0206 Objekt aus nicht-ThreadSafe-Allocator verlässt den Thread

**Regel:** 5.9.

```kotlin
Pool<Item>(2).use { pool -> context(pool) {
    val item = Item(1)
    spawn { println(item.id); item.free() }     // error[M0206]: `Pool` is not ThreadSafe
}}
```
Behoben: einen `Heap` benutzen. Korpus: `thread_nonsafe`.

### 7.18 Lints M0201–M0203

| Code | Schwere | Meldung |
|---|---|---|
| M0201 | info | Allokation entkommt nicht und wurde auf den Stack verschoben |
| M0202 | warning | Allokation in Schleife in einer `Scoped`-Arena: unbeschränktes Wachstum (Korpus: `arena_loop_warn`) |
| M0203 | warning | Objekt größer als N KB (Default 64) auf dem Stack |

Lints ändern nie, ob ein Programm akzeptiert wird. `-Werror` macht Warnungen zu Fehlern.

### 7.19 Übersicht der Codes

| Code | Schwere | Kurz |
|---|---|---|
| M0101 | error | Nutzung nach Freigabe |
| M0102 | error | Leck |
| M0103 | error | doppelte Freigabe |
| M0104 | error | Nutzung nach Move |
| M0105 | error | Entkommen aus Scoped-Allocator |
| M0106 | error | Einzelfreigabe eines Scoped-Objekts |
| M0107 | error | Freigabe eines nicht besessenen Werts |
| M0108 | error | falscher Allocator beim Freigeben |
| M0109 | error | Leihgabe gespeichert / als besessen zurückgegeben |
| M0110 | error | Strict/Managed-Grenze |
| M0111 | error | ungültige Ownership-Annotation |
| M0112 | error | besitzender Wert in entkommendem Lambda |
| M0113 | error | besessenes Feld bleibt verschoben |
| M0114 | error | mehrdeutiger Allocator |
| M0201 | info | auf den Stack verschoben |
| M0202 | warning | Schleifen-Allokation in Arena |
| M0203 | warning | großes Stack-Objekt |
| M0115 | error | Arena-Objekt besitzt Referenz eines anderen Allocators |
| M0204 | error | Strict: kein Allocator wählbar |
| M0205 | error (`@NoAlloc`) / warning | Allokation in `@NoAlloc` |
| M0206 | error | Objekt verlässt den Thread, Allocator nicht ThreadSafe |

---

## 8. Lints und `@NoAlloc` im Überblick

Fehler (M01xx und die Fehlercodes in M02xx) brechen den Build ab. Warnungen sind
abschaltbar (`@Suppress("M0202")`). `@NoAlloc` gilt für den Körper der Funktion einschließlich
Property-Accessoren (Ziele `FUNCTION`, `PROPERTY_GETTER`, `PROPERTY_SETTER`).

---

## 9. Bewusst nicht in P1 (kommt in P3)

* **Escape-Analyse als Optimierung** (Stack-Verschiebung, M0201, M0203) und ihr
  Zusammenspiel mit Inlining. P1 behandelt Escape nur syntaktisch (2.1).
* **Collections besessener Werte** (`List<@Owned T>`, `Array`, `Map`): P1 lehnt sie mit M0110 ab.
* **Echte Lebensdauern** (Regionsvariablen für Leihgaben über Funktionsgrenzen hinaus,
  mehrere Verleiher); P1: eine Leihgabe hängt an Receiver und allen geliehenen Argumenten.
* **Move-Capture in entkommenden Lambdas** (Closures als Besitzer), M0112.
* **Transitive `@NoAlloc`-Prüfung** und Funktionszusammenfassungen; Hot-Path-Heuristik.
* **Allgemeine Exception-Kanten** (jeder Aufruf kann werfen): P1 nur `throw`/`require`...
  (5.6). `@NoThrow`-Inferenz kommt mit dem Exception-ABI.
* **Prüfung von `@Borrowed` an Managed-Funktionen** und von `@Borrowed`-Feldern.
* **Mehr als eine Ebene** bei Teil-Moves (`a.b.c`).
* **Wechsel des Modus pro Funktion**, Allocatoren als Typparameter (`class Box<A : Allocator>`).
* **Zyklen** zwischen besessenen Objekten (Eigentum ist ein Baum; Graphen brauchen Arena
  oder `@Borrowed`-Rückverweise).
* **Globale besitzende Variablen**: erlaubt, ohne Lecks zu prüfen (Programmlebensdauer;
  `DebugAllocator` meldet am Ende).
* **FIR-Checker für IDE-Anzeige**: P1 liefert die Diagnosen im Build-Pass (IR); die
  Aufteilung in FIR (syntaktisch) und IR (Fluss) folgt dem Plan.
* **Kotlin/Native-`memScoped`/cinterop-Allocatoren** (P4).

---

## 10. Entscheidungen

Status: vom Projekteigner geprüft. Punkte 1-3 ausdrücklich bestätigt, 4-8 und 10-14 mit ihnen
bestätigt, Punkt 9 **geändert** (siehe dort); String-Literale sind statisch (Punkt 13 bestätigt).

1. **`x.free()` als Hauptform** (3.1) statt `alloc.free(x)`; `alloc.free(x)` bleibt Nebenweg.
2. **`@AllocatedBy` ist eine Einschränkung** des Context (2.2) und nicht nachrangig wie im
   Plan (4.2: Context vor `@AllocatedBy`). So kann ein Ambient-`Arena` keine Klasse
   verdrängen, die ausdrücklich einen `Pool` verlangt.
3. **Exception-Kanten nur an `throw` und `require`-artigen Aufrufen** (5.6). Die
   strengere Variante („jeder Aufruf kann werfen“) wäre im Alltag unbrauchbar.
4. **Analyse unabhängig von der Platzierung** (1.3): ein Heap-Objekt, das der Compiler auf den
   Stack legt, muss trotzdem freigegeben werden. Die Alternative (Pflicht entfällt bei
   Stack-Promotion) macht Diagnosen optimierungsabhängig.
5. **`MemoryMode` auch auf Klassen** (6.1) und `Mode.Strict` statt nacktem `Strict`.
6. **`Pool` und `Arena` sind `AutoCloseable`-Ressourcen** mit Pflicht (2.3); `Heap`/`Rc` nicht.
7. **Managed-Parameter nehmen verfolgte Werte nur mit `@Borrowed`** (6.2), ein Versprechen ohne
   Prüfung. Alternativ: alle Managed-Parameter generell borrowed (unsicherer, bequemer).
8. **Strikte Defaults:** Parameter geliehen, Rückgabe/Felder besitzend, Property-Parameter
   besitzend; **keine** Inferenz von `@Owned` aus dem Körper (4.2). Der Plan lässt das
   offen („nur an Modulgrenzen“).
9. **Zuweisung leiht, Move ist ausdrücklich** (5.1; geändert durch den Projekteigner): `val b = a`
   ist ein Alias. Implizite Moves nur bei `return`, `@Owned`-Argument, besitzendem Feld und
   Thread-Capture. Ausdrücklicher Move: `a.move()` (Hauptform, `fun <T> T.move(): T`), gleichwertig
   `val b: @Owned T = a`. `@Borrowed val b = a` ist redundant, aber erlaubt.
10. **Teil-Moves aus Feldern** mit `s.item.move()` und Wiederherstellungspflicht (M0113).
11. **`Heap` und `Rc` sind zustandslose Allocatoren ohne Pflicht**; Standardinstanz nur für diese.
12. **`@NoAlloc` prüft in P1 nur den eigenen Körper** und nur Allokationen, die tatsächlich
    nicht auf dem Stack liegen (7.16).
13. **M0115** (Auftrag des Projekteigners): Arena führt nie `dispose` aus; Strings in Arena-Objekten nur als Literal oder `@Borrowed`. String-Literale sind statisch (bestätigt).
14. **Codevergabe:** M0101–M0115 ersetzen die Reihenfolge des Plans (der nur M0102 nennt);
    M0201–M0206 behalten die Bedeutung aus dem Plan (M0205 ist im `@NoAlloc` ein Fehler).

---

## 11. Der Testkorpus

Verzeichnis `compiler/testdata/memory/`. Der Gradle-Test `:memory-spec:test`
(`MemoryCorpusTest`) prüft:

1. Alle Programme und `stdlib/mini/memory/*.kt` kompilieren zusammen mit dem
   echten Kotlin-Compiler (K2, 2.4.21, JVM) ohne Fehler.
2. Jede Datei hat eine Kopfzeile (`// REJECT <Code>: ...` oder `// ACCEPT: ...`) und liegt in
   `package memory.<dateiname>`.
3. `name.diag` (abgelehnte Programme; eine Zeile `<Zeile>:<Spalte>: <Code>`) stimmt mit den
   Caret-Zeilen im Quelltext überein (`//        ^ M0102`, das `^` steht in der Spalte
   des Fehlers über der Zeile davor). So kann keine Spalte unbemerkt verrutschen.
4. `name.out` (akzeptierte Programme) ist die erwartete Standardausgabe; das Programm
   läuft auf der JVM. `-Pregen` schreibt die `.out`-Dateien neu.

Das Korpusverzeichnis liegt in einem Unterordner, den `EndToEndTest` (er listet nur
`compiler/testdata/*.kt`) nicht aufnimmt. Sobald die Analyse existiert, läuft ein zweiter
Test mit `minic check`, vergleicht die ausgegebenen `line:col: code`-Zeilen mit `.diag`
und baut/führt die akzeptierten Programme aus (`.out`).

Ein Programm mit `.diag` und `.out` (`arena_loop_warn`) ist akzeptiert, hat aber eine Warnung.
