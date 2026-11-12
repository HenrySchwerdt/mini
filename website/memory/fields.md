# 6. Fields and containers of owned values

An object **owns its fields** of tracked types. Freeing the object frees them recursively, after `close()` has run ("field glue"). An object that is part of an owned tree is never freed on its own, only together with its root.

<<< @/samples/memory/linked_list.kt{kotlin}

`Node(v, list)` moves `list` into the new node's `next` field, and `push` returns the node as `@Owned`. The loop reassigns `list` each time, which is fine because the old value was moved into the parameter. `length` walks the list through a `@Borrowed` cursor. `list.free()` releases the head and, through the owned fields, the whole chain. The checker accepts this program.

## Overwriting a field

Assigning to an owning field whose old content is unknown is a leak ([M0102](/reference/diagnostics/M0102), path `overwritten`). Free the field or move out of it first.

## Taking a value out of a field

<<< @/samples/memory/field_move_out.kt{kotlin}

`s.item.move()` is a *partial move*. The field is then in the moved state and must be valid again (a new value, or `null`) before any other call and before the function ends; otherwise [M0113](/reference/diagnostics/M0113). Tracking goes one level deep (`local.field`).

## Back references

`@Borrowed var parent: Node?` marks an unchecked back reference. Assignment is allowed and the compiler does not check the lifetime. Ownership is a tree, so graphs with cycles need an arena or `@Borrowed` links.

## Collections of owned values

An object of the **default allocator** (a Strict class without `@AllocatedBy`, built where no allocator is visible) can be put into a Kotlin collection, a `vararg`, a map, a set or a pair: `val xs = mutableListOf(Box(1), Box(2)); xs.add(b)`. The collection keeps a reference count of its own and the owner keeps its ownership, so the object lives until both are done and nothing can dangle. `removeAt`, `set`, `put` and `remove` hand the element back as a new owner.

An element you read from a collection (`xs[0]`, an iterator, the variable of a `for` loop, the parameter of a `forEach` lambda) is a *loan*. Every call that may change the collection (`clear`, `remove`, `set`, `add`, `sort`... they carry `@Mutates` in the library) ends it, and using it afterwards is [M0101](/reference/diagnostics/M0101).

Objects of an explicit allocator (`Heap`, `Pool`, `Arena`) are not reference counted, so `list.add(box)` with one of them is still [M0110](/reference/diagnostics/M0110); so is a borrowed parameter, whose allocator is unknown. For those you build your own container class from owning fields, like the linked list above. Managed classes can go into collections as before, as the [Managed page](./managed) shows.

Next: [Lambdas and captures](./lambdas).
