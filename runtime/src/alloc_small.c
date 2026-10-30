/* Slow paths of the small-object allocator (see alloc_small.h). */
#include "alloc_small.h"

#if MK_SMALL_ALLOC

#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
typedef SRWLOCK MkSmallLock;
#define SMALL_LOCK_INIT SRWLOCK_INIT
static void small_lock(MkSmallLock *l) { AcquireSRWLockExclusive(l); }
static void small_unlock(MkSmallLock *l) { ReleaseSRWLockExclusive(l); }
#elif defined(__wasm__) && !defined(__wasm_atomics__)
/* single-threaded WebAssembly: no locking */
typedef int MkSmallLock;
#define SMALL_LOCK_INIT 0
static void small_lock(MkSmallLock *l) { (void)l; }
static void small_unlock(MkSmallLock *l) { (void)l; }
#else
#include <pthread.h>
typedef pthread_mutex_t MkSmallLock;
#define SMALL_LOCK_INIT PTHREAD_MUTEX_INITIALIZER
static void small_lock(MkSmallLock *l) { pthread_mutex_lock(l); }
static void small_unlock(MkSmallLock *l) { pthread_mutex_unlock(l); }
#define SMALL_HAVE_PTHREAD 1
#endif

#define CHUNK_FIRST ((size_t)16 << 10)
#define CHUNK_MAX ((size_t)256 << 10)

MK_TLS MkSmallCache mk_small_cache;

/* One lock for the whole central pool: it is only touched on the slow paths (a refill or a batch of
 * MK_SMALL_BATCH blocks), never per object. */
static MkSmallLock central_lock = SMALL_LOCK_INIT;
static void *central_head[MK_SMALL_CLASSES]; /* per class: chain of free blocks, linked at +sizeof(void*) */

static void **link_of(void *b) { return (void **)(void *)((unsigned char *)b + sizeof(void *)); }

/* Moves every block of the calling thread's lists to the central pool (thread exit). */
static void flush_all(void *unused) {
    (void)unused;
    MkSmallCache *t = &mk_small_cache;
    for (size_t c = 1; c < MK_SMALL_CLASSES; c++) {
        void *first = t->free[c];
        if (first == NULL) continue;
        void *last = first;
        while (*link_of(last) != NULL) last = *link_of(last);
        small_lock(&central_lock);
        *link_of(last) = central_head[c];
        central_head[c] = first;
        small_unlock(&central_lock);
        t->free[c] = NULL;
        t->count[c] = 0;
    }
}

#ifdef SMALL_HAVE_PTHREAD
static pthread_key_t exit_key;
static pthread_once_t exit_once = PTHREAD_ONCE_INIT;
static void make_key(void) { pthread_key_create(&exit_key, flush_all); }
#endif

static void register_thread(MkSmallCache *t) {
    t->registered = 1;
#ifdef SMALL_HAVE_PTHREAD
    pthread_once(&exit_once, make_key);
    pthread_setspecific(exit_key, t); /* any non-NULL value: arms the destructor */
#endif
}

void mk_small_overflow(size_t cls) {
    MkSmallCache *t = &mk_small_cache;
    if (!t->registered) register_thread(t);
    if (t->count[cls] <= MK_SMALL_LIMIT) return;
    /* detach the first BATCH blocks of the list and hand them to the central pool */
    void *first = t->free[cls];
    void *last = first;
    for (unsigned i = 1; i < MK_SMALL_BATCH; i++) last = *link_of(last);
    t->free[cls] = *link_of(last);
    t->count[cls] -= MK_SMALL_BATCH;
    small_lock(&central_lock);
    *link_of(last) = central_head[cls];
    central_head[cls] = first;
    small_unlock(&central_lock);
    MK_STAT_SMALL(MK_SMALL_STAT_OVERFLOW, 1);
}

void *mk_small_refill(size_t cls) {
    MkSmallCache *t = &mk_small_cache;
    if (!t->registered) register_thread(t);

    /* 1. a batch from the central pool */
    small_lock(&central_lock);
    void *first = central_head[cls];
    if (first != NULL) {
        void *last = first;
        unsigned n = 1;
        while (n < MK_SMALL_BATCH && *link_of(last) != NULL) {
            last = *link_of(last);
            n++;
        }
        central_head[cls] = *link_of(last);
        small_unlock(&central_lock);
        *link_of(last) = NULL;
        t->free[cls] = *link_of(first);
        t->count[cls] = n - 1;
        MK_STAT_SMALL(MK_SMALL_STAT_CENTRAL, 1);
        return first;
    }
    small_unlock(&central_lock);

    /* 2. fresh memory from the thread's bump chunk */
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
