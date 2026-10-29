# 7. Lambdas and captures

How a lambda is treated depends on whether it can outlive the code around it.

| Kind of lambda | Treatment |
|---|---|
| **Inline**: passed to an `inline` function, not `crossinline`/`noinline` (`use`, `repeat`, `run`, `let`, `forEach`, `context(x) { }`, ...) | transparent: the checker reads the body as if it were written at that place |
| **Thread lambda**: a parameter marked `@ThreadBoundary` (like `spawn`) | captured owners move in; the lambda runs once and owns them |
| **Everything else** (escaping) | may not capture owned values, see below |

## Inline lambdas are transparent

<<< @/samples/memory/inline_lambda.kt{kotlin}

Owners may be freed or moved inside the block, and locals created inside are checked like any other code. The checker accepts the program.

## Escaping lambdas

<<< @/samples/memory/escaping_lambda.kt{kotlin}

Rejected with [M0112](/reference/diagnostics/M0112): closures as owners (move capture) are not supported yet. Read the data you need before creating the lambda and capture that, or pass the value as a parameter.

Further rules for escaping lambdas:

- capturing a **borrowed** value is [M0109](/reference/diagnostics/M0109) (the loan would outlive its lender),
- capturing an **arena object** in a lambda that leaves the arena's scope is [M0105](/reference/diagnostics/M0105),
- capturing an untracked value (a primitive, a Managed object) is always allowed,
- a lambda that is only called locally may capture arena objects and loans.

Next: [Threads and ThreadSafe](./threads).
