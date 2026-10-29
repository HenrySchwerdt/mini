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

## Collections of owned values (not supported yet)

Tracked values must not be put into Kotlin collections: `list.add(box)` is [M0110](/reference/diagnostics/M0110). Types like `List<@Owned T>` and arrays of owned elements are planned. For now you build your own container class from owning fields, like the linked list above. Managed classes can still go into collections, as the [Managed page](./managed) shows.

Next: [Lambdas and captures](./lambdas).
