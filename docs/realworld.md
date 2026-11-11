# Real-world programs

`tests/realworld/` holds 31 programs of the kind found in real projects: 100 to 400 lines each, standard library only, written the way an
application developer writes Kotlin (data classes, sealed hierarchies, generics, extensions, delegates, DSL builders, coroutines,
threads, regular expressions, text formatting). They complement the conformance tests (`docs/conformance.md`), which check single language
features: here a program has to compile, run and print exactly what the reference compiler prints.

```sh
scripts/test-e2e.sh --suite realworld                  # C backend
scripts/test-e2e.sh --suite realworld-llvm             # LLVM backend
scripts/test-e2e.sh --suite realworld-leak             # C backend under AddressSanitizer + LeakSanitizer + UBSan
scripts/test-e2e.sh --suite realworld-llvm-leak        # the same through the LLVM backend
tests/realworld/gen-expected.sh [name...]              # (re)make <name>.out with kotlinc on the JVM (KOTLINC=/path/to/kotlinc)
```

`realworld` and `realworld-leak` are part of the default suites. The expected output (`<name>.out`) was made by running the program
with kotlinc 2.1.20 on the JVM (UTF-8 output); a program must not depend on hash codes, identity, timing or thread scheduling. The
programs do not use JVM-only APIs of the standard library (a `java.util.concurrent` subset that Mini provides is used by `thread_pool`).

## The programs

| program | what it does | features |
|---|---|---|
| `aoc_grid_paths` | maze BFS, weighted Dijkstra with an own heap, flood fill regions | classes, operators, sequences, `HashMap` / `ArrayDeque` |
| `aoc_dynamic_programming` | LCS, edit distance, knapsack, coin change, LIS, partitions, Pascal | arrays, `Pair`, destructuring, `Long` math |
| `aoc_instructions` | assembly-like machine parsed with regexes, boot-code repair | sealed classes, `Regex`, `when`, `destructured` |
| `cli_tool` | option parser with subcommands, short groups, `--`, help text | lambdas, local functions, enums, exceptions |
| `json_codec` | JSON parser / serializer / pretty printer / queries | sealed classes, recursion, operators, string building |
| `csv_report` | CSV with quoting, typed rows, aggregation report | `groupBy`, `groupingBy().fold`, formatting |
| `markdown_html` | Markdown to HTML (headings, lists, code, quotes, links) | regex replace with lambdas, sealed blocks |
| `mini_interpreter` | lexer, parser, evaluator of a small language with closures | sealed ASTs, recursion, exceptions as control flow |
| `event_system` | events, a typed bus, event-sourced replay | sealed classes, `inline reified`, data classes, `groupBy` |
| `bank_domain` | accounts, value class money, journal, repository | interfaces with defaults, abstract classes, `value class`, generics |
| `inventory_generics` | typed stores, indexes, bounded generics | generic classes / extensions, comparators, `Comparable` |
| `text_statistics` | word frequencies, n-grams, readability | `windowed`, `zipWithNext`, `groupingBy`, `runningFold`, sequences |
| `matrix_library` | matrices, vectors, rationals | operator overloading, `infix`, determinant / inverse |
| `state_machine` | vending machine, traffic light, declarative machine DSL | sealed states, `inner class`, DSL builder |
| `sequences_generators` | primes, Fibonacci, merge, tokenizer | `sequence { yield }`, `generateSequence`, iterators |
| `coroutine_scheduler` | cooperative scheduler, channels, pipeline stages | `suspendCoroutine`, `startCoroutine`, suspend lambdas with receiver |
| `thread_pool` | worker pool, futures, latch, locks, producer / consumer | `thread`, `java.util.concurrent`, atomics |
| `delegated_properties` | `lazy`, `observable`, `vetoable`, `provideDelegate`, `by` for interfaces | property delegates, `KProperty` |
| `html_dsl` | HTML builder and query builder DSLs | lambdas with receiver, `@DslMarker`, operators |
| `extension_toolkit` | string, number and collection extensions, `Result` | extensions, `tailrec`, scope functions, infix |
| `regex_logs` | access-log parsing with named groups, query strings | `Regex`, `MatchResult`, `replace` with lambdas |
| `string_formatting` | `format`, tables, word wrap, bases, StringBuilder editing | formatting, unicode, string API |
| `priority_scheduler` | generic heap, dependency-ordered scheduling, LRU cache | generics, `Comparator`, topological sort |
| `huffman_coding` | Huffman codes, bit packing, run-length coding | `ByteArray`, bit operations, sealed trees |
| `sudoku_solver` | bitmask Sudoku solver, N-Queens | `IntArray`, recursion, local functions |
| `trie_autocomplete` | prefix tree, suggestions, spell checker | `sortedMapOf`, recursion, edit distance |
| `calendar_dates` | date arithmetic, calendars, ISO weeks | `Comparable`, companion, `Long` / `Int` arithmetic |
| `graph_algorithms` | union-find, MST, Dijkstra, SCC, topological sort | generic classes, `Triple`, local recursive functions |
| `bignum_arithmetic` | arbitrary precision naturals | operators, `IntArray` / `LongArray`, secondary constructors |
| `library_system` | lending model with many language features | sealed interfaces, typealias, `value class`, `invoke`, `Result`, labels |
| `game_of_life` | Game of Life, rule 30 / 110, Langton's ant, random walk | arrays, bit operations, `Long` overflow |

## Findings and fixes

Most programs ran unchanged on the first try. What the programs found:

| finding | fix |
|---|---|
| `fun <T, R> List<T>.f(g: (T) -> R?): List<R> = mapNotNull(g)`: the lower bound `R` of the non-null variable of `mapNotNull` was not `R & Any` | `ConstraintSystem.addSub` strips nullability with `makeNotNull` (Infer.kt) |
| a `var` declared and assigned inside a lambda (`run { var y = ...; while (y != null) { use(y); y = ... } }`, `sequence { }`) was never smart cast: the names assigned in lambdas counted as captured even when the variable belongs to the lambda | `AstWalk.capturedAssigned` leaves out the variables declared in the lambda (they are found again for lambdas nested in it) |
| `a > b` where `a` has no `compareTo` was an internal compiler error (`compareTo is not resolved`) | the checker reports `no operator compareTo for T` |

See the list of programs above for the status of each: every program passes the four suites.
