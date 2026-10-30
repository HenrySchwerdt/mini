# Kotlin/Mini compiler (Phase 0 spike)

Compiles real Kotlin source through the official **K2 frontend** (pinned to Kotlin
**2.4.21**) into our own backend, which emits C17. clang compiles the C and links it with
the C runtime (`libminirt`, ABI in `runtime/include/minirt.h`).
See `docs/plan-kotlin.md` (sections 2, 3, 10, 10a) for the why.

```
hello.kt ─► K2 (FIR) ─► fir2ir ─► Kotlin IR ─► Ir2Mir ─► MIR ─► CEmitter ─► hello.c ─► clang + libminirt ─► binary
            └──────────────── compiler/jvm-bridge ───────────────┘   └──── compiler/backend ────┘
```

## Build and run

Requires JDK 21, Gradle 8.x (a wrapper is included), clang.

```sh
gradle -p compiler installDist            # builds compiler/jvm-bridge/build/install/minic/bin/minic
compiler/jvm-bridge/build/install/minic/bin/minic build hello.kt -o build/hello
./build/hello
```

```
minic build <file.kt>... -o <out> [--emit-c <path>] [--cc clang]
            [--runtime-src <dir>] [--runtime-include <dir>] [--cflag <flag>]...
            [--stdlib <dir> | --no-stdlib] [--extra-src <file.kt>]... [-Werror]
            [--stats] [--no-rc-opt]
minic check <file.kt>... [--extra-src <file.kt>]... [--brief] [-Werror] [--frontend=mini|k2]
```

The release `minic` (`bin/minic`, no JDK; `scripts/minic.sh`) has the same core plus more commands: `minic run foo.kt [-- args]`,
`minic build foo.kt` (output `./foo`, default `-O2`; `-O0..-O3`, `-g`/`--debug`), `minic check foo.kt` (no C compiler needed), `minic emit-c`, `--version`,
`<command> --help`, "did you mean" for unknown commands and options, and diagnostics options `--color=auto|always|never` (`NO_COLOR` is respected),
`--error-format=human|json` (one JSON object per diagnostic on stderr, for editors) and `--error-limit=N`. Exit status: 0 success, 1 compile error,
2 usage error, 3 internal compiler error (also what a C compiler failure on the generated C is reported as, with the C kept as `minic-ice-<name>.c`).
`minic build hello.kt` takes about 0.35 s (`--time` shows the split: ~185 ms parsing and analysing the stdlib plus the program, which is nearly all of the front end; ~120 ms
for the C compiler at `-O2` and the link; the default `-O2` costs +2% on the self-host C and +6% on a mid-size program compared with `-O1`).
The JVM `minic` above keeps clang at `-O1` and has no `run`. Reference: `website/reference/cli.md`.

* Several `.kt` files can be given (`minic build a.kt b.kt -o out`); exactly one of them has `fun main()`.
* The Mini standard library (`stdlib`, found next to `runtime/` or via `--stdlib <dir>`) is compiled from source together
  with the user files; only the reachable part ends up in the C file. `--no-stdlib` uses the JVM kotlin-stdlib jar instead
  (no stdlib functions then have bodies). Details: `docs/stdlib.md`. The `mini.memory` API is part of it.
* `--extra-src <file.kt>` (repeatable): additional Kotlin sources compiled together with the program, only
  as declarations (they are not translated to C). Files that are already part of the stdlib are ignored.
* `minic check`: only the Strict-mode ownership checker (see "Memory-model checker" below); prints rustc-like
  diagnostics to stderr, or with `--brief` `line:col: CODE` lines to stdout. Exit 1 on an error, 0 otherwise
  (warnings do not fail, unless `-Werror`; `build` accepts `-Werror` too). `minic build` runs the same checker after the frontend and before the C backend:
  errors stop the build (exit 1), warnings are printed.

* `--stats`: build with `-DMK_STATS`: the program counts allocations / frees / retains / releases (split into atomic,
  non-atomic and no-op) and the peak of live objects and bytes, and prints `mk-stats:` lines to stderr at exit
  (runtime/include/minirt.h, block D1; `docs/benchmarks.md`). Without the flag the runtime has no counters at all.
* `--no-rc-opt`: skip the reference-count elision pass (RcOpt, below): the baseline code generation.
* `--emit-c <path>`: keep the generated C file there (otherwise a temporary file is used).
* `--runtime-src <dir>`: directory whose `*.c` files (recursively) are linked in. Default:
  `runtime/src` in the repository root (found by walking up from the current
  directory); error if it does not exist.
* `--runtime-include <dir>`: directory with `minirt.h`. Default: `runtime/include`.
* clang is invoked with `-std=c17 -O1 -Wall`; extra flags via `--cflag`.
* Exit codes: 0 ok, 1 compile error (Kotlin error, unsupported construct, C compiler
  failure), 2 usage, 3 internal emitter error.

Tests: `gradle -p compiler test`.

* `backend` tests: purity rule (below), mangling, emitter details.
* `jvm-bridge` `EndToEndTest`: builds every `compiler/testdata/*.kt` with the CLI against the
  real runtime `runtime` (sources compiled in), compiles with `-Werror`, runs the
  binary and compares stdout with `<name>.out`.
  * `<name>.err` (optional): substrings expected on stderr; the program must then exit non-zero (panic or uncaught exception).
  * `<name>.cerr`: the build must fail with exit 1 and stderr must contain each line.
  * The `.out` files were generated by running the same programs on the JVM with the real
    Kotlin compiler, so they are the reference semantics.
* `jvm-bridge` `EndToEndTest.leakCheck`: every program with a `.out` and no `.err`/`.cerr` is built a
  second time with `-fsanitize=address,undefined` (LeakSanitizer on) and must print the same output,
  exit 0 and report nothing. The C compiler is clang when it can link the sanitizer runtimes, else gcc
  (as in `runtime/run_tests.sh`); the test is skipped when neither can. Set `MINIC_DEBUG=1` to get
  stack traces for unsupported-construct errors.

## Module layout

| Module | Role | May use JVM APIs? |
|---|---|---|
| `compiler/backend` | MIR data model (`Mir.kt`), name mangling (`Mangling.kt`), MIR → C emitter (`CEmitter.kt`) | **No** |
| `compiler/native-driver` | B1: `main(args)` that reads a `.mir` file, runs `CEmitter`, writes the `.c` file (runs on the JVM via Gradle, natively once minic can compile the backend) | **No** (file access through `mini.io`) |
| `compiler/frontend` | B2-1: own Kotlin frontend: lexer, lossless CST parser, typed AST, outline renderer (`mini.frontend`); B2-2: symbols, types, inference, call resolution, smart casts (`mini.frontend.sema`); `driver/` holds the self-compilation driver | **No** (PurityTest) |
| `compiler/lower` | B2-3: `Ast2Ir` (analysed AST to a K2-shaped IR), `Reachability`, and `Ir2Mir` (a port of the jvm-bridge one) behind `lowerToMir` | **No** (LowerPurityTest) |
| `compiler/minic-native` | B2-3: `minic-native`, the compiler without K2 (own frontend + lowering + backend, `main(args)` on `mini.io`) | **No** (MinicNativePurityTest) |
| `compiler/frontend-oracle` | Test-only: compares the frontend with the K2 PSI parser and the semantic analysis with K2's FIR resolution, hosts the native self-compilation tests | Yes (tests) |
| `compiler/jvm-bridge` | Runs K2 (`FrontendDriver`), converts Kotlin IR to MIR (`Ir2Mir*.kt`, see the source layout below), CLI (`Cli`, `Main`), starts clang | Yes, and the only module allowed to touch compiler internals |

### How the IR is obtained

`FrontendDriver` calls `K2JVMCompiler().exec(messageCollector, Services.EMPTY, args)` on the
source file (kotlin-stdlib 2.4.21 on the classpath, `-no-stdlib -no-reflect`, class output
to a temporary directory that is deleted). A `CompilerPluginRegistrar` (`MiniPluginRegistrar`)
registers an `IrGenerationExtension` whose `generate(IrModuleFragment, IrPluginContext)`
receives the **post-fir2ir, pre-lowering** module and runs `Ir2Mir` there. The registrar is
passed with `-Xplugin=<temp dir>` containing only `META-INF/services/...CompilerPluginRegistrar`;
the plugin class itself is loaded by the parent (application) class loader, so it shares state
with the CLI. These are all public/stable plugin APIs; no `internal` compiler classes are used.
Frontend errors are collected through the `MessageCollector` and reported as
`file.kt:line:col: error: ...`.

Because the IR is taken before any lowering, `for` loops, `when`, string templates, `i++`,
`&&`/`||` and compound assignments still have their high-level fir2ir shape
(`for` = iterator variable + `FOR_LOOP_INNER_WHILE`, `&&` = `IrWhen` with origin `ANDAND`,
comparisons = `kotlin.internal.ir.less/EQEQ` intrinsics, ...). `Ir2Mir` pattern-matches these.
`for (i in a..b / until / downTo / step)` over Int/Long/Char becomes a `MirStmt.ForRange` with
exact Kotlin semantics (no overflow at `Int.MAX_VALUE`, empty ranges, step > 0 check).

## The purity rule (plan 10a)

`compiler/backend` is pure common-style Kotlin so it can compile itself later: no `java.*`,
`javax.*`, `kotlin.jvm.*`, `@Jvm*`, `kotlin.reflect`, `::class.java`, `System.*`, no Java
sources, and no dependency but the Kotlin stdlib. `backend/src/test/kotlin/PurityTest.kt`
greps the main sources and the module's build file and fails the build on any violation.
(The check runs as part of `gradle -p compiler test`.)

## C name mangling

Implemented in `backend/.../Mangling.kt`.

* `fun main()` (any package) is always `mk_main`; the runtime calls it.
* Other top-level function: `kt_` + segments joined with `_D`, then `_A` + one letter per
  parameter if there are parameters. Segments are the package parts followed by the name.
  Inside a segment `[A-Za-z0-9]` is kept, `_` becomes `_u`, any other UTF-16 unit `_xHHHH`.
  Escapes always continue with `u`/`x`, so `_D`/`_A` can never appear inside a segment and the
  mapping is injective.
* Parameter letters: `I` Int, `J` Long, `Z` Boolean, `D` Double, `C` Char, `S` String.
* Examples: `fun foo(x: Int)` in package `a.b` → `kt_a_Db_Dfoo_AI`; `fun fib(n: Int)` in the
  root package → `kt_fib_AI`; `fun my_fun()` → `kt_my_ufun`.
* Locals/parameters: `v_<name>_<id>`. String literals: `kstr_<n>`. Helpers in the generated
  file: `mkh_*` (static inline). Runtime symbols (`mk_*`, `Mk*`) are never produced for user
  code, so user code cannot collide with the runtime.

* Classes: stem `kc_` + package parts and nested class path joined by `_D` (`kc_Foo`, `kc_a_Db_DOuter_DInner`);
  `struct <stem>` is the layout, `<stem>_ti` the `MkTypeInfo`. Methods `<stem>_M<name>_A<letters>`,
  constructors `<stem>_C_A<letters>` (initialiser, plus an allocating `..._new` wrapper), fields `f_<name>`,
  object accessors `<stem>_get`. Enum classes also get a hidden entries singleton `<stem>_E`.
* More parameter letters: `L<class stem>_E` for a class/interface (`Lmk_Any_E` is `Any`), a `Q` prefix for
  nullable (`QS` = `String?`, `QI` = `Int?`).

## Language scope

Supported (P0): top-level functions (also recursion, mutual recursion, forward references),
parameters and return values; types `Int`, `Long`, `Boolean`, `Double`, `Char`, `String`, `Unit`;
literals, string templates, `String + any supported value`, `String.length`, `==`/`!=` on
Strings; `val`/`var` (including deferred initialisation `val x: Int; x = 1`); arithmetic,
comparison, logical, bitwise/shift operators and `++`/`--`/`+=` family; numeric conversions
(`toInt`, `toLong`, `toDouble`, `toChar`, `Char.code`, `toString`); `if` and `when` as statements and
expressions (subject or subject-less, `in` range conditions); `while`, `do-while`, `break`/`continue`
(also labeled); `for` over `..`, `until`, `downTo`, `step`; `x in a..b`; `println`/`print`.

Supported (P1-A): classes with primary/secondary constructors, `init` blocks, property initialisers,
`val`/`var` members (default and custom accessors, `field`), methods, `this`, `private`/`public`;
`open`/`abstract`/`override`, `super.f()`, interfaces with abstract and default methods and
properties (also interface inheritance); `is`/`!is`/`as`/`as?`, smart casts, `when (x) { is A -> }`;
`object` declarations, `companion object` (named too), nested classes; `data class`
(`toString`/`equals`/`hashCode`/`copy`/`componentN` come from K2's IR); `enum class` (entries,
constructor parameters, members, `name`, `ordinal`, `toString`, `when`, `==`); default arguments for
functions, methods and constructors; nullable reference types, `null`, `?.`, `?:`, `!!`, `==`/`===`;
`Int?`/`Long?`/`Boolean?`/`Double?`/`Char?`; `Any`, `toString()`/`equals`/`hashCode` on everything.

Kotlin semantics that are implemented exactly: Int/Long overflow wraps (computed on the
unsigned type, no C UB), `Int.MIN_VALUE / -1`, shift counts are masked, `/` and `%` by zero raise
`ArithmeticException: / by zero`, `Double → Int/Long` saturates and maps NaN to 0,
evaluation order is left to right even when an operand needs hoisted statements (e.g. a `when`
inside an expression; operands with side effects are stored in temporaries unless last).

Anything else is a compile error of the form
`file.kt:3:1: error: unsupported construct: fun interface 'F'` (exit code 1); the
backend never emits C for IR it does not understand.

## Object model (P1-A)

* **Layout.** Every instance starts with the `MkObject` header (`type`, `refcount`, `flags`). A subclass
  embeds its superclass struct as the first member (`struct kc_B { struct kc_A base; ...fields }`), so
  a pointer to a subclass is a valid pointer to its superclass. Fields are stored in the struct that
  declares them; a field access is `((struct kc_A *)obj)->f_x`. All class-typed C values are
  `MkObject *` (strings are `const MkString *`); static types only matter for field offsets.
* **Dispose.** Each class with reference-typed fields gets a `dispose` that releases exactly its own
  fields; the runtime's `mk_release` walks the type chain (most derived first), so superclass fields
  are released by the superclass' dispose.
* **Virtual dispatch (vtable).** `MkTypeInfo.vtable` starts with three fixed slots `toString`, `equals`,
  `hashCode` (default: `mk_any_*` from the runtime), followed by one slot per open/abstract/override
  member that *introduces* a new overridable member in the hierarchy (the topmost class-level
  declaration in the override chain; superclass slots first). A call `a.f()` on a class-typed receiver
  loads `((MkObject *)a)->type->vtable[slot]` and calls it with the receiver first. `final` (the
  default) members are called directly. `super.f()` is a direct call.
  Calls to `toString`/`equals`/`hashCode` on anything (also strings, boxes, `null`) go through
  `mk_to_string`/`mk_equals`/`mk_hash_code`, which special-case the built-in types and otherwise use the
  three slots.
* **Interface dispatch (itable).** Each class lists all interfaces it implements (transitively,
  including those of superclasses) in `MkTypeInfo.interfaces`. The vtable gets one extra entry per
  interface (same order, after the class slots); each points to an array of function pointers for that
  interface, indexed by the position of the method among the interface's declared (non-inherited)
  members. A call through an interface receiver does `mk_itable(obj, &iface_ti)[index]`, a linear scan of
  the interface list: simple, correct, not fast. Interface default methods are ordinary functions
  stored in the table of classes that do not override them. Abstract entries point at a stub that
  aborts with `AbstractMethodError`.
* **`is`/`as`.** `mk_is_instance` / `mk_cast` (checked casts abort with `ClassCastException`,
  `null as Foo` with `NullPointerException`; `as?` yields null).
* **Objects.** An `object` (and every `companion object`) is a singleton created **lazily on first
  access** (`<stem>_get()`: allocate, publish the pointer, then run the constructor). It is never
  released (it is reachable from a global, which leak checkers accept).
* **Enums.** Entries live in a hidden singleton created on first use of any entry; `name`/`ordinal` are
  hidden fields set by the constructor. `values()`, `valueOf()` and `entries` are generated functions (see v1-L).
* **Nullability.** A nullable reference is the same pointer, `NULL` for `null`; `!!` is `mk_not_null`.
  Nullable primitives (`Int?` ...) are **boxed**: a heap object of type `kotlin.Int` etc. (`mk_box_*`,
  `mk_unbox_*`); primitives stored in `Any`/`Any?` are boxed the same way. `==` on references calls
  `mk_equals` (null-safe), `===` compares pointers.
* **Properties.** Accessors are ordinary methods; calls to final default accessors are inlined to a
  field read/write.

## Reference counting (Managed mode)

Rules implemented by `Ir2Mir` (ownership shape) and `CEmitter` (retain/release placement). These are the
baseline rules (`--no-rc-opt`); the elision pass RcOpt (next section) removes most of the pairs they produce.

1. Every reference-typed local owns one reference. It is `NULL`-initialised at the top of the C scope
   that contains its declaration and released at every exit of that scope: normal end, `break`,
   `continue` and `return` (each releases all scopes it leaves).
2. Parameters are **borrowed**: callees never release them; callers keep them alive for the call.
3. Expressions that create references (calls returning references, constructors, string
   conversions/concatenation, boxing) are *owned* (+1) and are immediately bound to a fresh temporary
   local (released at scope end), or directly to the `val`/`var`/field/return that consumes them (no
   extra retain). Borrowed values (locals, constants, casts of locals) are retained when stored.
4. Reads of reference fields are copied into retained temporaries, so a callee that overwrites the field
   cannot free a value the caller still uses.
5. `Assign`/field stores: retain the new value, store, release the old one.
6. Return values are owned: returning an owned local moves it (no retain/release pair); returning
   anything else retains it first.
7. String literals are static objects (retain/release are no-ops). `object`/enum singletons are
   immortal.

All non-cyclic test programs are verified leak-free and ASan/UBSan-clean (see Tests). **Reference
cycles leak** (no cycle collector; `weak` references do not exist yet) and the leak check programs avoid
them.

## RC elision (RcOpt)

`compiler/backend/.../RcOpt.kt` (pure Kotlin, on by default; `minic build --no-rc-opt` and
`native-driver --no-rc-opt` give the baseline). It does not rewrite the MIR: per function it computes an
`RcPlan` (borrowing locals, moves, takes, owned parameters, ...) that `CEmitter` consults when it places
retain/release. The result is a deterministic function of the MIR module, so the JVM backend and the native
`minic-backend` produce byte-identical C (SelfHostTest). Method: Perceus-style ("reference counting with reuse")
ownership inference, restricted to what is provably safe for our object model (mutable fields, no GC, one
reference convention for virtual calls).

**Effects.** `RcEffects` computes, as a fixpoint over the call graph (like `ThrowAnalysis`), which functions may
*overwrite a reference stored in the heap*: a reference field store, a `mk_ref_array_set`-like runtime call, or a
call of such a function (virtual and interface calls through the union over all implementations of that vtable
slot / itable entry; runtime calls not on an allow-list count as mutating). Only such an operation can free an
object that a borrowed alias still points to. Stores into the object a constructor is initialising do not count
for that constructor (the object is invisible to everybody else).

**Rules** (each has unit tests in `backend/src/test/kotlin/RcOptTest.kt`, and end-to-end programs under ASan/UBSan
in `testdata/rcopt_*.kt`):

1. *Borrowed locals.* A reference local whose every definition is a plain read (`val t = p`, `val t = o.f`,
   `x = x.next`, casts and `!!` of those, `object` singletons, constants) does not own a reference: no retain at the
   definition, no release at any exit, and `Assign` is a plain C assignment. It is valid for its whole lifetime when
   (a) the roots of its definitions are parameters (kept alive by the caller for the whole call), owned locals that are
   not reassigned in the lifetime (and whose scope encloses the borrower), or immortal objects; and (b) if a field was
   read on the way, no heap-mutating operation runs between the first definition and the last use. A call that
   receives the borrower as an argument counts as running at its end (the callee itself may overwrite the field
   while it still uses its parameter). The lifetime is extended to the end of every loop that contains a use but not
   the declaration. The set of borrowers is the greatest fixpoint of these conditions.
2. *Moves of the last use.* A statement that consumes the last use of an owned local - `Declare`/`Assign`/field store
   of the local (also through `Cast`/`!!`, or through a plain-copy borrower of it) or `return` of it - takes its
   reference instead of retaining a second one. "Last use" is exact liveness over the structured MIR (branches,
   loops with fixpoint, `break`/`continue`, `try` handlers: everything live in a handler is live in the whole
   `try` body); uses through borrowers of the local count as uses of it. A moved local is cleared (`x = NULL`) so
   the scope-exit releases on other paths stay correct, except in the closed case of rule 5.
3. *Owned constructor parameters.* A constructor parameter that the initialiser always gives away at its top level
   (stored into a field, returned, passed to another constructor) has the *owned* convention: the callee releases
   it at every exit unless it moved it, the caller passes a reference it owns - the last use of a local moves, anything
   else is retained for the call. Arguments are evaluated first (into C temporaries, left to right), then the
   references are handed over, so an exit while evaluating an argument still finds every local intact; an
   exception inside the constructor releases the parameter on its exit path. Calls through vtables/itables keep the
   borrowed convention (one ABI per slot). The conventions are a fixpoint over constructor delegation (max 4 rounds).
4. *Self takes.* `x = Foo(x)`: the old value of `x` dies at the assignment, so it can be handed to the callee and
   the release of the old value is skipped.
5. *Closed moves.* When the consuming statement is a direct child of the list that declares the local, the local is
   never reassigned, and no `continue` can jump past the statement to the end of the loop body, nothing can release
   the local on any later path: the emitter neither clears it nor releases it at later exits.
6. *Not-yet-declared locals.* Exits (`return`, `break`, `continue`, exception exits) that precede the `Declare` of a
   local of an enclosing scope do not release it (it is still `NULL`).
7. *Fresh stores.* The first store into each reference field of `this` in the top-level statements of a
   constructor, as long as `this` has not been handed to other code, overwrites the zeroed field: no old value to
   release.
8. *Inline NULL test.* With RcOpt, releases of references that are often `NULL` (old field values, scope-exit
   releases of conditionally assigned locals, fields in `dispose`) are emitted as `MKH_REL(x)`: the test is inline
   and the runtime is only called for a real object.

**Exception paths.** The exit code of every call that can throw releases the owned locals of the scopes it leaves.
Borrowing locals are not in that list; a moved local was cleared (or is dead) before anything after the move can
throw; an owned parameter is released by the callee's own exit path. `finally` is lowered by `Ir2Mir` into catch-all
clauses and copies of the finalizer, which RcOpt sees as ordinary MIR.

**Not done (ideas).** Owned parameters for non-constructor functions and vtable slots (needs one convention per
slot, so a fixpoint over slot groups), reuse analysis (an object that dies at a constructor call donates its memory:
`Tree(l, r)` after the last use of an old `Tree`), a cycle-free "unique" bit, inlined retain/release fast paths in
`minirt.h`, thread-sharing awareness (borrowed aliases of fields of `MK_FLAG_SHARED` objects assume the
program is data-race free, like the baseline's field-read retain).

## C interop

User guide: `website/guide/c-interop.md`. Design (docs/plan-kotlin.md section 7): the API of `kotlinx.cinterop`, implemented in the language, with no runtime shim.

* **MIR.** New scalar types `Byte`, `Short`, `Float` (never boxed; a `Byte?` is an error) and `Ptr`/`Ptr?` (a C `void *`: `CPointer<T>`, `NativePtr`, every `CPointed` such as `IntVar` or a `@CStruct` class, `CFunction`). New nodes: `Load`, `PtrAdd`, `MirStmt.Store`, `CCall` (call of a `MirExtern`), `CCallIndirect`, `FuncAddr`; the module carries `externs`, `includes` (`@file:CInclude`) and `links` (`@file:CLink`). Serialized additively (version 2 unchanged). Layout rules shared by both frontends: `backend/.../CInterop.kt`.
* **Frontends.** Both produce identical MIR: `jvm-bridge/.../Ir2MirInterop.kt` and its port `lower/.../Ir2MirInterop.kt`. Pointer classes never become MIR classes. `external fun` + `@CName` is a `CCall` (types are checked: only primitives and pointers). The intrinsics of `stdlib/kotlinx/cinterop/Intrinsics.kt` (builtin file) become loads, stores, pointer arithmetic, `sizeOf` constants, function pointers. `@CStruct` property accessors become loads/stores at offsets from the C layout rules. `staticCFunction` lifts a capture-free lambda to a function of its own wrapped in a catch-all that panics. Everything else (`memScoped`, `alloc`, `.value`, `p[i]`, `cstr`, `toKString`) is ordinary Kotlin in `stdlib/kotlinx/cinterop/` on top of those and libc `calloc`/`free`.
* **Backends.** C: calls go through own prototypes bound to the real symbol with an asm label (`extern int mkx_strlen(void *) __asm__("strlen")`), so headers never clash and C and LLVM call the same symbol; loads/stores are `memcpy` (any alignment, no aliasing rules). LLVM: `declare` per symbol, call-site function types, `signext`/`zeroext`, default argument promotion for `...`.
* **Variadic** externs: `@CVariadic(fixed)` per call shape. `@Owned(free = "fn")` on an external function: see the memory checker below.
* **Tools.** `minic build ... --c-src file.c` (repeatable) links C files; `-I` for the source directories; `@file:CLink` becomes `-l<lib>` (the generated C/IR also carry a `mini:link` comment for drivers that do not link themselves, e.g. `minic-native`).
* **K2 caveat.** `external class` is rejected by K2 on the JVM unless the file has `@file:Suppress("WRONG_MODIFIER_TARGET")` (the driver silences the suppression warning). The own frontend needs nothing.
* **Memory checker** (both frontends, `MemoryFacts`): pointer classes are tracked values; `memScoped` is `Arena().use { }` (M0105 on escape), `nativeHeap` is a `Deallocating` allocator (M0102/M0103/M0101), `@Owned(free = "fn")` results are `SK.Extern` allocations released by calling `fn`; pointers from other C functions are externally managed. The `kotlinx.cinterop` API is checked as Strict code with borrowed results and is no Managed boundary.
* **Own frontend additions that interop needed:** member extension functions and properties on lambda receivers (`"x".cstr.ptr` inside `memScoped`), top-level `operator fun T.invoke` for `f(a, b)` on a variable, generic member extension properties, and callable references as arguments of overloaded generic functions (`staticCFunction(::f)`).
* **Not done:** unsigned types, structs/unions by value (`CValue`, `readValue`, `useContents`), bit fields, exporting Kotlin functions with `@CName`, `mini-bindgen`, `ByteArray` conversions, pointers inside `Any`/collections, 32-bit targets.
* **Tests:** `testdata/cinterop_*.kt` (libc plus `cinterop_lib.c`, run in both frontends and backends and under ASan/UBSan), `testdata/memory-extra/x_cinterop_*.kt` (checker).

## Memory-model checker (Strict mode)

Implements the analysis of `docs/memory-model.md` (the corpus in `testdata/memory` is its test).

```
K2 -> Kotlin IR --+--> Ir2Mir -> MIR -> C                      (code generation, jvm-bridge + backend)
                  |
                  +--> MemoryFacts -> MemIr -> CFG -> dataflow -> diagnostics   (the checker)
                       (jvm-bridge)   \_______ compiler/backend/.../memory _______/

own frontend: sema -> Ast2Ir -> IR --+--> Ir2Mir (lower) -> MIR -> C
                                     +--> MemoryFacts (lower) -> the same MemIr and checker
```

**Both frontends run the checker** (`--frontend=mini`, the default, uses `mini.lower.MemoryFacts`, a port of the K2 one
to the K2-shaped IR of `Ast2Ir`; `--frontend=k2` uses `jvm-bridge/MemoryFacts.kt`). `minic build` and `minic check`
analyse **every user file** (not `--extra-src`, not the stdlib); a diagnostic knows its file (`Diag.file`) and is printed
with the file name as given on the command line. The native compiler has the same: `minic-native --check [--brief]
[-Werror] <user.kt>... --stdlib <std.kt>...` prints the same text (to stdout, the stdlib has no stderr) and ends with
status 1 on an error (0 otherwise; warnings only with `-Werror`); a normal `minic-native -o out.c` run checks first and
fails the same way. `MiniMemoryCheckTest` runs the whole corpus (`testdata/memory`, `memory-extra`, 117 programs) on
both frontends and requires identical rendered diagnostics (code, line:col, message, secondary positions, help) and the
`.diag` expectations: 117/117. The Ir of the own frontend carries what the checker needs: start offsets (set in
`Ast2Ir`, K2's convention: an expression starts at its leftmost receiver), type annotations (`x: @Owned T`) and annotation
classes resolved to qualified names (`mini.memory.Owned`, also `@mini.memory.Owned`; a user class named `Owned` is not
the annotation).

* **`MemoryFacts.kt` (jvm-bridge)** lowers the Kotlin IR of the user file to **MemIr** (`backend/.../memory/MemIr.kt`),
  the MIR-level program the checker consumes. It is a second view of the same IR module as `Ir2Mir` and lives in its
  own files, because the C-oriented MIR can neither carry what the checker needs (source positions, `@Owned` /
  `@Borrowed` / `@MemoryMode` / `@AllocatedBy` / `@NoAlloc` / `@ThreadBoundary`, context parameters, allocator
  objects, calls to `free` / `move` / `use` / `close`, `context(x) { }` blocks, Strict vs Managed per function) nor
  represent lambdas, try/finally or generics yet. MemIr is pure data; `Ir2Mir` is untouched. Inline lambdas (`use`,
  `context`, `run`, `let`, `repeat`, `forEach`, ...) become `MStmt.Scope` blocks read like code; thread-boundary
  lambdas are scopes whose captured owners move in; all other lambdas are `MExpr.Lambda` (analysed separately).
  `Foo.free()` / `alloc.free(x)` / `x.close()` become `MExpr.Free`, `x.move()` becomes `MExpr.Move`.
* **`backend/.../memory`** (pure Kotlin, covered by the purity rule, self-hosts later):
  * `Cfg.kt`: builds a control-flow graph per function (and per escaping lambda). A stack of frames (scope locals,
    loops, `finally` bodies, `use` closes, thread-lambda ends, try handlers) makes every leaving edge (end of
    scope, `return`, `break`, `continue`, `throw`) run the right exit operations: `finally` bodies are copied into
    each exit path, leaks of dying owners are checked per path (the path kind appears in the message). Exception
    edges only arise at `throw` and `require`-like calls and at calls of functions that (transitively) throw.
    `x == null` tests refine the state on the branch edges.
  * `State.kt`: the abstract domain. A tracked slot holds a *set* of states {Owned, Null, Freed, Moved, Borrowed,
    already-reported} (the join at a merge is the union, i.e. the spec's `Maybe`), the owner slots an alias borrows
    from, and the allocation sites of the value (position, allocator kind, allocator identity). Field states
    (`local.field`: unknown / null / owned / moved-out) support overwrite leaks and partial moves.
  * `Transfer.kt`: the transfer functions: allocator resolution per allocation site (innermost context level,
    `@AllocatedBy` as a restriction with default instances only for `Heap`/`Rc`, ambiguity M0114, no allocator =
    stack object or M0204 when it escapes), moves and borrows, function summaries from annotations (`@Owned`
    parameters, owned/borrowed returns, context parameters, Managed callees, a summary of borrowed parameters a
    callee stores), arena scopes (M0105/M0106/M0115/M0202), thread boundaries (M0206), `@NoAlloc` (M0205), field
    moves (M0113) and annotation checks (M0111).
  * `Checker.kt`: the driver. Per function: build the CFG, iterate the forward dataflow to a fixpoint (reporting
    off), then one reporting pass over the stable block entry states. One cause, one message: diagnostics are
    deduplicated by code and position, and a leak (M0102) of a slot that already has a use/free/move error is
    dropped (cascade suppression). Managed functions are not analysed except for the boundary rule M0110.
  * `Diagnostics.kt`: rustc-like rendering (`error[M0102]: ...`, `--> file:line:col label`, `help:`); the stable
    parts are the code and the primary position.
* **Tests.** `jvm-bridge/src/test/kotlin/MemoryCheckTest.kt` runs the checker on every `testdata/memory/*.kt`
  (`.diag` = exact expected `line:col: CODE` multiset, no `.diag` = no diagnostics at all) and on our own additional
  programs in `testdata/memory-extra` (same conventions); `build/reports/memory-check-summary.txt` has the pass
  rate per code. `MemoryRunTest` builds the accepted programs natively (`.out` comparison); programs the C backend
  cannot lower yet (context parameters, lambdas, generics) are skipped with the reason listed in
  `build/reports/memory-run-summary.txt`.
* **Code generation.** Strict programs are checked, but until `Ir2Mir` lowers context parameters / inline lambdas
  / allocator objects, the ones using them do not build natively; programs that do build run with the Managed
  (reference counting) code generation. Allocation through `mk_alloc_with`, `mk_free` for `free()` and
  `mk_arena_*` for arenas (all in `minirt.h`) is the next step.

## v1-L: lambdas, generics, exceptions and the rest of the core language

Everything in this section is implemented by `Ir2Mir*.kt` (bridge) and the MIR/emitter additions
(`Try`, `Throw`, `CaughtException`, `TypeInfoOf`, external classes, `ThrowAnalysis`), plus the runtime
block `/* ---- v1-L ---- */` in `minirt.h` / `src/v1l.c` (tests: `tests/test_v1l.c`).

### Source layout of the bridge

| File | Concern |
|---|---|
| `Ir2Mir.kt` | state, `convert()` (passes), file/class bookkeeping |
| `Ir2MirClasses.kt` | classes, enums (also `values()`/`valueOf()`), vtables/itables, bridge methods |
| `Ir2MirDeclarations.kt` | function bodies, statements, constructors, fields |
| `Ir2MirControlFlow.kt` | loops, `for` over ranges, `when` |
| `Ir2MirExpressions.kt` | operand ordering, values, type operators, string templates |
| `Ir2MirCalls.kt` | calls, dispatch, primitives, console, comparisons |
| `Ir2MirTypes.kt` | Kotlin type to MirType (erasure of type parameters) |
| `ClosureAnalysis.kt` | free variables of lambdas / local functions / local classes, boxed `var`s |
| `Ir2MirClosures.kt` | function types, lambdas, function references, local functions, Ref cells |
| `Ir2MirInline.kt` | in-place expansion of `inline fun` and of lambdas passed to them |
| `Ir2MirExceptions.kt` | built-in exception classes, `throw`, `try`/`catch`/`finally` |
| `Ir2MirArrays.kt` | arrays, `vararg`/spread, `for` over arrays |
| `Ir2MirTopLevel.kt` | top-level properties (file facade object) |
| `Ir2MirLocalClasses.kt` | inner / local / anonymous classes |
| (removed) | the planned `Intrinsics.kt` stopgap is gone: `let`/`run`/`require`/`error`/... now come from `stdlib/` as inline functions with bodies |

### Lambdas and function types

* A function type `(A, B) -> R` (also `A.(B) -> R`, `KFunctionN`) is the MIR interface `kotlin.FunctionN` with
  one method `invoke(Any?, ...): Any?`. The erased ABI means primitives are boxed (`mk_box_*`) across a function
  value call and unboxed on the other side; `Unit` results are `null`.
* Every lambda literal / function reference gets its own class `$closure<N>` implementing that interface. Closure
  objects are ordinary reference-counted objects: one field per captured value, `dispose` (generated like for
  any class) releases the captures, allocation goes through `MirExpr.New` (the normal placement path).
* Captured `val`s and parameters are **copied** into the closure. A captured `var` lives in a heap cell (`Ref<T>`
  class with one field `value`, one class per MIR type) that the declaring function and all closures share; the
  analysis (`ClosureAnalysis`) boxes exactly the `var`s that some closure captures.
* Local functions are **lambda-lifted**: a C function whose leading parameters are the captured values (cells for
  `var`s), calls are direct (recursion and mutual recursion work). `::local` creates a closure object.
* Function references: top-level, member (`obj::m` bound, `Type::m` unbound), constructors, local functions.
  Property references are unsupported (except `::lateinitProp.isInitialized`).
* `fun interface` / SAM conversions are not supported.

### Inline functions

K2 keeps calls of `inline fun` in the IR. A call of an inline function whose IR has a **body** (all user code, later
the stdlib compiled from source) is **expanded in place** (`Ir2MirInline`): the body is lowered in the caller's
function context with the parameters bound to fresh locals. A lambda literal passed for an inlinable parameter is
not turned into an object: `block(x)` expands the lambda body at the call, so captured variables are used
directly (no cell), `return` inside it is a **non-local return** from the enclosing function, and `reified` type
parameters work because the type arguments are substituted (`typeSubst`). `return` from the inline function
itself assigns a result local and leaves the expansion through a labelled loop (`Break`). Inline functions are
also compiled as ordinary functions (taking function objects) so they can be used as values; functions with
`reified` parameters only exist expanded. Limits: an inline lambda parameter may only be *invoked* (or forwarded
to another inline function), not stored or passed to a non-inline function (use `noinline`); `crossinline`
lambdas are compiled as closures.

### Generics

Reference type arguments share one implementation (type parameters are erased to their first supported bound,
else `Any?`), primitive type arguments are boxed (`mk_box_*`), `out`/`in` are frontend-only. Consequences
implemented in the bridge:

* a call whose declared (erased) result type differs from its Kotlin type (`Box<Int>.get(): T` used as `Int`) is
  unboxed / retyped (`adaptResult`), arguments are boxed to the erased parameter types;
* an override whose erased C signature differs from the vtable/itable slot (`override fun push(x: Int)` of
  `fun push(x: T)`, covariant returns) gets a small **bridge function** with the slot's signature;
  virtual and interface calls are compiled against the signature of the slot's root declaration so every
  function pointer type matches its definition exactly (clang `-fsanitize=function` clean);
* `T : Comparable<T>`: `a.compareTo(b)` calls `mk_compare_to` (boxed primitives and strings natively, other
  objects through the `Comparable` itable; `Comparable` is compiled like a user interface when a class
  implements it);
* `reified` only through inlining (above).

### Exceptions (error-return ABI)

* A function that throws leaves a non-NULL owned reference in the thread-local `mk_pending_exception` and returns
  a dummy value (0 / NULL). After every call that **can throw**, generated code tests the variable (GNU statement
  expression `({ T t = call; if (mk_pending_exception != NULL) { <exit> } t; })`) and takes the **exceptional
  exit**: release all locals of the scopes it leaves (the same scope machinery as `break`/`return`) and jump to
  the innermost `try`'s handler label, or return the dummy value from the function. Because locals are
  NULL-initialised at scope entry, the leak checker stays clean on exceptional paths.
* `ThrowAnalysis` (backend) computes which functions can throw (fixpoint: contains `throw`, a raising operation
  such as a checked cast / `!!` / division by a non-constant / array access, a call of a throwing function, or a
  dynamic call while some vtable/itable function throws), so numeric code has no checks. Constructors' `_new`
  wrappers release the half-built object when the initialiser throws.
* `MirStmt.Try(body, catches)`: handler = `mk_catch()` (takes ownership of the pending exception) followed by an
  `mk_is_instance` chain; no match rethrows (`mk_rethrow`). `finally` is lowered by the bridge: a catch-all clause
  that runs the finalizer and rethrows, a copy of the finalizer on the normal path, and copies before every
  `return`/`break`/`continue` that leaves the `try` (the returned value is computed first).
  `try` is an expression (`tryValue`).
* Exception classes are provided by the runtime with the `MkThrowable` layout `{message, cause}`:
  Throwable, Exception, RuntimeException, Error, IllegalStateException, IllegalArgumentException,
  NullPointerException, ArithmeticException, ClassCastException, IndexOutOfBoundsException,
  ArrayIndexOutOfBoundsException, StringIndexOutOfBoundsException, UnsupportedOperationException,
  NoSuchElementException, NumberFormatException, UninitializedPropertyAccessException, NotImplementedError,
  NegativeArraySizeException, AssertionError (JVM names, so `e.toString()` matches). They are `external` MIR
  classes: user classes extend them (`struct MkException base;`), vtable slot 3 is `Throwable.message`
  (overridable); members `message`, `cause`, `toString`, `printStackTrace()` (just `toString()` to stderr).
* Failures that used to abort are now exceptions: `/ by zero` (`ArithmeticException`), `!!` and unboxing of
  null (`NullPointerException`), failed `as` (`ClassCastException`), `IndexOutOfBounds`, lateinit access,
  `error()`, `require`, `check`, `requireNotNull`, `checkNotNull`, `TODO()`, `Enum.valueOf`.
* An exception escaping `main` is reported by the generated `mk_main` wrapper: stdout is flushed, then
  `Exception in thread "main" <toString>` on stderr (no stack trace), exit status 1.
* Gaps: no stack traces; an exception thrown by a `finally` block that runs on a `return` path is caught by the
  same `try`'s catch-all (the finalizer would run twice); exceptions inside object/file-facade initialisers
  propagate lazily; `suppressed` exceptions are not recorded.

### Stdlib integration

The stdlib sources have IR bodies, so `let`, `repeat`, `require`, `error`, `listOf`, `map`, ... are ordinary functions: inline
ones are expanded by `Ir2MirInline`, the rest are compiled as top-level functions (generics by erasure, lambdas as closures,
`vararg` as arrays, `fun interface Comparator` through SAM conversion, `by lazy` through delegated-property accessors).
The throw seam `mini.internal.throwXxx` is now plain Kotlin (`throw IllegalStateException(message)`). The exception classes in
`stdlib/kotlin/Throwable.kt` are *replaced* by the runtime-provided classes (`isRuntimeProvided`): their source declarations
are not compiled, constructors and members map to `MkThrowable` & co. Additions made to `stdlib/` for the backend:
`kotlin/Arrays.kt` (`arrayOf` family, `contentToString`, `lastIndex`, `String?.plus`, `isInitialized` marker),
`kotlin/ArrayIndices.kt` (Builtin file: `array.indices`, only pattern-matched in `for`), more `Throwable` subclasses and
constructors. `Comparable` and `CharSequence` are erased (`Any` / `String`); `compareTo` on such values is `mk_compare_to`.

### Arrays, `vararg`, enums

* `Array<T>` (references, erased; primitives boxed), `IntArray`, `LongArray`, `DoubleArray`, `BooleanArray`,
  `CharArray`: `MkArray` runtime objects (`mk_array_*`), bounds-checked (`ArrayIndexOutOfBoundsException`),
  `size`, `get`/`set`, `indices`, `lastIndex`, `IntArray(n)`, `Array(n) { }`, `arrayOf`, `intArrayOf`, ...,
  `arrayOfNulls`, `emptyArray`, `contentToString()`, `for (x in array)`, `for (i in array.indices)`.
  Other array members (`copyOf`, `sum`, ...) belong to the stdlib.
* `vararg xs: T` is an array parameter; call sites build the array, `*spread` copies (`mk_array_copy_into`).
* Enums: `values()`, `valueOf()` (throws `IllegalArgumentException: No enum constant ...`), `entries` (an array
  here: supports `for`, `size`, indexing), `name`, `ordinal`, `compareTo`.

### Other language features

* Extension functions and properties (top-level and member), local functions, default arguments in all of them.
* Top-level properties (`val`/`var`, initialisers, custom accessors, `lateinit`): the properties of a file are
  fields of a hidden singleton (`<File>Kt`, the file facade); accessors are ordinary functions. Initialisers run
  **eagerly in textual order before `main`** (forced at the start of `main`), as on the JVM.
* `lateinit` (members, top-level, locals): reading an unassigned one throws `UninitializedPropertyAccessException`;
  `::p.isInitialized` works.
* Inner classes (hidden `$outer` field, `this@Outer`), local classes and anonymous objects (`object : I {}`,
  `object { ... }`): captured values are hidden fields passed as extra constructor arguments (cells for `var`s).
* `Any()`.

## Known gaps

* The stdlib (`stdlib/`, see `docs/stdlib.md`) is compiled natively from source with the user program: collections,
  `map`/`filter`/`joinToString`, `StringBuilder`, `lazy`, `Pair`, ... work (`stdlib_collections`, `stdlib_maps` tests).
  Gaps of the stdlib itself (ranges as iterables, `entries`-style APIs, JDK message wording) are not backend issues.
  The stdlib's hash maps keep before/after links (reference cycles) and therefore leak under RC
  (`stdlib_maps.noleakcheck` excludes that test from the leak check).
* No property references (`::prop`), `suspend`/coroutines, type aliases are ignored (no code),
  `Byte`/`Short`/`Float`, `value class`, annotations with arguments, `fun main(args)`, enum entries with bodies,
  `Unit?`, local `object` declarations, nested classes inside local classes. SAM conversion works for `fun interface`s
  (lambda literals and function values), not for Java interfaces.
* A lambda parameter of an `inline` function may only be invoked or forwarded to another inline function.
* Unit values boxed in generic code are `null` (`println(listOf(f()))` of a Unit lambda prints `null`).
* Reference cycles leak (no cycle collector): e.g. a closure stored in a field of the object it captures.
* `>>` on negative Int/Long relies on arithmetic shift (clang/gcc behaviour, implementation-defined in C17).
* The generated C uses GNU statement expressions (clang and gcc; not MSVC).
* The Gradle build resolves from `repo1.maven.org` first (same content as Maven Central);
  this only matters in environments where `repo.maven.apache.org` is rate limited.

## Bootstrap B1: serialized MIR and the native driver

`minic emit-mir <file.kt>... -o out.mir` runs the frontend and writes the MIR as text (`MirWriter` in
`compiler/backend/.../MirSerial.kt`; `MirReader` reads it back). The format is versioned (`(mini-mir 1 ...)`),
S-expression based, ASCII only, one class/function/statement per line; the format description is the header
comment of `MirSerial.kt`. `MirSerialTest` checks for every `compiler/testdata` program that MIR -> text -> MIR -> C is
byte-identical to MIR -> C and that `native-driver` (JVM run) produces the same C from the `.mir` file.

`compiler/native-driver` (`fun main(args: Array<String>)`): `native-driver in.mir -o out.c`. Its file access is the Mini
stdlib API `mini.io.readTextFile/writeTextFile` (`stdlib/mini/io/Files.kt`, intrinsics `mk_b1_*` in the B1 block of
`minirt.h`, `runtime/src/b1.c`); on the JVM the Gradle build compiles `Files.kt` together with
`stdlib-test/jvm/mini/internal/B1Intrinsics.kt`. `fun main(args: Array<String>)` is supported by the compiler (the C
entry wrapper takes the array from `mk_b1_args()`).

## v1-A: the backend compiles and runs natively

`minic` compiles its own backend: `./minic build compiler/selfhost/SelfMain.kt $(find compiler/backend/src/main/kotlin -name '*.kt') -o build/selfhost`
(about 10 s plus clang) gives a native program that prints exactly what the same driver prints on the JVM.

* `compiler/selfhost/SelfMain.kt` (package `selfhost`) drives the pure-Kotlin backend: handcrafted MIR modules (every operator,
  conversion, loop form, class/itable/object, cast, box, exception construct), a few hundred pseudo-random well-formed MIR
  functions (deterministic LCG), and handcrafted plus pseudo-random MemIr programs for `MemoryChecker`. The output is ~25k
  lines (C code and diagnostics).
* `SelfHostTest` (jvm-bridge, part of `gradle test`, the v1 gate) has three parts: (1) the driver on the JVM (compiled by the real
  Kotlin compiler as part of the test source set) versus the natively compiled one, byte-identical, clang `-Werror`; (2) the same
  native build with ASan/UBSan: no memory error, no UB, no direct leak (reference cycles, e.g. `LinkedHashMap` nodes, show up as
  *indirect* leaks only and are tolerated); (3) the B1 acceptance test: `native-driver` + backend are compiled into a native
  `minic-backend`; for every `compiler/testdata` program `emit-mir` -> native `minic-backend` -> `.c` is byte-identical to what
  the JVM `CEmitter` makes of the same MIR.
* What it took in the compiler: `for (i in range)` over a range *value* and `a..b` as a value go through real `IntRange`/`LongRange`/
  `CharRange` classes (literal `for`/`in` shapes are still pattern-matched, no object); `Foo::class` and `x::class` give a `KClass`
  with `simpleName`/`qualifiedName` (from the type info name; no other reflection); expanding an inline function inside
  itself (`xs.any { ys.any { } }`) keeps the outer `return` target. Everything else was stdlib API (see `docs/stdlib.md`, section 9).

## Bootstrap B2-1: own frontend (lexer, parser, AST)

`compiler/frontend` (`mini.frontend`, pure Kotlin, compiled by minic itself): `lexKotlin` (all tokens, trivia kept, offset/line/col,
nested string templates, raw and multi-dollar strings), `parseKotlin` (hand-written recursive descent, newline rules of the
reference PSI parser, error recovery with positions) giving a lossless CST (`printCst` reproduces the source byte for byte) and a
typed AST (`Ast.kt`, sealed classes), and `Outline` (canonical text of every declaration and fully parenthesised expression).

Tests (`frontend` + `frontend-oracle`, all in `gradle test`): lossless + error-free parse of every `.kt` in the repo; the
Outline of our AST equals the same rendering of K2's PSI tree on the repo sources, ~230 tricky snippets, 30k random expressions,
20k random declarations and ~12k mutated repo files (whenever PSI accepts them); malformed input (positions, first error line vs PSI,
mutation/truncation fuzz, hostile nesting); native self-compilation (`FrontendSelfHostTest`): frontend + driver built by minic
(`-Werror`, ASan/UBSan clean), output identical to the JVM on all repo sources.

Known gaps: no semantic checks; no `.kts` script statements; PSI quirks not modelled (declaration used as operand, `>>=`, etc.);
non-ASCII characters are all treated as identifier characters; recovery is coarse (skip to next declaration/statement).
Native parse speed is ~70k lines/s against ~900k (JVM warm), dominated by reference counting traffic.


## Bootstrap B2-2: own semantic analysis (resolution and type checking)

`mini.frontend.sema` (in `compiler/frontend`, pure Kotlin, compiled by minic itself) resolves names and computes the type of every
expression, plus the callee of every call and reference, for the Kotlin subset that the compiler, the standard library and the test
programs use. The standard library is just another input (the `@file:kotlin.internal.Builtin` sources declare `Any`, `Int`, `String`, ...),
exactly like in the K2 run of `jvm-bridge`. Entry point: `analyzeSources(listOf(SourceInput(path, text), ...))` returns a `Sema`
(`records` hold type and callee per AST node, `diags` the diagnostics, `dumpText(file)` the canonical text used by the tests).

| File | Concern |
|---|---|
| `Types.kt` | type model: class types with variance projections, type parameters (also `T & Any`), function types with receivers, intersection (smart casts), captured types, integer literal types; canonical rendering |
| `Symbols.kt`, `Collector.kt` | symbols (classes, functions, properties, type parameters, enum entries, typealiases), scopes (file / class / type parameters / local / receiver), pass 1 that creates the symbols from the AST |
| `Sema.kt` | package tables, imports and default imports with K2's priority order, type resolution (typealias expansion, function types, qualified types), supertypes, lazily resolved signatures |
| `TypeOps.kt`, `Members.kt` | substitution, supertype closure, subtyping with variance, common supertype, smart-cast narrowing; member lookup with override filtering, data/enum synthesized members (`componentN`, `copy`, `values`, `valueOf`, `entries`) |
| `Infer.kt` | constraint system per call (lower / upper / equality bounds, incorporation, fixation); simplified from K2's `NewConstraintSystemImpl` / `ResultTypeResolver` |
| `Calls.kt`, `Names.kt` | candidate collection per scope level (locals, implicit receivers incl. companions and extension receivers, imports), argument binding (named, default, vararg, trailing lambda), applicability, most specific candidate, completion with postponed lambdas (simplified `FirCallCompleter` / `ConeOverloadConflictResolver`), operator conventions, `invoke`, SAM constructors |
| `Exprs.kt`, `Lambdas.kt`, `DataFlow.kt` | expression and statement checking, string templates, `if` / `when` (exhaustiveness for sealed, enum, Boolean) / `try` / loops, elvis, `!!`, safe calls, lambdas with expected-type inference, callable references, anonymous objects; flow-sensitive smart casts (`is`, null checks, `&&` / `||` / `!`, early exit, assignments, loop kills, boolean aliases) |
| `Declarations.kt` | class bodies (super constructor calls, init blocks, enum entries, secondary constructors), implicit return / property types with cycle detection, accessors and delegates |

Unsupported constructs (unsigned literals, `dynamic`, local typealiases, ...) produce an `unsupported: ...` diagnostic and an error type, never a
guessed type. Resolution problems produce `unresolved reference` / `type mismatch` diagnostics.

### The oracle

`frontend-oracle` runs K2's FIR resolution on the same inputs (the `FrontendDriver` configuration: our stdlib sources, `-no-stdlib -no-jdk
-Xallow-kotlin-package`; a FIR file checker dumps the resolved type of every expression / declaration and the fully qualified signature of
every callee, with fake overrides unwrapped, see `FirOracle.kt` / `FirDumper.kt`) and compares it position by position (source range) with our
records (`SemaOracle.kt`). `SemaOracleTest` writes `build/reports/sema-oracle.txt` (agreement per file, area and expression kind, first mismatches,
top mismatch categories), `sema-oracle-mismatches.txt` (all mismatches) and `sema-oracle-diagnostics.txt` (our diagnostics on code that K2 accepts, must be empty).
`src/test/resources/sema-oracle-baseline.txt` is the checked-in baseline: no file may lose positions, and our analysis may not report
any diagnostic on the corpus. `MINI_SEMA_UPDATE=1` rewrites the baseline, `MINI_SEMA_AREAS=testdata,stdlib,...` selects areas. K2 results are cached
in `build/sema-oracle-cache` (keyed by the content of all inputs).
Developer tools: `SemaProbeTest` (env `MINI_FIR_PROBE=<file>`, with `MINI_SEMA_COMPARE=1` or `MINI_SEMA_DUMP=1`).

Agreement (positions where K2 resolved a type or callee / positions): all of `compiler/testdata/*.kt`, `stdlib/**`, `compiler/backend`,
`compiler/native-driver`, `compiler/frontend/src/main` (including this analysis), `compiler/selfhost`, `examples` and `compiler/testdata/memory*`:
**86658 of 86668 (99.99%)**. Mismatches left: blocks (comment attached to a block), `x ?: y` with an intersection least upper bound,
smart casts after loops that reassign a variable.

### Native

`SemaSelfHostTest`: minic builds the frontend including `mini.frontend.sema` and the driver (`--sema <inputs> --report <files>`) with `-Werror`;
the native dump of every test program (analysed together with the stdlib) and of stdlib + backend + native driver + frontend sources
(77k dump lines) is identical to the JVM run, and an ASan/UBSan build reports nothing. Speed on 22k lines (parse, resolve, type check, dump): about 1.2 s on
the JVM (cold), 0.5 s warm, 1.7 s native.

Known gaps: only the subset the corpus needs (no Java interop, no coroutines / `suspend` typing beyond the type, no contracts, no context
receivers beyond function types, no `kotlin.js` / multiplatform `expect`/`actual`); inference is a simplified constraint solver (no PCLA, arguments are resolved
independently of the callee except for single candidates); K2's flexible loop analysis for smart casts is approximated by killing facts of reassigned variables.

## Bootstrap B2-3: MIR from our own frontend (no K2)

`minic build|emit-mir|check --frontend=mini` (the default since the memory checker runs on it too; `--frontend=k2` selects K2, `--no-stdlib` implies K2) runs parser + sema (B2-1/B2-2), `Ast2Ir` (builds the IR shapes K2's fir2ir
produces: declaration order, fake overrides with substituted signatures, `tmpN_*` temporaries, origins, smart-cast and SAM wrappers, constant
folding), `Reachability` and the ported `Ir2Mir` (all in `compiler/lower`, pure Kotlin) and then the unchanged backend. `compiler/minic-native` wraps
this in a `main(args)` (`minic-native [-o out.c] [--emit-mir out.mir] <user.kt>... --stdlib <files>...`).

Tests: `MiniFrontendTest` (jvm-bridge) checks, for every `compiler/testdata/*.kt`, that the MIR and the C of the own frontend equal the K2 path
byte for byte, and that the programs built from it print the expected output. Status: 47 of 47 programs identical (plus the `type_error` diagnostic).

Native: minic compiles `minic-native` (frontend, lowering, backend, driver) without K2 on the way; the binary reproduces the MIR (hence the C) of the K2
path for all 47 programs, the programs run correctly, and the binary compiles its own sources in about 5 s. The C it produces for its own sources
builds and again reproduces 47 of 47 programs. Not byte-identical yet: the MIR for the self-compilation differs from the K2 path in about 250
of 96,700 lines (K2 flow-sensitive smart casts the analysis does not model, e.g. through `val b = x != null && ...`, `as?` with `?: error`, and
some `tmpN` numbering inside lambdas).

### B3: bootstrap

`compiler/bootstrap.sh`: JVM `minic --frontend=mini` builds the compiler (stage1), stage1 compiles the compiler sources to C (stage2),
stage2 does the same (stage3); the C and MIR of stage2 and stage3 must be byte-identical (they are). Wall times on the dev box: stage1 89 s (JVM),
stage1 compiling the sources 4.4 s, each clang build about 75 s. `compiler/selfcompare.py` compares the self-compile MIR of K2 and of the own
frontend after normalising local ids and `tmpN` numbers (41 lines of 96,774 differ, plus 16 allowlisted flow-fact casts; the count is checked against
`selfcompare-baseline.txt` and must not grow).

### Releases

`.github/workflows/release.yml` (tag `v*` or manual run with a version) bootstraps on linux-x64, linux-arm64 and macos-arm64, packages
`scripts/package-release.sh` and publishes to GitHub Releases:

| Asset | Contents |
|---|---|
| `mini-<version>-<target>.tar.gz` | `bin/minic` (shell driver `scripts/minic.sh`: `build`, `run`, `check`, `emit-c`, `--version`; runs `bin/minic-native`, then `cc` with the bundled runtime), `bin/minic-native` (the self-hosted compiler, -O2), `lib/libminirt.a`, `include/`, `stdlib/`, `LICENSE`, `NOTICE`, `README.md` |
| `mini-<version>-bootstrap-c.tar.gz` | the generated C of the compiler (`minic.c`), `runtime/`, `build.sh`: `cc` alone builds a stage0 (`scripts/package-seed.sh`) |
| `SHA256SUMS` | checksums of the above; `install.sh` verifies them |

The `minic` driver is a shell script (`scripts/minic.sh`, copied into the tarball with `VERSION` and `TARGET`): the native stdlib has no process API yet, so `minic-native` only
emits C and the script calls `cc`, finds a C compiler (`--cc`, `CC`, `cc`, `clang`, `gcc`; otherwise it explains how to install one) and turns a C compiler error on the
generated C into an "internal compiler error". `minic-native` itself prints diagnostics to stderr (`--color=`, `--error-format=`, `--error-limit=`; the stdlib has the
intrinsics `stderrWrite` and `exitProcess` for that) and exits with 0, 1 (compile error), 2 (usage) or 3 (internal compiler error). `scripts/test-minic-cli.sh <MINI_HOME>` tests the CLI.

`compiler/bootstrap.sh --stage0 <native minic>` replaces the JVM stage1 by a prebuilt native compiler (a release's `bin/minic-native` or the stage
built from the seed): stage0 compiles the current sources, then stage1, stage2, stage3 follow and stage2 == stage3 is checked. It builds at -O0
by default (clang takes 9 s instead of 73 s per stage), so the whole check takes about a minute without Gradle or the JVM. CI runs it as the
`stage0` shard (`scripts/ci-stage0.sh`: downloads the latest release for the platform, verifies its SHA256, falls back to the JVM stage1 when there
is no release, the download fails or stage0 cannot build the sources); the JVM-stage1 `bootstrap` shard (guards the K2 path) runs on main.

## LLVM backend

`compiler/backend/.../llvm` (package `mini.backend.llvm`, pure Kotlin, scanned by `PurityTest`, compiled by minic like the rest of the backend) is the
second code generator: MIR to **textual LLVM IR** (`.ll`), an alternative to `CEmitter` (docs/plan-kotlin.md section 10g, phase 1). Phase 2
(not done) replaces the text by the in-process LLVM C API and ORC JIT and reuses the lowering.

```
MIR ─► RcOpt (RcPlan) ─► LlvmEmitter ─► prog.ll ─► clang (-c) ─► prog.o ─► clang + libminirt (runtime/src/*.c) ─► binary
```

```sh
minic build hello.kt -o build/hello --backend=llvm [--emit-ll hello.ll] [--cc clang] [--link-cc gcc] [--stats] [--no-rc-opt] [--cflag -O2]
native-driver hello.mir --backend=llvm -o hello.ll [--no-rc-opt] [--stats]       # MIR text to IR (JVM or native `minic-backend`)
minic-native <user.kt>... --emit-ll out.ll --stdlib <files>...                    # the compiler without K2: sources to IR
```

`--cc` compiles the `.ll` to an object file (`clang -O1 -Wno-override-module`), `--link-cc` (default: `--cc`) compiles `libminirt` and links. With a
sanitizer, clang instruments the IR and gcc can link `libasan` when clang has no sanitizer runtime (the situation on the dev box). `--stats` makes the
emitter call the runtime for every retain/release (`MK_STATS`) and compiles the runtime with `-DMK_STATS`.

**What is identical to `CEmitter`** (the emitter is a statement-by-statement port that consumes the same `RcPlan`, `ThrowAnalysis` and `Mangling`):
object layouts and headers, `MkTypeInfo`, class vtables (slots 0-2 toString/equals/hashCode, then user slots, then one itable pointer per interface),
itables, `dispose`, `<ctor>_new`, lazy `object` singletons, boxing, strings (`static const MkString` literals), RcOpt (borrowed locals, moves, owned
constructor parameters, self takes, closed moves, fresh stores), the error-return exception ABI (`mk_pending_exception` test after every call that
`ThrowAnalysis` says can throw, cold exit blocks that release the scopes they leave, `Try` as a handler block with `mk_catch`/`mk_is_instance`/`mk_rethrow`),
Kotlin integer semantics (wrapping `add/sub/mul`, `mkh_div_*` helpers, masked shifts, saturating `fptosi.sat` for Double to Int/Long, `frem` for `%`), the
`mk_main` wrapper, and `fun main(args)`.

**How it differs.**
* Locals are `alloca`s in the entry block (LLVM's mem2reg/SROA makes them SSA). Reference locals are NULL-initialised at the top of their scope exactly as in C.
* No data layout is assumed: struct layouts are computed in `LlvmTarget.kt` (natural alignment, a subclass embeds the superclass struct including its tail
  padding, so a subclass field starts at `sizeof(super)`) and every field access is a byte-offset `getelementptr`. `LlvmTarget` has the pointer size (8, or 4
  for `wasm32`), so the same lowering serves ILP32 targets. The type infos are `<{ ptr, size_t, ptr, ptr, ptr, size_t, ptr, size_t }>` constants.
* The C ABI is spelled out: `bool` and `MkChar` are `zeroext i1` / `zeroext i16` on parameters and results; runtime functions are declared from the MIR types of
  their first use (`declare i32 @mk_string_hash(ptr)`), vtable / itable entries that are runtime functions (`mk_throwable_message`, `mk_any_to_string`) are declared
  by address only.
* Retain/release fast paths (`MKH_RETAIN` / `MKH_REL` in C) are `alwaysinline` helper functions `@mkh_retain` / `@mkh_rel` in the module (so `-O0` inlines them too);
  cold exit paths and `--no-rc-opt` call `mk_release` directly. `MkObject` accesses use offsets (type 0, refcount `ptr`, flags `ptr+4`).
* Double constants are Kotlin's shortest round-trip text (LLVM rounds a decimal literal correctly; NaN and infinities are hex), so no `toRawBits` is needed.
* Branch weights (`!prof`) mark the pending-exception tests and exit blocks as cold. Every function has `nounwind` (error-return ABI, no unwinding)
  and `sanitize_address`, so `clang -fsanitize=address -c prog.ll` instruments the generated code (UBSan needs no instrumentation: wrapping arithmetic and explicit
  division checks leave no undefined behaviour to detect).
* Unlike C, an unreachable block after `return`/`break` is just a block without predecessors; the `goto`-style labels of `Break`/`Continue` are blocks `brk_N` / `cont_N`.

**Tests.** `backend` `LlvmEmitterTest` (IR shape), `PurityTest.llvmBackendIsScannedToo`; `jvm-bridge` `LlvmEndToEndTest`: every program of
`compiler/testdata` through `--backend=llvm` (same `.out`/`.err`/`.cerr` expectations and `-Werror` as `EndToEndTest`), the leak check (ASan/LSan/UBSan; the
runtime with sanitizers, the IR instrumented by clang) and the `--stats` / `--no-rc-opt` variants; `SelfHostTest.nativeMinicBackendOnAllTestPrograms` additionally
requires the natively compiled backend's `--backend=llvm` output to equal the JVM's byte for byte. `compiler/bootstrap.sh --backend=llvm` is the self-host check
(below). `compiler/bench/run-llvm.sh` compares the two backends (below).

### LLVM C API binding and the in-process backend (phase 2, step 1)

The lowering is written once against `IrBuilder<V, B>` (`backend/.../llvm/IrBuilder.kt`: scalar types, constants, globals, functions, blocks and the
instruction kinds the lowering needs). `Lowering.kt` is the former `LlvmEmitter`; `TextIrBuilder` prints the textual IR (`LlvmEmitter.emit`, unchanged
output except the order of some external type-info declarations and an explicit `entry:` label on the generated helper functions) and `ApiIrBuilder`
(`compiler/llvm-c`) builds the module through the LLVM C API. Both are pure with respect to the lowering; only the API builder needs C interop, so it is
not part of the Gradle build (the JVM cannot run `external fun`): it is compiled by `minic` into the native compiler.

```
MIR ─► RcOpt ─► Lowering ─► ApiIrBuilder ─► LLVMModuleRef ─► verify ─► LLVMRunPasses(default<O1>) ─► TargetMachine ─► prog.o ─► system linker
                                                          └─► ORC LLJIT (minic-native --run): no file, no link
```

`compiler/llvm-c/src/main/kotlin`:
* `mini/llvm/Core.kt`, `Backend.kt`, `Orc.kt`: the **bindings** of LLVM 18 (`@file:CInclude("llvm-c/Core.h")`, `@file:CLink("LLVM-18")`, `external fun` + `@CName`, handles are
  `COpaquePointer` typealiases under their C names, `@Owned(free = "LLVMDispose...")` on everything LLVM hands out). Covered: contexts, modules (name, target, data
  layout, print, named lookup), types (ints, float/double, `ptr`, void, function, array, packed and named struct), constants (int, real, null, string, array,
  struct), globals (linkage, constant, unnamed_addr, alignment, thread_local, initializer), functions (params, attributes by name, call-site attributes, intrinsic
  declarations), basic blocks, the whole instruction builder the lowering uses (arithmetic, shifts, compare, load/store with alignment, GEP, casts, phi, select, call,
  branches, `unreachable`, `freeze`), metadata for `!prof` branch weights, the verifier (`LLVMVerifyModule`), the bitcode writer, targets (x86 and AArch64 initializers: the
  `LLVMInitializeNativeTarget` family is `static inline` in the header), target machines and object emission, `LLVMRunPasses` (new pass manager, `opt -passes=` strings), and
  ORC: `LLVMOrcCreateLLJIT`, thread-safe contexts and modules, process-symbol generators, lookup. Not bound: debug-info metadata, vectors, atomics, inline asm, comdats.
* `mini/llvmapi/ApiIrBuilder.kt`, `LlvmApiEmitter.kt` (build, verify, optimize, emit object), `LlvmJit.kt`, `LlvmApiImpl.kt` (the `LlvmApi` of the driver), `MainApi.kt`.

```sh
# the compiler with the API: minic-native without Main.kt + llvm-c, built by minic (needs the LLVM 18 headers and libLLVM: llvm-config)
minic-native --backend=llvm-api -o prog.o [--opt=O0..O3] [--emit-ll prog.ll] [--emit-c prog.c] <user.kt>... --stdlib <stdlib.kt>...
minic-native --backend=llvm-api --exe prog [--link-arg libminirt.o ...] [--cc clang] ...      # object + system linker (embedded lld is a later step)
minic-native --backend=llvm-api --run [--time] [--opt=O1] <user.kt>... --stdlib <stdlib.kt>... [-- args]   # ORC JIT, in this process
```

**JIT and the thread-local pending exception.** ORC's loader cannot resolve the TLS relocation of `mk_pending_exception` in generated code (the reason `lli` was dropped).
The runtime now exports `MkObject **mk_pending_slot(void)` and `LlvmTarget(pendingAccessor = true)` makes the lowering test `*mk_pending_slot()` instead of the TLS
global (declared `memory(none)`: LLVM computes it once per function). This is a target option of the code generator, not a JIT special case: any target whose loader has no TLS
(wasm32 without threads, a JIT) uses it; ordinary builds keep the direct thread-local access. JIT'd code finds `mk_*` in the compiler's own process (a Mini program contains
libminirt; the compiler is linked with `-rdynamic`), so script and compiler share one runtime.

**Tests.** `LlvmApiEndToEndTest` (builds the API compiler, runs all of `compiler/testdata` through `--backend=llvm-api` with the C build's expectations, skipped without LLVM
headers); `bootstrap.sh --backend=llvm-api` (stage2 == stage3: C, MIR, module text and object file byte-identical).

### LLVM backend: status, measurements, stretch goals

**Parity.** 53/53 programs (47 + the six `strict_*` ones) of `compiler/testdata` build with `--backend=llvm` and give the same stdout/stderr/exit code as the C backend (plus the `type_error`
diagnostic), also with `--no-rc-opt`, `--no-strict`, `--no-conv` and `--stats`; all 48 leak-check programs are clean under ASan/LSan/UBSan. No known gaps against the C backend. Still C-only / not
Strict mode (D2) is covered: tracked values without counts, `_newa` allocator constructors (`mk_alloc_with`, `mk_discard` on a failing initialiser), `mk_free_owned` in dispose glue, borrowed results, owned parameters of virtual/interface calls and of `mk_ref_array_set_o`, `Any` casts reduced to the null check (`mkh_nn`).
done: debug info (`!dbg`), TBAA metadata (C gets it from clang), real unwinding (the error-return ABI is kept on purpose).

**Self-host via LLVM** (`compiler/bootstrap.sh --backend=llvm --reference <C bootstrap dir>`): stage1 = JVM `minic --frontend=mini --backend=llvm`; stage2/3 are built from
the `.ll` the previous stage writes (`minic-native --emit-ll`). C, MIR and IR of stage2 and stage3 are byte-identical, and the C and MIR equal those of the C-built chain. Wall
times on the dev box: stage1 65 s (C: 81 s), clang on the compiler's IR 60 s (clang on its C: 75-79 s), compiling the sources 6 s.

**Performance** (`compiler/bench/run-llvm.sh`; same MIR, same RcOpt plan, same clang `-O` level, same libminirt, best of 5, ms; ratio LLVM/C):

| benchmark | -O0 | -O1 | -O2 | -O3 |
|---|---|---|---|---|
| arraylist | 300 / 298 (0.99) | 251 / 245 (0.98) | 252 / 232 (0.92) | 254 / 244 (0.96) |
| hashmap | 89 / 81 (0.91) | 75 / 74 (0.99) | 72 / 80 (1.11) | 71 / 73 (1.03) |
| mapfilter | 172 / 172 (1.00) | 145 / 149 (1.03) | 150 / 149 (0.99) | 152 / 143 (0.94) |
| stringbuilder | 72 / 66 (0.92) | 58 / 56 (0.97) | 56 / 57 (1.02) | 57 / 61 (1.07) |
| templates | 116 / 111 (0.96) | 109 / 115 (1.06) | 110 / 110 (1.00) | 114 / 111 (0.97) |
| tree | 79 / 72 (0.91) | 61 / 64 (1.05) | 63 / 61 (0.97) | 65 / 65 (1.00) |
| selfhost (minic-backend, 6.6 MB of C) | 969 / 888 (0.92) | 754 / 735 (0.97) | 748 / 705 (0.94) | 731 / 701 (0.96) |

Run time is at parity (noise is about +-5 %; the self-hosted backend is 3-8 % faster). clang needs about 20 % less time on the `.ll` than on the equivalent C
(selfhost -O1: 16.8 s vs 21.4 s; no C parsing), the `.ll` is 1.5x the size of the C file.

**wasm32-wasi.** Done: `--target=wasm32` IR, libminirt and the whole compiler build for `wasm32-wasip1` and run under `node:wasi`; see "WebAssembly toolchain" below.

**JIT via `lli` (evaluated, dropped).** `lli` with a shared libminirt cannot resolve the thread-local `mk_pending_exception` from JIT-compiled code; making it work needs a runtime accessor or a compiler hook that exists only for the JIT. The real JIT is ORC through the LLVM C API (phase 2).

## WebAssembly toolchain

The browser playground (docs/plan-kotlin.md 10i) runs the **compiler itself as a WASI module** and the prebuilt LLVM of the npm package
[`@yowasp/clang`](https://www.npmjs.com/package/@yowasp/clang) (YoWASP: clang, wasm-ld, wasi-libc and compiler-rt, all compiled to wasm; pinned to
`21.1.4-3` in `scripts/wasm/package.json`). Nothing LLVM-related is built by us. The LLVM C API binding stays the native path (JIT, single binary).

```
prog.kt --minic.wasm--> prog.ll (wasm32 IR) --clang.wasm--> prog.o --clang.wasm/wasm-ld--> prog.wasm  (+ libminirt-wasm32.a, wasi-libc)  --> WASI
```

| artifact | what | size |
|---|---|---|
| `minic.wasm` | `minic-native` (own frontend, memory checker, lowering, RcOpt, `LlvmEmitter`) compiled with `--backend=llvm --target=wasm32`, `clang -O1`, linked with libminirt; 32 MB stack | 4.2 MB (0.64 MB gzip) |
| `libminirt-wasm32.a`, `minirt-entry-wasm32.o` | `runtime/src/*.c` for `wasm32-wasip1` (`alloc_debug.c` builds as is: wasi-libc has single-threaded pthread stubs; no runtime source needed a guard); the object holds `main` | 66 KB |
| `stdlib.json` | stdlib `.kt` sources in compilation order (the compiler parses them on every run) | 0.26 MB |
| `@yowasp/clang` | not ours: `llvm.core.wasm` + `llvm-resources.tar` (wasi sysroot `wasm32-wasip1`, builtins, headers) | 70 + 29 MB (22 + 4 MB gzip) |

**Pipeline commands** (what the browser runs; paths are in the virtual working directory):

```
minic.wasm --check --brief prog.kt --stdlib <stdlib files>                  # Strict diagnostics (M-codes); exit 1 on errors
minic.wasm --emit-ll prog.ll --target=wasm32 prog.kt --stdlib <stdlib files>
clang --target=wasm32-wasip1 -O1 -Wno-override-module -c prog.ll -o prog.o
clang --target=wasm32-wasip1 prog.o libminirt-wasm32.a minirt-entry-wasm32.o -o prog.wasm
# run prog.wasm as a WASI command (stdout/stderr/stdin/exit code; /tmp preopened for the mini.io tests)
```

`minic.wasm` reads sources and stdlib with `mini.io` (`fopen`), so it needs a **preopened directory**: node uses `new WASI({ preopens: { '/work': dir } })`,
the browser shim an in-memory file system (stdlib.json written under `/work/stdlib/`, the user source as `/work/prog.kt`). Output (`prog.ll`) is written there
too. `@yowasp/clang` is driven with the same in-memory idea: `commands.clang(args, tree, { stdout, stderr })` takes and returns a tree
`{ name: string | Uint8Array | subtree }` (its sysroot is built in: `--target=wasm32-wasip1` needs no `--sysroot`). `scripts/wasm/yowasp.mjs` is a
small node wrapper that mounts real files into that tree and writes the results back.

**Website integration** (`MiniToolchain` contract of `website/.vitepress/theme/playground/types.ts`). `scripts/wasm/pack-site.mjs <toolchain-dir> <out>` writes
`<out>/manifest.json` and `<out>/<version>/` (`version` = hash of all files; everything below it is immutable) for `website/public/toolchain/`:
`mini-toolchain.worker.js` (module worker, esbuild bundle of `scripts/wasm/worker/`, which includes `@yowasp/clang`'s JS and fetches its
`llvm.core*.wasm` / `llvm-resources.tar` next to itself), `minic.wasm` and `stdlib.json` (manifest stage `check`), `libminirt-wasm32.a`, `minirt-entry-wasm32.o`,
`llvm.core*.wasm`, `llvm-resources.tar` (stage `full`). The worker speaks the `load/check/compile` protocol: `check` and `compile` run `minic.wasm` on an in-memory
WASI file system (`worker/memwasi.mjs`, 12 imports) and turn its text output into `Diagnostic` objects (`worker/toolchain-core.mjs`); `compile` then runs clang.wasm
twice (compile, link) and returns the `.wasm` (plus MIR / LLVM IR on request). `scripts/wasm/test-worker.mjs` drives the packed worker through the protocol under
node (fake `self`, `file:` fetch): load check, M0101 and type-error diagnostics, load full, compile hello and run it, compile errors. Not tested here: a real browser
(the clang package is loaded by its own bundle, which is written for browsers and node). That is now covered by the Playwright test below.

**Scripts** (all relative to the repository root; `scripts/build-wasm-toolchain.sh` runs them in this order and packs `build/wasm-toolchain.tar.gz`):

| script | |
|---|---|
| `scripts/wasm/build-runtime.sh` | libminirt for wasm32 (17 s) |
| `scripts/wasm/test-programs.sh` | every `compiler/testdata` program through JVM compiler -> clang.wasm -> `node:wasi`, stdout / exit code / `.err` lines like `LlvmEndToEndTest` (53/53 pass; about 2-3 minutes with 4 jobs) |
| `scripts/wasm/build-minic-wasm.sh` | `minic.wasm`: JVM `emit-mir` of the compiler sources, `native-driver --target=wasm32`, then the **host's clang** (wasm32 target, `-c` on the IR needs no headers) and `wasm-ld` against the sysroot, crt1 and compiler-rt extracted from `@yowasp/clang`'s resources: about 50 s in total. Without a wasm32-capable host clang it falls back to clang.wasm, whose step takes about 29 minutes (the YoWASP build has LLVM assertions on and runs as wasm). Both give a module that passes `test-minic-wasm.sh` with byte-identical IR |
| `scripts/wasm/test-minic-wasm.sh` | `--check` in wasm (`M0101` for `memory/uaf_simple.kt`, clean for `ok_heap_basic.kt`), and `minic.wasm --emit-ll` -> clang.wasm -> run for hello and testdata programs; the `.ll` must equal the JVM compiler's byte for byte |
| `scripts/wasm/pack-site.mjs`, `scripts/wasm/test-worker.mjs` | website layout and a protocol test of the worker (see above) |
| `scripts/wasm/build-one.sh` | one `.kt` file to a `.wasm` with the JVM compiler |
| `.github/workflows/wasm-toolchain.yml` | the same on CI (artifact `wasm-toolchain`; `workflow_call` for `pages.yml`; the finished bundle is cached by the hash of its inputs) |
| `website/scripts/e2e-playground.mjs` | headless Chromium (Playwright) against the built site: playground load, Check M0102, Run hello, uncaught exception, reload, notebook Run all, Open in playground (`pages.yml`, push to main) |

**Findings.**
* wasm32 IR needed no emitter change: all 53 programs run under `node:wasi` with the output of the native build. `minic-native` gained `--target=wasm32` (it is the
  only entry point in the browser).
* V8 (Node 22) crashed (segfault) at start of `minic.wasm` when it was linked with `--stack-first -z stack-size=64MB`; wasmtime ran the same file. The default
  layout with `-z stack-size=32MB` works; keep it.
* Run time of `minic.wasm` under node: `--check` of a small program 0.7 s including module compile and reading the 262 KB stdlib.
* C interop programs (`cinterop_*`) are not part of the wasm check: they bind LP64 libc signatures (`strlen` as `Long`; wasm32 `size_t` is 32 bit) and need their own C files.
* The prebuilt clang loads in about 1 s per invocation under node (one `llvm.core.wasm` instantiation); compiling hello.ll at `-O1` is a few seconds.
