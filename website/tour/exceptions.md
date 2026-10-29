# Exceptions

<<< @/samples/tour/exceptions.kt{kotlin}

Output:

<<< @/samples/tour/exceptions.out{text}

## Notes

- Exceptions use an error-return convention, not unwinding: a function that throws stores the exception in a thread-local slot and returns a dummy value; callers test the slot after every call that can throw. See [Exceptions internals](/internals/exceptions).
- The runtime provides the standard exception classes (`Throwable`, `Exception`, `RuntimeException`, `IllegalArgumentException`, `IllegalStateException`, `NullPointerException`, `ArithmeticException`, `ClassCastException`, `IndexOutOfBoundsException`, `NoSuchElementException`, `NumberFormatException`, ...). Your own classes can extend them.
- An exception that escapes `main` prints `Exception in thread "main" ...` to stderr and exits with status 1 (`finally` blocks still run).
- There are no stack traces, `printStackTrace()` prints `toString()`, and suppressed exceptions are not recorded.
