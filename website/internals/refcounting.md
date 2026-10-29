# Reference counting and RcOpt

## The baseline rules

These are what the emitter does without optimization (`minic build --no-rc-opt`). They are implemented by `Ir2Mir` (ownership shape) and `CEmitter` (placement).

1. Every reference-typed local owns one reference. It is `NULL`-initialised at the top of its C scope and released at every exit of that scope: normal end, `break`, `continue`, `return`.
2. Parameters are **borrowed**: callees never release them, callers keep them alive for the call.
3. Expressions that create references (calls returning references, constructors, string conversions, boxing) are *owned* (+1) and are bound to a fresh temporary that is released at scope end, or directly to the `val`, field or return that consumes them.
4. Reads of reference fields are copied into retained temporaries, so a callee that overwrites the field cannot free a value the caller still uses.
5. Assignment and field stores retain the new value, store, release the old one.
6. Return values are owned: returning an owned local moves it, returning anything else retains it first.
7. String literals are static objects (retain and release do nothing). `object` and enum singletons are immortal.

At run time, `retain` and `release` accept `NULL` and are no-ops for static and stack objects. Updates are plain (non-atomic) normally and atomic for objects flagged `MK_FLAG_SHARED` (set by `mk_share` before an object is published to another thread). When the count reaches zero, `dispose` runs along the type chain and the memory is freed. **Reference cycles leak:** there is no cycle collector and no weak references yet.

## RcOpt

Counting every field read and every local costs a lot (`arraylist` did 67 million retains in the benchmark). `RcOpt` (`compiler/backend/.../RcOpt.kt`, on by default) removes most of it. It does **not** rewrite the MIR: per function it computes an `RcPlan` that `CEmitter` consults. The result is a deterministic function of the MIR module, so the JVM backend and the self-hosted native backend produce byte-identical C.

The method is Perceus-style ownership inference restricted to what is provably safe for Mini's object model (mutable fields, no GC, one reference convention for virtual calls).

**Effects.** `RcEffects` computes, as a fixpoint over the call graph, which functions may *overwrite a reference stored in the heap*. Only such an operation can free an object that a borrowed alias still points to.

**Rules.**

1. *Borrowed locals.* A local whose every definition is a plain read (`val t = p`, `val t = o.f`, `x = x.next`, casts of those, singletons) owns nothing: no retain, no release. It is valid while its roots are alive (parameters, owned locals not reassigned, immortal objects) and, if a field was read, while no heap-mutating operation runs between definition and last use.
2. *Moves of the last use.* A statement that consumes the last use of an owned local (storing it, returning it) takes its reference instead of retaining a second one. "Last use" is exact liveness over the structured MIR.
3. *Owned constructor parameters.* A constructor parameter that the initializer always gives away (stores in a field, returns, passes to another constructor) is passed owned.
4. *Self takes.* `x = Foo(x)`: the old value dies at the assignment, so it is handed over instead of retained and released.
5. *Closed moves.* In the simple case, the emitter neither clears nor releases a moved local at later exits.
6. *Not-yet-declared locals.* Exits that precede a local's declaration do not release it.
7. *Fresh stores.* The first store into each reference field of `this` in a constructor overwrites a zeroed field: no old value to release.
8. *Inline NULL test.* Releases of references that are often `NULL` are emitted as `MKH_REL(x)`, which tests inline and calls the runtime only for a real object.

Not done yet: owned parameters for ordinary functions and virtual slots, memory reuse for dying objects, a cycle-free "unique" bit, thread-sharing awareness for borrowed field aliases.

## Seeing it

`counter.kt` from the [pipeline page](./) has one object in `main`. The baseline emits a call into the runtime at the end of the scope:

<<< @/internals/generated/counter.noopt.main.c.txt{c}

and the optimized build uses the inline fast path (`MKH_REL`, see the full listing on the [MIR page](./mir#the-c-that-comes-out)). The effect on realistic code is much larger, see below.

## Measured effect

From [Benchmarks](/benchmarks): time drops 5 to 40 percent, retains drop 60 to 100 percent, releases 40 to 80 percent. The number of allocations and the peak memory are identical by construction, since RcOpt moves and removes counting but never changes what is allocated or when objects die. To measure your own program, build with `./minic build prog.kt -o prog --stats` and read the `mk-stats:` lines at exit.
