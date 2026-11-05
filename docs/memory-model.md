# Kotlin/Mini: The Memory Model (Specification P1)

Status: **normative for P1, test-first.** The analysis does not exist yet. This document,
the API `stdlib/mini/memory/*.kt` and the corpus `compiler/testdata/memory/` define
what the compiler must later accept and reject. The design is in
`docs/plan-kotlin.md` (section 4). Where this document deviates from it, this document wins;
the deviations are listed in section 10.

The keywords MUST, MUST NOT, SHOULD are meant as in RFC 2119.

---

## 1. Principles

1. **Every Mini program is a valid Kotlin program.** The memory model consists only of
   annotations, interfaces, ordinary functions and context parameters
   (`context(alloc: Arena)`, Kotlin 2.2+, no extra flag in 2.4.21). The whole corpus
   is compiled with the real `kotlinc` (K2), see section 11. On the JVM, `free`, allocators
   and annotations are ineffective or naive (`free` calls `close()` if the object is
   `AutoCloseable`). The programs behave there exactly as under Mini, as long as there is no error.
2. **Two modes, per file or class.** `Managed` (default) = refcounting, no obligations.
   `Strict` = every allocation has a proven owner, otherwise a compile error.
3. **The analysis is independent of placement.** Whether an object ends up on the stack,
   in an arena or on the heap never changes whether a program is accepted.
   Escape analysis (stack promotion) is a pure optimization (section 2.1).
   Diagnostics are thus stable even when optimizations change.
4. **One notation per concept.** Where Kotlin offers several ways, this document names
   the main one; alternatives are explicitly marked as equivalent.
5. **Locally checkable.** Each function is checked solely from its signature and the signatures
   of its callees. There is no global inference across function boundaries (only the
   default ownership of the signature, section 4.2).

---

## 2. Allocation

### 2.1 Every constructor call is an allocation site

`Foo(...)` for a class `Foo` is an allocation site, as are calls that produce such an
object as their return value (factory function with `@Owned` return, see 4.2).
Arrays and local classes/lambdas behave like classes.

Two things are kept strictly separate:

* **Nominal allocator** (static, determines the obligations): rules 2.2.
* **Actual placement** (codegen): An object that does not escape lives on the
  **stack**, regardless of the nominal allocator. Only when it escapes is it actually
  allocated via the nominal allocator. This is reported by the info M0201 (not in the `.diag`).

**Escaping** means: the value flows into a return, into an `@Owned` parameter, into a
field or a global variable, into the captures of an escaping lambda (5.5) or across a
thread boundary (5.9). Passing to a borrowed parameter, method calls and
field accesses do not make a value escape.

```kotlin
class Vec(val x: Int, val y: Int)
fun len2(v: Vec): Int = v.x * v.x + v.y * v.y

fun main() {
    val v = Vec(3, 4)        // does not escape: stack, no allocator needed, no free
    println(len2(v))
}
```

### 2.2 How an allocation site chooses its allocator

Order; the first applicable rule wins:

1. **Context allocator.** At least one context parameter of an
   `Allocator` type is visible in scope (`context(alloc: Arena)` on the function or
   `context(arena) { ... }` as a block). The lexically **innermost** wins.
   If several allocator parameters are visible at the same level
   (`context(a: Arena, h: Heap)`), the choice is **ambiguous**: M0114. Solution:
   nested `context(x) { ... }` blocks (the inner one wins).
2. **Class annotation** `@AllocatedBy(X::class)` on the class. It is a
   **restriction**: if it applies, rule 1 only considers context allocators that are
   instances of `X`. If there is none, the compiler uses the global default instance
   of `X`, if there is one. That is only the case for `Heap` and `Rc` (stateless);
   `Arena` and `Pool` always need a context.
3. **Module default.** In **Managed** files the default is `Rc`. In **Strict** files
   there is no default.
4. No allocator found:
   * The value does not escape: it lives on the stack, without obligations (2.1).
   * The value escapes: error **M0204**.

Derived from this, which class has which behavior (determined by the file or
class in which the **class is declared**, not by the caller):

| Class declared in | nominal allocator without context |
|---|---|
| `Managed` file/class | `Rc` (no obligations) |
| `Strict` file/class | none: stack or M0204 |

Constructing a Strict class in Managed code is allowed and uses rules 1 and 2,
otherwise `Rc` (see 6.2 for the boundary).

```kotlin
class Node(val value: Int, var next: Node?)

context(alloc: Arena)
fun buildList(n: Int): Node? {
    var head: Node? = null
    for (i in n downTo 1) head = Node(i, head)    // rule 1: alloc (Arena)
    return head                                   // caller's region, see 2.4
}
```

```kotlin
fun make(): @Owned Box = Box(1)     // Strict, escapes, no allocator: M0204
```

A nominal allocator with the trait `Deallocating` creates the obligation "free exactly
once or pass on" (sections 3 and 5). A `Scoped` allocator creates the obligation
"must not leave the scope" (2.4). A `Managed` allocator (`Rc`) creates no obligation.

### 2.3 The bundled allocators (`mini.memory`)

All as Kotlin source in `stdlib/mini/memory/`.

| Type | Traits | Resource? | Remark |
|---|---|---|---|
| `Heap` | `Deallocating`, `ThreadSafe` | no | malloc/free. Stateless: creating `Heap()` costs nothing and creates no obligation. |
| `Arena` | `Scoped` (hence `AutoCloseable`) | **yes** | Region. Must be closed (`use {}` or `close()`): otherwise M0102. Not `ThreadSafe`. |
| `Pool<T>` | `Deallocating`, `AutoCloseable` | **yes** | Fixed capacity, objects freed individually. Must be closed. Not `ThreadSafe`. |
| `Rc` | `Managed`, `ThreadSafe` | no | Refcounting, default of Managed code. |
| `memScoped { }` (`kotlinx.cinterop.MemScope`) | `Scoped` | no (the block closes it) | C interop: `alloc<T>()`, `allocArray`, `"s".cstr.ptr` allocate in it; a pointer that leaves the block is M0105. |
| `nativeHeap` (`kotlinx.cinterop`) | `Deallocating` | no | C interop: malloc/free; every `alloc` needs `nativeHeap.free`. `@Owned(free = "fn")` on an `external fun` gives a C result the same obligation, released by calling `fn`. |
| `DebugAllocator(inner)` | `Deallocating`, `ThreadSafe` | no | Wraps a `Deallocating` allocator and, under Mini, reports leaks with their allocation site at program end. |

Interfaces: `Allocator { allocate(size, align): NativePtr? }`,
`Deallocating : Allocator { free(ptr); free(obj) }`, `Scoped : Allocator, AutoCloseable`,
`Managed : Allocator`, `ThreadSafe` (marker).

**Rule: allocator instances are themselves not tracked values**, with the exception of the
resources in the table (`Arena`, `Pool`): these are `AutoCloseable` owners like any
other Strict object and follow 3.2. They normally live on the stack.

### 2.4 Arena and `Scoped`

All objects of an arena die together when the arena is closed. For them there is
**no free obligation**, but a **lifetime restriction**:

> A value allocated in the arena `a` (rule 2.2/1 with `a`) must not leave the scope of
> `a`. The scope of `a` is the lambda body for `a = Arena().use { a -> ... }`, and the
> region up to `a.close()` for `val a = Arena()`.

In particular, the following are forbidden (M0105):

* `return` from the scope, the value of the `use` block, the value of a `context(a) { ... }` block
  that is used outside the scope of `a`,
* assignment to a field of an object that lives longer, or to a global variable,
* capture in a lambda that leaves the scope (5.5).

Passing **inward** (call arguments to borrowed parameters) and copying out values
without allocation (`Int`, `Boolean`, ...) are allowed. A function with
`context(a: Arena)` may return arena objects to its caller: the region is
the caller's, and the scope test continues there.

```kotlin
fun main() {
    val total = Arena().use { arena ->
        context(arena) {
            val list = buildList(1000)        // arena objects
            sum(list)                         // Int leaves the scope: ok
        }
    }                                         // arena released
    println(total)
}
```

**Arena objects do not own foreign references (M0115).** On `close()`/reset an arena
**never** runs `dispose` (neither `close()` nor field glue) of the objects; it keeps
no per-object bookkeeping (this is how the runtime works, see `runtime/README.md`).
Therefore an object that lives in an arena may contain in its **owning** fields
(including property parameters of the constructor) only: primitives, constant string literals
(static, without refcount), and objects **of the same arena**. The compiler reports M0115 when a
value of another allocator flows into an owning field of an arena object (constructor
argument or assignment): refcounted `String`, Managed objects (`Rc`), heap and pool objects.
Non-owning fields (`@Borrowed`) are allowed, e.g. `@Borrowed val name: String`;
the caller is then responsible for the lifetime (gap as in 5.3).

A single arena object cannot be freed and cannot be passed to an `@Owned` parameter
(M0106). If an arena object has `close()` (resources that need an end),
`close()` may be called directly (it does not release the memory, the memory
belongs to the arena); `free()` remains forbidden.

### 2.5 `@AllocatedBy`

```kotlin
@AllocatedBy(Pool::class)
class Item(val id: Int)

Pool<Item>(64).use { pool ->
    context(pool) {
        val i = Item(1)          // Pool<Item> from the context (rule 2)
        i.free()
    }
}
```

### 2.6 Implementation notes: how the allocators get their memory

Everything below is runtime internals (`runtime/src/alloc*.c`); the language-level contract above does not change.

* **Block layout.** Every heap object is `[owner | size/link word][MkObject header | fields]`: a 16-byte prefix holds
  the owning `MkAllocator*` and, behind it, the block size. `mk_free_object_memory` routes the block by the owner.
* **`Rc` and `Heap` (small-object allocator, `alloc_small.c`).** Blocks of up to 256 bytes (prefix included) are served
  from thread-local free lists, one per 16-byte size class; larger blocks use `malloc`/`free`. A thread frees into its
  own lists, whichever thread allocated the block (reference counted objects die where the last reference is dropped);
  whole batches of 256 blocks move between a thread and a mutex-protected central pool, so memory freed on a consumer
  thread flows back to producers and a thread's exit hands its lists to the pool. New memory is carved from 16 KB
  .. 256 KB chunks that are never returned to the system. The free-list link sits behind the owner word, so the
  `MK_FLAG_FREED` double-free check keeps working until the block is reused. Reads and writes of the lists are
  thread-local; the only shared state is the central pool (lock), so TSan sees proper happens-before edges.
* **Sanitizer builds.** Under AddressSanitizer, or with `-DMK_PLAIN_MALLOC`, the small-object allocator (and the arena
  chunk cache) is compiled out: `Rc` and `Heap` objects are plain `malloc` blocks again, so ASan and the
  `leakCheck` tests see every object. `DebugAllocator`, `Pool` and `Arena` never used it: a `DebugAllocator` over
  `Heap` calls the stateless `malloc` callbacks. Embedders that call `mk_heap_allocator()->allocate` directly also get
  plain `malloc` blocks.
* **`Arena`.** A pointer bump in the current chunk (chunks start at 4 KB by default and double up to 4 MB; oversized
  requests get a dedicated chunk), inlined into `mk_alloc_sized_site`. Chunks come from `calloc` and carry a high-water
  mark, so a fresh chunk needs no zeroing of objects and reused memory is cleared only below the mark. Freeing an
  individual arena object stays a diagnostic (M0106, `mk_free` panics; `mk_discard` of a Scoped object is a no-op), the
  arena never runs `dispose` (M0115 keeps foreign references out of arena objects), and closing the arena returns the
  chunks to a process-wide cache (up to 32 MB) from which the next arena takes them warm.
* **Statistics.** `-DMK_STATS` adds an allocator line to the summary: objects served from size classes vs `malloc`,
  chunks and chunk bytes, central pool refills and hand-offs, arena allocations (slow path) and arena chunks.

---

## 3. Release

### 3.1 Main notation: `x.free()`

There is **one** main notation for ending ownership: the extension function

```kotlin
fun Any?.free()          // package mini.memory
```

Rationale:

* It is valid Kotlin and compiles with the real `kotlinc` without special status.
* It does not need to name the allocator. The object knows at runtime where it came from;
  the compiler knows it statically from the data flow and checks it (M0106, M0107).
  This matters for `@Owned` parameters whose allocator the caller chooses.
* It is defined on `Any?`. `x.free()` and `x?.free()` are both correct for `T?`
  (5.10), and `free` on `null` is a no-op.
* It reads briefly in chains (`make().free()`) and in `finally`.

Meaning: `x.free()` (1) calls `close()` if `x` is `AutoCloseable`; (2) then frees all
**fields that `x` owns** (recursively, 5.3); (3) returns the memory of `x` to the
allocator that supplied it (no-op for stack objects).

Equivalent alternatives (no additional obligation, same analysis):

* `alloc.free(x)` (3.3), if you want to name the allocator explicitly.
* `x.close()` on an `AutoCloseable` owner and `x.use { ... }` (3.2).
* The end of an arena's scope (3.4).

### 3.2 `use {}` and `close()`

For `AutoCloseable` classes, `close()` is the destructor. `x.use { ... }` calls `close()` at the
end of the block on **every** path (also on `throw`, `return`, `break`) and thereby ends
the ownership of `x`. Inside the block, `it` (or the lambda parameter) is **borrowed**. The
call `x.close()` on an owner counts like `x.free()`: a second call of `close()`
or `free()` is M0103.

`use` is `inline`. The analysis sees into the block (5.5).

```kotlin
class Res(val name: String) : AutoCloseable {
    override fun close() { println("close " + name) }
}

context(h: Heap)
fun run() {
    Res("a").use { println("using " + it.name) }   // close at the end
    val b = Res("b")
    b.free()                                       // calls close()
}
```

After `close()`/`free()`, the generated field releases run (5.3). A
`close()` body must **not** free owned fields itself: the compiler takes care of that.

### 3.3 `alloc.free(x)`

`Deallocating.free(obj: Any?)` is `x.free()` with the allocator named explicitly. If
the compiler knows the allocator of `x`, it compares: `pool.free(x)` for a heap object is
**M0108**. If the allocator is not statically known (e.g. an `@Owned` parameter), the
check is done at runtime in `DebugAllocator`/debug builds. The call
`scopedAlloc.free(x)` does not compile (Kotlin error), because `Scoped` has no `free`.

### 3.4 End of an arena

`Arena.close()` (also via `use`) frees all arena objects **without** calling `close()` of their
classes and without field glue (decision of the project owner, matching the runtime).
Hence M0115 (2.4). Resources in arenas must be ended individually with `close()` (2.4).

---

## 4. Ownership: Basic Concepts

### 4.1 What is tracked

The analysis tracks **references to instances of classes declared in a Strict file
(or Strict class)**, as well as `AutoCloseable` allocators (`Arena`, `Pool`).
Everything else is **not tracked** and subject to no obligations: primitives, `String`,
objects of Managed classes (refcount, "lifetime external"), function types without
tracked captures, `Heap`/`Rc`/`DebugAllocator` (allocators without a resource).

A tracked reference is in exactly one **state** at any moment (5.2). Tracked
references have an **owner**: a local variable, a parameter, a field.

### 4.2 Owned and Borrowed: defaults

| Position | Default | Annotation |
|---|---|---|
| Parameter of a function | **borrowed** | `p: @Owned T` takes over ownership |
| Return of a function | **owning** | `: @Borrowed T` returns a loan (4.3) |
| Property parameter of the constructor (`class C(var x: T)`) | **owning** (= field) | `@Borrowed` |
| Field | **owning** | `@Borrowed var f: T?` (unchecked back references, 5.3) |
| Local variable | **owning** if initialized with a fresh value (`Foo(...)`, call with owning return) or `a.move()`; **alias (loan)** if initialized/assigned with an existing owner (`val b = a`) | `a.move()` or `val b: @Owned T = a` makes `b` an owner; `@Borrowed val b = a` is redundant (allowed, no effect) |
| `this` and extension receiver | **borrowed** | – |
| Parameters of Managed functions | **externally managed** (6.2) | `@Borrowed` |

Between Strict functions there is **no inference of ownership transfer**: a function that stores,
frees or returns its parameter as owned needs `@Owned`, otherwise M0107/M0109. The
diagnostic's help names the annotation. Towards Managed and library code the checker *does* infer, from the
function's body, whether a borrowed argument is kept (see "Strict mode in practice").

As an annotation, the main form is in type position (`b: @Owned Buffer`, `: @Owned Buffer?`;
`List<@Owned T>` is P3, see 5.4). Declaration position (`@Owned fun f()`, `@Owned val x`)
is equivalent.

### 4.3 Borrowed results

A function with `: @Borrowed T` returns a loan. The loan is valid **until
one of the owners it derives from (receiver and all borrowed arguments) is freed,
moved or overwritten.** Any use afterwards is M0101. A
loan must neither be stored in an owning field nor returned as owned:
M0109.

```kotlin
fun first(h: Holder): @Borrowed Box? = h.item

val holder = Holder(Box(1))
@Borrowed val peek = first(holder)
holder.free()
println(peek?.v)               // M0101
```

### 4.4 Validity of the annotations

`@Owned`/`@Borrowed` only make sense on types of tracked classes (4.1, also nullable).
`@Owned` on primitives, `String` or Managed classes is an error: **M0111**.
`@Borrowed` is additionally allowed on fields and parameters of arbitrary reference types (also `String`,
Managed) and there means "non-owning" (needed for fields of arena objects, 2.4). Both
at the same place: likewise M0111. `@Borrowed` on an `@Owned` return
is contradictory: M0111.

---

## 5. Flow Rules (Strict)

### 5.1 Moves and borrows

**Simple assignment borrows.** `val b = a`, `var b = a` and `b = a` with an existing
owner `a` (local variable, owned parameter, owned field) on the right-hand side
make `b` an **alias** (loan): `a` stays the owner, `b` may read, but not
free or own. An alias lives at most as long as its owner (see 4.3).

A **move** transfers ownership. It happens **implicitly** at exactly these places:

1. `return a` from a function with owning return,
2. argument to an `@Owned` parameter (also property parameter of the constructor),
3. assignment to an owning **field** (`s.item = a`),
4. capture in a thread lambda (5.9); also `a.use { }` / `a.close()` (3.2),

and **explicitly** through the main notation `a.move()`:

```kotlin
fun <T> T.move(): T = this      // package mini.memory, recognized by the compiler
```

`val b = a.move()` moves `a` into the new local variable `b`; `b` is the owner.
The type annotation `val b: @Owned Box = a` is equivalent. Rationale for `move()` as
the main form: it is an ordinary expression and works at any place (argument,
`return`, right-hand side, partial move out of fields: `s.item.move()`), needs no declaration and
stands out when reading. Fresh values (`val b = Box(1)`, `val b = make()`) are owners
anyway; a variable that receives an owner is reassigned with `move()`
(`b = a.move()`).

**Rules for aliases**

* Using an alias after its owner was freed: **M0101**; after it
  was moved: **M0104**; after the owner's scope ended: likewise (owner
  `Freed`/`Moved`, depending on how it left the scope). Reported at the use of the alias.
* `alias.free()`, `alias.close()`, `alias.use { }`, passing to an `@Owned` parameter,
  assignment to an owning field and `return alias` from an owning function:
  **M0107** (release) or **M0109** (store/return). This does not change the
  owner's state.
* An alias can be reassigned (`var cur = n; cur = cur.next`): it then borrows from the
  new owner.

**Use after move** (M0104): any access to a moved slot (except a
new assignment that reinitializes it). Likewise a move in a loop that hits again in the
next iteration (5.7).

```kotlin
val a = Box(1)
val b = a               // alias: a stays owner
println(b.v)            // ok
a.free()
println(b.v)            // M0101: b borrows from a, a is freed

val c = Box(2)
val d = c.move()        // ownership moves to d
println(c.v)            // M0104
d.free()

var list: Node? = null
for (i in 1..5) list = push(list, i)   // ok: list is moved into the @Owned parameter and reassigned
```

Taking out of a field is a **partial move** and needs `move()`: see 5.3.

### 5.2 States (model of the analysis)

For every owning slot, a state holds at every program point:

| State | Meaning |
|---|---|
| `Owned` | valid, obligation open |
| `Null` | for `T?` known to be `null`, no obligation |
| `Freed` | freed (3) |
| `Moved` | moved (5.1) |
| `Maybe(a, b)` | union of two paths; join at merge points |

Rules:

* **Use** (read, method call, borrow) in `Freed`/`Moved`/`Maybe` with such a
  component: M0101 or M0104. The message says "may have been freed" if only one path
  is affected.
* **Release** (`free`, `close`, end of `use`) in `Freed`/`Maybe` with a `Freed` component: M0103.
  In `Moved`: M0107 (no longer owned).
* **Scope end, return, break, continue, throw edge** of a slot in `Owned` or in
  `Maybe` with an `Owned` component: M0102 with path kind (7.2).
* **Overwriting** a slot in `Owned`: M0102, path kind `overwritten`.
* **Cascade suppression:** After a report about a slot, the analysis sets it to
  the state that makes the next step consistent (after M0101/M0104: `Owned`; after
  M0103: `Freed`; after M0108: `Freed`). Thus one cause produces exactly one message.
* Two paths with `Null` and `Owned` yield a state in which `free()` is allowed and
  `x == null` removes the obligation (5.10).

### 5.3 Fields

* Fields of tracked types are **owning**. An object owns its fields; `x.free()`
  frees them recursively (field glue) after `close()` has run. An object that is
  part of an owned tree is never freed individually, but together with the root.
* Property parameters of the constructor own (= field): `Node(i, head)` **moves** `head`.
* **Overwriting** an owning field with unknown old content: M0102 (path kind
  `overwritten`, position: the assignment). Beforehand `f.free()` or move out of the field.
* **Partial moves:** `val old = s.item.move()` (without `move()`, `old` would only be an alias; right-hand side an owning field of a local root `s`,
  even if `s` is a borrowed parameter) moves the field out. The field is
  then `Moved`. Before **any call** (except methods of local owners with borrow) and
  before the **end of the function**, every moved field must be valid again (new
  assignment, also `null`), otherwise **M0113**. Tracking is **one level deep** (`local.field`).
* `@Borrowed var parent: Node?` on a field is an **unchecked back reference**: assignment
  allowed, no lifetime check (gap, closed in P3).
* Fields in `Managed` classes: see 6.2.

```kotlin
fun swapOut(s: Slot): @Owned Box? {
    val old = s.item.move() // partial move
    s.item = null           // restored
    return old
}
```

### 5.4 Collections of owned values

**Not supported yet.** Tracked values must not be put into Kotlin collections
(`List`, `MutableList`, `Map`, ...): M0110 (Strict → Managed). Type arguments
like `List<@Owned T>` and arrays of owned elements are part of P3 (section 9). For now
you build your own container classes from owning fields (see `ok_linked_list`).
Managed classes may still be put in collections.

*Why a borrowing collection is not offered (design note).* The obvious cheap rule is a temporary
`listOf(a, b)` that lives inside one expression and borrows its elements. It is sound only if the
collection is dropped before the owners are freed. The generated code releases the reference-counted
temporaries (the vararg array, the list) at the end of the *function*, after `a.free()`, so the release
of the elements reads freed memory (this was tried: `listOf(a, b).size` followed by `a.free()` aborts with
a reference count underflow). A borrowing collection therefore needs statement-scoped release of
temporaries in Strict functions first. The sound alternatives, in the order they are planned:
(1) statement-scoped temporaries, which makes a temporary collection of loans possible
(the closure rules of 5.5 already track such a value: a use after the owner's `free()` is M0101, an escape is M0112/M0110);
(2) an owning `OwnedList<T>` in `mini.memory` whose `free()` frees its elements, the collection analogue of an owning field.
Until then: container classes with owning fields, or Managed classes in collections.

### 5.5 Lambdas and captures

* **Inline lambdas** (parameter of an `inline` function, not `crossinline`/`noinline`:
  `use`, `repeat`, `run`, `let`, `forEach`, `context(x) { }` ...) are **transparent**: the
  analysis reads the body like code at this place. Owners may be freed or
  moved inside.
* **Thread lambdas** (parameter with `@ThreadBoundary`, see 5.9): captures are a moving
  value, the lambda runs exactly once and owns afterwards.
* **All other lambdas** are **escaping**. For them:
  * Capture of an **owning** value: the closure **borrows** it (*borrowing capture*): the capture field of the
    closure object is not counted (no retain when the closure is created, no release when it dies), so
    the closure must not outlive the owner. The checker proves that where the closure goes:
    - it may be called, held in a `val`/`var` local, copied, passed to a function whose parameter does not keep it
      (the inferred summary of the callee, also for library stubs), captured by another closure and passed
      on likewise; the comparator `compareBy`/`sortBy` builds is such a closure;
    - **a use after the owner was freed or moved is M0101/M0104** (the closure is a loan of its owners);
    - it may be returned from a function only when it borrows parameters (the summary says the result *carries*
      them, so the caller treats the result as a loan of the arguments); it may never be returned over a local owner;
    - it must not be stored into a field or a global, returned over a local owner, kept by a callee (summary says the
      parameter escapes), captured by a thread or constructor-stored: **M0112**, naming the owner and where the closure goes;
    - an owned **`var`** captured by a closure: M0112 (the `var` lives in a counted cell shared with the closure).
  * Capture of a **borrowed** value: the closure borrows what the value borrows; if it escapes, M0109 (the loan outlives its lender).
  * Capture of an **arena object** if the lambda leaves the arena scope: M0105
    (position: the lambda).
  * Capture of an untracked value (primitive, Managed): always allowed.
* A lambda that does not escape (called immediately, held locally and used only
  locally) may capture arena objects and loans.

### 5.6 `try`, `finally`, early `return`, `throw`

The analysis works on the control flow graph with these edges:

* **Normal edges.** `return`, `break`, `continue` leave scopes. Every owner that leaves the
  scope and is not `Freed`/`Moved`/`Null` is a leak: M0102 (path kind
  `early return`, `break`, `continue`).
* **`finally`** is inserted into every outgoing edge of the `try`. A release in the
  `finally` therefore covers all paths. The state after the `try` is the state after the
  `finally`: if freed in the `finally`, the value is `Freed` afterwards.
* **Exception edges (P1 approximation).** An exception edge arises only at `throw` expressions
  and at calls of `error`, `require`, `check`, `requireNotNull`, `checkNotNull`,
  `TODO`, as well as at calls of functions of the same Strict modules that (transitively, according to
  the summary) contain a `throw`. Any other calls, division by zero,
  array index etc. produce **no** exception edge (otherwise all code would be a leak).
  A `catch` clause catches the edge and continues the state there; without a matching
  `catch` the edge leaves the function: leak M0102 (path kind `throw`).
  See section 10, item 3.
* **`use`** is a `try/finally` with `close()`.

```kotlin
context(h: Heap)
fun risky(n: Int): Int {
    val b = Box(n)
    try {
        if (n < 0) throw IllegalStateException("neg")
        return b.v * 2
    } finally {
        b.free()           // covers return and throw
    }
}
```

### 5.7 Loops

The state at the loop head is the union of entry and back edge (fixed point).
Consequences:

* **Per-iteration owners** (declared in the loop) must be freed or moved in every iteration and on
  every exit (`break`, `continue`, end): M0102.
* **Outer owners** must be neither moved nor freed in the loop body,
  unless the body restores them before the back edge (`x = ...`). Otherwise: M0104
  (move) or M0103 (free) on the second iteration, position of the site.
* In arena scopes an allocation in a loop is allowed, but grows
  without bound: lint M0202.

### 5.8 `if`, `when`

At every merge point the state is joined per slot. A branch that leaves an
owner in `Owned` while another frees or moves it leads to
`Maybe` and thus to M0102 at scope end (or M0103/M0101 on further use).
A `when` without `else` that is used as a statement has an implicit empty
branch. Smart casts on `null` determine `Null` states (5.10).

### 5.9 Threads and `ThreadSafe`

A **thread boundary** is the call of a function whose lambda parameter is marked with
`@ThreadBoundary` (`mini.memory.spawn`), as well as the standard sites listed by the compiler
(`kotlin.concurrent.thread`, `Thread(Runnable)`). For the lambda:

* Captures of **owning** values are moves (5.1.4). The lambda owns them and must
  free or pass them on. Afterwards, access in the caller: M0104.
* A value whose nominal allocator is **not** `ThreadSafe` (`Arena`, `Pool`) must not
  cross the boundary, nor may the allocator itself: **M0206** (position: the
  lambda). Heap objects may.
* Borrowed values and arena objects must not go into a thread lambda (M0109 / M0105).

```kotlin
context(h: Heap)
fun run() {
    val b = Box(41)
    val t = spawn { println(b.v + 1); b.free() }   // b moves into the thread
    t.join()
}
```

### 5.10 Nullability

* `T?` is owning like `T`. `null` carries no obligation.
* `free()` on `T?` is allowed (`x.free()` and `x?.free()`); on `null` a no-op. After
  `x.free()`, `x` is `Freed` (not `Null`).
* `var b: Box? = null; if (flag) b = Box(1)` yields `Maybe(Null, Owned)`. This is
  completely released by `b.free()` (or `b?.free()`) or by `if (b != null) {
  b.free() }` (the else branch is `Null`).
* The result of a function with `@Owned T?` that is discarded is a leak (M0102,
  path kind `result discarded`): even if it could be `null` at runtime.
* Assigning `null` to a slot in `Owned` is an overwrite (M0102).

---

## 6. Modes and Managed Code

### 6.1 Mode selection

* `@file:MemoryMode(Mode.Strict)` or `Mode.Managed` on the file.
* `@MemoryMode(Mode.Managed)` on a class **overrides** the file for that class
  (addition relative to the plan, so that mixed code in one file works and is testable).
* Without annotation: **Managed**. Managed code is subject to no analysis (no M01xx,
  except M0110 at the boundary). Managed classes are `Rc` and need no `free`.

Note on notation: the plan shows `@file:MemoryMode(Strict)`. In Kotlin the
enum entry must be qualified or imported; we write `Mode.Strict`.

### 6.2 Strict <-> Managed boundary

| Direction | Rule |
|---|---|
| Strict calls Managed, argument **not tracked** (Int, String, Managed object) | allowed. |
| Strict calls Managed, argument **tracked** | allowed when the callee only borrows the argument: the parameter is `@Borrowed`, or the *inferred summary* of the callee (computed from its body, transitively, stored in the `.mlib` for libraries) says the parameter does not escape. A callee that keeps the value (stores it into a field or array, returns it, hands it to a keeping callee) is **M0110**, and the message names why. Callees without a known body (function values, `external` functions) borrow, and their result is Managed. Standard library functions (`println`, `toString`, ...) are covered by their summaries. |
| Strict stores a tracked value in a field or global of a Managed class | **M0110**. |
| Strict puts a tracked value into a Kotlin collection | **M0110** (5.4). |
| Strict uses Managed objects | they are **borrowed with external lifetime**: no `free` (M0107). |
| Managed calls a Strict function with borrowed parameters/without owning return | allowed. |
| Managed calls a Strict function with an `@Owned` parameter or owning return | **M0110**: Managed code cannot fulfill the obligation. Solution: Strict wrapper that frees internally. |

The assumption "`@Borrowed` on a Managed function is correct" is not checked; for an unannotated
parameter the summary replaces it (section "Strict mode in practice": inferred summaries).

---

## 7. Diagnostic Catalog

### 7.0 Format and positions

The output format is rustc-like (`plan-kotlin.md` 4.3):

```
error[M0102]: allocation from `Heap` (Deallocating) is never released
  --> src/load.kt:5:15   `val buf = Buffer(4096)` allocated here
  --> src/load.kt:6:24   path: early return without `buf.free()` / `buf.close()`
  help: call `buf.close()` before returning, or wrap in `buf.use { … }`
```

More precisely:

```
<severity>[<code>]: <message>
  --> <file>:<line>:<col>   <label>        (first line: primary position, exactly one)
  --> <file>:<line>:<col>   <label>        (zero or more secondary positions)
  help: <text>                             (zero or more)
```

* `severity` is `error`, `warning` or `info`. Columns and lines count from 1; the column
  counts UTF-16 units like Kotlin.
* Message text, labels and `help` are **not stable**. The **code** and the
  **primary position** are stable. Tests compare `line:col: code`.
* **Exactly one** message per cause (cascade suppression, 5.2). Order: by
  file, line, column.
* Kotlin's own errors precede the Mini diagnostics. If there is one, the Mini analyses do not run.
* Mini diagnostics with `info` severity (M0201) appear in the output only with `-Minfo` and
  not in `.diag` files. Warnings appear in `.diag`; an `error` makes the build
  fail (exit 1), a `warning` does not.

The primary position per code is the start of the named expression (column of the first character):

| Code | Primary position |
|---|---|
| M0101, M0104 | the using expression (start of `b.v`, `peek?.v`, `b` in the argument) |
| M0102 | the allocation site (start of `Buffer(n)`, `make(3)`, `Arena()`); for `@Owned` parameters the parameter name; when overwriting a field with unknown content, the assignment (start of the target) |
| M0103, M0106 (free), M0107, M0108 | the `free` call, start of the expression including receiver (`b.free()`, `pool.free(b)`) |
| M0106 (`@Owned` argument), M0109, M0110, M0105 (value) | the expression that carries the value (argument, right-hand side, returned expression) |
| M0105 (lambda capture), M0112, M0206 | the start of the lambda (`{`) |
| M0111 | the annotation (`@Owned`) |
| M0113 | the partial move (start of `s.item`) |
| M0114, M0204, M0205 | the allocation site |
| M0202 | the allocation site |

### 7.1 M0101 Use after free

**Rule:** A slot in state `Freed` (or `Maybe` with a `Freed` component) is read, called or
borrowed. Also applies to loans whose owner was freed (4.3).

```kotlin
val b = Box(1)
b.free()
println(b.v)          // error[M0101]: use of `b` after it was freed
```
Fixed:
```kotlin
val b = Box(1)
println(b.v)
b.free()
```
Corpus: `borrow_use_after_free`, `uaf_simple`, `uaf_borrowed_result`, `uaf_finally`, `uaf_loop`.

### 7.2 M0102 Leak: allocation is never released

**Rule:** An owning slot leaves its scope of validity in `Owned`. Path kinds
(in the label): `end of scope`, `early return`, `break`, `continue`, `throw`,
`not released on all branches`, `overwritten`, `result discarded`, `parameter`. Also applies to
`Arena`/`Pool` that are not closed.

```kotlin
context(h: Heap)
fun load(n: Int): @Owned Buffer? {
    val buf = Buffer(n)             // error[M0102]: allocation from `Heap` (Deallocating) is never released
    if (!fill(buf)) return null     //   path: early return
    return buf
}
```
Fixed:
```kotlin
    if (!fill(buf)) { buf.free(); return null }
```
Corpus: `leak_end_of_scope`, `leak_early_return`, `leak_branch`, `leak_when_branch`,
`leak_loop_break`, `leak_loop_body`, `leak_overwrite`, `leak_overwrite_field`, `leak_throw`,
`leak_owned_param`, `leak_arena_not_closed`, `leak_ignored_result`, `leak_pool`.

### 7.3 M0103 Double free

**Rule:** `free`/`close`/end of `use` on a slot in `Freed` (or `Maybe` with a `Freed` component).

```kotlin
b.free()
b.free()          // error[M0103]: `b` is freed twice
```
Fixed: delete the second call. Corpus: `dfree_simple`, `dfree_branch`, `dfree_loop`.

### 7.4 M0104 Use after move

**Rule:** Access to a slot in `Moved` (5.1).

```kotlin
val a = Box(1)
val b = a.move()
println(a.v)      // error[M0104]: use of moved value `a`
```
Fixed: use `b` instead of `a`, or write `val b = a` (alias) if no move is intended.
Corpus: `move_explicit`, `borrow_use_after_move`, `borrow_scope_moved`, `move_param`, `move_loop`, `move_into_field`, `move_thread`.

### 7.5 M0105 Escape from a Scoped allocator

**Rule:** An arena object leaves the scope of its arena (2.4, 5.5).

```kotlin
fun build(): Box = Arena().use { a -> context(a) { Box(1) } }
//                                                 ^ error[M0105]: value allocated in arena `a` escapes its scope
```
Fixed: return a value without allocation (Int), or move the arena into the caller
(`context(a: Arena) fun build(): Box`).
Corpus: `arena_escape_return`, `arena_escape_use_result`, `arena_escape_field`,
`arena_escape_global`, `arena_escape_lambda`.

### 7.6 M0106 Individual free of a Scoped object

**Rule:** `free()` on an arena object, or passing an arena object to an
`@Owned` parameter (the callee could free it).

```kotlin
Arena().use { a -> context(a) { val b = Box(1); b.free() } }   // error[M0106]: arena object `b` cannot be freed individually
```
Fixed: omit `free()`, the arena cleans up. Corpus: `free_scoped`, `scoped_to_owned_param`.

### 7.7 M0107 Release of a non-owned value

**Rule:** `free()` on a borrowed value (parameter without `@Owned`, `@Borrowed` slot,
receiver), on a Managed value or on an already moved slot.

```kotlin
fun drop(b: Box) { b.free() }          // error[M0107]: cannot free a borrowed value
```
Fixed: `fun drop(b: @Owned Box)`. Corpus: `borrow_free_via_alias`, `free_borrowed_param`, `free_managed_object`.

### 7.8 M0108 Wrong allocator when freeing

**Rule:** `alloc.free(x)`, where `x` demonstrably comes from a different allocator, as determined statically.

```kotlin
context(heap) { val b = Box(1); pool.free(b) }   // error[M0108]: `b` was allocated by `Heap`, not `Pool`
```
Fixed: `b.free()` or `heap.free(b)`. Corpus: `free_wrong_allocator`.

### 7.9 M0109 Loan is stored or returned as owned

**Rule:** A borrowed value flows into an owning field, into an owning return
or an `@Owned` argument, or is captured by an escaping lambda (5.5).

```kotlin
fun keep(h: Holder, i: Item) { h.item = i }   // error[M0109]: borrowed `i` is stored in an owned field
fun pick(a: Item): Item = a                   // error[M0109]: borrowed `a` is returned as owned
```
Fixed: `i: @Owned Item` (and the caller moves), or `: @Borrowed Item`.
Corpus: `borrow_alias_stored`, `borrow_stored_field`, `borrow_returned_owned`.

### 7.10 M0110 Violation of the Strict/Managed boundary

**Rule:** See 6.2.

```kotlin
val list = ArrayList<Box>()
list.add(b)            // error[M0110]: Strict value handed to Managed code that keeps it
println(b)             // fine: println only borrows its argument (inferred)
```
Fixed: your own container class with owning fields; for the Managed call a
Strict wrapper. Corpus: `boundary_store_managed`, `boundary_collection`,
`boundary_managed_calls_owned`.

### 7.11 M0111 Invalid ownership annotation

**Rule:** 4.4.

```kotlin
fun twice(x: @Owned Int): Int = x * 2         // error[M0111]: `@Owned` on `Int`, which has no ownership
```
Fixed: remove the annotation. Corpus: `annotation_misuse`.

### 7.12 M0112 Owning value in escaping lambda

**Rule:** 5.5. A closure (lambda, anonymous object, local class instance) borrows the owners it captures; M0112 is reported
where the closure leaves the owner's lifetime.

```kotlin
return { b.v }         // error[M0112]: owned `b` is captured by a closure that escapes
```
Fixed: pass the value through as a parameter, or read `b.v` before the lambda. A closure that is only called, or passed to a
function that does not keep it, is fine (`apply2 { it + b.v }`, `xs.sortBy { it - b.v }`); using it after `b.free()` is M0101.
Corpus: `capture_escaping_lambda`.

### 7.13 M0113 Owned field stays moved

**Rule:** 5.3, partial moves.

```kotlin
fun steal(s: Slot): @Owned Box? { val b = s.item.move(); return b }   // error[M0113]: field `s.item` is moved out and not restored
```
Fixed: `s.item = null` before the `return`. Corpus: `field_left_moved`.

### 7.14 M0114 Ambiguous allocator

**Rule:** 2.2, rule 1.

```kotlin
context(a: Arena, h: Heap)
fun both() { val b = Box(1) }    // error[M0114]: ambiguous allocator for `Box`
```
Fixed: `context(h) { Box(1) }` (the inner block wins). Corpus: `ambiguous_allocator`.

### 7.14a M0115 Arena object owns a foreign reference

**Rule:** 2.4. A value that does not come from the same arena and is not a primitive/string
literal flows into an owning field of an arena object. Primary position: the value.

```kotlin
val s = 5.toString()
val l = Label(5, s)       // error[M0115]: arena object `Label` would own a foreign reference in field `text`; the arena never releases it
```
Fixed: `@Borrowed val text: String` in the label, or the label and the string both live without
an arena (heap + `free`). Corpus: `arena_owns_string`, `arena_owns_heap`, `arena_owns_managed`,
`ok_arena_plain_data`, `ok_arena_borrowed_ref`.

### 7.15 M0204 Strict: allocation without a selectable allocator (error)

**Rule:** 2.2, rule 4 (plan: "needs Managed fallback"). A Strict value escapes, and
no context and no `@AllocatedBy` default instance is available.

```kotlin
fun make(): @Owned Box = Box(1)      // error[M0204]: no allocator for an escaping Strict value
```
Fixed: `context(h: Heap)` on `make` or `@AllocatedBy(Heap::class)` on `Box`. Corpus:
`no_allocator_escape`.

### 7.16 M0205 Allocation in `@NoAlloc`

`@NoAlloc` on a function: every allocation site of the function that is **actually not
on the stack** (it escapes) and every call `alloc.allocate(...)` is an error.
Stack objects are allowed. P1 checks **only the body itself**; calls of other functions
are checked transitively only in P3 (summaries). Outside `@NoAlloc`
(hot-path heuristic) M0205 is a warning (P3).

```kotlin
@NoAlloc context(h: Heap)
fun make(): @Owned Box = Box(1)      // error[M0205]: allocation in `@NoAlloc` function `make`
```
Corpus: `noalloc_alloc`, `ok_noalloc`.

### 7.17 M0206 Object from a non-ThreadSafe allocator leaves the thread

**Rule:** 5.9.

```kotlin
Pool<Item>(2).use { pool -> context(pool) {
    val item = Item(1)
    spawn { println(item.id); item.free() }     // error[M0206]: `Pool` is not ThreadSafe and cannot cross a thread boundary
}}
```
Fixed: use a `Heap`. Corpus: `thread_nonsafe`.

### 7.18 Lints M0201–M0203

| Code | Severity | Message |
|---|---|---|
| M0201 | info | Allocation does not escape and was moved to the stack |
| M0202 | warning | Allocation in a loop in a `Scoped` arena: unbounded growth (corpus: `arena_loop_warn`) |
| M0203 | warning | Object larger than N KB (default 64) on the stack |

Lints never change whether a program is accepted. `-Werror` turns warnings into errors.

### 7.19 Overview of the codes

| Code | Severity | Short |
|---|---|---|
| M0101 | error | use after free |
| M0102 | error | leak |
| M0103 | error | double free |
| M0104 | error | use after move |
| M0105 | error | escape from Scoped allocator |
| M0106 | error | individual free of a Scoped object |
| M0107 | error | release of a non-owned value |
| M0108 | error | wrong allocator when freeing |
| M0109 | error | loan stored / returned as owned |
| M0110 | error | Strict/Managed boundary |
| M0111 | error | invalid ownership annotation |
| M0112 | error | owning value in escaping lambda |
| M0113 | error | owned field stays moved |
| M0114 | error | ambiguous allocator |
| M0201 | info | moved to the stack |
| M0202 | warning | loop allocation in arena |
| M0203 | warning | large stack object |
| M0115 | error | arena object owns a reference of another allocator |
| M0204 | error | Strict: no allocator selectable |
| M0205 | error (`@NoAlloc`) / warning | allocation in `@NoAlloc` |
| M0206 | error | object leaves the thread, allocator not ThreadSafe |

---

## 8. Lints and `@NoAlloc` at a Glance

Errors (M01xx and the error codes in M02xx) abort the build. Warnings can be
turned off (`@Suppress("M0202")`). `@NoAlloc` applies to the body of the function including
property accessors (targets `FUNCTION`, `PROPERTY_GETTER`, `PROPERTY_SETTER`).

---

## 9. Deliberately Not in P1 (comes in P3)

* **Escape analysis as an optimization** (stack promotion, M0201, M0203) and its
  interplay with inlining. P1 treats escape only syntactically (2.1).
* **Collections of owned values** (`List<@Owned T>`, `Array`, `Map`): P1 rejects them with M0110.
* **True lifetimes** (region variables for loans beyond function boundaries,
  multiple lenders); P1: a loan hangs on the receiver and all borrowed arguments.
* **Move capture in escaping lambdas** (closures as owners), M0112.
* **Transitive `@NoAlloc` check** and function summaries; hot-path heuristic.
* **General exception edges** (any call may throw): P1 only `throw`/`require`...
  (5.6). `@NoThrow` inference comes with the exception ABI.
* **Checking of `@Borrowed` on Managed functions** and of `@Borrowed` fields.
* **More than one level** for partial moves (`a.b.c`).
* **Mode switching per function**, allocators as type parameters (`class Box<A : Allocator>`).
* **Cycles** between owned objects (ownership is a tree; graphs need an arena
  or `@Borrowed` back references).
* **Global owning variables**: allowed, without leak checking (program lifetime;
  `DebugAllocator` reports at the end).
* **FIR checker for IDE display**: P1 delivers the diagnostics in the build pass (IR); the
  split into FIR (syntactic) and IR (flow) follows the plan.
* **Kotlin/Native `memScoped`/cinterop allocators** (P4).

---

## 10. Decisions

Status: reviewed by the project owner. Items 1-3 explicitly confirmed, 4-8 and 10-14 confirmed
along with them, item 9 **changed** (see there); string literals are static (item 13 confirmed).

1. **`x.free()` as the main form** (3.1) instead of `alloc.free(x)`; `alloc.free(x)` remains an alternative.
2. **`@AllocatedBy` is a restriction** of the context (2.2) and not subordinate as in
   the plan (4.2: context before `@AllocatedBy`). This way an ambient `Arena` cannot displace a class
   that explicitly demands a `Pool`.
3. **Exception edges only at `throw` and `require`-like calls** (5.6). The
   stricter variant ("every call may throw") would be unusable in everyday code.
4. **Analysis independent of placement** (1.3): a heap object that the compiler puts on the
   stack must still be freed. The alternative (obligation lapses on stack
   promotion) makes diagnostics depend on optimization.
5. **`MemoryMode` also on classes** (6.1) and `Mode.Strict` instead of bare `Strict`.
6. **`Pool` and `Arena` are `AutoCloseable` resources** with an obligation (2.3); `Heap`/`Rc` are not.
7. **Managed parameters accept tracked values only with `@Borrowed`** (6.2), an unchecked
   promise. Alternative: all Managed parameters generally borrowed (less safe, more convenient).
8. **Strict defaults:** parameters borrowed, return/fields owning, property parameters
   owning; **no** inference of `@Owned` from the body (4.2). The plan leaves this
   open ("only at module boundaries").
9. **Assignment borrows, move is explicit** (5.1; changed by the project owner): `val b = a`
   is an alias. Implicit moves only for `return`, `@Owned` argument, owning field and
   thread capture. Explicit move: `a.move()` (main form, `fun <T> T.move(): T`), equivalent
   `val b: @Owned T = a`. `@Borrowed val b = a` is redundant but allowed.
10. **Partial moves out of fields** with `s.item.move()` and a restoration obligation (M0113).
11. **`Heap` and `Rc` are stateless allocators without obligation**; default instance only for these.
12. **`@NoAlloc` checks only its own body in P1** and only allocations that are actually
    not on the stack (7.16).
13. **M0115** (commissioned by the project owner): an arena never runs `dispose`; strings in arena objects only as a literal or `@Borrowed`. String literals are static (confirmed).
14. **Code assignment:** M0101–M0115 replace the plan's numbering (which only names M0102);
    M0201–M0206 keep the meaning from the plan (M0205 is an error in `@NoAlloc`).

---

## 11. The Test Corpus

Directory `compiler/testdata/memory/`. The test `:memory-spec:test` of `tools/k2-oracle`
(`MemoryCorpusTest`) checks:

1. All programs and `stdlib/mini/memory/*.kt` compile together with the
   real Kotlin compiler (K2, 2.4.21, JVM) without errors.
2. Each file has a header line (`// REJECT <code>: ...` or `// ACCEPT: ...`) and lives in
   `package memory.<filename>`.
3. `name.diag` (rejected programs; one line `<line>:<column>: <code>`) matches the
   caret lines in the source (`//        ^ M0102`, the `^` stands in the column
   of the error, above the preceding line). This way no column can drift unnoticed.
4. `name.out` (accepted programs) is the expected standard output; the program
   runs on the JVM. `-Pregen` rewrites the `.out` files.

The corpus directory lies in a subfolder that `EndToEndTest` (which lists only
`compiler/testdata/*.kt`) does not pick up. Once the analysis exists, a second
test runs with `minic check`, compares the emitted `line:col: code` lines with `.diag`
and builds/runs the accepted programs (`.out`).

A program with `.diag` and `.out` (`arena_loop_warn`) is accepted, but has a warning.

## Strict mode in practice

This section records an audit of Strict mode on everyday Kotlin, the root causes of the false positives it found and the rules that
fix them. The sections above stay the specification; where this one refines them it says so (4.2, 6.2).

### Corpus and measurement

`compiler/testdata/strict-corpus/` holds 99 small programs a Kotlin developer would write (data classes in `println`, templates and
`toString`/`equals`/`hashCode`, scope functions, extension functions, interfaces and abstract classes, generics, nullable handling,
builders, exceptions with objects in flight, arenas and heaps, collections, lambdas, Managed library functions). The first line of
each file is the verdict: `// ACCEPT: ...` (must compile and, with a `.out` file, run with that output) or `// REJECT Mxxxx: ...`
(the checker must report exactly that code and nothing else: the program really leaks, double-frees or breaks the boundary).

```sh
scripts/strict-corpus.sh            # check every program; counters: ok, false positives, false negatives, wrong code
scripts/strict-corpus.sh --run      # also build and run the accepted programs and compare with the .out files
scripts/strict-corpus.sh s01 s13    # full diagnostics of the programs whose name starts with these prefixes
```

| | programs | as expected | false positives | false negatives | wrong code |
|---|---:|---:|---:|---:|---:|
| before (the 0.9.0 checker) | 99 | 84 | 13 | 1 | 1 |
| after | 99 | 99 | 0 | 0 | 0 |

The existing negative corpus (`compiler/testdata/memory`, 123 programs checked by `memcheck`, 34 run by `memrun`) is unchanged and
green: every true leak, use after free and double free is still an error.

### Audit: root causes of the false positives

| # | Root cause | Programs | Was | Fix |
|---|---|---:|---|---|
| 1 | Library functions are not known to borrow: `println(p)`, `print`, `toString`, `equals`, `hashCode` are declared in the standard library (a Managed file), so the name list `BORROWS_ALL` (which only applied to bodiless foreign functions) never matched; every `println(strictValue)` was M0110 | 6 | M0110 | inferred summary: `println` has no escaping parameter (7.10) |
| 2 | Unannotated Managed code that only reads: `StringBuilder.append(p)`, `requireNotNull(x)`, user Managed library classes | 3 | M0110 | inferred summaries from the bodies, transitively; `requireNotNull` returns a loan of its argument |
| 3 | Function values and receivers: `init: Box.() -> Unit` called as `b.init()`, `f(b)` | 4 | M0110 | a call of a function value has no known target: the arguments are lent, the result is Managed (6.2) |
| 4 | Generics: `fun <T> peek(h: Holder<T>): T` returns an existing element, not a fresh allocation | 1 | M0102 | a result whose declared type is a type parameter is a loan of the arguments |
| 5 | Diagnostic noise: a follow-up M0110 next to the real M0102 for the same `println(p)` | 1 | wrong code | disappears with 1 |
| 6 | Vararg arrays: `listOf(a, b)` stored Strict objects into a Kotlin array without any diagnostic, and the program crashed at run time (reference count underflow) | 1 | accepted (false negative) | the elements of a vararg are stored: M0110 |

Closure objects that capture an owned Strict object (`s24`, `s64`, `s75`: a lambda passed to a non-inline function, a lambda value,
the comparator `sortBy` builds) were first rejected with M0112, because the closure object retained the object it captured, `free()`
deallocated it at once, and the closure's release afterwards read freed memory. They are accepted now (round 2): the capture field
of a closure created in Strict code is a non-retaining `MirField.borrowed` field (C and LLVM backend), and the checker proves the
closure does not outlive the owner (5.5). The same rule covers anonymous objects and local classes (`s100`-`s103`, `s106`), which
used to double-free at run time without any diagnostic.

Programs that were already correct and are guard rails for the rules below: `s13`, `s14`, `s51` (storing, returning or freeing a
borrowed parameter), `s45`, `s79`, `s96`, `s97`, `s99` (Strict values entering Managed code that keeps them).

### Borrowed by default, decided by inferred summaries

An unannotated parameter is borrowed (4.2). What changes is how the checker decides whether a function that is *not* Strict code
(Managed code of the program, the standard library, a precompiled library) keeps its argument. It no longer asks for an annotation
nobody wrote; it reads the function.

**Summary.** For every function with a body the checker computes which parameters *escape*: the body

* stores the parameter into a field (`this.item = p`), an array (`refArraySet`, `@TakesOwnership`), or a global,
* returns it, throws it, moves it (`p.move()`) or releases it (`p.free()`),
* hands it to a callee parameter that escapes (this makes the analysis transitive), to a constructor that owns its property
  parameter, or to an `@Owned` parameter,
* captures it in a closure object that may outlive the call, or in a thread.

Everything else only borrows. Aliases (`val q = p`) are followed. Recursion and mutual recursion are solved by a fixpoint that starts
optimistic (nothing escapes) and iterates to stability. Summaries are computed on demand and cached per function
(`Summaries.of`, `compiler/backend/src/memory/Summary.kt`); a body is lowered only when a Strict call actually needs its summary.

**Calls that cannot be resolved.**

| Callee | Summary | Consequence |
|---|---|---|
| function with a body (Strict or Managed, source or library source) | inferred | M0110 only for an escaping parameter; the message names the reason |
| virtual call (open/abstract/interface method) | union over all known overriders | `MutableList.add(x)` escapes because `ArrayList.add` stores it |
| `external` function (C runtime: `println(Any?)`) and function values (`f(x)`, `x.block()`) | none | borrowed-in, Managed result: nothing is known that could store the value |
| library stub (`.mlib`) | stored in the library (see below) | the same answer as from source |
| generic result `T` | n/a | the result is a loan of the arguments (7.9 applies if it is stored) |

This is a deliberate rule, not a proof: the Managed boundary stays "borrowed in, Managed out". Code reached through a function
value or an overrider that is not part of the compilation can still keep a lent Strict object; that gap is the same as the unchecked
`@Borrowed` promise of 6.2 and is documented rather than hidden. A Strict parameter of a *Strict* function keeps the rule of
4.2 (an unannotated parameter that is stored is M0109, with help to add `@Owned`): ownership transfer between Strict functions stays
visible in the signature.

**Data classes.** The synthesized `toString`, `equals`, `hashCode`, `copy` and `componentN` read `this`; `copy` returns a fresh
object the caller owns (`s48`).

**Lambdas.** Inline lambdas are code at the call (5.5). A lambda that is a closure object borrows what it captures (borrowing
captures, 5.5); where it goes is checked with the inferred summaries: a parameter that a function stores, returns or captures
into an escaping closure *escapes*; a parameter only captured by a closure that the function returns is *carried* (the result
is a loan of the argument); everything else is only used. Summaries are computed per strongly connected component of the call
graph (a fixpoint inside a component, cached once outside), so large mutually recursive packages stay linear.

### Diagnostics

Every remaining M0110 says why. The same call shows:

```text
error[M0110]: Strict value handed to Managed code that keeps it
  --> app.kt:12:12   parameter `value` of `set` takes ownership of it (`@TakesOwnership`)
   12 |     m[1] = b
      |            ^
  help: keep the value in Strict code and pass only what is needed (`x.field`), or mark the parameter `@Borrowed` if `set` really only lends it
```

and for a program's own Managed helper "parameter `b` of `keep` stored into field `kept` of `Keeper`". M0112 now names the closure
object and lists the three ways out.

### Libraries: summaries in the `.mlib`

A `.mlib` carries the stubs of its sources, and stubs have no bodies, so a program checked against a library cannot infer
anything about it. `--emit-mlib` therefore computes the summary of every library function and stores one line per function with an
escaping parameter in a new `summary` section (format version 2, docs/mlib.md). Keys are the qualified name plus the parameter
types, so a stub and its source agree. Precompiled libraries (the standard library included) keep Strict checking as precise as
source libraries; a version-1 library is rejected with the usual "rebuild the library" message.

### What remains

* Strict values in Kotlin collections (`List<Box>`): still M0110 by 5.4; the design note there says what is needed (statement-scoped
  release of temporaries, then a temporary collection of loans; or an owning `OwnedList<T>`).
* Local functions (`fun helper()` inside a function) that capture owners are lifted and called directly: a call reads the captures
  (a call after the owner's `free()` is M0101), but a local function used as a value (`::helper`) is not tracked as a closure.
* Overriders that live in code outside the compilation are invisible to the union rule of virtual calls.
* No test opts out of the leak suites any more (`*.noleakcheck` is gone): the four Strict programs that leaked were a caught exception of a
  Strict class never released, and a Strict object passed into a generic container (`Cell(Item(5))`) whose owner count was never taken
  over (a compiler temporary of tracked type now holds a counted reference that the callee takes or the scope releases); the
  Managed ones were reference cycles (the collector, section "Cycles").

## Cycles: the cycle collector of Managed mode

Reference counting alone cannot free a reference cycle (a doubly linked list, a tree whose children point to their parent, an
observer that registers itself with its subject, a closure that captures the object that holds it). Kotlin programs create such
structures all the time and a Managed program must stay leak free, so Managed mode has a **synchronous trial-deletion cycle
collector** (Bacon & Rajan, *Concurrent Cycle Collection in Reference Counted Systems*, 2001, the synchronous variant). It lives in
`runtime/src/cycles.c`; the compiler only contributes a `trace` function per class.

### Decision

| Alternative | Why not |
|---|---|
| Weak references / "back pointer" annotations | Kotlin has no weak-by-default semantics for `parent`, `prev`, `observer`; programs would have to be rewritten and the stdlib's `LinkedHashMap` (`before`/`after`) needs a cycle by design. |
| Tracing GC for all objects | Changes the whole model (no deterministic release, needs stack maps), loses Strict/arena interplay. |
| Conservative backup tracing over all objects | Needs a registry of every object (header words, alloc/free cost). |
| **Trial deletion from buffered candidates** | Pay only for objects of classes that *can* be in a cycle and only when one of their references is dropped without freeing them; no stack maps, no registry, exact (precise) counts. |

### What is a candidate

* A Managed object whose class has a `trace` function (`MkTypeInfo.trace != NULL`) is born with the header flag
  `MK_FLAG_ARMED`. The compiler emits `trace` for every class with a reference field that can lead to a cycle (anything but
  `String` and boxed primitives; a subclass inherits the trace of its superclass); the runtime has one for object arrays
  and the `RefArray` behind `ArrayList`/`HashMap`. Strings, boxes, primitive arrays and plain data classes are never armed.
* **Strict, Heap, Pool and arena objects are never armed** (`mk_alloc_sized_site` arms only objects of the default
  reference-counting allocator). They do not know the collector exists: no flag, no extra branch, no buffer entry.
* The inline fast path of a release (`MKH_REL`, `mkh_rel` in the LLVM backend) was `not SHARED and rc > 1: rc--`. It is now `not
  (SHARED or ARMED) and rc > 1: rc--`. The first release of an armed object that does not free it goes through `mk_release`,
  which turns `ARMED` into `BUFFERED` (+ colour purple) and appends the object to the candidate buffer. While buffered, releases
  take the inline path again. The cost is therefore one slow-path call per object per collection cycle, not per release.
* If the count of a buffered object reaches zero it is *dying* during its dispose and a *zombie* afterwards: the buffer
  keeps pointing at the block, so the collector frees it. (A collection triggered from inside that dispose leaves dying objects
  alone.)

### Collection

`mk_cycles_collect` runs from the slow path that records a candidate, when (a) the buffer holds `threshold` candidates (4096 at the
start, afterwards a quarter of the objects that survived the last collection, at most 2^20) or (b) the candidate-capable objects
allocated since the last collection add up to `alloc_limit` bytes (8 MiB at the start, afterwards the bytes that survived the last
collection: the "heap doubling" rule, so the amortised cost per allocated byte is bounded however big the live heap is). Rule (b)
matters because a single candidate can hide a 100 MB cycle: counting candidates alone let `stress_cycles` grow 4x with the rounds.
It also runs, until nothing is freed any more, when `main` returns (so LeakSanitizer does not see garbage cycles).
`MINI_GC_TRACE=1` prints one line per collection to stderr.
It never runs recursively and has no recursion in its own traversal (explicit stacks): a 300 000 element list is no problem.

1. **MarkRoots**: for every purple root, colour it and everything reachable gray and subtract one for every reference
   between the visited objects (`trace` enumerates the fields; non-candidate children such as strings are skipped).
2. **Scan**: a gray object whose count is still positive is referenced from outside the visited subgraph: restore it and
   everything reachable from it (black, counts added back). The others are white.
3. **CollectWhite**: white objects are garbage. They are pinned with a huge count, the trial deletion of their references to
   *live* objects is undone (the dispose releases those for real, once), their `dispose` chain runs (it releases strings,
   buffers, live neighbours) and the blocks are freed. Releases that produce new candidates during this phase go to a fresh buffer.

### Threads

The collector needs exact counts and no concurrent mutation, so it runs only when **no Kotlin thread is running**
(`mk_threads_running == 0`: incremented by `mk_sys_thread_start`, decremented as the last action of the thread). The
atomic-RC mode that the first thread switches on (`mk_threads_active`) stays on; in that mode candidates are appended under a
spin lock with atomic flag updates, the buffer is bounded (2^20 entries, further candidates stay armed and are simply
not recorded) and the cycles that were created while threads ran are collected by the first collection after the last thread
ended (or at the end of `main`). The documented limitation: a program that keeps a worker thread running for its whole life never
frees cycles during that time.

### Release depth

Dropping the head of a long singly linked list released the next node from the dispose of the previous one: recursion as deep as
the list, a stack overflow for lists of some 10^5 nodes. `mk_release` now bounds the nesting (200 levels); deeper dead objects are queued
thread-locally and the outermost release works the queue off. Dispose only releases, so the different order is not observable.

### Measured overhead

`bench/run.sh --langs mini-managed` (best of 3-5, noisy shared host), before -> after: binarytrees 1.95 -> 2.24 s (+15 %, 100 M short-lived
`Node`s: every allocation is armed and every release goes through the depth bookkeeping; +12 % instructions), json 1.89 -> 2.20 s (+16 %),
wordcount 7.5 -> 8.1 s (+8 %), strbuild 1.41 -> 1.40 s (strings are never armed); peak RSS unchanged (json 125 -> 128 MiB). The
compiler compiling itself (stage1 without, stage3 with the collector): 633 MiB peak RSS both, 27 s both. Programs with garbage cycles get
the opposite trade: `stress_cycles` peak RSS 35 MiB -> 134 MiB at 4x the rounds without the collector, a flat 11.4 MiB with it.

### Tests

`compiler/testdata/cycles_{dll,tree,graph,closure,observer}.kt` run on the C and LLVM backend under LeakSanitizer (the leak
suites). `scripts/test-e2e.sh --suite stress` runs allocation-heavy programs (`compiler/testdata/stress/`): LeakSanitizer clean and the
peak RSS at 4x the rounds must stay within 25 percent of the peak at 1x.

## Experience from D2 (Strict code generation)

- Annotations: the new test programs `strict_{heap_list,arena_tree,pool,boundary,auto,close}.kt` need one allocator
  choice per object graph and a `free()` for each owner; no `@Owned` beyond the fields that own children. The stdlib
  needed 10 `@ReturnsBorrowed`, 10 `@TakesOwnership` and 27 `@NoHeapEffects`.
- Diagnostics that got in the way: M0102 (leak on the throw edge) on every call of a function that may throw while an
  owned value is live; M0204 / M0115 constraints when an owned value is handed to an owned parameter and used again
  (fixed by `x.move()` at the last use).
- Spec nuances found: `use { }` must free its resource (not only close it); the free glue does not call `close()` of
  nested objects (this matches the JVM oracle); borrowed fields (`@Borrowed`) must not be counted, or an
  arena-allocated graph leaks the field value.
- `minic mem-report` / `-Wrc` (M0207) show where counting remains: in the stdlib mostly "returned reference is not owned
  here", "reference stored into a field" and string temporaries; the report has function names but no source positions.

## Native status of the allocator types

`Heap`, `Arena`, `Pool`, `DebugAllocator` and `context(a) { ... }` build and run natively on the C and LLVM backends (the old blocker, value
classes, is gone: `NativePtr` is an ordinary class and general `value class` support exists). The accepted samples under
`website/samples/memory/` are Run cells and are verified by `website/scripts/check-samples.sh`; the checker (Strict mode) is unchanged.
What is still missing: threads (`spawn`, `thread_move`, `thread_pool` stay Check-only).
