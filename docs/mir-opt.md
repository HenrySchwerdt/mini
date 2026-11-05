# MIR optimization passes

The driver runs `MirOpt` (compiler/backend/src/opt/) on the whole program after linking, pruning and the module-wide
devirtualization (`Devirt.kt`), before the C / LLVM emitters and `RcOpt`. Everything here works on the structured MIR
(`Mir.kt`) and keeps its ownership rules (arguments are borrowed, results are owned), so reference counting is decided
afterwards, by `RcOpt`, on the optimized code.

| pass | switch | effect |
|---|---|---|
| exact-type devirtualization | `-Xno-exact-devirt` | a virtual / interface call on a local that is initialised by `New(C)` and never reassigned calls C's implementation directly |
| inlining | `-Xno-inline` | small (<= 70 MIR nodes), non-recursive, non-Strict functions whose `return`s are all in tail position are expanded in place, callees first; the evaluation order of the operands of the calling statement is kept (stable operands stay, other primitive operands are hoisted into temporaries, a call behind a non-local reference operand or in the right side of `&&`, `\|\|` or a conditional is not inlined) |
| clean-up | with inlining | copy propagation of aliasing locals, dead pure locals, `declare x; ...; x = v` merged into `declare x = v`, `if` on constants |
| scalar replacement | `-Xno-sroa` | `val o = New(C, args)` of which, after inlining the constructors and the small methods called on it, only field reads and writes remain: the object is never allocated, its fields become locals (no allocation, no reference count, nothing a cycle collector could see) |

`-Xno-mir-opt` turns all of them off, `-Xopt-stats` prints what they did. With `minic` pass the switches as they are
(`minic build prog.kt -Xno-sroa`). The whole program is internal (`static` in C, `internal` in LLVM) except `main`, so the
C compiler / LLVM can inline and drop functions.

Soundness notes
* An object is only removed when *no* use of it is left once its constructor is inlined; any other use (argument of a call
  that stayed a call, store into a field or list, return, `===`, cast, capture by a closure, `throw`) keeps it a real heap
  object. Stack/scalar objects are therefore never visible to the reference counter or to a cycle collector.
* Strict functions, Strict classes and library-style programs (no `main`) are not touched.
* A callee is inlined at most to the budget of the caller (700 nodes per round, 4 rounds).
