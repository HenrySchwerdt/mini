#ifndef _WIN32
#define _POSIX_C_SOURCE 200809L
#endif
#include "harness.h"

/* The concurrency layer (src/sys.c): clock, locks, condition variables, atomics, atomic reference counts. */

#ifdef _WIN32
#include <windows.h>
typedef HANDLE test_thread_t;
#else
#include <pthread.h>
typedef pthread_t test_thread_t;
#endif

static MkObject *lock_storage, *cond_storage;
static int ready_flag;
static MkObject *int_cell, *long_cell, *ref_cell;
static MkObject *counted;
enum { THREADS = 4, ITERS = 50000 };

static void worker(void) {
    for (int i = 0; i < ITERS; i++) {
        mk_atomic_int_add(int_cell, 1);
        mk_atomic_long_add(long_cell, 2);
        mk_sys_lock(lock_storage);
        ready_flag++;
        mk_sys_unlock(lock_storage);
    }
}

static void waiter(void) {
    mk_sys_lock(lock_storage);
    while (ready_flag != -2) mk_sys_cond_wait(cond_storage, lock_storage);
    ready_flag = 1000;
    mk_sys_unlock(lock_storage);
}

static void rc_worker(void) {
    for (int i = 0; i < ITERS; i++) {
        mk_retain(counted);
        mk_release(counted);
    }
}

#ifdef _WIN32
static DWORD WINAPI tmain(LPVOID p) {
    ((void (*)(void))p)();
    return 0;
}
static void start(test_thread_t *t, void (*f)(void)) { *t = CreateThread(NULL, 0, tmain, (LPVOID)f, 0, NULL); }
static void join(test_thread_t *t) {
    WaitForSingleObject(*t, INFINITE);
    CloseHandle(*t);
}
#else
static void *tmain(void *p) {
    ((void (*)(void))p)();
    return NULL;
}
static void start(test_thread_t *t, void (*f)(void)) { CHECK(pthread_create(t, NULL, tmain, (void *)f) == 0); }
static void join(test_thread_t *t) { CHECK(pthread_join(*t, NULL) == 0); }
#endif

static const MkTypeInfo T = {"Counted", sizeof(MkObject), &mk_type_Any, NULL, 0, 0, 0, 0, NULL};

int main(void) {
    int64_t t0 = mk_sys_monotonic_ns();
    mk_sys_sleep_ns(2000000);
    CHECK(mk_sys_monotonic_ns() - t0 >= 1500000);
    CHECK(mk_sys_wall_ms() > 1600000000000LL);
    CHECK(mk_sys_cpu_count() >= 1);

    lock_storage = mk_array_new_long(MK_SYS_LOCK_WORDS);
    cond_storage = mk_array_new_long(MK_SYS_LOCK_WORDS);
    int_cell = mk_array_new_int(1);
    long_cell = mk_array_new_long(1);
    ref_cell = mk_array_new_obj(1);
    mk_sys_lock_init(lock_storage);
    mk_sys_cond_init(cond_storage);

    /* mutual exclusion and atomics */
    test_thread_t th[THREADS];
    for (int i = 0; i < THREADS; i++) start(&th[i], worker);
    for (int i = 0; i < THREADS; i++) join(&th[i]);
    CHECK_EQ_INT(mk_atomic_int_get(int_cell), THREADS * ITERS);
    CHECK_EQ_INT(mk_atomic_long_get(long_cell), (int64_t)THREADS * ITERS * 2);
    CHECK_EQ_INT(ready_flag, THREADS * ITERS);
    CHECK(mk_atomic_int_cas(int_cell, THREADS * ITERS, 7));
    CHECK(!mk_atomic_int_cas(int_cell, 1, 8));
    CHECK_EQ_INT(mk_atomic_int_exchange(int_cell, 9), 7);

    /* condition variable: a timeout, then a real wake-up */
    mk_sys_lock(lock_storage);
    CHECK(mk_sys_cond_wait_ns(cond_storage, lock_storage, 2000000));
    mk_sys_unlock(lock_storage);
    ready_flag = -1;
    test_thread_t w;
    start(&w, waiter);
    mk_sys_sleep_ns(5000000);
    mk_sys_lock(lock_storage);
    ready_flag = -2;
    mk_sys_cond_broadcast(cond_storage);
    mk_sys_unlock(lock_storage);
    join(&w);
    CHECK_EQ_INT(ready_flag, 1000);

    /* reference cell */
    MkObject *a = mk_alloc(&T);
    MkObject *b = mk_alloc(&T);
    mk_atomic_ref_set(ref_cell, a);
    CHECK_EQ_INT(a->refcount, 2);
    MkObject *got = mk_atomic_ref_get(ref_cell);
    CHECK(got == a);
    mk_release(got);
    CHECK(!mk_atomic_ref_cas(ref_cell, b, a));
    CHECK(mk_atomic_ref_cas(ref_cell, a, b));
    CHECK_EQ_INT(a->refcount, 1);
    CHECK_EQ_INT(b->refcount, 2);
    MkObject *old = mk_atomic_ref_exchange(ref_cell, a);
    CHECK(old == b);
    mk_release(old);
    mk_release(b);

    /* with threads active every reference count is atomic, also on objects that were never marked shared */
    counted = mk_alloc(&T);
    mk_threads_active = 1;
    for (int i = 0; i < THREADS; i++) start(&th[i], rc_worker);
    for (int i = 0; i < THREADS; i++) join(&th[i]);
    CHECK_EQ_INT(counted->refcount, 1);
    mk_release(counted);
    mk_release(a);
    mk_release(ref_cell);
    mk_release(int_cell);
    mk_release(long_cell);
    mk_release(lock_storage);
    mk_release(cond_storage);
    TEST_DONE();
}
