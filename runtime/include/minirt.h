/*
 * libminirt — runtime ABI for Kotlin/Mini generated code.
 *
 * This header is the contract between the IR->C backend (compiler/) and the
 * runtime (runtime/). Generated C includes only this header. Changing a
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

/* Cycle collection (docs/memory-model.md, "Cycles"): calls `visit(child, ctx)` for every reference field of `self` that can
 * lead to a reference cycle (it may omit others, e.g. strings; NULL children are passed and must be ignored). */
typedef void (*MkTraceFn)(MkObject *self, void (*visit)(MkObject *child, void *ctx), void *ctx);

struct MkTypeInfo {
    const char *name;          /* fully qualified Kotlin name, e.g. "kotlin.String" */
    size_t instance_size;      /* bytes, including the MkObject header */
    const MkTypeInfo *super;   /* NULL for kotlin.Any */
    MkDisposeFn dispose;       /* may be NULL when there are no reference fields */
    void *const *vtable;       /* virtual method table, may be NULL */
    size_t vtable_length;
    const MkTypeInfo *const *interfaces; /* implemented interfaces, may be NULL */
    size_t interface_count;
    MkTraceFn trace;           /* NULL: instances cannot be part of a reference cycle (not cycle collection candidates) */
    /* Object arrays only (docs/memory-model.md, "Reified object arrays"): the element type the array was created with, as a
     * tag: the element's type info with bit 0 set when the elements may be null. NULL is `Any?` (an array created where the
     * element type is not known, and the base `MkObjectArray_ti`); every other object array has its own type info (interned by
     * mk_array_new_of) whose `super` is MkObjectArray_ti, so `is Array<*>` is the ordinary super walk. */
    const MkTypeInfo *elem;
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
    MK_FLAG_PREFIXED = 1u << 4, /* runtime-private: the block starts MK_PREFIX_SIZE bytes before the object and holds the
                                 owning allocator and the block size (every object of an allocator other than Rc/Heap, and
                                 large Rc/Heap objects). Never set it from generated code. */
    MK_FLAG_HEAP = 1u << 5,     /* runtime-private: an unprefixed block of the Heap allocator (else of the Rc allocator) */
    MK_FLAG_ARMED = 1u << 6,    /* cycle collection: a Managed object of a class with a trace function that is not in the
                                 candidate buffer. A release that leaves it alive must go through mk_release (the inline
                                 fast path of generated code tests this bit together with MK_FLAG_SHARED). */
    MK_FLAG_BUFFERED = 1u << 7, /* cycle collection: in the candidate buffer (runtime only) */
    /* bits 8..15 are runtime-private too: the small-object size class of an unprefixed block */
    MK_COLOR_SHIFT = 16,        /* cycle collection: colour of the object during a collection (bits 16..18, runtime only) */
    MK_FLAG_LATIN1 = 1u << 20,  /* kotlin.String only: the characters are one byte each (all below 256), see MkString */
};

/* Initialiser for an MkObject header in static storage. */
#define MK_STATIC_HEADER(type_info) {(type_info), 0u, MK_FLAG_STATIC}

/* Allocates a zeroed instance with refcount 1 from the default (Managed) allocator
 * (see mk_default_allocator). Aborts through mk_panic on out-of-memory. */
MkObject *mk_alloc(const MkTypeInfo *type);
/* Same, with the cycle collection decision made by the compiler (it knows whether the class has a trace function): the object is
 * armed (MK_FLAG_ARMED) / never armed. `mk_alloc` decides at run time from `type->trace`. */
MkObject *mk_alloc_armed(const MkTypeInfo *type);
MkObject *mk_alloc_plain(const MkTypeInfo *type);

/* The thread-local cache of the small-object allocator (runtime/src/alloc_small.h describes it); generated code takes a block of a
 * constant size class from it inline (mkh_alloc_inline of the C prelude) and calls mk_alloc_armed / mk_alloc_plain when the cache cannot serve. */
#define MK_SMALL_CLASSES_PUBLIC 17
typedef struct MkSmallCache {
    void *free[MK_SMALL_CLASSES_PUBLIC];
    void *tail[MK_SMALL_CLASSES_PUBLIC]; /* the oldest block of `free` (valid while count > 0) */
    void *spare[MK_SMALL_CLASSES_PUBLIC]; /* a full batch or NULL */
    void *spare_tail[MK_SMALL_CLASSES_PUBLIC];
    uint32_t count[MK_SMALL_CLASSES_PUBLIC];
    unsigned char *bump, *end;
    size_t next_chunk;
    int registered;
    size_t cycles_bytes; /* the thread's share of the allocation volume counter of the cycle collector (plain adds, no atomics; see MK_CYCLES_ADD_TLS) */
} MkSmallCache;
/* Inline allocation: only where the runtime itself uses the small allocator (see alloc_small.h MK_SMALL_ALLOC) and nothing counts allocations. */
#if (defined(__GNUC__) || defined(__clang__)) && !defined(MK_STATS) && !defined(MK_PLAIN_MALLOC) && !defined(_WIN32) && !defined(__wasm__) && \
    !defined(__SANITIZE_ADDRESS__) && !defined(MK_NO_INLINE_ALLOC)
#if defined(__has_feature)
#if !__has_feature(address_sanitizer)
#define MK_INLINE_ALLOC 1
#endif
#else
#define MK_INLINE_ALLOC 1
#endif
#endif
#ifdef MK_INLINE_ALLOC
extern _Thread_local MkSmallCache mk_small_cache;
extern size_t mk_cycles_alloc_bytes;
extern volatile int mk_cycles_ready; /* the collector is installed (set by the first armed allocation) */
#endif

extern size_t mk_cycles_alloc_bytes;
/* The allocation volume counter is a heuristic: a lost update between threads is harmless, but the access is a relaxed atomic load and store
 * (no data race for ThreadSanitizer; a plain mov, add, mov on x86-64 and arm64, not a locked instruction). */
/* The same for an allocation that goes through the thread's small cache (the inline path of generated code, alloc.c): a plain add to the thread's own
 * counter. The collector reads the counter of the thread that runs it plus the global one, to which a thread adds its share when it exits. */
#define MK_CYCLES_ADD_TLS(n) (mk_small_cache.cycles_bytes += (n))
#define MK_CYCLES_ADD(n) __atomic_store_n(&mk_cycles_alloc_bytes, __atomic_load_n(&mk_cycles_alloc_bytes, __ATOMIC_RELAXED) + (n), __ATOMIC_RELAXED)


/* Slow path of the generated `<object>_get` accessors of Kotlin `object`s and file classes holding top-level properties:
 * creates the instance and runs its initialiser once, also when threads race on the first access. `*slot` is published
 * (release store) only after the initialiser returned, so readers that see a non-NULL slot see an initialised object;
 * other threads block until it is. A thread that re-enters during its own initialisation gets the partial object. */
MkObject *mk_object_init(MkObject **slot, const MkTypeInfo *type, void (*ctor)(MkObject *));

/* Reference counting. Both accept NULL. Release frees the object (after calling
 * `dispose` along its type chain, most derived first) when the count reaches 0. */
void mk_retain(MkObject *object);
void mk_release(MkObject *object);

/* Runs the cycle collector now: frees the unreachable reference cycles among the buffered candidates (no-op while
 * threads run). The runtime also runs it when enough candidates piled up and at the end of main. Returns the number of
 * objects freed. */
size_t mk_cycles_collect(void);
/* Generated code: a release that left an object with MK_FLAG_ARMED alive (inline fast path of MKH_REL) records it as a candidate. */
void mk_cycles_candidate(MkObject *object);

/* Marks an object (but not its fields) as shared between threads. */
void mk_share(MkObject *object);

/* Type tests used for `is` / `as`. Accept NULL (returns false). */
bool mk_is_instance(const MkObject *object, const MkTypeInfo *type);

/* Built-in type infos. */
extern const MkTypeInfo mk_type_Any;
extern const MkTypeInfo mk_type_String;

/* ---- Allocators (mini.memory) ----------------------------------------------- */

/* C interop calloc wrapper. Kotlin Long arguments are always 64-bit, while C
 * size_t is 32-bit on wasm32. Reject negative or overflowing sizes before
 * converting to size_t, then call the platform calloc with its native ABI. */
void *mk_cinterop_calloc(int64_t count, int64_t size);

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

/* Immutable string. Two representations, told apart by MK_FLAG_LATIN1 in the header: UTF-16 (`chars`, two bytes per unit) and
 * "Latin-1" (every character below 256, one byte each: the same storage read as `uint8_t`). Both mean the same Kotlin string
 * (equal characters are equal strings whatever the representation); read it through mk_string_at / mk_string_to_utf8 / the
 * helpers below, never through `chars` alone. `chars` is not NUL-terminated. */
typedef struct MkString {
    MkObject header;
    MkInt length;
    MkChar chars[];
} MkString;

static inline int mk_string_is_latin1(const MkString *s) { return (s->header.flags & MK_FLAG_LATIN1) != 0; }
static inline const uint8_t *mk_string_bytes(const MkString *s) { return (const uint8_t *)(const void *)s->chars; }
/* The character at `index` (no checks). */
static inline MkChar mk_string_at(const MkString *s, MkInt index) {
    return mk_string_is_latin1(s) ? (MkChar)mk_string_bytes(s)[index] : s->chars[index];
}
/* Copies `count` characters starting at `start` as UTF-16 into `dst`. */
static inline void mk_string_copy_chars(const MkString *s, MkInt start, MkInt count, MkChar *dst) {
    if (mk_string_is_latin1(s)) {
        const uint8_t *b = mk_string_bytes(s) + start;
        for (MkInt i = 0; i < count; i++) dst[i] = b[i];
    } else {
        for (MkInt i = 0; i < count; i++) dst[i] = s->chars[start + i];
    }
}
/* The text as UTF-8 (malloc'ed, NUL-terminated); `*out_length` gets the length in bytes. */
char *mk_string_to_utf8(const MkString *s, size_t *out_length);

/* Static string literal: MK_STRING_LITERAL(name, u"text") declares
 * `static const` storage named `name` with the header and the characters. */
#define MK_STRING_LITERAL(name, utf16_literal)                                         \
    static const struct {                                                              \
        MkObject header;                                                               \
        MkInt length;                                                                  \
        MkChar chars[sizeof(utf16_literal) / sizeof(MkChar)];                          \
    } name = {MK_STATIC_HEADER(&mk_type_String),                                       \
              (MkInt)(sizeof(utf16_literal) / sizeof(MkChar) - 1), utf16_literal}

/* Static Latin-1 literal: MK_STRING_LITERAL8(name, len, {b0, b1, ..., 0}) (len bytes plus a terminating 0). */
#define MK_STRING_LITERAL8(name, len, ...)                                                       \
    static const struct {                                                                        \
        MkObject header;                                                                         \
        MkInt length;                                                                            \
        uint8_t chars[(len) + 1];                                                                \
    } name = {{&mk_type_String, 0u, MK_FLAG_STATIC | MK_FLAG_LATIN1}, (MkInt)(len), __VA_ARGS__}

MkString *mk_string_from_utf8(const char *utf8, size_t length); /* refcount 1 */
/* A string of `length` one-byte characters (refcount 1; MK_FLAG_LATIN1) whose bytes the caller must fill in. */
MkString *mk_string_alloc8(MkInt length);
/* A string of `length` UTF-16 units (refcount 1) whose characters the caller must
 * fill in: their contents are unspecified. Panics when length < 0 or on
 * out-of-memory. */
MkString *mk_string_alloc(MkInt length);
MkInt mk_string_length(const MkString *string); /* NULL is a NullPointerException (fatal) */
MkString *mk_string_concat(const MkString *left, const MkString *right);
/* a + b + c ... in one allocation (a NULL operand is "null", like mk_string_concat) */
MkString *mk_string_concat3(const MkString *a, const MkString *b, const MkString *c);
MkString *mk_string_concat4(const MkString *a, const MkString *b, const MkString *c, const MkString *d);
MkString *mk_string_concat5(const MkString *a, const MkString *b, const MkString *c, const MkString *d, const MkString *e);
MkString *mk_string_concat6(const MkString *a, const MkString *b, const MkString *c, const MkString *d, const MkString *e, const MkString *f);
/* a + b + c ... with Int / Long / Char operands among the strings: two bits per part in `mask` (0 = the string, 1 = decimal digits of the number,
 * 2 = the character), the number in the part's MkLong slot; one allocation, no string per number (string.c) */
MkString *mk_string_concat_m3(MkInt mask, const MkString *s0, MkLong v0, const MkString *s1, MkLong v1, const MkString *s2, MkLong v2);
MkString *mk_string_concat_m4(MkInt mask, const MkString *s0, MkLong v0, const MkString *s1, MkLong v1, const MkString *s2, MkLong v2,
                              const MkString *s3, MkLong v3);
MkString *mk_string_concat_m5(MkInt mask, const MkString *s0, MkLong v0, const MkString *s1, MkLong v1, const MkString *s2, MkLong v2,
                              const MkString *s3, MkLong v3, const MkString *s4, MkLong v4);
MkString *mk_string_concat_m6(MkInt mask, const MkString *s0, MkLong v0, const MkString *s1, MkLong v1, const MkString *s2, MkLong v2,
                              const MkString *s3, MkLong v3, const MkString *s4, MkLong v4, const MkString *s5, MkLong v5);
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
MkInt mk_string_hash_range(const MkString *string, MkInt from, MkInt to);                  /* hash of substring(from, to), none is made */
bool mk_string_equals_range(const MkString *key, const MkString *string, MkInt from, MkInt to); /* key == substring(from, to) */

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
/* Returns const like generated toString overrides, so both fit the same vtable slot type. */
const MkString *mk_any_to_string(MkObject *self); /* refcount 1 */
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
extern const MkTypeInfo mk_type_Float;
extern const MkTypeInfo mk_type_Byte;
extern const MkTypeInfo mk_type_Short;
extern const MkTypeInfo mk_type_Char;
typedef struct MkBoxIntCell { MkObject header; MkInt value; } MkBoxIntCell;
extern MkBoxIntCell *volatile mk_int_box_cache; /* shared immortal boxes of -128..1023 (NULL until the first is made): mk_box_int's fast path, inline */
#define MK_INT_BOX_CACHE_LO (-128)
#define MK_INT_BOX_CACHE_COUNT 1152
MkObject *mk_box_int(MkInt value); /* refcount 1 */
MkObject *mk_box_long(MkLong value);
MkObject *mk_box_boolean(MkBoolean value);
MkObject *mk_box_double(MkDouble value);
MkObject *mk_box_float(MkFloat value);
MkObject *mk_box_byte(MkByte value);
MkObject *mk_box_short(MkShort value);
MkObject *mk_box_char(MkChar value);
/* Unboxing aborts with NullPointerException on NULL. */
MkInt mk_unbox_int(const MkObject *box);
MkLong mk_unbox_long(const MkObject *box);
MkBoolean mk_unbox_boolean(const MkObject *box);
MkDouble mk_unbox_double(const MkObject *box);
MkFloat mk_unbox_float(const MkObject *box);
MkByte mk_unbox_byte(const MkObject *box);
MkShort mk_unbox_short(const MkObject *box);
MkChar mk_unbox_char(const MkObject *box);

/* ---- end P1-A ------------------------------------------------------------------- */

/* ---- Program entry ------------------------------------------------------------- */

/* Generated code defines `void mk_main(void)` for Kotlin `fun main()`.
 * The runtime provides C `main`, which initialises the runtime, calls mk_main,
 * flushes output and returns 0. */
void mk_main(void);

/* ---- v1-S: stdlib primitives (stdlib/mini/internal/Intrinsics.kt) --------- */
/* Arguments are borrowed; reference results are owned (+1). Out-of-range indices abort via mk_panic. */

MkString *mk_type_name(MkObject *object);  /* MkTypeInfo.name, e.g. "kotlin.Int" */
MkBoolean mk_type_is_named(MkObject *object, const MkString *name); /* is the type [name] the type of [object] or a supertype of it (NULL: false) */
MkInt mk_identity_hash(MkObject *object);

MkChar mk_string_char_at(const MkString *s, MkInt index);
MkInt mk_string_compare(const MkString *a, const MkString *b); /* java.lang.String.compareTo */
MkString *mk_string_substring(const MkString *s, MkInt start, MkInt end);
MkBoolean mk_string_is_double(const MkString *s); /* Java Double.parseDouble syntax (decimal), trimmed */
MkDouble mk_string_parse_double(const MkString *s);

/* Growable UTF-16 buffer behind kotlin.text.StringBuilder (an opaque heap object). */
/* The layout of the builder (runtime/src/v1s.c owns it; the C prelude of generated code appends a narrow character inline). */
typedef struct MkSb {
    MkObject header;
    void *data;      /* uint8_t[capacity] when narrow, MkChar[capacity] when wide */
    MkInt length;
    MkInt capacity;  /* in characters */
    MkInt wide;
    MkString *backing; /* NULL, or the string whose characters `data` is (see mk_sb_to_string); the builder owns one reference */
    MkChar inline_chars[];
} MkSb;
MkObject *mk_sb_new(void);
void mk_sb_append_string(MkObject *sb, const MkString *s);
void mk_sb_append_char(MkObject *sb, MkChar c);
void mk_sb_append_int(MkObject *sb, MkInt value);   /* decimal digits, written straight into the buffer */
void mk_sb_append_long(MkObject *sb, MkLong value);
MkInt mk_sb_length(MkObject *sb);
MkChar mk_sb_char_at(MkObject *sb, MkInt index);
void mk_sb_set_char_at(MkObject *sb, MkInt index, MkChar c);
void mk_sb_set_length(MkObject *sb, MkInt length); /* truncates, or pads with NUL characters */
MkString *mk_sb_to_string(MkObject *sb);

/* Fixed-size array of retained `Any?` slots (storage of ArrayList, HashMap, ...). */
MkObject *mk_ref_array_new(MkInt size); /* all slots null */
MkObject *mk_ref_array_get(MkObject *array, MkInt index); /* returns +1 (or NULL) */
void mk_ref_array_set(MkObject *array, MkInt index, MkObject *value); /* retains value, releases the old one */
MkInt mk_ref_array_length(MkObject *array);
MkObject *mk_ref_array_get_b(MkObject *array, MkInt index); /* D2: borrowed, no retain (valid while the array keeps the slot) */
void mk_ref_array_set_o(MkObject *array, MkInt index, MkObject *value); /* D2: takes over the caller's reference to value */
/* Moves the first `count` slots of `src` into the same slots of `dst` (whose slots there must be NULL): no reference count changes,
 * the slots of `src` are NULL afterwards. Growing a collection's storage. */
void mk_ref_array_move(MkObject *dst, MkObject *src, MkInt count);

/* Failures of the stdlib (until exceptions exist): print "fatal: <Kind>: <message>" to stderr and abort. */
_Noreturn void mk_throw_illegal_argument(const MkString *message);
_Noreturn void mk_throw_illegal_state(const MkString *message);
_Noreturn void mk_throw_index_out_of_bounds(const MkString *message);
_Noreturn void mk_throw_no_such_element(const MkString *message);
_Noreturn void mk_throw_number_format(const MkString *message);
_Noreturn void mk_throw_unsupported_operation(const MkString *message);
_Noreturn void mk_throw_not_implemented(const MkString *message);

/* ---- Concurrency layer (src/sys.c, docs/coroutines.md) ---------------------------------------------------------------- */

/* Clock, sleep, number of CPUs. */
int64_t mk_sys_monotonic_ns(void);
int64_t mk_sys_wall_ms(void);
void mk_sys_sleep_ns(int64_t ns);
int64_t mk_sys_cpu_count(void);

/* Locks and condition variables use the elements of a LongArray of at least MK_SYS_LOCK_WORDS elements as storage. */
#define MK_SYS_LOCK_WORDS 16
void mk_sys_lock_init(MkObject *storage);
void mk_sys_lock(MkObject *storage);
bool mk_sys_try_lock(MkObject *storage);
void mk_sys_unlock(MkObject *storage);
void mk_sys_cond_init(MkObject *storage);
void mk_sys_cond_wait(MkObject *cond, MkObject *lock);
bool mk_sys_cond_wait_ns(MkObject *cond, MkObject *lock, int64_t ns); /* true when it timed out */
void mk_sys_cond_signal(MkObject *cond);
void mk_sys_cond_broadcast(MkObject *cond);

/* Starts a thread running the Kotlin function `() -> Unit`; the handle (0 on failure) is joined with mk_sys_thread_join.
 * The first thread switches every reference count operation of the process to atomic. */
int64_t mk_sys_thread_start(MkObject *fn);
void mk_sys_thread_join(int64_t handle);   /* waits for the end of the thread; gives the handle up */
void mk_sys_thread_detach(int64_t handle); /* gives the handle up without waiting */
int64_t mk_sys_thread_id(void); /* 1 for the thread that started the first thread, then in start order */
void mk_sys_thread_yield(void);
MkObject *mk_sys_thread_data(void);              /* the object this thread stored (+1), or NULL */
void mk_sys_thread_set_data(MkObject *o);        /* stores o (retained; NULL clears) in the slot of this thread */

/* Atomics on element 0 of an IntArray / LongArray / object array (reference cell). */
int32_t mk_atomic_int_get(MkObject *a);
void mk_atomic_int_set(MkObject *a, int32_t v);
int32_t mk_atomic_int_add(MkObject *a, int32_t d);
int32_t mk_atomic_int_exchange(MkObject *a, int32_t v);
bool mk_atomic_int_cas(MkObject *a, int32_t expected, int32_t v);
int64_t mk_atomic_long_get(MkObject *a);
void mk_atomic_long_set(MkObject *a, int64_t v);
int64_t mk_atomic_long_add(MkObject *a, int64_t d);
int64_t mk_atomic_long_exchange(MkObject *a, int64_t v);
bool mk_atomic_long_cas(MkObject *a, int64_t expected, int64_t v);
MkObject *mk_atomic_ref_get(MkObject *a);
void mk_atomic_ref_set(MkObject *a, MkObject *v);
MkObject *mk_atomic_ref_exchange(MkObject *a, MkObject *v);
bool mk_atomic_ref_cas(MkObject *a, MkObject *expected, MkObject *v);
/* true once a thread has been started: objects may be reachable from several threads (read-only operations must not write caches then) */
bool mk_sys_threads_active(void);
extern volatile int mk_threads_active;
#if defined(__GNUC__) || defined(__clang__)
#define mk_sys_threads_active() (__atomic_load_n(&mk_threads_active, __ATOMIC_RELAXED) != 0) /* (inline: HashMap.get tests it on every hit) */
#endif

/* ---- end v1-S ------------------------------------------------------------------- */

/* ---- v1-L ---------------------------------------------------------------------- */

/* Exceptions, arrays and a few helpers for the v1-L language features (lambdas, generics,
 * exceptions). Implemented in src/v1l.c.
 *
 * Exception ABI (error-return, plan section 5): a function that can throw reports it by
 * leaving a non-NULL owned reference in the thread-local `mk_pending_exception` and returning a
 * dummy value (0 / NULL). Every caller checks `mk_pending_exception != NULL` right after the call
 * and either jumps to its `catch` handler or releases its locals and returns the same way. The
 * handler takes the exception with mk_catch(). The entry point (generated `mk_main` wrapper)
 * reports a still pending exception with mk_report_uncaught() and exits with status 1. */

#if defined(_MSC_VER) && !defined(__clang__)
#define MK_THREAD_LOCAL __declspec(thread)
#else
#define MK_THREAD_LOCAL _Thread_local
#endif

extern MK_THREAD_LOCAL MkObject *mk_pending_exception;

/* The address of this thread's `mk_pending_exception`. For generated code that cannot name the thread-local variable: the in-process
 * JIT (the ORC loader resolves no TLS relocations of generated code, `LlvmTarget.pendingAccessor`); optimizers may treat the call as
 * pure, so it costs one call per function that tests for exceptions. Ordinary builds reference the variable directly. */
MkObject **mk_pending_slot(void);

/* `throw e`: retains `e`, makes it the pending exception (a previously pending one is released).
 * `e == NULL` throws NullPointerException. The caller then runs its exceptional exit path. */
void mk_throw(MkObject *exception);
/* Makes `exception` pending, transferring the caller's reference (rethrow after a handler). */
void mk_rethrow(MkObject *exception);
/* Takes the pending exception: returns it (owned by the caller) and clears the pending state. */
MkObject *mk_catch(void);
/* Prints `Exception in thread "main" <toString>` to stderr (after flushing stdout) and exits
 * with status 1. Called with an exception pending. */
_Noreturn void mk_report_uncaught(void);

/* java.lang.Throwable layout; user exception classes embed it as their first member. */
typedef struct MkThrowable {
    MkObject header;
    const MkString *message; /* may be NULL */
    MkObject *cause;         /* may be NULL */
} MkThrowable;

/* Built-in exception classes. Every one has the MkThrowable layout; `struct <Name>` wraps it so that
 * generated subclasses can embed their superclass as the first member. vtable: toString, equals,
 * hashCode, then slot 3 = `message` getter (`const MkString *(MkObject *)`, owned result). Type
 * infos are named `<struct>_ti`, constructors `<struct>_new`. */
#define MK_EXCEPTION_SUBCLASSES(X)                                                       \
    X(MkException) X(MkRuntimeException) X(MkError)                                      \
    X(MkIllegalStateException) X(MkIllegalArgumentException) X(MkNullPointerException)  \
    X(MkArithmeticException) X(MkClassCastException) X(MkIndexOutOfBoundsException)     \
    X(MkArrayIndexOutOfBoundsException) X(MkStringIndexOutOfBoundsException)           \
    X(MkUnsupportedOperationException)                                                   \
    X(MkNoSuchElementException) X(MkNumberFormatException)                              \
    X(MkUninitializedPropertyAccessException) X(MkNotImplementedError)                   \
    X(MkNegativeArraySizeException) X(MkAssertionError) X(MkKotlinNothingValueException)  \
    X(MkExceptionInInitializerError) X(MkNoClassDefFoundError)
#define MK_DECLARE_EXCEPTION(Name)                                                       \
    extern const MkTypeInfo Name##_ti;                                                   \
    /* allocates an instance (refcount 1); retains message and cause */                  \
    MkObject *Name##_new(const MkString *message, MkObject *cause);
#define MK_DEFINE_EXCEPTION_STRUCT(Name) struct Name { MkThrowable base; };
extern const MkTypeInfo MkThrowable_ti;
MkObject *MkThrowable_new(const MkString *message, MkObject *cause);
MK_EXCEPTION_SUBCLASSES(MK_DECLARE_EXCEPTION)
MK_EXCEPTION_SUBCLASSES(MK_DEFINE_EXCEPTION_STRUCT)

/* Initialiser used by constructors of user classes that extend a built-in exception. */
void mk_throwable_init(MkObject *self, const MkString *message, MkObject *cause);
/* `Throwable(cause)`: message is cause.toString() (NULL cause gives NULL). Owned result. */
const MkString *mk_throwable_cause_message(MkObject *cause);
const MkString *mk_throwable_message(MkObject *self); /* the slot-3 default, owned result */
const MkString *mk_throwable_to_string(MkObject *self); /* the slot-0 default of every Throwable */
MkObject *mk_throwable_cause(MkObject *self);         /* owned result (may be NULL) */
void mk_throwable_print_stack_trace(MkObject *self);  /* toString to stderr */

/* Raising helpers for runtime-detected failures: they make the exception pending and return
 * (generated code checks afterwards, so callers return a dummy value). */
void mk_raise(const MkTypeInfo *type, const char *message); /* message may be NULL */
void mk_raise_npe(void);
void mk_raise_class_cast(const MkObject *object, const MkTypeInfo *target);
void mk_raise_arith(const char *message);
void mk_raise_index(MkInt index, MkInt length);
void mk_raise_illegal_argument(const char *message);
void mk_raise_illegal_state(const char *message);
void mk_raise_uninitialized(const char *property);

/* Exception-raising variants of the P1-A checks (those abort instead). */
MkObject *mk_checked_cast(MkObject *object, const MkTypeInfo *target); /* NULL passes */
MkObject *mk_checked_cast_nn(MkObject *object, const MkTypeInfo *target); /* NULL raises NullPointerException */
MkObject *mk_checked_nn(MkObject *object);                                   /* NULL raises NullPointerException */
MkObject *mk_checked_not_null(MkObject *object);
MkInt mk_checked_unbox_int(const MkObject *box);
MkLong mk_checked_unbox_long(const MkObject *box);
MkBoolean mk_checked_unbox_boolean(const MkObject *box);
MkDouble mk_checked_unbox_double(const MkObject *box);
MkFloat mk_checked_unbox_float(const MkObject *box);
/* kotlin.Number members on a boxed Int, Long, Double, Float, Byte or Short (NPE on null, ClassCastException otherwise). */
bool mk_is_number(const MkObject *o);
bool mk_is_function(const MkObject *o);
MkInt mk_double_compare(MkDouble a, MkDouble b); /* Double.compareTo: total order */
MkInt mk_checked_number_to_int(const MkObject *o);
MkLong mk_checked_number_to_long(const MkObject *o);
MkDouble mk_checked_number_to_double(const MkObject *o);
MkFloat mk_checked_number_to_float(const MkObject *o);
MkByte mk_checked_number_to_byte(const MkObject *o);
MkShort mk_checked_number_to_short(const MkObject *o);
MkChar mk_checked_number_to_char(const MkObject *o);
MkChar mk_number_to_char_default(MkObject *o);
MkByte mk_checked_unbox_byte(const MkObject *box);
MkShort mk_checked_unbox_short(const MkObject *box);
MkChar mk_checked_unbox_char(const MkObject *box);

/* `kotlin.Any()`: a plain instance (refcount 1). */
MkObject *mk_any_new(void);

/* Comparable.compareTo for boxed primitives and strings; for any other object the function
 * table of `comparable` (the Comparable interface type info, may be NULL) is used, method 0. */
MkInt mk_compare_to(MkObject *left, MkObject *right, const MkTypeInfo *comparable);
/* CharSequence values are Any references: a String (fast path) or an object whose class implements the CharSequence interface
   `cs` (itable slots: 0 length, 1 get, 2 subSequence). `cs` is NULL when no class implements the interface. */
MkInt mk_cs_length(MkObject *value, const MkTypeInfo *cs);
MkChar mk_cs_get(MkObject *value, MkInt index, const MkTypeInfo *cs);
MkObject *mk_cs_sub_sequence(MkObject *value, MkInt start, MkInt end, const MkTypeInfo *cs);
MkBoolean mk_is_char_sequence(const MkObject *value, const MkTypeInfo *cs);

/* Arrays. `Array<T>` holds references (released by dispose); IntArray etc. hold primitives.
 * Constructors raise NegativeArraySizeException for a negative size. Element access raises
 * ArrayIndexOutOfBoundsException. get_obj returns an owned reference. */
typedef struct MkArray {
    MkObject header;
    MkInt length;
    /* elements follow, 8-byte aligned */
} MkArray;
extern const MkTypeInfo MkObjectArray_ti, MkIntArray_ti, MkLongArray_ti, MkDoubleArray_ti, MkFloatArray_ti, MkByteArray_ti, MkShortArray_ti, MkBooleanArray_ti,
    MkCharArray_ti;
MkObject *mk_array_new_obj(MkInt length);
/* Reified object arrays: the array remembers its element type (a tag, see MkTypeInfo.elem). `Array<T>` values statically typed with a
 * known element class are read without a check; the check happens where an array enters such a type (mk_array_cast / mk_array_is)
 * and on stores through a view that does not know the element type (mk_array_store_obj). */
MkObject *mk_array_new_of(MkInt length, const MkTypeInfo *elem, MkInt nullable); /* elem == NULL: Any? */
MkObject *mk_array_new_like(const MkObject *source, MkInt length, MkInt nullable);  /* same element type as `source` (copyOf, plus, ...), optionally with null slots */
/* flags: 1 = the elements may be null, 2 = covariant view (`Array<out E>`: the recorded element type may be a subtype), 4 = the result may be null. */
MkObject *mk_array_cast(MkObject *object, const MkTypeInfo *elem, MkInt flags);  /* ClassCastException / NullPointerException (pending) */
MkBoolean mk_array_is(const MkObject *object, const MkTypeInfo *elem, MkInt flags);
void mk_array_store_obj(MkObject *array, MkInt index, MkObject *value);          /* set_obj plus the element type check (ClassCastException) */
static inline bool mk_is_object_array_type(const MkTypeInfo *t) { return t == &MkObjectArray_ti || t->super == &MkObjectArray_ti; }
MkObject *mk_array_new_int(MkInt length);
MkObject *mk_array_new_long(MkInt length);
MkObject *mk_array_new_double(MkInt length);
MkObject *mk_array_new_float(MkInt length);
MkObject *mk_array_new_byte(MkInt length);
MkObject *mk_array_new_short(MkInt length);
MkObject *mk_array_new_boolean(MkInt length);
MkObject *mk_array_new_char(MkInt length);
/* Element access is inline (hot loops): an unsigned compare covers both bounds, the failing path is an out-of-line
 * mk_raise_index that leaves ArrayIndexOutOfBoundsException pending (callers test mk_pending_exception afterwards) and
 * the access yields 0 / NULL. The elements start right behind the MkArray header. */
#define MK_ARRAY_ELEMS(type, array) ((type *)(((MkArray *)(array)) + 1))
#define MK_ARRAY_ACCESS(suffix, type, zero)                                                              \
    static inline type mk_array_get_##suffix(const MkObject *array, MkInt index) {                        \
        if (__builtin_expect((uint32_t)index >= (uint32_t)((const MkArray *)array)->length, 0)) {          \
            mk_raise_index(index, ((const MkArray *)array)->length);                                       \
            return (zero);                                                                                 \
        }                                                                                                  \
        return MK_ARRAY_ELEMS(type, array)[index];                                                         \
    }                                                                                                      \
    static inline void mk_array_set_##suffix(MkObject *array, MkInt index, type value) {                  \
        if (__builtin_expect((uint32_t)index >= (uint32_t)((const MkArray *)array)->length, 0)) {          \
            mk_raise_index(index, ((const MkArray *)array)->length);                                       \
            return;                                                                                        \
        }                                                                                                  \
        MK_ARRAY_ELEMS(type, array)[index] = value;                                                        \
    }
MK_ARRAY_ACCESS(int, MkInt, 0)
MK_ARRAY_ACCESS(long, MkLong, 0)
MK_ARRAY_ACCESS(double, MkDouble, 0.0)
MK_ARRAY_ACCESS(float, MkFloat, 0.0f)
MK_ARRAY_ACCESS(byte, MkByte, 0)
MK_ARRAY_ACCESS(short, MkShort, 0)
MK_ARRAY_ACCESS(boolean, MkBoolean, false)
MK_ARRAY_ACCESS(char, MkChar, 0)
#undef MK_ARRAY_ACCESS
static inline MkInt mk_array_length(const MkObject *array) { return ((const MkArray *)array)->length; }
/* get_obj returns an owned reference; set_obj retains the new element and releases the old one. */
static inline MkObject *mk_array_get_obj(const MkObject *array, MkInt index) {
    if (__builtin_expect((uint32_t)index >= (uint32_t)((const MkArray *)array)->length, 0)) {
        mk_raise_index(index, ((const MkArray *)array)->length);
        return NULL;
    }
    MkObject *v = MK_ARRAY_ELEMS(MkObject *, array)[index];
    mk_retain(v);
    return v;
}
/* Borrowed read for the code generator (RcOpt proved the array keeps the slot alive while the result is used): no retain. */
static inline MkObject *mk_array_get_obj_b(const MkObject *array, MkInt index) {
    if (__builtin_expect((uint32_t)index >= (uint32_t)((const MkArray *)array)->length, 0)) {
        mk_raise_index(index, ((const MkArray *)array)->length);
        return NULL;
    }
    return MK_ARRAY_ELEMS(MkObject *, array)[index];
}
static inline void mk_array_set_obj(MkObject *array, MkInt index, MkObject *value) {
    if (__builtin_expect((uint32_t)index >= (uint32_t)((const MkArray *)array)->length, 0)) {
        mk_raise_index(index, ((const MkArray *)array)->length);
        return;
    }
    MkObject **p = &MK_ARRAY_ELEMS(MkObject *, array)[index];
    mk_retain(value);
    MkObject *old = *p;
    *p = value;
    mk_release(old);
}
/* `string[index]`: the UTF-16 unit, or StringIndexOutOfBoundsException (pending) and 0. */
MkChar mk_string_get_char(const MkString *string, MkInt index);

/* Copies all elements of `src` (same kind) into `dst` starting at `index` (vararg spread). */
void mk_array_copy_into(MkObject *dst, MkInt index, const MkObject *src);
/* `contentToString()`: "[1, 2, 3]"; elements of object arrays through toString. NULL gives "null". */
const MkString *mk_array_content_to_string(const MkObject *array);

/* ---- end v1-L ------------------------------------------------------------------- */

/* ---- B1: file access and program arguments (stdlib/mini/internal/B1Intrinsics.kt) ----- */
/* Used by the self-hosted backend driver (compiler/native-driver). Arguments are borrowed,
 * reference results are owned (+1). Text files are UTF-8. */

/* Compiler host support. Spawn takes a borrowed U+0001-separated argv (no shell),
 * inherits streams/environment/cwd and returns a direct exit status, or -1. */
MkInt mk_platform_spawn(const MkString *argv);
MkLong mk_platform_nanos(void);

/* Reads the whole file and decodes it as UTF-8; NULL when it cannot be opened or read. */
MkString *mk_b1_read_file(const MkString *path);
/* Writes `text` as UTF-8 (create/truncate); false when it cannot be written. */
MkBoolean mk_b1_write_file(const MkString *path, const MkString *text);
/* 0 = does not exist, 1 = regular file, 2 = directory (stat, symbolic links followed). */
MkInt mk_b1_file_kind(const MkString *path);
/* The entries of a directory (without "." and ".."), sorted, joined with '\n'; NULL when it is not a readable directory. */
MkString *mk_b1_list_dir(const MkString *path);
/* Creates the directory and its missing parents (mode 0777 & umask); true when it exists afterwards. */
MkBoolean mk_b1_mkdirs(const MkString *path);
/* Removes a file or an empty directory; false when that fails. */
MkBoolean mk_b1_delete(const MkString *path);
/* Stores the process arguments (called by the runtime entry point before mk_main). */
void mk_b1_set_args(int argc, char **argv);
/* `Array<String>` of the command line arguments without the program name (for `fun main(args)`). */
MkObject *mk_b1_args(void);
/* Writes `text` (UTF-8) to standard error (standard output is flushed first). */
void mk_b1_eprint(const MkString *text);
/* Flushes the output and ends the process with `status`. */
void mk_b1_exit(MkInt status);

/* ---- end B1 ----------------------------------------------------------------------- */

/* ---- SYS: standard input and clocks (stdlib/mini/internal/SysIntrinsics.kt, runtime/src/sysio.c) ---- */
/* One line of standard input without its terminator, NULL at end of input. */
MkString *mk_sys_read_line(void);
/* Monotonic clock in nanoseconds. */
MkLong mk_sys_nano_time(void);
/* Milliseconds since the Unix epoch. */
MkLong mk_sys_epoch_millis(void);
/* IEEE 754 bits of a Double / Float. */
MkLong mk_sys_double_bits(MkDouble v);
MkInt mk_sys_float_bits(MkFloat v);
/* The Double / Float with the given IEEE 754 bits (Double.fromBits, Float.fromBits). */
MkDouble mk_sys_bits_double(MkLong bits);
MkFloat mk_sys_bits_float(MkInt bits);
/* ---- end SYS ---- */

/* ---- D1 ---- */
/* Runtime statistics (docs/benchmarks.md). Compile the runtime AND the generated code with
 * -DMK_STATS (`minic build --stats`) to count allocations, frees, retains and releases (split
 * atomic / non-atomic / no-op on NULL and static objects) and the peak of live objects and bytes.
 * A one-line summary per group goes to stderr at exit (lines start with "mk-stats:", key=value).
 * Without the macro none of this exists and the runtime has no extra code or data: zero cost. */
#ifdef MK_STATS
typedef struct MkStats {
    uint64_t allocs;          /* objects allocated (all allocators) */
    uint64_t frees;           /* objects whose memory was returned (mk_free / release at zero) */
    uint64_t live;            /* allocs - frees right now */
    uint64_t peak_live;       /* maximum of `live` */
    uint64_t bytes_total;     /* bytes requested from allocators (including the hidden prefix) */
    uint64_t bytes_live;      /* bytes of objects currently live */
    uint64_t bytes_peak;      /* maximum of `bytes_live` */
    uint64_t retains;         /* calls of mk_retain (including no-ops) */
    uint64_t retains_atomic;  /* ... on objects marked MK_FLAG_SHARED */
    uint64_t retains_noop;    /* ... on NULL, static or stack objects */
    uint64_t releases;        /* calls of mk_release (including no-ops) */
    uint64_t releases_atomic;
    uint64_t releases_noop;
} MkStats;
void mk_stats_get(MkStats *out);  /* consistent enough snapshot of all counters */
void mk_stats_reset(void);        /* zeroes the counters; live/peak restart from the current live set */
void mk_stats_dump(void);         /* writes the summary to stderr (also done by atexit) */
#else
#define mk_stats_reset() ((void)0)
#define mk_stats_dump() ((void)0)
#endif

/* ---- end D1 ---- */

/* ---- D2: Strict code generation (docs/memory-model.md, compiler/README.md "Strict code generation") ---- */

/* Allocator handles for the mini.memory classes: the address of the MkAllocator as a 64-bit integer (0 = the default
 * Rc allocator). The Kotlin classes Heap/Arena/Pool/DebugAllocator keep one in a field; the compiler passes it to
 * mk_alloc_with for allocation sites it routes there. */
int64_t mk_heap_allocator_h(void);
int64_t mk_arena_new_h(void);                       /* mk_arena_create(0) */
void mk_arena_close_h(int64_t arena);               /* mk_arena_destroy */
int64_t mk_pool_new_h(const MkTypeInfo *type, MkInt capacity); /* blocks for instances of `type`, `capacity` per slab */
void mk_pool_close_h(int64_t pool);                 /* mk_pool_destroy */
int64_t mk_debug_new_h(int64_t inner);              /* DebugAllocator over the allocator `inner` (0 = heap) */

/* `x.free()` in generated code: like mk_free for objects of a Deallocating allocator (Heap, Pool, DebugAllocator).
 * NULL is ignored; objects of the reference-counting allocator (Rc) are left to their count; objects of a Scoped
 * allocator panic like mk_free (arena objects cannot be freed individually). */
void mk_free_dealloc(MkObject *object);
/* The free glue of an object that owns `object` in a field (dispose of Strict classes): mk_free for objects of a
 * Deallocating allocator, one mk_release (the owner's count) for reference counted ones, nothing for static/stack. */
void mk_free_owned(MkObject *object);
/* Throws away an object whose constructor failed: released when reference counted, freed when it comes from a
 * Deallocating allocator, left to the arena when Scoped. */
void mk_discard(MkObject *object);

/* ---- end D2 ---- */

#ifdef __cplusplus
}
#endif

#endif
