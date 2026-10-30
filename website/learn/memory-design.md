---
title: "22. Choose memory boundaries deliberately"
notebook: true
learnNotebook: true
aside: false
---

# 22. Choose memory boundaries deliberately

Knowing the syntax of `Heap` or `Arena` is not yet enough to design a lifetime boundary. Start with the work your program performs and the data that must remain valid afterward. Then select a memory policy that expresses that lifetime.

This chapter combines the rules, including closures, threads and the limits of the current checker.

## Separate allocation strategy from checking mode

An allocator answers where storage comes from and how it can be released. A memory mode answers which obligations the compiler enforces and whether ordinary reference-counted behavior is available.

`Arena` is not another spelling of Strict. Strict is not another allocator. A context parameter makes an allocator available to a function; a file annotation changes how that file's tracked allocations are checked.

| Situation | Useful starting point | Question that remains |
|---|---|---|
| Small application with ordinary acyclic objects | Managed | Are long-lived references retaining unnecessary data? |
| Many temporary objects die after one phase | Arena | Does anything escape the phase? |
| Individual objects must be released or transferred | Heap with an explicit contract | Who owns each allocation on every path? |
| Many objects of one size are repeatedly reused | Pool | Are capacity and lifetime constraints suitable? |
| A function must avoid direct allocating operations | `@NoAlloc` where supported | What does the current checker actually analyze? |

The table is a starting point, not an automatic optimization rule. First establish correct behavior and a meaningful measurement.

## Design an arena around a phase

Imagine a parser creating many temporary nodes. It produces a compact report of counts and messages. The parser's working nodes are needed while parsing, but callers only need the report afterward.

A clean boundary separates temporary graph data from the durable result. Values copied out must be independent of the arena. A reference to a node, a view into its storage or a callback capturing it is still dependent on that region.

<<< @/samples/memory/arena_copy_out.kt{kotlin}

The sample returns an `Int` derived from points, rather than returning a point itself. Follow the regions: the caller owns the arena scope; the context function allocates in that arena; the computed scalar no longer depends on it.

An arena function can return region objects when its allocator is supplied by the caller and the returned objects remain inside that caller's lifetime. It cannot create a short-lived internal arena, close it and hand those objects to an unrelated caller.

## A transformation can change the lifetime contract

Suppose an API initially returns an integer total. Later someone changes it to return a lambda computing the total on demand. The return type has changed from a copied result to an operation that may retain the original data.

```kotlin
class MutableScore(var points: Int)

fun immediate(score: MutableScore): Int = score.points * 2
fun delayed(score: MutableScore): () -> Int = { score.points * 2 }

fun main() {
    val score = MutableScore(4)
    val now = immediate(score)
    val later = delayed(score)
    score.points = 7
    println(now)
    println(later())
}
```
```output
8
14
```

The difference is visible even in Managed mode. Across an arena or borrow boundary it can become a lifetime violation. An API review should ask whether a new return value contains references or code that can access region-dependent objects later.

## Inline and escaping lambdas have different obligations

A lambda body passed to an eligible inline operation can be checked as part of the surrounding control flow. A stored or returned lambda can outlive that flow.

<<< @/samples/memory/inline_lambda.kt{kotlin}

The checker can see the release within `repeat` and `run`. Moving the same body into a returned closure is a different program:

<<< @/samples/memory/escaping_lambda.kt{kotlin}

The second sample is expected to report `M0112`. General escaping closures that own captured tracked values are not supported yet. A borrowed capture can also outlive its lender, and an arena capture can outlive its region.

Possible redesigns include computing a scalar result before creating the callback, passing an object as a parameter when the callback is invoked, or keeping the callback inside the region. Choose based on the intended behavior; copying a scalar snapshot is wrong if the callback is supposed to observe live state.

## Thread safety is a separate contract

A value having an owner does not automatically make every allocator or operation thread-safe. Mini's checker recognizes annotated thread boundaries. Captured owners move into a thread lambda, which becomes responsible for them. Arena and Pool allocators are not `ThreadSafe` and cannot simply cross that boundary.

<<< @/samples/memory/thread_move.kt{kotlin}

This is a **checker example**, not a browser threading tutorial. The current `spawn`/thread support and runtime limits are documented in [Threads and ThreadSafe](/memory/threads); a complete native concurrency and async layer is still planned.

Using `Heap` for a transferable object answers an allocator restriction. It does not prove that concurrently mutating an arbitrary object is safe. Shared-state protocols and data races remain separate questions from ownership transfer and atomic reference counts.

## Understand the scope of `@NoAlloc`

<<< @/samples/memory/noalloc.kt{kotlin}

The local vector does not escape, so the sample is accepted and runs. The annotation is not a promise that every transitive call in the entire application has been proven allocation-free. Currently the checker analyzes the annotated body without complete interprocedural allocation summaries.

When a requirement is strict, such as “no allocation during this operation,” inspect those limits before treating an annotation as a whole-program guarantee. Also consider library calls, collection transformations and captured lambdas. A function name or a short expression is not evidence about allocation.

## Exercise: leave only independent data outside

Read this API design and propose a boundary before changing code:

- A phase builds a temporary graph of `Node` objects.
- The next phase needs only the number of nodes and a list of user-visible diagnostic strings.
- A callback currently captures the graph so that diagnostics can be formatted later.

Should the callback leave the arena? Which results could be copied before closing it? Who should own the durable diagnostic list?

::: details One defensible design
Format or copy the durable diagnostic content before closing the temporary region, and return an independently owned result containing only that content and scalar statistics. Keep all graph references and graph-dependent callbacks inside the region. The caller owns the durable result under its selected memory policy.

That describes a design target, not a claim that ownership-aware standard collection APIs are already implemented. The current Strict collection boundary may require a Managed result boundary or an explicit owning container. Check the supported boundary rather than forcing tracked nodes into a regular list.
:::

## Review the promises together

Before introducing an explicit-memory API, answer all of these in a short design note:

- What data must remain valid after the operation?
- Which allocations share one lifetime, and which leave independently?
- Which parameters are borrowed and which are consumed?
- Can an error, callback or thread carry references outside the intended region?
- Which guarantees are checked today, and which remain API responsibilities?
- What measurement justifies the added lifetime complexity?

An arena is most useful when the region is simple to explain. Strict ownership is most useful when the transfer contract is simple to explain. Start by simplifying the graph if neither description is clear.
