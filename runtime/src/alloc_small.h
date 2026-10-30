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
 * The free-list link lives at block + sizeof(void*) (like the Pool): the owner word at block+0 and the object header
 * stay intact, which keeps the MK_FLAG_FREED double-free check working until the block is reused.
 *
 * Not used (the plain malloc path stays) when MK_PLAIN_MALLOC is defined or under AddressSanitizer, so sanitizer
 * builds see every object. Blocks larger than MK_SMALL_MAX always use malloc/free. */
#ifndef MINIRT_ALLOC_SMALL_H
#define MINIRT_ALLOC_SMALL_H

#include "internal.h"

#if defined(MK_PLAIN_MALLOC) || defined(MK_HAS_ASAN)
#define MK_SMALL_ALLOC 0
#else
#define MK_SMALL_ALLOC 1
#endif

#if MK_SMALL_ALLOC

#define MK_SMALL_GRAN ((size_t)16)
#define MK_SMALL_MAX ((size_t)256)
#define MK_SMALL_CLASSES (MK_SMALL_MAX / MK_SMALL_GRAN + 1) /* index 0 unused */
#ifndef MK_SMALL_BATCH
#define MK_SMALL_BATCH 256u /* blocks per batch: the unit moved between a thread and the central pool */
#endif

typedef char mk_small_gran_is_max_align[MK_MAX_ALIGN == MK_SMALL_GRAN ? 1 : -1];

/* Per size class a thread keeps a LIFO `free` list (fewer than MK_SMALL_BATCH blocks) and at most one full `spare`
 * batch behind it, so at most 2 * MK_SMALL_BATCH blocks per class. Both are chains with a known tail, which makes
 * handing a batch to the central pool (or taking one) O(1): no pointer chasing through cold memory. */
typedef struct MkSmallCache {
    void *free[MK_SMALL_CLASSES];
    void *tail[MK_SMALL_CLASSES]; /* the oldest block of `free` (valid while count > 0) */
    void *spare[MK_SMALL_CLASSES]; /* a full batch (MK_SMALL_BATCH blocks) or NULL */
    void *spare_tail[MK_SMALL_CLASSES];
    uint32_t count[MK_SMALL_CLASSES];
    unsigned char *bump, *end;
    size_t next_chunk;
    int registered;
} MkSmallCache;
extern MK_TLS MkSmallCache mk_small_cache;

void *mk_small_refill(size_t cls); /* slow path of mk_small_alloc */
void mk_small_full(size_t cls);    /* slow path of mk_small_free: `free` holds a full batch */
void mk_small_register(void);      /* first block of a thread's list: arm the thread-exit flush */

static inline size_t mk_small_class(size_t total) { return (total + (MK_SMALL_GRAN - 1)) / MK_SMALL_GRAN; }

/* total <= MK_SMALL_MAX; the block is NOT zeroed. Returns NULL when out of memory. */
static inline void *mk_small_alloc(size_t cls) {
    MkSmallCache *t = &mk_small_cache;
    void *b = t->free[cls];
    if (b != NULL) {
        t->free[cls] = *(void **)(void *)((unsigned char *)b + sizeof(void *));
        t->count[cls]--;
        return b;
    }
    size_t sz = cls * MK_SMALL_GRAN;
    if ((size_t)(t->end - t->bump) >= sz) {
        b = t->bump;
        t->bump += sz;
        return b;
    }
    return mk_small_refill(cls);
}

static inline void mk_small_free(void *b, size_t cls) {
    MkSmallCache *t = &mk_small_cache;
    *(void **)(void *)((unsigned char *)b + sizeof(void *)) = t->free[cls];
    t->free[cls] = b;
    uint32_t n = ++t->count[cls];
    if (n == 1) {
        t->tail[cls] = b;
        if (!t->registered) mk_small_register();
    } else if (n == MK_SMALL_BATCH) {
        mk_small_full(cls);
    }
}

#endif /* MK_SMALL_ALLOC */
#endif
