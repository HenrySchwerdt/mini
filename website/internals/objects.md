# Object layout and dispatch

## Header and layout

Every object, whether on the heap, in an arena or (in the future) on the stack, starts with the 16-byte `MkObject` header from `runtime/include/minirt.h`:

```c
struct MkObject {
    const MkTypeInfo *type;   // type info: name, size, super, dispose, vtable, interfaces
    uint32_t refcount;        // ignored for static and stack objects
    uint32_t flags;           // MK_FLAG_STATIC, MK_FLAG_STACK, MK_FLAG_SHARED, ...
};
```

A class is a C struct whose first member is the header (or the embedded superclass struct). Fields follow in declaration order:

```c
struct kc_Counter { MkObject header; MkInt f_n; };
struct kc_B       { struct kc_A base; /* fields of B */ };
```

Because the superclass is the first member, a pointer to a subclass is a valid pointer to its superclass. All class-typed values in the generated C are `MkObject *`; static types only decide field offsets, accessed as `((struct kc_A *)obj)->f_x`.

Each heap object is also preceded by a hidden prefix, `MK_MAX_ALIGN` bytes (16 on common targets) that stores a pointer to the allocator that produced it. That is how `x.free()` and the last `release` know where the memory goes back to. See [The runtime](./runtime).

## Type info and dispose

`MkTypeInfo` holds the Kotlin name, instance size, the superclass pointer, a `dispose` function, the vtable and the interface list. `dispose` releases exactly the reference-typed fields declared by that class. `mk_release` walks the type chain, most derived first, so a superclass's fields are released by the superclass's own `dispose`.

## Virtual dispatch

The vtable starts with three **fixed slots**: `toString`, `equals`, `hashCode` (defaults point to `mk_any_to_string` and friends in the runtime). After them comes one slot per member that *introduces* a new overridable member in the hierarchy (the topmost declaration in an override chain), superclass slots first.

A call `a.f()` on a class-typed receiver loads `((MkObject *)a)->type->vtable[slot]` and calls it with the receiver first. `final` members, which is the Kotlin default, are called directly. `super.f()` is a direct call.

`toString`/`equals`/`hashCode` on anything (strings, boxes, `null`) go through `mk_to_string`, `mk_equals` and `mk_hash_code`, which special-case built-in types and otherwise use the three slots.

## Interface dispatch (itables)

Each class lists every interface it implements, including those of its superclasses, in `MkTypeInfo.interfaces`. The vtable gets one extra entry per interface (same order, after the class slots) that points to an array of function pointers, indexed by the method's position among the interface's own declared members. A call through an interface receiver does `mk_itable(obj, &iface_ti)[index]`, a linear scan of the interface list: simple and correct, not fast. Default methods are ordinary functions stored in the table of classes that do not override them; abstract entries point to a stub that aborts with `AbstractMethodError`.

You can see this in the [closure listing](./closures): the closure class has a `Function1` itable.

## Casts and type tests

`is`/`!is` are `mk_is_instance`; `as` is `mk_cast`, which throws `ClassCastException` on failure (and `NullPointerException` for `null as Foo`); `as?` yields `null`.

## Nullability and boxing

A nullable reference is the same pointer with `NULL` for `null`; `!!` is `mk_not_null`. Primitives are unboxed `MkInt`, `MkLong`, `MkDouble`, ... as long as their static type is primitive. When a primitive must be an object (`Int?`, a value stored in `Any`, a type argument of a generic class, a function value's argument), it is **boxed**: a heap object of the type `kotlin.Int` and so on, created by `mk_box_int` and read by `mk_unbox_int`. See [Generics](./generics).

## Singletons and enums

An `object` (and every companion) is a singleton created lazily on first access by a `<stem>_get()` accessor: allocate, publish the pointer, then run the constructor. It is never released. Enum entries live in a hidden singleton created on first use of any entry.

## Strings

`kotlin.String` is a runtime object (`MkString`): UTF-16 code units plus length, not NUL-terminated. String literals are static objects whose retain and release are no-ops.
