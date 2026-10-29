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

int main(void) {
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
