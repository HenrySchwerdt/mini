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
| `task_scheduler` | `sched`: abstract `Task` with `protected` members and `internal set`, `Scheduler`, `Retrying` | subclasses of library classes in the app (`protected` access also from a lambda), `object :` of a library class in a library function, sealed `Outcome` matched exhaustively in the app, `const val` / top-level `val`, default and named arguments, `vararg`, `inline reified` extension on `List<Task>` |
| `validation_kit` | `validk`: validation DSL with `@DslMarker`, `Rule<in T>` as a `fun interface`, `value class FieldName` | receiver lambdas over two nesting levels, `@PublishedApi internal constructor` behind a public `inline` entry point, `Iterable` implemented by the library, `inline reified` with a `noinline` block, generic bounds (`N : Comparable<N>`) |
| `plugin_registry` | `plugreg`: `Registry<I, O>`, `BasePlugin`, `Version`, `Global` | variance (`in I, out O`) with plugins implemented in the app, `inner class`, `object` with `by lazy` and a top-level property initialized on first use, a `ReadWriteProperty` delegate defined in the library and used by the app, `companion` with `const val` and a private constructor, `where` clauses, a local class returned through a `fun interface`, `tailrec` |
| `stream_toolkit` | `streamk`: `sequence { }` pipelines, `Window<T>`, an RPN evaluator, `AutoCloseable` helpers | `suspend` extension on `SequenceScope`, exception hierarchies thrown in the library and caught (and extended) in the app, `try` / `finally` with `use`, enum `entries` / `valueOf` across the boundary, `Regex` in the library |
| `api_surface` | `surface`: a collection of small API shapes | `inline val` on a generic `value class`, interface `companion` with `const val` and a private implementation class, default arguments that use other parameters and are overridden in the app, `out` variance with `Nothing`, `@JvmField` / `@JvmStatic`, non-local `return` through an `inline` function, annotations, extension `operator get` with a value class index |
| `visibility_error_members` | `ledger` | the app uses a `private` top-level function / property, an `internal` constructor, extension, `object` and typealias, a `private set` and a `protected` member of the library, and a `private` nested class: the build must fail |
| `strict_geometry` | `geom` (`memory = "strict"`) | Strict library and Strict app, `@AllocatedBy(Heap::class)`, owned results freed by the caller, `mini build --strict` |

What the projects found:

| finding | fix |
|---|---|
| a local class or anonymous object inside an `inline` function of a library was not supported in a consumer (`construction of '<anonymous>'`) | the consumer compiles its own copy of such classes (named `inl$...`, apart from the library's) |
| `internal` and `private` declarations of a library could be used by the app | the checker reports `cannot access 'x': it is internal in a library` for declarations of API stubs (not for the standard library, and not in files that suppress `INVISIBLE_*`) |
| the setter of a library property declared `private set` / `internal set` could be called by the app, and `protected` members of a library class could be used from anywhere | the checker reports `cannot assign 'x': its setter is private in a library` and `cannot access 'x': it is protected in a library` (a `protected` member is visible in the classes that have its owner as a supertype, also from lambdas and objects inside them); test `visibility_error_members` |
| an `internal` or `private` class or typealias of a library named in a type position (`val c: Cents`, `Account.Secret`) was accepted | the type resolution reports `cannot access 'x': it is internal in a library`; test `visibility_error_members` |
| a lambda whose result type does not fit the parameter type of a call with no type variables is accepted (`call { "x" }` for `f: () -> Int`) | not fixed: only wrong programs are affected, the conformance risk of rejecting more is larger than the gain |

## Strict mode over the real-world programs

`scripts/strict-coverage.sh --set realworld` adds `@file:MemoryMode(Mode.Strict)` to each program and runs the checker
(`tools/conformance/strict_coverage.py`, classes as in `docs/memory-model.md`):

| programs | accepted | (a) correct rejection | (b) limitation | (c) ICE |
|---:|---:|---:|---:|---:|
| 35 | 16 (46 %) | 13 | 6 | 0 |

Before strict-5 (no default allocator, no Strict collections) it was 6 of 35 (19 + 10 + 0). Every rejection of that measurement
was a consequence of the default allocator (5a) or of default-allocated values in collections, varargs and pairs (5c); both are
in now.

| class | cause | programs |
|---|---|---:|
| (a) | M0109: a borrowed value is returned as owned or stored in an owned field (`return account`, a parameter that becomes a field) | 9 |
| (a) | M0109: owner and borrower are mixed (an owner that is moved into a collection and read again; a value that is a new object on one path and a borrowed one on another) | 2 |
| (a) | a closure that captures the borrowed `this` is kept by a scheduler (release of a non-owned value) | 1 |
| (a) | two borrowed parameters put into a `Pair` (`from to to`): the pair would keep values whose allocator is unknown | 1 |
| (b) | M0110: a borrowed parameter or an element read from a collection is put into another collection (`chosen.add(items[i])`, `queue.addLast(start)`): the object of a borrowed parameter has an unknown allocator, so it cannot be counted by the new collection | 4 |
| (b) | M0109 / M0112: a closure that borrows a local or an owner is kept by a Managed function (`register(handler)`, `iterator { ... }`) | 2 |

Accepted (16): `aoc_instructions`, `calendar_dates`, `csv_report`, `delegated_properties`, `extension_toolkit`, `game_of_life`,
`inventory_generics`, `markdown_html`, `matrix_library`, `regex_logs`, `sequences_generators`, `stdlib_breadth`,
`string_formatting`, `sudoku_solver`, `text_statistics`, `tictactoe_minimax`. The rest is rejected for the ownership rules of the
specification: a function that returns or stores a *borrowed* value as an owner, or puts a value of unknown allocator into a
collection, is not memory safe without a reference count on every object, which Strict does not have. The (b) rows would need a
new specification decision (a count on every Strict object, or a `@Owned` copy), none is made here.

Rejected (19), grouped by cause (each needs a specification decision, none is made in strict-5; a rejection stays sound):

| cause | programs |
|---|---|
| a borrowed value (parameter, `this`, a read element) is returned as owned or stored in an owned field | `bank_domain`, `bignum_arithmetic`, `cli_tool`, `html_dsl`, `huffman_coding`, `json_codec`, `library_system`, `mini_interpreter`, `trie_autocomplete` |
| owner and borrower mixed (an owner moved into a collection and read again; new object on one path, borrowed on another) | `expression_calculator`, `state_machine` |
| a borrowed parameter or collection element is put into another collection (needs an `@Owned` copy or a count) | `aoc_dynamic_programming`, `aoc_grid_paths`, `graph_algorithms`, `priority_scheduler` |
| a closure that captures a borrowed `this` or an owner is kept by a Managed function (scheduler, `iterator {}`, handler registry) | `coroutine_scheduler`, `language_tour`, `event_system` |
| two borrowed parameters put into a `Pair` (`a to b`) | `thread_pool` |

The measurement found one checker bug, which is fixed: a collection of default-allocated objects built in an inner scope and
assigned to a variable of an outer scope (`if (k) { b = listOf(stmt()) }`, as in `mini_interpreter`) was reported as used
after free (corpus `s231_collection_assigned_to_outer_var`). `mini_interpreter` is now rejected for a correct reason
(`env` is a borrowed value stored in an owned field).

### The two M0101 rejections (`bignum_arithmetic`, `trie_autocomplete`), examined in strict-m0101

* `trie_autocomplete` (`longestCommonPrefix`, `val (c, child) = n.children.entries.first(); ... n = child`) was a **checker bug**: the destructured `Map.Entry` was treated as a collection of its own, and
  rebinding it in the next round ended the loan of `child`, so `n` was reported as read after the collection changed. The same program with `n = n.children.entries.first().value` was accepted.
  Fixed (an entry is a view of its map, `docs/memory-model.md` "Map views and entries"); corpus `s514` (accepted) and `s515`-`s518` (the real error class, a map cleared while a value read from it is in use, is still M0101;
  the same four programs were *accepted* before, a false negative that this fix closes). `trie_autocomplete` is still rejected, now only for the M0109 of group 1 (`find` returns the loan `n`).
* `bignum_arithmetic` (`fibonacci`: `val t = a + b; a = b; b = t` in `repeat`) is a **correct rejection**. By section 5.1 `b = t` with an existing owner `t` on the right makes `b` an alias, and `t` is
  released at the end of the loop body, so `b` (and `a`, an alias of `b`) read a released object in the next round. Corpus `s519` (rejected) is the reduced program; `s520` (accepted, runs) is the same
  rotation written with moves (`a = b.move()`, `b = t.move()`), the form the diagnostic's help should lead to. The rest of the file is group 1 (`return result`) and one M0110 (group 3).
  The message names `t` ("use of `t` after it was freed") although the use is of the aliases `a`/`b`; improving that text is a possible follow-up.

Field collections (strict-fieldcoll): the loans of the elements of a collection held in a field (`program[pc]` in `aoc_instructions`, `n.children` in `trie_autocomplete`) are now tracked and ended by a mutating call, a
reassignment of the field, or a call that may change a collection field (`docs/memory-model.md`, "Collections held in fields"). Measured over the 35 programs: still 16 accepted, no new M0101.
