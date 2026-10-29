# Extensions and null safety

<<< @/samples/tour/extensions.kt{kotlin}

Output:

<<< @/samples/tour/extensions.out{text}

## Notes

- An extension function is a function whose first parameter is the receiver. Nothing is added to the class.
- A nullable reference is the same pointer as a non-null one, with `NULL` for `null`. Nullable primitives (`Int?`) are boxed objects.
- `lazy` is implemented in the Mini stdlib as an unsynchronized lazy value, which is enough here because the stdlib is single-threaded.
