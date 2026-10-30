---
title: "18. Ownership across API boundaries"
notebook: true
learnNotebook: true
aside: false
---

# 18. Ownership across API boundaries

The previous Strict chapter introduced the errors. Now design APIs that make those errors less likely. Treat ownership as a responsibility that moves through the program: exactly which part must eventually release a tracked allocation?

The examples in this chapter use **Check**. They teach the ownership model without claiming that every allocator program can currently execute on every native backend.

## Read a signature as a lifetime contract

| Signature position | Meaning for tracked objects |
|---|---|
| `fun inspect(value: Box)` | Borrow for this call; the caller remains responsible |
| `fun consume(value: @Owned Box)` | Take responsibility from the caller |
| `fun create(): @Owned Box` | Give a new responsibility to the caller |
| `fun view(...): @Borrowed Box?` | Lend an object whose owner must stay valid |

These annotations do not alter the business data inside `Box`. They describe how access and responsibility may cross the boundary.

<<< @/samples/memory/borrowed_param.kt{kotlin}

Trace this program on paper. `Box(7)` creates the obligation. `peek(b)` can read the value while `b` remains owned by the caller. `consume(b)` transfers responsibility; its body releases `b`. The caller must not release it again.

Change `peek` to call `b.free()`, then Check. The error is not that releasing memory is universally forbidden inside a function. The function was only lent access, so it has no authority to end the caller's ownership.

## Use a responsibility ledger

For each owned local, write a row while reviewing a function:

| Point | Owner | Permitted next actions |
|---|---|---|
| After allocation | Current local | Borrow, release or transfer |
| After a plain alias | Original local | Same responsibility; alias only borrows |
| After `move()` | Destination | Old owner may no longer be used |
| After an owned call argument | Callee | Caller may no longer use the transferred value |
| After release | None | No access through the old reference or its aliases |

A nullable owner follows the same idea. `null` carries no allocation, while a non-null value carries the responsibility. A function returning `@Owned Box?` cannot have its result discarded merely because it might be null. The non-null path still needs handling.

<<< @/samples/memory/move.kt{kotlin}

In the sample, `alias` does not split ownership into two pieces. After moving `a` to `b`, the obligation belongs to `b`; neither `a` nor a borrow derived from it becomes an independent owner.

## An object can own a tree

A container with owning fields releases its owned children when the container is released. This lets an API expose a single root rather than asking callers to track every node separately.

<<< @/samples/memory/linked_list.kt{kotlin}

Read the list construction slowly. `push` takes the old root as an owned parameter. The new node stores it in its owning `next` field. The result is the new root, which now owns the old list as a child. Repeating this operation builds a tree-shaped responsibility chain.

`length` only borrows. Its cursor can walk the nodes but cannot release them individually. `list.free()` releases the root and the owned fields beneath it.

An object's `close` implementation must not separately free fields that field cleanup will release. Doing both creates two attempts to discharge the same responsibility. Cleanup work for an external handle and automatic cleanup of owning fields have different roles.

## Taking a child out changes the parent's state

<<< @/samples/memory/field_move_out.kt{kotlin}

`swapOut` takes the owned value from `s.item`. It then restores the field to a valid empty state with `null`. The returned value becomes the caller's responsibility, independent of the now-empty slot.

A partial move cannot leave the parent with an invalid field indefinitely. The checker reports `M0113` if the field is not restored as required. Try removing `s.item = null`, Check, then restore it.

When replacing an owning field, also consider its old value. Simply assigning a new allocation can lose the obligation associated with the previous content. Release or move the old value according to the API's contract before replacing it.

This is why a method such as `replace` needs a precise answer to “what happens to the previous item?” Returning it, releasing it and keeping it elsewhere are three different APIs.

## Borrowed links are not a general safety escape hatch

A parent pointer marked `@Borrowed` can avoid an owning cycle. Mini currently treats these back-reference fields as unchecked: assignment is allowed without a complete lifetime proof. The design must ensure the parent remains valid while a child uses the link.

Do not describe every borrowed field as “the compiler proved this safe.” The tracked function/local borrowing rules and unchecked back-reference fields have different guarantees. If a graph naturally shares one lifetime, an arena may express it more clearly.

Similarly, ordinary Kotlin collections cannot currently store tracked owned values. The checker reports `M0110`. An explicit owning container with fields is supported; generic ownership-aware collection APIs are future work. See [Fields and containers](/memory/fields).

## Exercise: lend or consume?

Change `show` so that it only reads its argument. The caller should keep ownership and release the box after reading it twice. The starter tries to free a borrowed parameter.

```kotlin exercise check=M0107
@file:MemoryMode(Mode.Strict)
import mini.memory.*

class Box(val value: Int)

fun show(box: Box) {
    println(box.value)
    box.free()
}

fun main() {
    context(Heap()) {
        val box = Box(8)
        show(box)
        show(box)
        box.free()
    }
}
```

::: details Hint
The function's signature already says that it borrows. Keep its implementation within that contract. Do not add `@Owned` if the caller still needs two reads afterward.
:::

::: details Show solution
```kotlin check
@file:MemoryMode(Mode.Strict)
import mini.memory.*

class Box(val value: Int)

fun show(box: Box) {
    println(box.value)
}

fun main() {
    context(Heap()) {
        val box = Box(8)
        show(box)
        show(box)
        box.free()
    }
}
```
:::

## Review an API before writing its body

For a new function, state whether each tracked argument is borrowed or consumed; whether its result owns or borrows; which allocator its allocations require; and what happens on failure. A signature that leaves those answers ambiguous will make every caller harder to review.

Mini references: [ownership defaults](/memory/ownership), [fields and containers](/memory/fields), [M0107](/reference/diagnostics/M0107) and [M0113](/reference/diagnostics/M0113).
