/* Small-object allocator behind the Rc and Heap allocators (alloc_small.c): size classes, reuse, zeroing,
 * blocks freed on another thread, thread exit. Meaningful without ASan (with ASan or MK_PLAIN_MALLOC the
 * allocators use malloc and the checks still hold). run_tests.sh runs it under UBSan alone and under TSan. */
#include "harness.h"

#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>

typedef struct { MkObject h; unsigned char data[1]; } Blob;
static const MkTypeInfo T_Blob = {"Blob", sizeof(Blob), &mk_type_Any, NULL, 0, 0, 0, 0};

static MkObject *blob(MkAllocator *a, size_t payload) {
    return mk_alloc_sized(a, &T_Blob, sizeof(MkObject) + payload);
}
static unsigned char *data(MkObject *o) { return (unsigned char *)o + sizeof(MkObject); }

static void fill(MkObject *o, size_t n, unsigned char v) { memset(data(o), v, n); }
static int check(MkObject *o, size_t n, unsigned char v) {
    for (size_t i = 0; i < n; i++)
        if (data(o)[i] != v) return 0;
    return 1;
}

/* many sizes across the class boundaries (and past MK_SMALL_MAX), random frees, zeroed reuse */
static void check_sizes(MkAllocator *a) {
    enum { N = 4000 };
    static MkObject *objs[N];
    static size_t sizes[N];
    srand(7);
    for (int i = 0; i < N; i++) {
        sizes[i] = (size_t)(rand() % 420);
        objs[i] = blob(a, sizes[i]);
        CHECK(objs[i] != NULL);
        CHECK(((uintptr_t)objs[i] & (MK_MAX_ALIGN - 1)) == 0);
        CHECK(check(objs[i], sizes[i], 0));
        fill(objs[i], sizes[i], (unsigned char)(i & 0xff));
    }
    for (int i = 0; i < N; i++) CHECK(check(objs[i], sizes[i], (unsigned char)(i & 0xff))); /* no overlap */
    for (int round = 0; round < 3; round++) {
        for (int k = 0; k < N / 2; k++) { /* free a random half, allocate again (reuse of freed blocks) */
            int i = rand() % N;
            if (objs[i] == NULL) continue;
            CHECK(check(objs[i], sizes[i], (unsigned char)(i & 0xff)));
            mk_release(objs[i]);
            objs[i] = NULL;
        }
        for (int i = 0; i < N; i++) {
            if (objs[i] != NULL) continue;
            sizes[i] = (size_t)(rand() % 420);
            objs[i] = blob(a, sizes[i]);
            CHECK(check(objs[i], sizes[i], 0)); /* reused blocks come back zeroed */
            fill(objs[i], sizes[i], (unsigned char)(i & 0xff));
        }
    }
    for (int i = 0; i < N; i++) {
        CHECK(check(objs[i], sizes[i], (unsigned char)(i & 0xff)));
        mk_release(objs[i]);
    }
}

/* freeing more than MK_SMALL_LIMIT blocks pushes batches to the central pool; allocating them again pulls them back */
static void check_overflow(void) {
    enum { N = 5000 };
    static MkObject *objs[N];
    for (int i = 0; i < N; i++) { objs[i] = blob(NULL, 24); fill(objs[i], 24, 0x5a); }
    for (int i = 0; i < N; i++) mk_release(objs[i]);
    for (int i = 0; i < N; i++) { objs[i] = blob(NULL, 24); CHECK(check(objs[i], 24, 0)); }
    for (int i = 0; i < N; i++) mk_release(objs[i]);
}

/* portable two-party barrier */
static pthread_mutex_t bar_mu = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t bar_cv = PTHREAD_COND_INITIALIZER;
static int bar_count, bar_phase;
static void barrier(void) {
    pthread_mutex_lock(&bar_mu);
    int phase = bar_phase;
    if (++bar_count == 2) {
        bar_count = 0;
        bar_phase++;
        pthread_cond_broadcast(&bar_cv);
    } else {
        while (phase == bar_phase) pthread_cond_wait(&bar_cv, &bar_mu);
    }
    pthread_mutex_unlock(&bar_mu);
}

/* producer allocates, consumer frees (other thread), repeatedly; objects of several sizes */
enum { BATCH = 20000, ROUNDS = 4 };
static MkObject *handoff[BATCH];
static size_t size_of(int i) { return (size_t)((i * 37) % 300); }

static void *producer(void *arg) {
    (void)arg;
    for (int r = 0; r < ROUNDS; r++) {
        for (int i = 0; i < BATCH; i++) {
            handoff[i] = blob(NULL, size_of(i));
            fill(handoff[i], size_of(i), (unsigned char)(i + r));
        }
        barrier(); /* batch ready */
        barrier(); /* consumer done */
    }
    return NULL;
}

static int consumer_bad;
static void *consumer(void *arg) {
    (void)arg;
    for (int r = 0; r < ROUNDS; r++) {
        barrier();
        for (int i = 0; i < BATCH; i++) {
            if (!check(handoff[i], size_of(i), (unsigned char)(i + r))) consumer_bad++;
            mk_release(handoff[i]);
        }
        barrier();
    }
    return NULL;
}

static void check_cross_thread(void) {
    pthread_t p, c;
    pthread_create(&p, NULL, producer, NULL);
    pthread_create(&c, NULL, consumer, NULL);
    pthread_join(p, NULL);
    pthread_join(c, NULL);
    CHECK_EQ_INT(consumer_bad, 0);
}

/* threads that allocate and free on their own and exit with full free lists; then the main thread reuses the memory */
static int worker_bad;
static void *worker(void *arg) {
    int id = (int)(intptr_t)arg;
    enum { K = 3000 };
    MkObject **objs = malloc(K * sizeof *objs);
    int bad = 0;
    for (int round = 0; round < 5; round++) {
        for (int i = 0; i < K; i++) {
            size_t n = (size_t)((i + id) % 200);
            objs[i] = blob(NULL, n);
            if (!check(objs[i], n, 0)) bad++;
            fill(objs[i], n, (unsigned char)id);
        }
        for (int i = 0; i < K; i++) {
            size_t n = (size_t)((i + id) % 200);
            if (!check(objs[i], n, (unsigned char)id)) bad++;
            mk_release(objs[i]);
        }
    }
    free(objs);
    if (bad) __atomic_fetch_add(&worker_bad, bad, __ATOMIC_RELAXED);
    return NULL;
}

static void check_threads(void) {
    enum { T = 6 };
    pthread_t th[T];
    for (int i = 0; i < T; i++) pthread_create(&th[i], NULL, worker, (void *)(intptr_t)(i + 1));
    for (int i = 0; i < T; i++) pthread_join(th[i], NULL);
    CHECK_EQ_INT(worker_bad, 0);
    check_sizes(NULL); /* the main thread picks up what the exited threads handed back */
}

/* arena objects are zeroed on every allocation, also in chunk memory reused after a reset (the chunks come zeroed from
 * calloc and track a high-water mark, so only reused memory is cleared) */
static void check_arena_zeroing(void) {
  for (int life = 0; life < 3; life++) { /* a new arena takes the dirty chunks of the destroyed one from the cache */
    MkAllocator *arena = mk_arena_create(256);
    for (int round = 0; round < 4; round++) {
        enum { N = 600 };
        static MkObject *objs[N];
        for (int i = 0; i < N; i++) {
            size_t n = (size_t)((i * 13 + round) % 150);
            objs[i] = blob(arena, n);
            CHECK(check(objs[i], n, 0));
            CHECK(((uintptr_t)objs[i] & (MK_MAX_ALIGN - 1)) == 0);
            fill(objs[i], n, (unsigned char)(0xa0 + round));
        }
        for (int i = 0; i < N; i++) CHECK(check(objs[i], (size_t)((i * 13 + round) % 150), (unsigned char)(0xa0 + round)));
        CHECK(mk_arena_used(arena) > 0);
        mk_arena_reset(arena);
        CHECK_EQ_INT(mk_arena_used(arena), 0);
    }
    mk_arena_destroy(arena);
  }
}

int main(void) {
    check_arena_zeroing();
    check_sizes(NULL);
    check_sizes(mk_heap_allocator());
    check_overflow();
    check_cross_thread();
    check_threads();
    TEST_DONE();
}
