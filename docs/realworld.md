# Real-world programs

`tests/realworld/` holds 35 programs of the kind found in real projects: 100 to 400 lines each, standard library only, written the way an
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
| `stdlib_breadth` | about 40 lines of everyday collection, map, math, number and string API calls | stdlib breadth, number formatting |
| `language_tour` | variance, operator conventions, enums with bodies, inner / local classes, init order, inline variants, references, control flow | language breadth |
| `tictactoe_minimax` | minimax with alpha-beta over a generic game interface, memoized full tree, Connect Three | generics, interfaces, recursion |
| `expression_calculator` | tokenizer, shunting-yard, RPN evaluation with functions and variables | sealed tokens, stack machines, error handling |
| `game_of_life` | Game of Life, rule 30 / 110, Langton's ant, random walk | arrays, bit operations, `Long` overflow |

## Findings and fixes

Most programs ran unchanged on the first try. What the programs found:

| finding | fix |
|---|---|
| `fun <T, R> List<T>.f(g: (T) -> R?): List<R> = mapNotNull(g)`: the lower bound `R` of the non-null variable of `mapNotNull` was not `R & Any` | `ConstraintSystem.addSub` strips nullability with `makeNotNull` (Infer.kt) |
| a `var` declared and assigned inside a lambda (`run { var y = ...; while (y != null) { use(y); y = ... } }`, `sequence { }`) was never smart cast: the names assigned in lambdas counted as captured even when the variable belongs to the lambda | `AstWalk.capturedAssigned` leaves out the variables declared in the lambda (they are found again for lambdas nested in it) |
| the iteration order of a `HashMap` filled with `putAll` / `toMap(destination)` and of `Array.toHashSet()` differed from the JVM (the JVM sizes an empty table from the argument of `putAll`, and `toHashSet` uses `mapCapacity`) | `HashMap.putAll` pre-sizes like `putMapEntries`, the array `toHashSet` functions use `HashSet(mapCapacity(size))` (stdlib); test `realworld1_hash_order` |
| programs call `System.currentTimeMillis()`, `System.nanoTime()`, `System.lineSeparator()`, `System.arraycopy`, `System.exit` and `Runtime.getRuntime().availableProcessors()` | a small `java.lang.System` / `Runtime` (stdlib/java/lang/System.kt); test `realworld1_system` |
| `a > b` where `a` has no `compareTo` was an internal compiler error (`compareTo is not resolved`) | the checker reports `no operator compareTo for T` |

See the list of programs above for the status of each: every program passes the four suites.

## Multi-module projects

`tests/projects/` holds workspaces of a library and an app (and one dependency chain): the way a project is split in practice. Each
project has a workspace `mini.toml`, packages with their own `mini.toml` (library `kind = "lib"`, app, path dependencies), sources
in `src/` and optionally `tests/` with `@Test` functions, and the files that say what is checked:

| file | meaning |
|---|---|
| `expected.out` | `mini run` prints exactly this; made by `tests/projects/gen-expected.sh` with kotlinc 2.1.20 (the sources of all packages compiled together) |
| `packages` | the libraries to package: `mini package -p <lib>` writes the `.mlib`, and the app is then compiled by `minic-native` against the `.mlib` files only (no sources of the libraries) and must print the same |
| `expected.err` | `mini build` must fail and print every line of this file (a visibility error) |
| `strict` | the project must also build with `mini build --strict` |
| tests in `<pkg>/tests/` | `mini test` of the workspace passes |

```sh
scripts/test-e2e.sh --suite projects          # all projects      (also in the default suites)
tests/projects/run.sh collections_util        # one project (a scratch copy is built)
```

| project | library | what it exercises |
|---|---|---|
| `collections_util` | `collx`: multimap, `pairwise`, `splitBy`, `maxesBy`, memoize, counter | generic inline functions with lambdas, `reified` across the module boundary, `internal` helper behind a public function, `@Test` classes in the library |
| `parser_combinators` | `parsekit`: `Parser<T>` as a `fun interface`, `map` / `then` / `or` / `many` / `sepBy` / `chainl1` | `crossinline` lambdas into SAM objects, sealed result types, recursive grammars, extension and infix functions |
| `event_bus` | `bus`: typed bus with priorities, subscriptions, `once` | `inline fun <reified E>` with `E::class`, `@PublishedApi internal`, `internal` constructors, `crossinline` |
| `result_either` | `either`: right-biased `Either`, `traverse`, `sequence`, `partitionEithers` | variance (`out L, out R`, `@UnsafeVariance`), inline combinators over a sealed class, `Nothing` branches |
| `money_domain` | `money`: `Money` in minor units, rounding, currencies | `internal` constructor with public factories, operators, `Comparable`, `internal object`, extension properties |
| `template_engine` | `tmpl`: `{{var|filter}}`, `{{#each}}`, `{{#if}}` templates | private sealed classes behind a public API, typealiases, `inline` DSL entry point with a receiver lambda |
| `dependency_chain` | `core` <- `rules` <- app | three packages: `inline` functions that create anonymous objects (`object : Rule<T>`) used through two library hops, generics with `in` variance |
| `visibility_error` | `vault` | the app names `internal` and `private` members of the library: the build must fail |
| `strict_geometry` | `geom` (`memory = "strict"`) | Strict library and Strict app, `@AllocatedBy(Heap::class)`, owned results freed by the caller, `mini build --strict` |

What the projects found:

| finding | fix |
|---|---|
| a local class or anonymous object inside an `inline` function of a library was not supported in a consumer (`construction of '<anonymous>'`) | the consumer compiles its own copy of such classes (named `inl$...`, apart from the library's) |
| `internal` and `private` declarations of a library could be used by the app | the checker reports `cannot access 'x': it is internal in a library` for declarations of API stubs (not for the standard library, and not in files that suppress `INVISIBLE_*`) |
| a lambda whose result type does not fit the parameter type of a call with no type variables is accepted (`call { "x" }` for `f: () -> Int`) | not fixed: only wrong programs are affected, the conformance risk of rejecting more is larger than the gain |

## Strict mode over the real-world programs

`scripts/strict-coverage.sh --set realworld` adds `@file:MemoryMode(Mode.Strict)` to each program and runs the checker
(`tools/conformance/strict_coverage.py`, classes as in `docs/memory-model.md`):

| programs | accepted | (a) correct rejection | (b) limitation | (c) ICE |
|---:|---:|---:|---:|---:|
| 35 | 6 (17 %) | 19 | 10 | 0 |

| class | cause | programs |
|---|---|---:|
| (a) | an escaping allocation of a class that never opted into an allocator (every program that keeps objects in a collection or returns them) | 14 |
| (a) | M0109: a borrowed value is stored or returned as owned (for example `list.add(strictValue)`) | 3 |
| (a) | an allocation that is never released (the program never calls `free()` on its objects) | 1 |
| (a) | use after move (`val pair = a to b` then `a` again) | 1 |
| (b) | M0110: a Strict value is handed to Managed code that keeps it (`ArrayList.add`, `HashMap.set`, `Pair`): the collections of the standard library are Managed | 10 |

Accepted: `csv_report`, `game_of_life`, `sequences_generators`, `sudoku_solver`, `text_statistics`, `tictactoe_minimax`. Every
rejection traces back to one of the open specification questions (the default allocator, `@Owned T`, Strict collections); none of
them is a bug of the checker that could be fixed without a decision, so none was changed.
