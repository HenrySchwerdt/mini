---
title: "8. Choose and transform collections"
notebook: true
learnNotebook: true
aside: false
---

# 8. Choose and transform collections

A collection choice encodes what matters about a group of values. Does order matter? Are duplicates allowed? Do you look up a value by a key? Start with those questions before choosing an implementation.

This chapter builds a small attendance report and explains the costs and edge cases hidden by concise pipelines.

## List, set or map?

| Need | Shape | Example |
|---|---|---|
| Preserve an ordered series, including duplicates | `List<T>` | Events in arrival order |
| Record distinct membership | `Set<T>` | Names that have appeared |
| Associate one value with each key | `Map<K, V>` | Number of visits per name |

```kotlin
fun main() {
    val arrivals = listOf("Ada", "Bo", "Ada", "Cy")
    val attendees = arrivals.toSet()
    val visits = mutableMapOf<String, Int>()
    for (name in arrivals) visits[name] = (visits[name] ?: 0) + 1
    println(arrivals.size)
    println(attendees.size)
    println("Ada" in attendees)
    println(visits["Ada"])
    println(visits["missing"])
}
```
```output
4
3
true
2
null
```

The map lookup is nullable because a key may be absent. The expression `(visits[name] ?: 0) + 1` uses zero as the initial count. That fallback is valid because this map stores counts; using zero as a fallback for an invalid price or a missing temperature would need a separate justification.

Do not rely on incidental hash iteration order for a user-facing report. Sort keys or entries when order is part of the result. A set answers membership; it does not universally promise insertion order.

## Read-only is an interface, not a snapshot

```kotlin
fun main() {
    val editable = mutableListOf("Ada")
    val view: List<String> = editable
    val snapshot = editable.toList()
    editable.add("Bo")
    println(view)
    println(snapshot)
}
```
```output
[Ada, Bo]
[Ada]
```

The `List` interface does not offer `add`. It does not prove that no mutable alias exists. `snapshot` has a separate list structure. If an API promises a stable view, merely changing its declared return type to `List<T>` is insufficient; decide whether to copy or to share genuinely stable data.

Conversely, avoid copying automatically at every call. Sharing an immutable representation can be efficient and correct. The contract should tell callers whether subsequent changes are observable.

## Trace a pipeline one stage at a time

```kotlin
fun main() {
    val raw = listOf("  Ada", "", "Bo ", "Ada")
    val trimmed = raw.map { it.trim() }
    val nonempty = trimmed.filter { it.isNotEmpty() }
    val unique = nonempty.distinct()
    val display = unique.sorted().joinToString(", ")
    println(trimmed)
    println(nonempty)
    println(unique)
    println(display)
}
```
```output
[Ada, , Bo, Ada]
[Ada, Bo, Ada]
[Ada, Bo]
Ada, Bo
```

`map` transforms every element; `filter` selects elements without changing them; `distinct` removes repeated equal values; `sorted` produces an ordered result; `joinToString` formats it. Each operation has a different contract.

For eager lists these stages can create intermediate collections. Splitting them into named values is useful while learning and debugging. A chain is convenient once you understand each stage. Do not assume the compiler fuses every chain into one loop.

The order of operations can change meaning. Removing duplicate raw strings before trimming keeps `"Ada"` and `" Ada"` as distinct inputs; normalizing first merges them. Choose that policy deliberately, especially when case and Unicode affect identity.

## Grouping is different from associating

```kotlin
fun main() {
    val words = listOf("red", "blue", "tan", "gold")
    val groups = words.groupBy { it.length }
    println(groups[3])
    println(groups[4])
    val lastByLength = words.associateBy { it.length }
    println(lastByLength[3])
    println(lastByLength[4])
}
```
```output
[red, tan]
[blue, gold]
tan
gold
```

`groupBy` keeps all values under a key. `associateBy` keeps one value per key; later collisions replace earlier values. If repeated keys indicate invalid input, neither silent replacement nor automatic grouping is necessarily correct. Detect and report the duplicate.

This is a common source of plausible but incomplete reports. A concise transformation can discard information while still producing a correctly typed result.

## Aggregation needs an initial value

```kotlin
fun main() {
    val durations = listOf(12, 5, 8)
    val total = durations.fold(0L) { sum, duration -> sum + duration.toLong() }
    val emptyTotal = emptyList<Int>().fold(0) { sum, value -> sum + value }
    println(total)
    println(emptyTotal)
    println(durations.maxOrNull())
    println(emptyList<Int>().maxOrNull())
}
```
```output
25
0
12
null
```

`fold` starts from the value you supply and works on empty input. A maximum may be missing, so `maxOrNull` uses a nullable result. A default such as zero is not a valid maximum for every domain: a list of negative temperatures should not suddenly have a maximum of zero.

The accumulator type also matters. Start with `0L` when the total should be a `Long`. A function accepting a `List<Int>` can still return a wider aggregate.

## Arrays have a fixed size

```kotlin
fun main() {
    val counts = IntArray(3)
    for (index in counts.indices) counts[index] = index * index
    for (index in counts.indices) {
        if (index > 0) print(", ")
        print(counts[index])
    }
    println()
    println(counts.size)
}
```
```output
0, 1, 4
3
```

You can update elements of an array, but you cannot grow that same array with `add`. A primitive array such as `IntArray` is useful for dense numeric storage. A mutable list is useful when the number of elements changes. Neither choice is universally faster for an entire program: lookup patterns, boxing and copying also matter.

The example prints with a loop because this Mini stdlib does not currently provide every Kotlin primitive-array formatting extension. Use `indices` rather than writing `0..size`, which includes an invalid final index. Never mutate a collection's structure while iterating it unless the particular iteration API explicitly supports that operation.

## Exercise: count and present deterministically

Normalize names with `trim`, skip blank names, count occurrences and print keys alphabetically. The starter prints each nonblank arrival without counting it.

```kotlin exercise
fun main() {
    val input = listOf(" Bo", "Ada", "", "Bo ", "Ada", "Cy")
    for (name in input) if (name.isNotBlank()) println(name.trim())
}
```
```output
Ada: 2
Bo: 2
Cy: 1
```

::: details Hint
Use a mutable map while building the counts. Iterate `counts.keys.sorted()` when presenting the result. Keep normalization before lookup, so surrounding spaces do not create different keys.
:::

::: details Show solution
```kotlin
fun main() {
    val input = listOf(" Bo", "Ada", "", "Bo ", "Ada", "Cy")
    val counts = mutableMapOf<String, Int>()
    for (raw in input) {
        val name = raw.trim()
        if (name.isNotEmpty()) counts[name] = (counts[name] ?: 0) + 1
    }
    for (name in counts.keys.sorted()) println("$name: ${counts[name]}")
}
```
```output
Ada: 2
Bo: 2
Cy: 1
```

Should `"ada"` and `"Ada"` be the same person? That is a domain decision, not a property of `Map`. State the rule before adding a lowercase operation.
:::

## What changes in Mini?

Managed collections can hold ordinary Managed objects. Strict tracked owners currently cannot be placed into ordinary Kotlin collections; the checker reports `M0110`. A program that works with `List<Task>` in Managed mode cannot necessarily be converted by pasting a Strict annotation above it. Use the [fields and containers guide](/memory/fields) when you reach explicit ownership.

References: Kotlin [collections](https://kotlinlang.org/docs/collections-overview.html) and [collection transformations](https://kotlinlang.org/docs/collection-transformations.html); Mini's [standard library](/stdlib).
