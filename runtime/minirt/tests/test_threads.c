/* uv.h needs POSIX/GNU declarations that strict -std=c17 hides. */
#ifndef _WIN32
#define _GNU_SOURCE
#endif
#include "harness.h"

#include <uv.h>

/* Only the final release (on the main thread, after the joins) may dispose. */
static int disposed;
static void dispose_it(MkObject *s) {
    (void)s;
    disposed++;
}
static const MkTypeInfo T = {"Shared", sizeof(MkObject), &mk_type_Any, dispose_it, 0, 0, 0, 0};

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
static const MkTypeInfo T_Plain = {"Plain", sizeof(MkObject), &mk_type_Any, NULL, 0, 0, 0, 0};
static void alloc_worker(void *arg) {
    MkAllocator *d = arg;
    for (int i = 0; i < 5000; i++) {
        MkObject *a = mk_alloc_with_site(d, &T_Plain, "thread");
        MkObject *b = mk_alloc_with(d, &T_Plain);
        if (i % 2) { mk_free(a); mk_release(b); } else { mk_release(a); mk_free(b); }
    }
}

int main(void) {
    MkAllocator *dbg = mk_debug_allocator_create(mk_heap_allocator());
    CHECK((mk_allocator_traits(dbg) & MK_ALLOCATOR_THREAD_SAFE) != 0);
    uv_thread_t at[THREADS];
    for (int i = 0; i < THREADS; i++) CHECK(uv_thread_create(&at[i], alloc_worker, dbg) == 0);
    for (int i = 0; i < THREADS; i++) CHECK(uv_thread_join(&at[i]) == 0);
    MkDebugStats st;
    mk_debug_stats(dbg, &st);
    CHECK_EQ_INT(st.live, 0);
    CHECK_EQ_INT(st.allocations, THREADS * 10000);
    CHECK_EQ_INT(st.frees, THREADS * 10000);
    mk_debug_allocator_destroy(dbg);

    MkObject *o = mk_alloc(&T); /* refcount 1: main's */
    mk_share(o);
    for (int i = 0; i < THREADS; i++) mk_retain(o); /* one per worker */
    uv_thread_t th[THREADS];
    for (int i = 0; i < THREADS; i++) CHECK(uv_thread_create(&th[i], worker, o) == 0);
    for (int i = 0; i < THREADS; i++) CHECK(uv_thread_join(&th[i]) == 0);
    CHECK_EQ_INT(disposed, 0);
    CHECK_EQ_INT(o->refcount, 1);
    mk_release(o);
    CHECK_EQ_INT(disposed, 1);
    TEST_DONE();
}
