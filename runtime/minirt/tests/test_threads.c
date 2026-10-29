#include "harness.h"

#ifdef _WIN32
int main(void) {
    fprintf(stderr, "test_threads: skipped on Windows\n");
    return 0;
}
#else
#include <pthread.h>

static int disposed;
static void dispose_it(MkObject *s) {
    (void)s;
    __atomic_fetch_add(&disposed, 1, __ATOMIC_SEQ_CST);
}
static const MkTypeInfo T = {"Shared", sizeof(MkObject), &mk_type_Any, dispose_it, 0, 0, 0, 0};

enum { THREADS = 4, ITERS = 200000 };

static void *worker(void *arg) {
    MkObject *o = arg;
    for (int i = 0; i < ITERS; i++) {
        mk_retain(o);
        mk_retain(o);
        mk_release(o);
        mk_release(o);
    }
    mk_release(o); /* drop the reference handed to this thread */
    return NULL;
}

int main(void) {
    MkObject *o = mk_alloc(&T); /* refcount 1: main's */
    mk_share(o);
    for (int i = 0; i < THREADS; i++) mk_retain(o); /* one per worker */
    pthread_t th[THREADS];
    for (int i = 0; i < THREADS; i++) CHECK(pthread_create(&th[i], NULL, worker, o) == 0);
    for (int i = 0; i < THREADS; i++) pthread_join(th[i], NULL);
    CHECK_EQ_INT(disposed, 0);
    CHECK_EQ_INT(o->refcount, 1);
    mk_release(o);
    CHECK_EQ_INT(disposed, 1);
    TEST_DONE();
}
#endif
