/* Slow paths of the small-object allocator (see alloc_small.h). */
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include "alloc_small.h"

#if MK_SMALL_ALLOC

#include "lock.h"

#if defined(__linux__)
#include <sys/mman.h>
#endif

/* relaxed atomics for the unlocked hint read of the central pool size */
#if defined(_MSC_VER) && !defined(__clang__)
#define SMALL_LOAD(p) (*(volatile size_t *)(p))
#define SMALL_STORE(p, v) (*(volatile size_t *)(p) = (v))
#else
#define SMALL_LOAD(p) __atomic_load_n((p), __ATOMIC_RELAXED)
#define SMALL_STORE(p, v) __atomic_store_n((p), (v), __ATOMIC_RELAXED)
#endif

#define CHUNK_FIRST ((size_t)16 << 10)
#define CHUNK_MAX ((size_t)2 << 20)
#define HUGE_PAGE ((size_t)2 << 20)

/* The memory of a bump chunk. Chunks of 2 MiB (the size they grow to) are 2 MiB aligned and offered to the kernel as transparent huge
 * pages (Linux, where THP is set to madvise): a program that walks a big graph of small objects in random order is bound by TLB
 * misses (4 KiB pages), and huge pages remove most of them. Nothing is committed that the bump pointer does not reach in the
 * end: a chunk is carved front to back. */
static unsigned char *chunk_alloc(size_t chunk) {
#if defined(__linux__) && defined(MADV_HUGEPAGE)
    if (chunk >= HUGE_PAGE) {
        unsigned char *raw = mmap(NULL, chunk + HUGE_PAGE, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (raw != (unsigned char *)MAP_FAILED) {
            unsigned char *mem = (unsigned char *)(((uintptr_t)raw + HUGE_PAGE - 1) & ~(uintptr_t)(HUGE_PAGE - 1));
            if (mem > raw) munmap(raw, (size_t)(mem - raw));
            size_t tail = (size_t)((raw + chunk + HUGE_PAGE) - (mem + chunk));
            if (tail > 0) munmap(mem + chunk, tail);
            madvise(mem, chunk, MADV_HUGEPAGE);
            return mem;
        }
    }
#endif
    return malloc(chunk);
}

MK_TLS MkSmallCache mk_small_cache;

typedef struct Batch {
    void *head, *tail;
    uint32_t count;
} Batch;

/* The central pool: per class a stack of batches. One lock for all classes: it is only taken on the slow paths (once
 * per MK_SMALL_BATCH frees or refills of a thread), never per object. `central_n` is also read without the lock as a
 * hint (relaxed atomic) so that a thread that has nothing to fetch does not touch the lock. */
static MkLock central_lock = MK_LOCK_INIT;
static Batch *central_stack[MK_SMALL_CLASSES];
static size_t central_cap[MK_SMALL_CLASSES];
static size_t central_n[MK_SMALL_CLASSES];

static void **link_of(void *b) { return (void **)b; }

static void central_push(size_t cls, void *head, void *tail, uint32_t count) {
    mk_lock(&central_lock);
    if (central_n[cls] == central_cap[cls]) {
        size_t cap = central_cap[cls] != 0 ? central_cap[cls] * 2 : 64;
        Batch *grown = realloc(central_stack[cls], cap * sizeof *grown);
        if (grown == NULL) {
            mk_unlock(&central_lock);
            mk_panic("out of memory");
        }
        central_stack[cls] = grown;
        central_cap[cls] = cap;
    }
    Batch b = {head, tail, count};
    central_stack[cls][central_n[cls]] = b;
    SMALL_STORE(&central_n[cls], central_n[cls] + 1);
    mk_unlock(&central_lock);
    MK_STAT_SMALL(MK_SMALL_STAT_OVERFLOW, 1);
}

static int central_pop(size_t cls, Batch *out) {
    if (SMALL_LOAD(&central_n[cls]) == 0) return 0;
    mk_lock(&central_lock);
    int ok = central_n[cls] > 0;
    if (ok) {
        *out = central_stack[cls][central_n[cls] - 1];
        SMALL_STORE(&central_n[cls], central_n[cls] - 1);
    }
    mk_unlock(&central_lock);
    if (ok) MK_STAT_SMALL(MK_SMALL_STAT_CENTRAL, 1);
    return ok;
}

#ifdef MK_HAVE_PTHREAD
/* Thread exit: every block of the thread's lists goes to the central pool. */
static void flush_all(void *unused) {
    (void)unused;
    MkSmallCache *t = &mk_small_cache;
    for (size_t c = 1; c < MK_SMALL_CLASSES; c++) {
        if (t->spare[c] != NULL) central_push(c, t->spare[c], t->spare_tail[c], MK_SMALL_BATCH);
        if (t->free[c] != NULL) central_push(c, t->free[c], t->tail[c], t->count[c]);
        t->spare[c] = t->free[c] = NULL;
        t->count[c] = 0;
    }
}

static pthread_key_t exit_key;
static pthread_once_t exit_once = PTHREAD_ONCE_INIT;
static void make_key(void) { pthread_key_create(&exit_key, flush_all); }
#endif /* MK_HAVE_PTHREAD: Windows threads and single-threaded wasm do not flush at thread exit */

void mk_small_register(void) {
    MkSmallCache *t = &mk_small_cache;
    t->registered = 1;
#ifdef MK_HAVE_PTHREAD
    pthread_once(&exit_once, make_key);
    pthread_setspecific(exit_key, t); /* any non-NULL value: arms the destructor */
#endif
}

/* `free` holds MK_SMALL_BATCH blocks: it becomes the spare batch; a spare that is already there goes to the central pool */
void mk_small_full(size_t cls) {
    MkSmallCache *t = &mk_small_cache;
    if (t->spare[cls] != NULL) central_push(cls, t->spare[cls], t->spare_tail[cls], MK_SMALL_BATCH);
    t->spare[cls] = t->free[cls];
    t->spare_tail[cls] = t->tail[cls];
    t->free[cls] = NULL;
    t->count[cls] = 0;
}

/* `free` is empty and the bump chunk cannot serve the class (mk_small_carve) */
void *mk_small_refill(size_t cls) {
    MkSmallCache *t = &mk_small_cache;
    if (!t->registered) mk_small_register();

    void *first = NULL, *last = NULL;
    uint32_t n = 0;
    if (t->spare[cls] != NULL) { /* 1. the thread's own spare batch */
        first = t->spare[cls];
        last = t->spare_tail[cls];
        n = MK_SMALL_BATCH;
        t->spare[cls] = NULL;
    } else {
        Batch b; /* 2. a batch from the central pool */
        if (central_pop(cls, &b)) {
            first = b.head;
            last = b.tail;
            n = b.count;
        }
    }
    if (first != NULL) {
        t->free[cls] = n > 1 ? *link_of(first) : NULL;
        t->tail[cls] = last;
        t->count[cls] = n - 1;
        return first;
    }

    /* 3. fresh memory from the thread's bump chunk */
    size_t chunk = t->next_chunk != 0 ? t->next_chunk : CHUNK_FIRST;
    unsigned char *mem = chunk_alloc(chunk);
    if (mem == NULL) return NULL;
    MK_STAT_SMALL(MK_SMALL_STAT_CHUNK, chunk);
    t->bump = mem; /* the tail of the old chunk is abandoned (< 320 bytes) */
    t->end = mem + chunk;
    if (chunk < CHUNK_MAX) chunk *= 2;
    t->next_chunk = chunk;
    return mk_small_carve(cls);
}

/* A block of a size that divides a cache line (16, 32, 64 bytes: the alignment is the largest power of two that divides the size, at
 * most 64) is carved at that alignment, so that it never straddles two lines (a 32 byte object at an offset of 48 modulo 64 needs two
 * cache misses for its header and its fields). The bytes that the alignment skips (16, 32 or 48) become one free block of that size. */
void *mk_small_carve(size_t cls) {
    MkSmallCache *t = &mk_small_cache;
    size_t sz = cls * MK_SMALL_GRAN;
    size_t align = sz & (~sz + 1u);
    if (align > 64) align = 64;
    unsigned char *p = t->bump;
    size_t pad = (size_t)(-(uintptr_t)p & (align - 1));
    if ((size_t)(t->end - p) >= sz + pad) {
        if (pad != 0) mk_small_free(p, pad / MK_SMALL_GRAN);
        t->bump = p + pad + sz;
        return p + pad;
    }
    return mk_small_refill(cls);
}

#endif /* MK_SMALL_ALLOC */

typedef int mk_alloc_small_translation_unit_not_empty;
