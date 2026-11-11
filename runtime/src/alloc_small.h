/* Small-object allocator behind the default (Rc) and Heap allocators.
 *
 * mimalloc/tcmalloc style, simplified: blocks of 16-byte granules up to MK_SMALL_MAX bytes (the hidden prefix
 * included) come from thread-local free lists, one per size class. A class that runs dry is refilled from the
 * thread's spare batch, then from the central pool (a mutex-protected stack of batches per class) and, when that is
 * empty too, carved from a thread-local bump chunk. A thread that frees more than a batch of blocks of a class hands
 * the older batch to the central pool, so memory freed on another thread than it was allocated on (reference counted
 * objects die wherever the last reference is dropped) flows back to producers instead of piling up. No page headers,
 * no ownership lookup: a block simply joins the free list of the thread that frees it. Chunks are never returned to
 * the system.
 *
 * Blocks have no prefix: the object header is at block+0 and the size class is kept in its flags. The free-list link lives
 * in the first word of the block (the type pointer of the dead object): refcount and flags stay intact, which keeps the
 * MK_FLAG_FREED double-free check working until the block is reused.
 *
 * Not used (the plain malloc path stays) when MK_PLAIN_MALLOC is defined or under AddressSanitizer, so sanitizer
 * builds see every object. Blocks larger than MK_SMALL_MAX always use malloc/free. */
#ifndef MINIRT_ALLOC_SMALL_H
#define MINIRT_ALLOC_SMALL_H

#include "internal.h"

/* Windows currently has no per-thread cache-exit callback here. Keep the
 * default allocator on malloc until an FLS cleanup path can return caches. */
#if defined(MK_PLAIN_MALLOC) || defined(MK_HAS_ASAN) || defined(_WIN32)
#define MK_SMALL_ALLOC 0
#else
#define MK_SMALL_ALLOC 1
#endif

#if MK_SMALL_ALLOC

#define MK_SMALL_GRAN ((size_t)16)
#define MK_SMALL_MAX ((size_t)256)
#define MK_SMALL_CLASSES (MK_SMALL_MAX / MK_SMALL_GRAN + 1) /* index 0 unused */
typedef char mk_small_classes_match[MK_SMALL_CLASSES == MK_SMALL_CLASSES_PUBLIC ? 1 : -1];
#ifndef MK_SMALL_BATCH
#define MK_SMALL_BATCH 256u /* blocks per batch: the unit moved between a thread and the central pool */
#endif

typedef char mk_small_gran_is_max_align[MK_MAX_ALIGN == MK_SMALL_GRAN ? 1 : -1];

/* Per size class a thread keeps a LIFO `free` list (fewer than MK_SMALL_BATCH blocks) and at most one full `spare`
 * batch behind it, so at most 2 * MK_SMALL_BATCH blocks per class. Both are chains with a known tail, which makes
 * handing a batch to the central pool (or taking one) O(1): no pointer chasing through cold memory. */
/* (the typedef MkSmallCache is in minirt.h: generated code allocates from it inline) */
extern MK_TLS MkSmallCache mk_small_cache;

void *mk_small_refill(size_t cls); /* slow path of mk_small_carve: batches of freed blocks, then new memory */
void *mk_small_carve(size_t cls);  /* slow path of mk_small_alloc: the next block of the bump chunk, else mk_small_refill */
void mk_small_full(size_t cls);    /* slow path of mk_small_free: `free` holds a full batch */
void mk_small_register(void);      /* first block of a thread's list: arm the thread-exit flush */

static inline size_t mk_small_class(size_t total) { return (total + (MK_SMALL_GRAN - 1)) / MK_SMALL_GRAN; }

/* total <= MK_SMALL_MAX; the block is NOT zeroed. Returns NULL when out of memory. */
static inline void *mk_small_alloc(size_t cls) {
    MkSmallCache *t = &mk_small_cache;
    void *b = t->free[cls];
    if (b != NULL) {
        t->free[cls] = *(void **)b;
        t->count[cls]--;
        return b;
    }
    size_t sz = cls * MK_SMALL_GRAN;
    size_t align = sz & (~sz + 1u); /* the alignment a block of this size is carved at, see mk_small_carve */
    if (align > 64) align = 64;
    unsigned char *p = t->bump;
    if (((uintptr_t)p & (align - 1)) == 0 && (size_t)(t->end - p) >= sz) { /* the common case: the bump pointer is aligned already */
        t->bump = p + sz;
        return p;
    }
    return mk_small_carve(cls);
}

#if defined(__GNUC__) || defined(__clang__)
__attribute__((always_inline))
#endif
static inline void mk_small_free(void *b, size_t cls) {
    MkSmallCache *t = &mk_small_cache;
    *(void **)b = t->free[cls];
    t->free[cls] = b;
    uint32_t n = ++t->count[cls];
    if (n == 1) {
        t->tail[cls] = b;
        if (!t->registered) mk_small_register();
    } else if (n == MK_SMALL_BATCH) {
        mk_small_full(cls);
    }
}

/* An object (no prefix) carries its size class in bits 8..15 of its flags, see alloc.c. */
#define SMALL_CLASS_SHIFT 8
#define SMALL_CLASS_MASK 0xffu

/* The object is dead (disposed): mark it freed and give its block back. Only for objects without MK_FLAG_PREFIXED. */
#if defined(__GNUC__) || defined(__clang__)
__attribute__((always_inline))
#endif
static inline void mk_small_free_object(MkObject *object) {
    uint32_t flags = object->flags;
    size_t cls = (flags >> SMALL_CLASS_SHIFT) & SMALL_CLASS_MASK;
    object->refcount = 0;
    object->flags = flags | MK_FLAG_FREED;
    MK_STAT_FREE(cls * MK_SMALL_GRAN);
    mk_small_free(object, cls); /* overwrites the type word with the free-list link */
}

/* A reference counted object of `size` bytes (<= MK_SMALL_MAX) for a type that is never a cycle collection candidate and whose
 * bytes the caller writes in full (strings): no zeroing, no call. NULL when the block cannot be had (take mk_alloc_sized_plain). */
#if defined(__GNUC__) || defined(__clang__)
__attribute__((always_inline))
#endif
static inline MkObject *mk_small_new_plain(const MkTypeInfo *type, size_t size, uint32_t flags) {
    size_t cls = mk_small_class(size);
    MkObject *object = (MkObject *)mk_small_alloc(cls);
    if (object == NULL) return NULL;
    MK_STAT_SMALL(MK_SMALL_STAT_SMALL, 0);
    MK_STAT_ALLOC(cls * MK_SMALL_GRAN);
    object->type = type;
    object->refcount = 1;
    object->flags = ((uint32_t)cls << SMALL_CLASS_SHIFT) | flags;
    return object;
}

#endif /* MK_SMALL_ALLOC */
#endif
