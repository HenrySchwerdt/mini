# Exceptions: the error-return ABI

Mini does not unwind the stack and does not use `setjmp`. Exceptions are an **error-return convention**, similar to Swift's error ABI.

## The convention

- A function that throws stores an owned reference to the exception object in the thread-local variable `mk_pending_exception` and returns a dummy value (0 or `NULL`).
- After every call that **can throw**, the caller tests the variable. If it is set, the caller takes its *exceptional exit*: it releases the locals of the scopes it leaves (the same machinery as `break` and `return`) and then either jumps to the handler label of the innermost `try`, or returns the dummy value to its own caller.
- Because locals are `NULL`-initialised at scope entry, the exit code is always safe, and the leak checker stays clean on exceptional paths.

## What it looks like

A function that throws, and a caller with a `try`, from [`throws.kt`](https://github.com/HenrySchwerdt/mini/blob/main/website/samples/internals/throws.kt):

<<< @/samples/internals/throws.kt{kotlin}

The generated C for `half`: `mk_throw` records the exception, the temporary is released, and the function returns a dummy 0:

<<< @/internals/generated/throws.half.c.txt{c}

And the caller. The GNU statement expression `({ ...; if (mk_pending_exception != NULL) { goto mkcatch_0; } mkt0; })` is the check after each call. The handler takes ownership of the exception with `mk_catch()`, tests it against each `catch` clause with `mk_is_instance`, and rethrows if none matches:

<<< @/internals/generated/throws.main.c.txt{c}

## Who can throw

Checking after every call would slow numeric code down, so `ThrowAnalysis` computes, as a fixpoint over the call graph, which functions can throw. A function can throw if it contains a `throw`, a raising operation (a checked cast, `!!`, division by a non-constant, an array access), a call to a throwing function, or a dynamic call while some function in the corresponding vtable or itable slot throws. Calls to functions that cannot throw get no check. Constructors' `_new` wrappers release the half-built object when the initializer throws.

## `finally`

`finally` is lowered by `Ir2Mir` into a catch-all clause that runs the finalizer and rethrows, a copy of the finalizer on the normal path, and copies before every `return`, `break` or `continue` that leaves the `try` (the return value is computed first). `try` is an expression.

## Exception classes

The runtime provides the standard classes with a `MkThrowable` layout `{message, cause}`: `Throwable`, `Exception`, `RuntimeException`, `Error`, `IllegalStateException`, `IllegalArgumentException`, `NullPointerException`, `ArithmeticException`, `ClassCastException`, `IndexOutOfBoundsException` and its array and string variants, `UnsupportedOperationException`, `NoSuchElementException`, `NumberFormatException`, `UninitializedPropertyAccessException`, `NotImplementedError`, `NegativeArraySizeException`, `AssertionError`. They use JVM names so `e.toString()` matches. In MIR they are `external` classes that user classes can extend.

Failures that would otherwise abort are exceptions: `/ by zero`, `!!` and unboxing of `null`, a failed `as`, index out of bounds, `lateinit` access, `error()`, `require`, `check`, `requireNotNull`, `checkNotNull`, `TODO()`.

## An exception escaping `main`

The generated `mk_main` wrapper checks the pending exception after `mkh_user_main` returns. It flushes stdout, prints `Exception in thread "main" <toString>` to stderr and exits with status 1.

## Known gaps

No stack traces, no suppressed exceptions. An exception thrown by a `finally` block that runs on a `return` path is caught by the same `try`'s catch-all (the finalizer would run twice). Exceptions inside object or file-facade initializers propagate lazily. An LLVM backend could use real unwinding later; the plan keeps the error-return ABI for the C backend.
