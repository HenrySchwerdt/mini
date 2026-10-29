# Collections and strings

<<< @/samples/tour/collections.kt{kotlin}

Output:

<<< @/samples/tour/collections.out{text}

## Notes

- The collections are Kotlin source in `stdlib/` (ported from the Kotlin stdlib), compiled together with your program. Only what the program can reach ends up in the generated C. See [Standard library](/stdlib).
- `HashMap` iteration order matches `java.util.HashMap`, which is why the map prints `{a=3, b=2, c=1}` exactly as it does on the JVM.
- Everything is reference counted. The stdlib hash maps keep before/after links between nodes, which are reference cycles, so a `HashMap` or `LinkedHashMap` leaks its nodes under plain reference counting today.
