# Generics

<<< @/samples/tour/generics.kt{kotlin}

Output:

<<< @/samples/tour/generics.out{text}

## Notes

- Generics are **erased**, like on the JVM. All reference type arguments share one implementation; a type parameter becomes its first bound, or `Any?`. Primitive type arguments are boxed (`Box<Int>` holds a heap-allocated `Int` object).
- When an override has a different erased signature than the slot it overrides (`override fun push(x: Int)` of `fun push(x: T)`), the compiler generates a small bridge function.
- `reified` type parameters work because `inline` functions are expanded at the call site with the type arguments substituted. A function with `reified` parameters only exists in expanded form, so it cannot be used as a function value.
- `T : Comparable<T>` calls compile to a runtime comparison that handles boxed numbers and strings natively.

See [Generics internals](/internals/generics).
