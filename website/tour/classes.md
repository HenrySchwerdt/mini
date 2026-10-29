# Classes and interfaces

<<< @/samples/tour/classes.kt{kotlin}

Output:

<<< @/samples/tour/classes.out{text}

## Notes

- Classes are C structs that start with an object header; a subclass embeds its superclass struct. `open`/`override` members are called through a vtable, interface members through an itable, `final` members are called directly. See [Object layout and dispatch](/internals/objects).
- `object` declarations and companion objects are singletons created lazily on first access.
- Property accessors are ordinary methods; calls to default accessors are inlined to a field read or write.
- Objects are reference counted: when the last reference goes away, the object's fields are released and the memory is freed. You do not write anything for this in ordinary Kotlin code. See [Managed by default](/memory/managed).
