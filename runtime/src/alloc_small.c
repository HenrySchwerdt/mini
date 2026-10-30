/* Slow paths of the small-object allocator (see alloc_small.h). */
#include "alloc_small.h"

#if MK_SMALL_ALLOC

#include "lock.h"

/* relaxed atomics for the unlocked hint read of the central pool size */
#if defined(_MSC_VER) && !defined(__clang__)
#define SMALL_LOAD(p) (*(volatile size_t *)(p))
#define SMALL_STORE(p, v) (*(volatile size_t *)(p) = (v))
#else
#define SMALL_LOAD(p) __atomic_load_n((p), __ATOMIC_RELAXED)
#define SMALL_STORE(p, v) __atomic_store_n((p), (v), __ATOMIC_RELAXED)
#endif

#define CHUNK_FIRST ((size_t)16 << 10)
#define CHUNK_MAX ((size_t)256 << 10)

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

static void **link_of(void *b) { return (void **)(void *)((unsigned char *)b + sizeof(void *)); }

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

/* `free` is empty and the bump chunk cannot serve the class */
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
    size_t sz = cls * MK_SMALL_GRAN;
    if ((size_t)(t->end - t->bump) < sz) {
        size_t chunk = t->next_chunk != 0 ? t->next_chunk : CHUNK_FIRST;
        unsigned char *mem = malloc(chunk);
        if (mem == NULL) return NULL;
        MK_STAT_SMALL(MK_SMALL_STAT_CHUNK, chunk);
        t->bump = mem; /* the tail of the old chunk is abandoned (< 256 bytes) */
        t->end = mem + chunk;
        if (chunk < CHUNK_MAX) chunk *= 2;
        t->next_chunk = chunk;
    }
    void *b = t->bump;
    t->bump += sz;
    return b;
}

#endif /* MK_SMALL_ALLOC */

typedef int mk_alloc_small_translation_unit_not_empty;
