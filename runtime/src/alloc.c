/* Allocation: default (Rc) and Heap allocators, the hidden allocator prefix,
 * mk_alloc*, mk_free.
 *
 * Small blocks of the Rc and Heap allocators (the vast majority of objects) have no prefix: the object is the block, and
 * its flags say who owns it (MK_FLAG_HEAP, else Rc) and which size class it is (bits 8..15), see alloc_small.h.
 *
 * Every other object (arena, pool, debug and user allocators; Rc/Heap objects too large for the size classes) has a prefix:
 *
 *   block                      object (= MkObject*)
 *   |<---- MK_MAX_ALIGN ------>|
 *   [ MkAllocator *owner | size ][ MkObject header | fields ... ]
 *
 * and MK_FLAG_PREFIXED in its flags. The prefix is exactly MK_MAX_ALIGN bytes so the object stays max-aligned.
 * `allocator->free` receives the block pointer (what `allocate` returned).
 * Static and stack objects have no prefix; their flags keep them away from
 * the free path.
 *
 * Pool and DebugAllocator leave the owner pointer (block+0) and the object
 * header (block+MK_MAX_ALIGN) of freed blocks intact (the Pool stores its
 * free-list link at block+sizeof(void*); the DebugAllocator quarantines the
 * block), which is what
 * lets mk_free diagnose double frees (MK_FLAG_FREED). A freed small block keeps the refcount and flags of its header
 * (its first word, the type, becomes the free-list link). */
#include "alloc_arena.h"
#include "alloc_small.h"
#include "internal.h"

#include <string.h>

MK_TLS MkAllocCtx mk_tls_ctx;
void *(*mk_arena_marker)(MkAllocator *self, size_t size, size_t align);
void *(*mk_arena_zeroed_hook)(MkAllocator *arena, size_t size);
size_t mk_cycles_alloc_bytes; /* (defined here, next to its only writer: a direct access) */

/* malloc/free underneath both the Rc and the Heap allocator. Stateless, so
 * trivially thread-safe. */
static void *malloc_allocate(MkAllocator *self, size_t size, size_t align) {
    (void)self;
    if (align > MK_MAX_ALIGN) return NULL; /* malloc cannot over-align portably */
    return malloc(size);
}

static void malloc_free(MkAllocator *self, void *memory) {
    (void)self;
    free(memory);
}

/* Rc: reference counted. The compiler may rely on release-at-zero. */
static MkAllocator default_allocator = {malloc_allocate, malloc_free,
                                        MK_ALLOCATOR_MANAGED | MK_ALLOCATOR_THREAD_SAFE};
/* Heap: manual ownership. Same memory source, different contract. */
static MkAllocator heap_allocator = {malloc_allocate, malloc_free,
                                     MK_ALLOCATOR_DEALLOCATING | MK_ALLOCATOR_THREAD_SAFE};

/* The allocator that owns a heap object: from the flags for an unprefixed (small Rc/Heap) block, else from the prefix. */
static inline MkAllocator *object_owner(const MkObject *object) {
    if (object->flags & MK_FLAG_PREFIXED) return *(MkAllocator **)(void *)((unsigned char *)object - MK_PREFIX_SIZE);
    return (object->flags & MK_FLAG_HEAP) ? &heap_allocator : &default_allocator;
}

MkAllocator *mk_default_allocator(void) { return &default_allocator; }
MkAllocator *mk_rc_allocator(void) { return &default_allocator; }
MkAllocator *mk_heap_allocator(void) { return &heap_allocator; }

uint32_t mk_allocator_traits(MkAllocator *allocator) {
    if (allocator == NULL) allocator = &default_allocator;
    if (allocator->traits != 0) return allocator->traits;
    return allocator->free != NULL ? MK_ALLOCATOR_DEALLOCATING : MK_ALLOCATOR_SCOPED;
}

size_t mk_object_block_size(const MkTypeInfo *type) {
    if (type == NULL || type->instance_size < sizeof(MkObject)) return 0;
    return type->instance_size + MK_PREFIX_SIZE;
}

#if defined(__GNUC__) || defined(__clang__)
#define MK_ALWAYS_INLINE static inline __attribute__((always_inline))
#else
#define MK_ALWAYS_INLINE static inline
#endif

/* Zeroes `n` bytes (a multiple of 16) at `b`: small blocks without a libc call. */
#if MK_SMALL_ALLOC
static inline void zero_small(void *b, size_t cls) {
    uint64_t *p = (uint64_t *)b;
    switch (cls) {
    case 4: p[6] = 0; p[7] = 0; /* fallthrough */
    case 3: p[4] = 0; p[5] = 0; /* fallthrough */
    case 2: p[2] = 0; p[3] = 0; /* fallthrough */
    case 1: p[0] = 0; p[1] = 0; break;
    default: memset(b, 0, cls * MK_SMALL_GRAN); break;
    }
}
#endif

#if MK_SMALL_ALLOC
/* A small object of the Rc allocator (no prefix); NULL when the block cannot be had (the callers fall back to the slow path).
 * `arm`: the object is a cycle collection candidate (a class with a trace function; the compiler decides, see mk_alloc_armed). */
MK_ALWAYS_INLINE MkObject *alloc_small_rc(const MkTypeInfo *type, size_t size, bool arm) {
    size_t cls = mk_small_class(size);
    unsigned char *block = mk_small_alloc(cls);
    if (block == NULL) return NULL;
    MK_STAT_SMALL(MK_SMALL_STAT_SMALL, 0);
    zero_small(block, cls);
    MK_STAT_ALLOC(cls * MK_SMALL_GRAN);
    MkObject *object = (MkObject *)(void *)block;
    object->type = type;
    object->refcount = 1;
    object->flags = ((uint32_t)cls << SMALL_CLASS_SHIFT) | (arm ? MK_FLAG_ARMED : 0u);
    if (arm) mk_cycles_alloc_bytes += cls * MK_SMALL_GRAN; /* (not atomic: only a heuristic) */
    return object;
}
#endif

MkObject *mk_alloc_sized_site(MkAllocator *allocator, const MkTypeInfo *type, size_t size,
                              const char *site) {
    if (type == NULL || size < sizeof(MkObject)) return NULL;
    if (allocator == NULL) allocator = &default_allocator;
    if (size > SIZE_MAX - MK_PREFIX_SIZE) return NULL;
#if MK_SMALL_ALLOC
    if ((allocator == &default_allocator || allocator == &heap_allocator) && size <= MK_SMALL_MAX) {
        /* Rc and Heap: thread-local size classes for small blocks, no prefix. Blocks of these two allocators are only ever
         * made here; mk_free_object_memory finds the class and the owner in the flags. */
        size_t cls = mk_small_class(size);
        unsigned char *block = mk_small_alloc(cls);
        MK_STAT_SMALL(MK_SMALL_STAT_SMALL, 0);
        if (block == NULL) return NULL;
        zero_small(block, cls);
        MK_STAT_ALLOC(cls * MK_SMALL_GRAN);
        MkObject *object = (MkObject *)(void *)block;
        object->type = type;
        object->refcount = 1;
        object->flags = ((uint32_t)cls << SMALL_CLASS_SHIFT) | (allocator == &heap_allocator ? MK_FLAG_HEAP : 0u);
        if (allocator == &default_allocator && MK_ARMABLE(type)) { /* a candidate for the cycle collector (cycles.c) */
            object->flags |= MK_FLAG_ARMED;
            mk_cycles_alloc_bytes += cls * MK_SMALL_GRAN; /* (not atomic: only a heuristic) */
        }
        return object;
    }
#endif
    size_t total = size + MK_PREFIX_SIZE;
    unsigned char *block;
    bool zeroed = false;
#if MK_SMALL_ALLOC
    if (allocator == &default_allocator || allocator == &heap_allocator) {
        /* too large for the size classes. A big block comes from calloc: libc maps fresh pages (already zero) and skips the memset */
        if (total >= ((size_t)128 << 10)) {
            block = calloc(1, total);
            zeroed = true;
        } else {
            block = malloc(total);
        }
        MK_STAT_SMALL(MK_SMALL_STAT_LARGE, 0);
    } else
#endif
    if (mk_arena_is_fast(allocator)) {
        MK_STAT_SMALL(MK_SMALL_STAT_ARENA_ALLOC, 0);
        block = mk_arena_zeroed_hook(allocator, total);
        zeroed = true;
    } else {
        mk_tls_ctx.type = type;
        mk_tls_ctx.site = site;
        block = allocator->allocate(allocator, total, MK_MAX_ALIGN);
        mk_tls_ctx.type = NULL;
        mk_tls_ctx.site = NULL;
    }
    if (block == NULL) return NULL;
    if (!zeroed) memset(block, 0, total);
    *(MkAllocator **)(void *)block = allocator;
    /* the padding of the prefix remembers the block size (mk_free_object_memory; statistics) */
    *(size_t *)(void *)(block + sizeof(void *)) = total;
    MK_STAT_ALLOC(total);
#ifdef MK_STATS
    if (mk_arena_is_fast(allocator)) mk_arena_stat_alloc(allocator, total);
#endif
    MkObject *object = (MkObject *)(void *)(block + MK_PREFIX_SIZE);
    object->type = type;
    object->refcount = 1;
    object->flags = MK_FLAG_PREFIXED;
    if (allocator == &default_allocator && MK_ARMABLE(type)) { /* a large candidate for the cycle collector */
        object->flags |= MK_FLAG_ARMED;
        mk_cycles_alloc_bytes += total;
    }
    return object;
}

MkObject *mk_alloc_sized(MkAllocator *allocator, const MkTypeInfo *type, size_t size) {
#if MK_SMALL_ALLOC
    if ((allocator == NULL || allocator == &default_allocator) && type != NULL && size >= sizeof(MkObject) && size <= MK_SMALL_MAX) {
        MkObject *object = alloc_small_rc(type, size, MK_ARMABLE(type));
        if (object != NULL) return object;
    }
#endif
    return mk_alloc_sized_site(allocator, type, size, NULL);
}

/* A block of `size` bytes of the default allocator for a type that is never a cycle collection candidate (strings, primitive arrays). */
MkObject *mk_alloc_sized_plain(const MkTypeInfo *type, size_t size) {
#if MK_SMALL_ALLOC
    if (size >= sizeof(MkObject) && size <= MK_SMALL_MAX) {
        MkObject *object = alloc_small_rc(type, size, false);
        if (object != NULL) return object;
    }
#endif
    return mk_alloc_sized_site(&default_allocator, type, size, NULL);
}

MkObject *mk_alloc_with(MkAllocator *allocator, const MkTypeInfo *type) {
    if (type == NULL) return NULL;
    return mk_alloc_sized_site(allocator, type, type->instance_size, NULL);
}

MkObject *mk_alloc_with_site(MkAllocator *allocator, const MkTypeInfo *type, const char *site) {
    if (type == NULL) return NULL;
    return mk_alloc_sized_site(allocator, type, type->instance_size, site);
}

/* The compiler knows whether a class has a trace function (can be part of a reference cycle): it calls mk_alloc_armed / mk_alloc_plain
 * and the decision costs nothing at run time. mk_alloc decides from `type->trace` (classes defined by the runtime). */
MK_ALWAYS_INLINE MkObject *alloc_class(const MkTypeInfo *type, bool arm) {
    (void)arm; /* (without the small-object allocator the generic path decides from type->trace) */
#if MK_SMALL_ALLOC
    if (type->instance_size >= sizeof(MkObject) && type->instance_size <= MK_SMALL_MAX) {
        MkObject *object = alloc_small_rc(type, type->instance_size, arm);
        if (object != NULL) return object;
    }
#endif
    MkObject *object = mk_alloc_with(&default_allocator, type);
    if (object == NULL) mk_panic("out of memory");
    return object;
}

MkObject *mk_alloc_armed(const MkTypeInfo *type) {
    mk_cycles_ensure();
    return alloc_class(type, true);
}
MkObject *mk_alloc_plain(const MkTypeInfo *type) { return alloc_class(type, false); }

MkObject *mk_alloc(const MkTypeInfo *type) {
    if (type == NULL) mk_panic("out of memory");
    return alloc_class(type, MK_ARMABLE(type));
}

void mk_free_object_memory(MkObject *object) {
#if MK_SMALL_ALLOC
    if (!(object->flags & MK_FLAG_PREFIXED)) {
        mk_small_free_object(object);
        return;
    }
#endif
    object->refcount = 0;
    object->flags |= MK_FLAG_FREED;
    unsigned char *block = (unsigned char *)object - MK_PREFIX_SIZE;
    MkAllocator *owner = *(MkAllocator **)(void *)block;
    size_t total = *(size_t *)(void *)(block + sizeof(void *));
    (void)total;
#ifdef MK_STATS
    if (!mk_arena_is_fast(owner)) MK_STAT_FREE(total); /* an arena counts its objects when it dies */
#endif
#if MK_SMALL_ALLOC
    if (owner == &default_allocator || owner == &heap_allocator) {
        free(block); /* a large Rc/Heap block */
        return;
    }
#endif
    if (owner->free != NULL) owner->free(owner, block);
}

static void free_with_site(MkObject *object, const char *site) {
    if (object == NULL) return;
    if (object->flags & (MK_FLAG_STATIC | MK_FLAG_STACK))
        mk_panic("mk_free: object is static or stack-allocated");
    MkAllocator *owner = object_owner(object);
    if (object->flags & MK_FLAG_FREED) {
        if (object->flags & MK_FLAG_PREFIXED) {
            if (mk_debug_is_debug(owner)) mk_debug_double_free(owner, (unsigned char *)object - MK_PREFIX_SIZE, site);
            char msg[160];
            snprintf(msg, sizeof msg, "mk_free: double free of %s",
                     object->type != NULL && object->type->name != NULL ? object->type->name : "?");
            mk_panic(msg);
        }
        mk_panic("mk_free: double free"); /* the type word of a freed small block is a free-list link */
    }
    if (mk_allocator_traits(owner) & MK_ALLOCATOR_SCOPED)
        mk_panic("mk_free: objects of a scoped allocator (arena) cannot be freed individually");
    for (const MkTypeInfo *t = object->type; t != NULL; t = t->super) {
        if (t->dispose != NULL) t->dispose(object);
    }
    mk_tls_ctx.site = site;
    mk_free_object_memory(object);
    mk_tls_ctx.site = NULL;
}

void mk_free(MkObject *object) { free_with_site(object, NULL); }
void mk_free_with_site(MkObject *object, const char *site) { free_with_site(object, site); }

/* ---- D2: handles for the mini.memory classes and the helpers of the Strict code generation ---- */

int64_t mk_heap_allocator_h(void) { return (int64_t)(intptr_t)mk_heap_allocator(); }

int64_t mk_arena_new_h(void) {
    MkAllocator *a = mk_arena_create(0);
    if (a == NULL) mk_panic("out of memory");
    return (int64_t)(intptr_t)a;
}

void mk_arena_close_h(int64_t arena) {
    if (arena != 0) mk_arena_destroy((MkAllocator *)(intptr_t)arena);
}

int64_t mk_pool_new_h(const MkTypeInfo *type, MkInt capacity) {
    MkAllocator *p = mk_pool_create(mk_object_block_size(type), capacity > 0 ? (size_t)capacity : 0);
    if (p == NULL) mk_panic("out of memory");
    return (int64_t)(intptr_t)p;
}

void mk_pool_close_h(int64_t pool) {
    if (pool != 0) mk_pool_destroy((MkAllocator *)(intptr_t)pool);
}

/* DebugAllocators of mini.memory have no close(): they report their leaks and are destroyed when the program ends. */
#define MK_DEBUG_MAX 64
static MkAllocator *debug_live[MK_DEBUG_MAX];
static int debug_count;

static void debug_at_exit(void) {
    for (int i = debug_count - 1; i >= 0; i--) {
        mk_debug_report_leaks(debug_live[i]);
        mk_debug_allocator_destroy(debug_live[i]);
    }
    debug_count = 0;
}

int64_t mk_debug_new_h(int64_t inner) {
    MkAllocator *d = mk_debug_allocator_create((MkAllocator *)(intptr_t)inner);
    if (d == NULL) mk_panic("out of memory");
    if (debug_count == 0) atexit(debug_at_exit);
    if (debug_count < MK_DEBUG_MAX) debug_live[debug_count++] = d;
    return (int64_t)(intptr_t)d;
}

void mk_free_dealloc(MkObject *object) {
    if (object == NULL) return;
    if (object->flags & (MK_FLAG_STATIC | MK_FLAG_STACK)) return;
    if (!(object->flags & MK_FLAG_FREED)) {
        MkAllocator *owner = object_owner(object);
        if (mk_allocator_traits(owner) & MK_ALLOCATOR_MANAGED) return; /* reference counted: frees itself */
    }
    free_with_site(object, NULL);
}

void mk_free_owned(MkObject *object) {
    if (object == NULL) return;
    if (object->flags & (MK_FLAG_STATIC | MK_FLAG_STACK)) return;
    if (!(object->flags & MK_FLAG_FREED)) {
        MkAllocator *owner = object_owner(object);
        if (mk_allocator_traits(owner) & MK_ALLOCATOR_MANAGED) {
            mk_release(object); /* the owner's count; anybody else holding a reference keeps the object alive */
            return;
        }
    }
    free_with_site(object, NULL);
}

void mk_discard(MkObject *object) {
    if (object == NULL) return;
    MkAllocator *owner = object_owner(object);
    uint32_t traits = mk_allocator_traits(owner);
    if (traits & MK_ALLOCATOR_SCOPED) return;
    if (traits & MK_ALLOCATOR_MANAGED) mk_release(object);
    else free_with_site(object, NULL);
}
