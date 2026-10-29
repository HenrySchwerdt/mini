# Kotlin-inspired syntax: implemented core

The syntax package is implemented and tested in the bootstrap compiler. The
current language is documented in [language-v0.2.md](language-v0.2.md).

## Implemented

- `val` and `var`, with local type inference from an initializer.
- Explicitly typed deferred initialization, including `val` with exactly one
  assignment on each reachable execution path.
- `val`/`var` field declarations; field types remain explicit and record literals
  supply initial values.
- Return annotations with `:` and expression-bodied methods with `= expression`.
  Method return types are still explicit for expression bodies.
- Optional semicolons at line boundaries, before a closing brace, or at EOF.
- Multiline grouped expressions, argument lists, trailing operators and member chains.
- Function types, typed/contextual lambdas, implicit `it`, and trailing lambda arguments
  after parenthesized calls; immutable scalar captures and explicit borrowed pointers.
- Automatic borrowing for `value.method()` calls; pointers use the same dot syntax.
- Kotlin-style explicit generic calls (`Core.identity<i32>(42)`).
- `match` with nested payload binding and optional commas between newline-separated arms.

`fn`, `let`, return arrows and generic `::<...>` calls remain accepted for
migration. Methods remain class/interface members and `this` remains an implicit
instance pointer. No nullable-reference or exception subsystem was introduced.

```mini
class Main {
    static answer(): Result<i32, bool> = Result.Ok(42)

    static main(): i32 {
        val result = Main.answer()
        val value = match result {
            Ok(value) => value
            Err(error) => 0
        }
        print_i32(value)
        return 0
    }
}
```

See [the native syntax example](../examples/kotlin.mini).

## Binding and continuation rules

`val` prevents reassignment of its binding; it does not deep-freeze a class
instance or pointer pointee. A scalar `val` cannot expose its storage via a mutable
address. An initialized class-valued `val` can be borrowed for instance methods and
mutable-field updates. `val` fields cannot be reassigned after literal construction.
Method parameter bindings are immutable; pointer parameters still permit mutable
pointee access. Deferred `val` writes from a potentially repeating enclosing loop
are rejected conservatively.

A newline ends a complete outer expression. Parentheses, argument lists and array
indices allow multiline expressions. A trailing operator requires a right operand
and continues across the newline. A member-access dot can start the next line.
A leading binary operator must be grouped; call/index parentheses must follow the
receiver on the same line outside a continuation group. `return` followed by a
newline is a bare return. Multiple statements on one line still require semicolons.
Line breaks inside comments and CRLF files follow the same rules.

## Remaining syntax/compiler work

Primary constructors, default property initialization, property accessors, method
return inference for ordinary methods, broader closure captures, generic instance
methods and extension methods are separate increments. They are prerequisites for
parts of a larger Kotlin-style std; this implemented package does not claim to
provide them.
