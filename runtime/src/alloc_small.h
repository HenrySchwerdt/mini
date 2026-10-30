/* Small-object allocator behind the default (Rc) and Heap allocators.
 *
 * mimalloc/tcmalloc style, simplified: blocks of 16-byte granules up to MK_SMALL_MAX bytes (the hidden prefix
 * included) come from thread-local free lists, one per size class. A class that runs dry is refilled from the
 * central pool (a mutex-protected chain per class) and, when that is empty too, carved from a thread-local bump
 * chunk. A thread that frees more than MK_SMALL_LIMIT blocks of a class hands a batch to the central pool, so memory
 * freed on another thread than it was allocated on (reference counted objects die wherever the last reference is
 * dropped) flows back to producers instead of piling up. No page headers, no ownership lookup: a block simply joins
 * the free list of the thread that frees it. Chunks are never returned to the system.
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
#define MK_SMALL_LIMIT 512u                                  /* free blocks per class and thread before a batch moves to the central pool */
#define MK_SMALL_BATCH 256u

typedef char mk_small_gran_is_max_align[MK_MAX_ALIGN == MK_SMALL_GRAN ? 1 : -1];

typedef struct MkSmallCache {
    void *free[MK_SMALL_CLASSES];
    uint32_t count[MK_SMALL_CLASSES];
    unsigned char *bump, *end;
    size_t next_chunk;
    int registered;
} MkSmallCache;
extern MK_TLS MkSmallCache mk_small_cache;

void *mk_small_refill(size_t cls);   /* slow path of mk_small_alloc */
void mk_small_overflow(size_t cls);  /* slow path of mk_small_free */

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
    if (++t->count[cls] > MK_SMALL_LIMIT || !t->registered) mk_small_overflow(cls);
}

#endif /* MK_SMALL_ALLOC */
#endif
