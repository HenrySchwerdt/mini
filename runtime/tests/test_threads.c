#ifndef _WIN32
#define _POSIX_C_SOURCE 200809L
#endif
#include "harness.h"

/* Portable thread wrapper (the runtime itself has no dependency on a thread library besides the platform's own). */
#ifdef _WIN32
#include <windows.h>
typedef HANDLE mk_test_thread_t;
typedef struct { void (*fn)(void *); void *arg; } mk_test_start_t;
static DWORD WINAPI test_thread_main(LPVOID p) {
    mk_test_start_t s = *(mk_test_start_t *)p;
    free(p);
    s.fn(s.arg);
    return 0;
}
static int test_thread_create(mk_test_thread_t *t, void (*fn)(void *), void *arg) {
    mk_test_start_t *s = malloc(sizeof *s);
    s->fn = fn;
    s->arg = arg;
    *t = CreateThread(NULL, 0, test_thread_main, s, 0, NULL);
    return *t == NULL;
}
static int test_thread_join(mk_test_thread_t *t) {
    WaitForSingleObject(*t, INFINITE);
    CloseHandle(*t);
    return 0;
}
#else
#include <pthread.h>
typedef pthread_t mk_test_thread_t;
typedef struct { void (*fn)(void *); void *arg; } mk_test_start_t;
static void *test_thread_main(void *p) {
    mk_test_start_t s = *(mk_test_start_t *)p;
    free(p);
    s.fn(s.arg);
    return NULL;
}
static int test_thread_create(mk_test_thread_t *t, void (*fn)(void *), void *arg) {
    mk_test_start_t *s = malloc(sizeof *s);
    s->fn = fn;
    s->arg = arg;
    return pthread_create(t, NULL, test_thread_main, s);
}
static int test_thread_join(mk_test_thread_t *t) { return pthread_join(*t, NULL); }
#endif

/* Only the final release (on the main thread, after the joins) may dispose. */
static int disposed;
static void dispose_it(MkObject *s) {
    (void)s;
    disposed++;
}
static const MkTypeInfo T = {"Shared", sizeof(MkObject), &mk_type_Any, dispose_it, 0, 0, 0, 0, NULL, NULL};

enum { THREADS = 4, ITERS = 200000 };

static void worker(void *arg) {
    MkObject *o = arg;
    for (int i = 0; i < ITERS; i++) {
        mk_retain(o);
        mk_retain(o);
        mk_release(o);
        mk_release(o);
    }
    mk_release(o); /* drop the reference handed to this thread */
}

/* Shared DebugAllocator over the Heap: every thread allocates and frees. */
static const MkTypeInfo T_Plain = {"Plain", sizeof(MkObject), &mk_type_Any, NULL, 0, 0, 0, 0, NULL, NULL};
static void alloc_worker(void *arg) {
    MkAllocator *d = arg;
    for (int i = 0; i < 5000; i++) {
        MkObject *a = mk_alloc_with_site(d, &T_Plain, "thread");
        MkObject *b = mk_alloc_with(d, &T_Plain);
        if (i % 2) { mk_free(a); mk_release(b); } else { mk_release(a); mk_free(b); }
    }
}

/* Thread ids: stable per thread, distinct between threads. */
static void id_worker(void *arg) {
    int64_t *slot = arg;
    slot[0] = mk_sys_thread_id();
    mk_sys_thread_yield();
    slot[1] = mk_sys_thread_id();
}

int main(void) {
    int64_t main_id = mk_sys_thread_id();
    CHECK_EQ_INT(main_id, mk_sys_thread_id());
    int64_t ids[THREADS][2];
    mk_test_thread_t it[THREADS];
    for (int i = 0; i < THREADS; i++) CHECK(test_thread_create(&it[i], id_worker, ids[i]) == 0);
    for (int i = 0; i < THREADS; i++) CHECK(test_thread_join(&it[i]) == 0);
    for (int i = 0; i < THREADS; i++) {
        CHECK_EQ_INT(ids[i][0], ids[i][1]);
        CHECK(ids[i][0] != main_id);
        for (int j = 0; j < i; j++) CHECK(ids[i][0] != ids[j][0]);
    }
    MkAllocator *dbg = mk_debug_allocator_create(mk_heap_allocator());
    CHECK((mk_allocator_traits(dbg) & MK_ALLOCATOR_THREAD_SAFE) != 0);
    mk_test_thread_t at[THREADS];
    for (int i = 0; i < THREADS; i++) CHECK(test_thread_create(&at[i], alloc_worker, dbg) == 0);
    for (int i = 0; i < THREADS; i++) CHECK(test_thread_join(&at[i]) == 0);
    MkDebugStats st;
    mk_debug_stats(dbg, &st);
    CHECK_EQ_INT(st.live, 0);
    CHECK_EQ_INT(st.allocations, THREADS * 10000);
    CHECK_EQ_INT(st.frees, THREADS * 10000);
    mk_debug_allocator_destroy(dbg);

    MkObject *o = mk_alloc(&T); /* refcount 1: main's */
    mk_share(o);
    for (int i = 0; i < THREADS; i++) mk_retain(o); /* one per worker */
    mk_test_thread_t th[THREADS];
    for (int i = 0; i < THREADS; i++) CHECK(test_thread_create(&th[i], worker, o) == 0);
    for (int i = 0; i < THREADS; i++) CHECK(test_thread_join(&th[i]) == 0);
    CHECK_EQ_INT(disposed, 0);
    CHECK_EQ_INT(o->refcount, 1);
    mk_release(o);
    CHECK_EQ_INT(disposed, 1);
    TEST_DONE();
}
