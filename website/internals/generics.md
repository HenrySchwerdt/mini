# Generics: erasure and bridges

Mini erases generics the way the JVM does. There is one compiled copy of a generic class or function.

- **Reference type arguments share one implementation.** A type parameter is erased to its first supported bound, otherwise to `Any?`. Every value is an `MkObject *`.
- **Primitive type arguments are boxed.** `Box<Int>` stores a heap object of type `kotlin.Int`.
- `out` and `in` variance exist only in the frontend.

## What the C looks like

`class Box<T>(val value: T) { fun get(): T = value }` from the [closure sample](./closures):

<<< @/internals/generated/generics.box.c.txt{c}

The field `f_value` is an `MkObject *`, and `get` returns it retained (an owned result). At the use site, `Box(41).get() + 1` boxes `41` with `mk_box_int` on the way in and unboxes with `mk_checked_unbox_int` on the way out. The compiler does this adaptation when a call's erased result type differs from its Kotlin type (`adaptResult`) and when arguments must be boxed to the erased parameter types.

## Bridges

When an override has a different erased C signature than the vtable or itable slot it overrides (`override fun push(x: Int)` of `fun push(x: T)`, or a covariant return type), the compiler generates a small **bridge function** with the slot's signature that unboxes and forwards. Virtual and interface calls are compiled against the signature of the slot's root declaration, so every function pointer type matches its definition exactly, which keeps `clang -fsanitize=function` clean.

## Bounds and comparison

For `T : Comparable<T>`, `a.compareTo(b)` calls `mk_compare_to`, which handles boxed primitives and strings natively and otherwise dispatches through the `Comparable` itable.

## `reified`

Only through inlining: see [Closures and inline](./closures#inline-functions).

## Why it matters

Because of erasure, a `List<Int>` is a list of boxed objects, and each element costs an allocation and reference counting. The plan is to monomorphize primitive instantiations later (`docs/plan-kotlin.md`, section 5); that is not implemented today.
