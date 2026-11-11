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
| array scalar replacement | `-Xno-array-sroa` (needs sroa) | `val a = arrayOf(o0, ..., on)` (constant length <= 16, elements are never-reassigned locals) that never leaves its function: calls that take it are inlined (callee up to 800 nodes), counting loops with constant bounds that index it are fully unrolled (<= 16 trips, nested and triangular ones too), constant index arithmetic is folded, and when every use is `a[<constant>]` / `a.size` the array is not allocated: `a[k]` is the k-th element local, and the elements (fresh objects) are then scalar-replaced like any other object. Tried on a copy; one remaining use of the array (stored, returned, non-constant index, a later `a[k] = v`, a loop that is too long) leaves the function untouched |
| leaf-class tests | `-Xno-exact-is` | `x is C` where no class of the linked program has `C` as superclass (the leaves of a sealed hierarchy: `when (j) { is JNum -> ...; is JStr -> ... }`) is `o != null && o->type == &C_ti`, one compare, instead of a walk up the superclass chain (`MirExpr.IsInstance.exact`, `(isx ...)` in the MIR text; the LLVM emitter inlines the same compare). Interfaces, runtime classes and classes with subclasses keep the general test |
| substring keys | `-Xno-substr-key` | `val w = s.substring(a, b)` whose only uses are the key of `HashMap.findNode` (get, containsKey, getOrDefault ... after inlining) and of `HashMap.put` is never made: the lookups call `findNodeRange(s, a, b)` (hash and comparison read the characters of `s` in place, `mk_string_hash_range` / `mk_string_equals_range`) and `putRange(s, a, b, v)` (the String is made only when the key is new). The range test of `substring` stays at the original statement (`checkSubstringRange`: the same IndexOutOfBoundsException at the same place); `s`, `a`, `b` are used as they are when the function never assigns them, else copied into fresh locals where `w` was declared; any other use of `w` (passed on, stored, returned, captured, printed) leaves it alone. The helpers are kept by the pruning (`MirLink.Companions`) when `substring` and `findNode` / `put` are |
| array length cache | `-Xno-array-len` | a primitive-array local that is never reassigned (parameter or initialised declaration) and indexed inside a loop reads its length once, after the declaration; the accessors take it as an extra last argument (`mk_array_get_int(a, i, len)`). The test is unchanged (unsigned compare against the length): the C compiler could not keep the length in a register because an `Int` element store may alias the `Int` length field |
| bounds-check hoisting | `-Xno-range-check` (needs array-len) | an innermost ascending `for` loop of at most ~220 MIR nodes that indexes cached-length arrays with `i`, `i + x`, `i - x` or `x - i` (`x` a constant or a local the loop does not touch) is emitted twice behind one exact (Long arithmetic) range test done before the loop: the copy uses `mk_arrayu_*` accessors without a length test, the original loop runs when the test fails, so a bad index fails at the same iteration after the same side effects |

`-Xno-mir-opt` turns all of them off, `-Xopt-stats` prints what they did. With `minic` pass the switches as they are
(`minic build prog.kt -Xno-sroa`). The whole program is internal (`static` in C, `internal` in LLVM) except `main`, so the
C compiler / LLVM can inline and drop functions.

Soundness notes
* An object is only removed when *no* use of it is left once its constructor is inlined; any other use (argument of a call
  that stayed a call, store into a field or list, return, `===`, cast, capture by a closure, `throw`) keeps it a real heap
  object. Stack/scalar objects are therefore never visible to the reference counter or to a cycle collector.
* Strict functions, Strict classes and library-style programs (no `main`) are not touched.
* An array is only removed when no use of it is left after inlining and unrolling; constant in-bounds indices cannot fail, an out-of-bounds constant index keeps the array (and its exception). Elements that are the same object in two slots stay one local, so aliasing is preserved.
* A callee is inlined at most to the budget of the caller (700 nodes per round, 4 rounds).
