/*
 * libminirt — runtime ABI for Kotlin/Mini generated code.
 *
 * This header is the contract between the IR->C backend (compiler/) and the
 * runtime (runtime/minirt/). Generated C includes only this header. Changing a
 * declaration here is an ABI change: update both sides in the same commit.
 *
 * Naming: every exported symbol starts with `mk_` (functions) or `Mk` (types).
 */
#ifndef MINIRT_H
#define MINIRT_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ---- Kotlin primitive types ------------------------------------------------ */

typedef int8_t MkByte;
typedef int16_t MkShort;
typedef int32_t MkInt;
typedef int64_t MkLong;
typedef float MkFloat;
typedef double MkDouble;
typedef uint16_t MkChar; /* UTF-16 code unit, as in Kotlin */
typedef bool MkBoolean;

/* ---- Objects ---------------------------------------------------------------- */

typedef struct MkObject MkObject;
typedef struct MkTypeInfo MkTypeInfo;

/* Releases references held by the fields of `self`. It must not free `self`. */
typedef void (*MkDisposeFn)(MkObject *self);

struct MkTypeInfo {
    const char *name;          /* fully qualified Kotlin name, e.g. "kotlin.String" */
    size_t instance_size;      /* bytes, including the MkObject header */
    const MkTypeInfo *super;   /* NULL for kotlin.Any */
    MkDisposeFn dispose;       /* may be NULL when there are no reference fields */
    void *const *vtable;       /* virtual method table, may be NULL */
    size_t vtable_length;
    const MkTypeInfo *const *interfaces; /* implemented interfaces, may be NULL */
    size_t interface_count;
};

/* Every heap or stack object starts with this header. */
struct MkObject {
    const MkTypeInfo *type;
    uint32_t refcount; /* ignored when MK_FLAG_STATIC or MK_FLAG_STACK is set */
    uint32_t flags;
};

enum {
    MK_FLAG_STATIC = 1u << 0, /* static storage, e.g. string literals: never freed */
    MK_FLAG_STACK = 1u << 1,  /* lives on a C stack frame: never freed */
    MK_FLAG_SHARED = 1u << 2, /* reachable from several threads: atomic refcount */
    MK_FLAG_FREED = 1u << 3,  /* set by the runtime when the memory is returned; lets
                                 mk_free / mk_release diagnose double frees. Never set
                                 it from generated code. */
};

/* Initialiser for an MkObject header in static storage. */
#define MK_STATIC_HEADER(type_info) {(type_info), 0u, MK_FLAG_STATIC}

/* Allocates a zeroed instance with refcount 1 from the default (Managed) allocator
 * (see mk_default_allocator). Aborts through mk_panic on out-of-memory. */
MkObject *mk_alloc(const MkTypeInfo *type);

/* Reference counting. Both accept NULL. Release frees the object (after calling
 * `dispose` along its type chain, most derived first) when the count reaches 0. */
void mk_retain(MkObject *object);
void mk_release(MkObject *object);

/* Marks an object (but not its fields) as shared between threads. */
void mk_share(MkObject *object);

/* Type tests used for `is` / `as`. Accept NULL (returns false). */
bool mk_is_instance(const MkObject *object, const MkTypeInfo *type);

/* Built-in type infos. */
extern const MkTypeInfo mk_type_Any;
extern const MkTypeInfo mk_type_String;

/* ---- Allocators (mini.memory) ----------------------------------------------- */

/* Capability traits of an allocator (plan section 4.1). The compiler derives its
 * checks from them; the runtime uses them to decide what mk_free may do. */
enum {
    MK_ALLOCATOR_DEALLOCATING = 1u << 0, /* objects are freed one by one (mk_free / release) */
    MK_ALLOCATOR_SCOPED = 1u << 1,       /* everything dies together; no individual free */
    MK_ALLOCATOR_MANAGED = 1u << 2,      /* reference counted; releasing the last ref frees */
    MK_ALLOCATOR_THREAD_SAFE = 1u << 3,  /* may be used from several threads */
};

typedef struct MkAllocator MkAllocator;
struct MkAllocator {
    /* Returns a block of at least `size` bytes aligned to `align` (a power of two,
     * up to the maximal fundamental alignment, 16 on common targets, is always supported), or NULL on failure. Contents unspecified. */
    void *(*allocate)(MkAllocator *self, size_t size, size_t align);
    /* Returns a block obtained from `allocate`. May be NULL (Scoped allocators). */
    void (*free)(MkAllocator *self, void *memory);
    /* Bit set of MK_ALLOCATOR_*. ABI note: this field was appended after the first
     * release of the struct. Zero-initialised / positionally initialised legacy
     * allocators (`{allocate, free}`) have traits == 0, which mk_allocator_traits
     * reports as DEALLOCATING when `free != NULL` and SCOPED otherwise. Always read
     * traits through mk_allocator_traits. */
    uint32_t traits;
};

/* Trait bits of `allocator` (NULL = the default allocator), with the legacy
 * fallback described above. */
uint32_t mk_allocator_traits(MkAllocator *allocator);

/* Hidden allocator prefix. mk_alloc_with does NOT hand `type->instance_size` bytes
 * to `allocate`: every heap object lives in a block laid out as
 *
 *   block (what allocate returned)   object (the MkObject* seen by Kotlin code)
 *   |<-- max alignment (16) -->|
 *   [ MkAllocator *owner | padding  ][ MkObject header | fields ... ]
 *
 * so a custom allocator is asked for `mk_object_block_size(type)` bytes
 * (instance_size + prefix) with maximal alignment, the whole block is zeroed, and
 * `free` later receives the BLOCK pointer, not the object pointer. Static and stack
 * objects have no prefix and never reach `free`. */
size_t mk_object_block_size(const MkTypeInfo *type);

/* The allocator used by mk_alloc: reference counted, process-global, thread-safe.
 * Traits: MANAGED | THREAD_SAFE. It is backed by malloc/free, but unlike the Heap
 * allocator below the compiler may assume RC semantics (objects are released, not
 * freed by hand) for objects from it. */
MkAllocator *mk_default_allocator(void);
/* The same instance under its plan-section-4 name (Rc : Managed, ThreadSafe). */
MkAllocator *mk_rc_allocator(void);
/* Heap : Deallocating, ThreadSafe. malloc/free; objects are owned manually and
 * must be freed exactly once with mk_free (or by their refcount reaching 0). */
MkAllocator *mk_heap_allocator(void);

/* Allocates a zeroed instance with refcount 1 from `allocator` (NULL = default).
 * Returns NULL on failure, if `type` is NULL, or if type->instance_size is smaller
 * than sizeof(MkObject) (such "types" are interfaces and cannot be instantiated). */
MkObject *mk_alloc_with(MkAllocator *allocator, const MkTypeInfo *type);

/* Like mk_alloc_with, additionally telling the allocator where the allocation
 * happens (e.g. "src/Foo.kt:12:5"). Only the DebugAllocator uses the site; every
 * other allocator ignores it. The string is copied. */
MkObject *mk_alloc_with_site(MkAllocator *allocator, const MkTypeInfo *type, const char *site);

/* Explicit free for manual memory management. Runs the dispose chain (most derived
 * first) and returns the memory to the owning allocator REGARDLESS of the refcount:
 * manual-mode objects have refcount 1 and are never retained by generated code, so
 * mk_free is their equivalent of the final mk_release. Accepts NULL (no-op).
 * Panics if the object is static or stack-allocated, was already freed (reliable
 * with Pool and DebugAllocator memory, best effort otherwise: ASan reports the rest)
 * or belongs to a SCOPED allocator (arena objects cannot be freed individually).
 * Do not mix: after mk_free any remaining reference is dangling. */
void mk_free(MkObject *object);
/* mk_free that reports `site` as the place of the free to the DebugAllocator. */
void mk_free_with_site(MkObject *object, const char *site);

/* -- Arena (Scoped, not thread-safe) --
 * Bump allocation in chunks that grow geometrically; oversized requests get a
 * dedicated chunk. Alignment up to any power of two is honoured. `initial` is the
 * first chunk size in bytes (0 = 4096). Returns NULL on out-of-memory.
 * Dispose policy: the arena NEVER runs `dispose` for objects still alive in it,
 * neither in reset nor in destroy. It does not track objects (zero per-allocation
 * overhead) and the compiler's escape analysis guarantees nothing outlives the
 * scope; arena objects must therefore not own reference-counted fields that need
 * releasing, or the code must release them explicitly. Releasing an arena object
 * through mk_release runs dispose but not free. */
MkAllocator *mk_arena_create(size_t initial);
/* Invalidates every object allocated so far but keeps regular chunks for reuse and
 * frees dedicated oversized chunks. */
void mk_arena_reset(MkAllocator *arena);
/* Frees all chunks. All objects of the arena become invalid. */
void mk_arena_destroy(MkAllocator *arena);
size_t mk_arena_used(const MkAllocator *arena);     /* bytes handed out since the last reset */
size_t mk_arena_reserved(const MkAllocator *arena); /* bytes held in chunks */

/* -- Pool (Deallocating, not thread-safe) --
 * Free-list allocator for blocks of one fixed size, carved from slabs of
 * `blocks_per_slab` blocks (0 = 64). Requests larger than `block_size`, or with an
 * alignment above the maximal fundamental one, fail with NULL. For objects of one type use
 * block_size = mk_object_block_size(type). `block_size` is rounded up to the maximal alignment. */
MkAllocator *mk_pool_create(size_t block_size, size_t blocks_per_slab);
void mk_pool_destroy(MkAllocator *pool); /* frees all slabs, live blocks included */
size_t mk_pool_live_count(const MkAllocator *pool);

/* -- DebugAllocator --
 * Wraps `inner` (NULL = heap) and records every live allocation with the site given
 * through mk_alloc_with_site (or "<unknown>"), counts allocations and frees, and
 * diagnoses misuse: freeing a block twice or freeing a pointer it never returned
 * panics with the allocation site (and the earlier free site). Freed blocks are
 * quarantined (the last few are kept unreleased) so double frees stay detectable.
 * Its traits are the inner allocator's; it is thread-safe when the inner one is
 * (internally locked). For a SCOPED inner allocator, releases only update the books.
 * Intended for a `--debug-memory` build mode. */
MkAllocator *mk_debug_allocator_create(MkAllocator *inner);
/* Releases the books, the quarantine and, through `inner`, every block that is
 * still live. It does not run dispose. Does not destroy `inner`. */
void mk_debug_allocator_destroy(MkAllocator *debug);

typedef struct MkDebugStats {
    size_t allocations; /* total successful allocations */
    size_t frees;       /* total frees */
    size_t live;        /* allocations - frees */
    size_t live_bytes;  /* block bytes of live allocations (prefix included) */
} MkDebugStats;
void mk_debug_stats(MkAllocator *debug, MkDebugStats *out);

/* Prints `leak: <TypeName> allocated at <site>` to stderr for every live allocation
 * (in allocation order) and returns how many there are. */
size_t mk_debug_report_leaks(MkAllocator *debug);

/* ---- kotlin.String ----------------------------------------------------------- */

/* Immutable UTF-16 string. `chars` is not NUL-terminated. */
typedef struct MkString {
    MkObject header;
    MkInt length;
    MkChar chars[];
} MkString;

/* Static string literal: MK_STRING_LITERAL(name, u"text") declares
 * `static const` storage named `name` with the header and the characters. */
#define MK_STRING_LITERAL(name, utf16_literal)                                         \
    static const struct {                                                              \
        MkObject header;                                                               \
        MkInt length;                                                                  \
        MkChar chars[sizeof(utf16_literal) / sizeof(MkChar)];                          \
    } name = {MK_STATIC_HEADER(&mk_type_String),                                       \
              (MkInt)(sizeof(utf16_literal) / sizeof(MkChar) - 1), utf16_literal}

MkString *mk_string_from_utf8(const char *utf8, size_t length); /* refcount 1 */
/* A string of `length` UTF-16 units (refcount 1) whose characters the caller must
 * fill in: their contents are unspecified. Panics when length < 0 or on
 * out-of-memory. */
MkString *mk_string_alloc(MkInt length);
MkInt mk_string_length(const MkString *string); /* 0 for NULL */
MkString *mk_string_concat(const MkString *left, const MkString *right);
MkString *mk_string_from_int(MkInt value);
MkString *mk_string_from_long(MkLong value);
MkString *mk_string_from_float(MkFloat value); /* Kotlin/JVM Float.toString */
MkString *mk_string_from_double(MkDouble value);
MkString *mk_string_from_byte(MkByte value);
MkString *mk_string_from_short(MkShort value);
MkString *mk_string_from_boolean(MkBoolean value);
MkString *mk_string_from_char(MkChar value);
bool mk_string_equals(const MkString *left, const MkString *right); /* NULL-safe */
MkInt mk_string_hash(const MkString *string);                         /* Kotlin/Java hash */

/* ---- Console (kotlin.io) ------------------------------------------------------- */

/* stdout is fully buffered. A NULL string prints as "null" (Kotlin String?). */
void mk_print_string(const MkString *string); /* writes UTF-8 to stdout, no newline */
/* Allocation-free printing without newline, so `print(x)` needs no temporaries.
 * Output is identical to the corresponding mk_string_from_* text. */
void mk_print_int(MkInt value);
void mk_print_long(MkLong value);
void mk_print_float(MkFloat value);
void mk_print_double(MkDouble value);
void mk_print_boolean(MkBoolean value);
void mk_print_char(MkChar value);
void mk_flush(void); /* flushes stdout; mk_panic and the entry point do it too */
void mk_println_string(const MkString *string);
void mk_println_int(MkInt value);
void mk_println_long(MkLong value);
void mk_println_float(MkFloat value);
void mk_println_double(MkDouble value);
void mk_println_boolean(MkBoolean value);
void mk_println_char(MkChar value);
void mk_println_empty(void);

/* ---- Failure ------------------------------------------------------------------- */

/* Prints the message to stderr and aborts. Used until exceptions exist. */
_Noreturn void mk_panic(const char *message);
/* Same with a Kotlin string message (NULL prints "null"). */
_Noreturn void mk_panic_string(const MkString *message);

/* ---- P1-A: classes ------------------------------------------------------------ */

/* Class model used by generated code.
 *
 * Virtual dispatch: `MkTypeInfo.vtable` of every compiler-generated class starts with
 * three fixed slots (the kotlin.Any members), followed by one slot per user-declared
 * open/abstract/override member in declaration order (superclass slots first).
 * Slot functions take the receiver as first (MkObject *) argument. Reference results
 * are owned (+1); reference arguments are borrowed.
 *
 * Interface dispatch (per-class itable lookup by interface type info): the class'
 * `interfaces` array lists ALL interfaces the class implements, transitively and
 * including those of its superclasses. The vtable holds, after the class slots, one
 * pointer per entry of `interfaces` (same order), each pointing to that interface's
 * function table for this class (an array of function pointers indexed by the method
 * index the interface declares). `vtable_length` counts class slots + interface
 * entries, so the interface block starts at vtable[vtable_length - interface_count]. */
enum {
    MK_VSLOT_TO_STRING = 0,
    MK_VSLOT_EQUALS = 1,
    MK_VSLOT_HASH_CODE = 2,
    MK_VSLOT_FIRST_USER = 3,
};

/* Default kotlin.Any members. toString gives "Name@<hex identity hash>". */
MkString *mk_any_to_string(MkObject *self); /* refcount 1 */
bool mk_any_equals(MkObject *self, MkObject *other);
MkInt mk_any_hash_code(MkObject *self);

/* Null-safe virtual versions (also correct for strings and boxed primitives).
 * mk_to_string(NULL) is "null" and returns an owned string; mk_hash_code(NULL) is 0;
 * mk_equals(NULL, NULL) is true. */
MkString *mk_to_string(MkObject *object);
bool mk_equals(MkObject *left, MkObject *right);
MkInt mk_hash_code(MkObject *object);

/* Function table of `object`'s class for `iface`, or NULL if it does not implement it. */
void *const *mk_itable(const MkObject *object, const MkTypeInfo *iface);

/* Failures with Kotlin exception names (until exceptions exist these abort). */
_Noreturn void mk_throw_npe(void);
_Noreturn void mk_throw_class_cast(const MkObject *object, const MkTypeInfo *target);

/* `as`: returns `object` (NULL passes) or aborts with ClassCastException. */
MkObject *mk_cast(MkObject *object, const MkTypeInfo *target);
/* `!!`: returns `object` or aborts with NullPointerException. */
MkObject *mk_not_null(MkObject *object);

/* Boxed primitives (representation of Int?, Long?, Boolean?, Double?, Char?, and of
 * primitives stored as Any). Boxes are ordinary reference-counted objects. */
extern const MkTypeInfo mk_type_Int;
extern const MkTypeInfo mk_type_Long;
extern const MkTypeInfo mk_type_Boolean;
extern const MkTypeInfo mk_type_Double;
extern const MkTypeInfo mk_type_Char;
MkObject *mk_box_int(MkInt value); /* refcount 1 */
MkObject *mk_box_long(MkLong value);
MkObject *mk_box_boolean(MkBoolean value);
MkObject *mk_box_double(MkDouble value);
MkObject *mk_box_char(MkChar value);
/* Unboxing aborts with NullPointerException on NULL. */
MkInt mk_unbox_int(const MkObject *box);
MkLong mk_unbox_long(const MkObject *box);
MkBoolean mk_unbox_boolean(const MkObject *box);
MkDouble mk_unbox_double(const MkObject *box);
MkChar mk_unbox_char(const MkObject *box);

/* ---- end P1-A ------------------------------------------------------------------- */

/* ---- Program entry ------------------------------------------------------------- */

/* Generated code defines `void mk_main(void)` for Kotlin `fun main()`.
 * The runtime provides C `main`, which initialises the runtime, calls mk_main,
 * flushes output and returns 0. */
void mk_main(void);

#ifdef __cplusplus
}
#endif

#endif
