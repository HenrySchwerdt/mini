/* Operating-system layer of the concurrency library (docs/coroutines.md): clock, sleep, threads, locks, condition variables
 * and atomics. No libuv: POSIX threads on Linux/macOS, Win32 threads and SRW locks on Windows, stubs on wasm.
 *
 * Locks and condition variables live in the storage of a Kotlin LongArray (so reference counting frees them): the Kotlin
 * side allocates LongArray(MK_SYS_LOCK_WORDS), these functions initialise and use its elements. */
#if !defined(_WIN32) && !defined(_POSIX_C_SOURCE)
#define _POSIX_C_SOURCE 200809L
#endif
#if defined(__APPLE__)
#define _DARWIN_C_SOURCE 1
#endif
#include "internal.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#if defined(_WIN32)
#include <windows.h>
#elif defined(__wasm__)
/* single-threaded: no thread support (a thread start or an unbounded wait panics instead of hanging); the clock is WASI's */
#include <time.h>
#else
#define MK_POSIX_THREADS 1
#include <errno.h>
#include <pthread.h>
#include <sched.h>
#include <time.h>
#include <unistd.h>
#endif

/* Once a thread has been started every reference count operation is atomic (object.c reads this). */
volatile int mk_threads_active = 0;
volatile int mk_threads_running = 0;

bool (mk_sys_threads_active)(void) { return __atomic_load_n(&mk_threads_active, __ATOMIC_RELAXED) != 0; }

/* ---- clock and sleep ----------------------------------------------------------------------------------------------- */

#if defined(_WIN32)
int64_t mk_sys_monotonic_ns(void) {
    LARGE_INTEGER f, c;
    QueryPerformanceFrequency(&f);
    QueryPerformanceCounter(&c);
    return (int64_t)((double)c.QuadPart * 1e9 / (double)f.QuadPart);
}
int64_t mk_sys_wall_ms(void) {
    FILETIME ft;
    GetSystemTimeAsFileTime(&ft);
    uint64_t t = ((uint64_t)ft.dwHighDateTime << 32) | ft.dwLowDateTime;
    return (int64_t)(t / 10000u) - 11644473600000LL;
}
void mk_sys_sleep_ns(int64_t ns) {
    if (ns > 0) Sleep((DWORD)((ns + 999999) / 1000000));
}
int64_t mk_sys_cpu_count(void) {
    SYSTEM_INFO si;
    GetSystemInfo(&si);
    return (int64_t)si.dwNumberOfProcessors;
}
#elif defined(MK_POSIX_THREADS)
int64_t mk_sys_monotonic_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}
int64_t mk_sys_wall_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    return (int64_t)ts.tv_sec * 1000LL + ts.tv_nsec / 1000000;
}
void mk_sys_sleep_ns(int64_t ns) {
    if (ns <= 0) return;
    struct timespec ts = {(time_t)(ns / 1000000000LL), (long)(ns % 1000000000LL)};
    while (nanosleep(&ts, &ts) != 0 && errno == EINTR) {}
}
int64_t mk_sys_cpu_count(void) {
    long n = sysconf(_SC_NPROCESSORS_ONLN);
    return n > 0 ? (int64_t)n : 1;
}
#elif defined(__wasm__)
int64_t mk_sys_monotonic_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}
int64_t mk_sys_wall_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    return (int64_t)ts.tv_sec * 1000LL + ts.tv_nsec / 1000000;
}
void mk_sys_sleep_ns(int64_t ns) {
    if (ns <= 0) return;
    struct timespec ts = {(time_t)(ns / 1000000000LL), (long)(ns % 1000000000LL)};
    nanosleep(&ts, NULL);
}
int64_t mk_sys_cpu_count(void) { return 1; }
#else
int64_t mk_sys_monotonic_ns(void) { return 0; }
int64_t mk_sys_wall_ms(void) { return 0; }
void mk_sys_sleep_ns(int64_t ns) { (void)ns; }
int64_t mk_sys_cpu_count(void) { return 1; }
#endif

/* a hint to the CPU in a spin loop */
static inline void mk_cpu_relax(void) {
#if defined(__x86_64__) || defined(__i386__)
    __builtin_ia32_pause();
#elif defined(__aarch64__)
    __asm__ __volatile__("yield");
#endif
}

/* ---- locks and condition variables ----------------------------------------------------------------------------------- */

#if defined(MK_POSIX_THREADS)
#define LOCK_OF(a) ((pthread_mutex_t *)MK_ARRAY_ELEMS(MkLong, (a)))
#define COND_OF(a) ((pthread_cond_t *)MK_ARRAY_ELEMS(MkLong, (a)))

void mk_sys_lock_init(MkObject *a) { pthread_mutex_init(LOCK_OF(a), NULL); }
void mk_sys_lock(MkObject *a) { pthread_mutex_lock(LOCK_OF(a)); }
MkBoolean mk_sys_try_lock(MkObject *a) { return pthread_mutex_trylock(LOCK_OF(a)) == 0; }
void mk_sys_unlock(MkObject *a) { pthread_mutex_unlock(LOCK_OF(a)); }

void mk_sys_cond_init(MkObject *a) {
    pthread_condattr_t at;
    pthread_condattr_init(&at);
#if !defined(__APPLE__)
    pthread_condattr_setclock(&at, CLOCK_MONOTONIC);
#endif
    pthread_cond_init(COND_OF(a), &at);
    pthread_condattr_destroy(&at);
}
void mk_sys_cond_wait(MkObject *c, MkObject *l) { pthread_cond_wait(COND_OF(c), LOCK_OF(l)); }
/* waits at most [ns]; true when it timed out */
MkBoolean mk_sys_cond_wait_ns(MkObject *c, MkObject *l, MkLong ns) {
    if (ns <= 0) return true;
    struct timespec ts;
#if defined(__APPLE__)
    struct timespec rel = {(time_t)(ns / 1000000000LL), (long)(ns % 1000000000LL)};
    int r = pthread_cond_timedwait_relative_np(COND_OF(c), LOCK_OF(l), &rel);
    (void)ts;
    return r == ETIMEDOUT;
#else
    clock_gettime(CLOCK_MONOTONIC, &ts);
    int64_t t = (int64_t)ts.tv_nsec + ns % 1000000000LL;
    ts.tv_sec += (time_t)(ns / 1000000000LL + t / 1000000000LL);
    ts.tv_nsec = (long)(t % 1000000000LL);
    return pthread_cond_timedwait(COND_OF(c), LOCK_OF(l), &ts) == ETIMEDOUT;
#endif
}
void mk_sys_cond_signal(MkObject *c) { pthread_cond_signal(COND_OF(c)); }
void mk_sys_cond_broadcast(MkObject *c) { pthread_cond_broadcast(COND_OF(c)); }
#elif defined(_WIN32)
#define LOCK_OF(a) ((SRWLOCK *)MK_ARRAY_ELEMS(MkLong, (a)))
#define COND_OF(a) ((CONDITION_VARIABLE *)MK_ARRAY_ELEMS(MkLong, (a)))
void mk_sys_lock_init(MkObject *a) { InitializeSRWLock(LOCK_OF(a)); }
void mk_sys_lock(MkObject *a) { AcquireSRWLockExclusive(LOCK_OF(a)); }
MkBoolean mk_sys_try_lock(MkObject *a) { return TryAcquireSRWLockExclusive(LOCK_OF(a)) != 0; }
void mk_sys_unlock(MkObject *a) { ReleaseSRWLockExclusive(LOCK_OF(a)); }
void mk_sys_cond_init(MkObject *a) { InitializeConditionVariable(COND_OF(a)); }
void mk_sys_cond_wait(MkObject *c, MkObject *l) { SleepConditionVariableSRW(COND_OF(c), LOCK_OF(l), INFINITE, 0); }
MkBoolean mk_sys_cond_wait_ns(MkObject *c, MkObject *l, MkLong ns) {
    if (ns <= 0) return true;
    DWORD ms = (DWORD)((ns + 999999) / 1000000);
    if (SleepConditionVariableSRW(COND_OF(c), LOCK_OF(l), ms, 0)) return false;
    return GetLastError() == ERROR_TIMEOUT;
}
void mk_sys_cond_signal(MkObject *c) { WakeConditionVariable(COND_OF(c)); }
void mk_sys_cond_broadcast(MkObject *c) { WakeAllConditionVariable(COND_OF(c)); }
#else
void mk_sys_lock_init(MkObject *a) { (void)a; }
void mk_sys_lock(MkObject *a) { (void)a; }
MkBoolean mk_sys_try_lock(MkObject *a) { (void)a; return true; }
void mk_sys_unlock(MkObject *a) { (void)a; }
void mk_sys_cond_init(MkObject *a) { (void)a; }
/* Nothing can ever signal: an unbounded wait is a deadlock (it used to return at once and spin forever); a timed wait just sleeps. */
void mk_sys_cond_wait(MkObject *c, MkObject *l) { (void)c; (void)l; mk_panic("deadlock: waiting for another thread, but this platform has no threads"); }
MkBoolean mk_sys_cond_wait_ns(MkObject *c, MkObject *l, MkLong ns) { (void)c; (void)l; mk_sys_sleep_ns(ns); return true; }
void mk_sys_cond_signal(MkObject *c) { (void)c; }
void mk_sys_cond_broadcast(MkObject *c) { (void)c; }
#endif

/* ---- threads ----------------------------------------------------------------------------------------------------------- */

#if defined(_WIN32) || defined(MK_POSIX_THREADS)
/* Calls `fn.invoke()` of a Kotlin `() -> Unit` (the itable of its kotlin.Function0 interface, slot 0). */
static void call_function0(MkObject *fn) {
    const MkTypeInfo *t = fn->type;
    for (; t != NULL; t = t->super) {
        for (size_t i = 0; i < t->interface_count; i++) {
            const MkTypeInfo *ifc = t->interfaces[i];
            if (ifc != NULL && strcmp(ifc->name, "kotlin.Function0") == 0) {
                void *const *it = mk_itable(fn, ifc);
                MkObject *(*invoke)(MkObject *) = (MkObject * (*)(MkObject *)) it[0];
                MkObject *r = invoke(fn);
                mk_release(r);
                return;
            }
        }
    }
    mk_panic("thread body is not a function");
}

/* The operating-system thread is detached at once (its resources go back when it ends). A MkThread is shared by the thread itself and
 * by the handle that mk_sys_thread_start returned: `refs` counts both, the last one frees it. */
typedef struct MkThread {
    MkObject *fn;
    int refs;
    int done; /* the thread has run to its end (set before the thread's own reference goes) */
} MkThread;

static void thread_unref(MkThread *th) {
    if (__atomic_sub_fetch(&th->refs, 1, __ATOMIC_ACQ_REL) == 0) free(th);
}

static void run_thread_body(MkThread *th) {
    call_function0(th->fn);
    if (mk_pending_exception != NULL) {
        /* an exception that ends a thread is reported like in the main thread, but only this thread ends */
        MkObject *e = mk_catch();
        fflush(stdout);
        fprintf(stderr, "Exception in thread: %s\n", e->type->name);
        mk_release(e);
    }
    mk_release(th->fn);
    th->fn = NULL;
    mk_sys_thread_set_data(NULL);
    mk_release_drain();
    __atomic_fetch_sub(&mk_threads_running, 1, __ATOMIC_SEQ_CST); /* the last reference count operation of this thread */
    __atomic_store_n(&th->done, 1, __ATOMIC_RELEASE);
    thread_unref(th);
}

#if defined(_WIN32)
static DWORD WINAPI thread_main(LPVOID p) {
    run_thread_body((MkThread *)p);
    return 0;
}
#elif defined(MK_POSIX_THREADS)
static void *thread_main(void *p) {
    run_thread_body((MkThread *)p);
    return NULL;
}
#endif

#endif /* threads */

/* A small id per thread: the thread that starts the first thread (normally main) is 1, the others follow in start order. */
static _Thread_local int64_t tl_thread_id = 0;
static int64_t thread_id_counter = 0;

int64_t mk_sys_thread_id(void) {
    if (tl_thread_id == 0) tl_thread_id = __atomic_add_fetch(&thread_id_counter, 1, __ATOMIC_SEQ_CST);
    return tl_thread_id;
}

/* One object per thread that the library keeps there (java.lang.Thread stores its Thread object): the slot holds a reference. */
static _Thread_local MkObject *tl_thread_data = NULL;

MkObject *mk_sys_thread_data(void) {
    MkObject *d = tl_thread_data;
    if (d != NULL) mk_retain(d);
    return d;
}

void mk_sys_thread_set_data(MkObject *o) {
    MkObject *old = tl_thread_data;
    if (o != NULL) mk_retain(o);
    tl_thread_data = o;
    mk_release(old);
}

void mk_sys_thread_yield(void) {
#if defined(_WIN32)
    SwitchToThread();
#elif defined(MK_POSIX_THREADS)
    sched_yield();
#endif
}

/* Starts a thread that runs the Kotlin function [fn]; returns a handle for mk_sys_thread_join, or 0 when threads are not supported. */
int64_t mk_sys_thread_start(MkObject *fn) {
#if defined(_WIN32) || defined(MK_POSIX_THREADS)
    MkThread *th = (MkThread *)malloc(sizeof(MkThread));
    if (th == NULL) mk_panic("out of memory");
    (void)mk_sys_thread_id(); /* the starting thread gets its id before any thread it starts */
    /* from now on all reference counts are atomic: everything the thread can reach may be shared */
    __atomic_store_n(&mk_threads_active, 1, __ATOMIC_SEQ_CST);
    __atomic_fetch_add(&mk_threads_running, 1, __ATOMIC_SEQ_CST);
    mk_retain(fn);
    th->fn = fn;
    th->refs = 2;
    th->done = 0;
#if defined(_WIN32)
    HANDLE h = CreateThread(NULL, 0, thread_main, th, 0, NULL);
    if (h == NULL) {
        __atomic_fetch_sub(&mk_threads_running, 1, __ATOMIC_SEQ_CST);
        mk_release(fn);
        free(th);
        return 0;
    }
    CloseHandle(h); /* detached: the thread's resources go back when it ends */
#else
    pthread_t h;
    if (pthread_create(&h, NULL, thread_main, th) != 0) {
        __atomic_fetch_sub(&mk_threads_running, 1, __ATOMIC_SEQ_CST);
        mk_release(fn);
        free(th);
        return 0;
    }
    pthread_detach(h);
#endif
    return (int64_t)(intptr_t)th;
#else
    (void)fn;
    mk_panic("threads are not supported on this platform (wasm32)");
    return 0;
#endif
}

/* Waits until the thread has run to its end, then gives up the handle (the handle is invalid afterwards). */
void mk_sys_thread_join(int64_t h) {
#if defined(_WIN32) || defined(MK_POSIX_THREADS)
    MkThread *th = (MkThread *)(intptr_t)h;
    if (th == NULL) return;
    while (__atomic_load_n(&th->done, __ATOMIC_ACQUIRE) == 0) mk_sys_thread_yield();
    thread_unref(th);
#else
    (void)h;
#endif
}

/* Gives up the handle without waiting (the thread frees what is left of it when it ends). */
void mk_sys_thread_detach(int64_t h) {
#if defined(_WIN32) || defined(MK_POSIX_THREADS)
    MkThread *th = (MkThread *)(intptr_t)h;
    if (th != NULL) thread_unref(th);
#else
    (void)h;
#endif
}

/* ---- atomics ---------------------------------------------------------------------------------------------------------------- */

MkInt mk_atomic_int_get(MkObject *a) { return __atomic_load_n(MK_ARRAY_ELEMS(MkInt, a), __ATOMIC_SEQ_CST); }
void mk_atomic_int_set(MkObject *a, MkInt v) { __atomic_store_n(MK_ARRAY_ELEMS(MkInt, a), v, __ATOMIC_SEQ_CST); }
MkInt mk_atomic_int_add(MkObject *a, MkInt d) { return __atomic_fetch_add(MK_ARRAY_ELEMS(MkInt, a), d, __ATOMIC_SEQ_CST) + d; }
MkInt mk_atomic_int_exchange(MkObject *a, MkInt v) { return __atomic_exchange_n(MK_ARRAY_ELEMS(MkInt, a), v, __ATOMIC_SEQ_CST); }
MkBoolean mk_atomic_int_cas(MkObject *a, MkInt expected, MkInt v) {
    return __atomic_compare_exchange_n(MK_ARRAY_ELEMS(MkInt, a), &expected, v, false, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);
}

MkLong mk_atomic_long_get(MkObject *a) { return __atomic_load_n(MK_ARRAY_ELEMS(MkLong, a), __ATOMIC_SEQ_CST); }
void mk_atomic_long_set(MkObject *a, MkLong v) { __atomic_store_n(MK_ARRAY_ELEMS(MkLong, a), v, __ATOMIC_SEQ_CST); }
MkLong mk_atomic_long_add(MkObject *a, MkLong d) { return __atomic_fetch_add(MK_ARRAY_ELEMS(MkLong, a), d, __ATOMIC_SEQ_CST) + d; }
MkLong mk_atomic_long_exchange(MkObject *a, MkLong v) { return __atomic_exchange_n(MK_ARRAY_ELEMS(MkLong, a), v, __ATOMIC_SEQ_CST); }
MkBoolean mk_atomic_long_cas(MkObject *a, MkLong expected, MkLong v) {
    return __atomic_compare_exchange_n(MK_ARRAY_ELEMS(MkLong, a), &expected, v, false, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);
}

/* the same on element [i] of an IntArray / LongArray (AtomicIntegerArray, AtomicLongArray, the atomic element operations of kotlin.concurrent) */
MkInt mk_atomic_int_get_at(MkObject *a, MkInt i) { return __atomic_load_n(MK_ARRAY_ELEMS(MkInt, a) + i, __ATOMIC_SEQ_CST); }
void mk_atomic_int_set_at(MkObject *a, MkInt i, MkInt v) { __atomic_store_n(MK_ARRAY_ELEMS(MkInt, a) + i, v, __ATOMIC_SEQ_CST); }
MkInt mk_atomic_int_add_at(MkObject *a, MkInt i, MkInt d) { return __atomic_fetch_add(MK_ARRAY_ELEMS(MkInt, a) + i, d, __ATOMIC_SEQ_CST) + d; }
MkInt mk_atomic_int_exchange_at(MkObject *a, MkInt i, MkInt v) { return __atomic_exchange_n(MK_ARRAY_ELEMS(MkInt, a) + i, v, __ATOMIC_SEQ_CST); }
MkBoolean mk_atomic_int_cas_at(MkObject *a, MkInt i, MkInt expected, MkInt v) {
    return __atomic_compare_exchange_n(MK_ARRAY_ELEMS(MkInt, a) + i, &expected, v, false, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);
}
MkLong mk_atomic_long_get_at(MkObject *a, MkInt i) { return __atomic_load_n(MK_ARRAY_ELEMS(MkLong, a) + i, __ATOMIC_SEQ_CST); }
void mk_atomic_long_set_at(MkObject *a, MkInt i, MkLong v) { __atomic_store_n(MK_ARRAY_ELEMS(MkLong, a) + i, v, __ATOMIC_SEQ_CST); }
MkLong mk_atomic_long_add_at(MkObject *a, MkInt i, MkLong d) { return __atomic_fetch_add(MK_ARRAY_ELEMS(MkLong, a) + i, d, __ATOMIC_SEQ_CST) + d; }
MkLong mk_atomic_long_exchange_at(MkObject *a, MkInt i, MkLong v) { return __atomic_exchange_n(MK_ARRAY_ELEMS(MkLong, a) + i, v, __ATOMIC_SEQ_CST); }
MkBoolean mk_atomic_long_cas_at(MkObject *a, MkInt i, MkLong expected, MkLong v) {
    return __atomic_compare_exchange_n(MK_ARRAY_ELEMS(MkLong, a) + i, &expected, v, false, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);
}

/* A reference cell (slot 0 of an object array): the reference counts of the old and new value are updated under a spin lock chosen by the
   address of the cell (64 locks, one cache line each, so cells of different atomics do not wait for each other). A waiting thread spins a
   few rounds with a CPU pause and then yields its time slice (the holder may have been preempted: a pure spin would burn the slice). */
#define MK_REF_LOCKS 64
typedef struct { volatile int v; char pad[60]; } MkRefLock;
static MkRefLock ref_locks[MK_REF_LOCKS];
static inline volatile int *ref_lock_of(MkObject *a) { return &ref_locks[(((uintptr_t)a) >> 4) % MK_REF_LOCKS].v; }
static void ref_lock_acquire(volatile int *l) {
    int spins = 0;
    while (__atomic_load_n(l, __ATOMIC_RELAXED) != 0 || __atomic_exchange_n(l, 1, __ATOMIC_ACQUIRE) != 0) {
        if (++spins < 64) mk_cpu_relax(); else { mk_sys_thread_yield(); spins = 0; }
    }
}
static void ref_lock_release(volatile int *l) { __atomic_store_n(l, 0, __ATOMIC_RELEASE); }

MkObject *mk_atomic_ref_get(MkObject *a) {
    volatile int *l = ref_lock_of(a);
    ref_lock_acquire(l);
    MkObject *r = MK_ARRAY_ELEMS(MkObject *, a)[0];
    mk_retain(r);
    ref_lock_release(l);
    return r;
}

void mk_atomic_ref_set(MkObject *a, MkObject *v) {
    volatile int *l = ref_lock_of(a);
    mk_retain(v);
    ref_lock_acquire(l);
    MkObject **slot = &MK_ARRAY_ELEMS(MkObject *, a)[0];
    MkObject *old = *slot;
    *slot = v;
    ref_lock_release(l);
    mk_release(old);
}

MkObject *mk_atomic_ref_exchange(MkObject *a, MkObject *v) {
    volatile int *l = ref_lock_of(a);
    mk_retain(v);
    ref_lock_acquire(l);
    MkObject **slot = &MK_ARRAY_ELEMS(MkObject *, a)[0];
    MkObject *old = *slot;
    *slot = v;
    ref_lock_release(l);
    return old; /* the cell's reference moves to the caller */
}

MkBoolean mk_atomic_ref_cas(MkObject *a, MkObject *expected, MkObject *v) {
    volatile int *l = ref_lock_of(a);
    ref_lock_acquire(l);
    MkObject **slot = &MK_ARRAY_ELEMS(MkObject *, a)[0];
    if (*slot != expected) {
        ref_lock_release(l);
        return false;
    }
    mk_retain(v);
    MkObject *old = *slot;
    *slot = v;
    ref_lock_release(l);
    mk_release(old);
    return true;
}
