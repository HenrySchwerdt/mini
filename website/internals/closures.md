# Closures and inline functions

## Function types

A function type `(A, B) -> R` (also `A.(B) -> R`) is a MIR interface `kotlin.FunctionN` with a single method `invoke(Any?, ...): Any?`. The ABI is erased: primitives are boxed across a function-value call and unboxed on the other side, and `Unit` results are `null`.

## Lambdas become classes

Every lambda literal or function reference that is not inlined gets its own class `$closure<N>` that implements the interface. A closure is an ordinary reference-counted object with one field per captured value and a generated `dispose` that releases the captures. From [`closure.kt`](https://github.com/HenrySchwerdt/mini/blob/main/website/samples/internals/closure.kt):

<<< @/samples/internals/closure.kt{kotlin}

The class generated for `{ x -> x + k }` captures the `Int` `k` in `f_c0` and lists the `Function1` interface in its itable:

<<< @/internals/generated/closure.class.c.txt{c}

`adder` just allocates it, and `invoke` unboxes the argument, adds the captured value and boxes the result:

<<< @/internals/generated/closure.adder.c.txt{c}

<<< @/internals/generated/closure.invoke.c.txt{c}

## Captured variables

- Captured `val`s and parameters are **copied** into the closure.
- A captured `var` lives in a heap cell (a `Ref<T>` class with one field `value`, one class per MIR type) shared by the declaring function and every closure that captures it. `ClosureAnalysis` boxes exactly the `var`s that some closure captures.
- Local functions are lambda-lifted: a C function whose leading parameters are the captured values. Calls to them are direct, so recursion works; `::local` creates a closure object.

Closures go through the normal allocation path. A reference cycle such as a closure stored in a field of the object it captures leaks under reference counting.

## Inline functions

K2 keeps calls of `inline fun` in its IR. When the callee has a body (all user code and the stdlib compiled from source), `Ir2MirInline` **expands it in place**: the body is lowered in the caller's function with parameters bound to fresh locals, and a lambda passed for an inline parameter is not turned into an object, its body is expanded at the call. In the example, `twice { n += 1 }` becomes two copies of `n += 1` wrapped in labelled loops that implement `return` from the inline function, and `n` is an ordinary C local with no heap cell:

<<< @/internals/generated/closure.inline.c.txt{c}

Consequences:

- `return` inside such a lambda is a **non-local return** from the enclosing function.
- `reified` type parameters work because the type arguments are substituted during expansion. Functions with `reified` parameters only exist expanded.
- Inline functions are also compiled as ordinary functions taking function objects, so they can be used as values (`twice` above has a `kt_twice_...` version that calls `invoke` through the itable).
- Limit: an inline lambda parameter may only be *invoked* or forwarded to another inline function. Store it with `noinline`. `crossinline` lambdas are compiled as closures.
