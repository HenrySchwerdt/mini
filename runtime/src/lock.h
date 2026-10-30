/* A tiny mutex for the allocator slow paths (small-object central pool, arena chunk cache). Statically
 * initialisable; a no-op on single-threaded WebAssembly. */
#ifndef MINIRT_LOCK_H
#define MINIRT_LOCK_H

#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
typedef SRWLOCK MkLock;
#define MK_LOCK_INIT SRWLOCK_INIT
static inline void mk_lock(MkLock *l) { AcquireSRWLockExclusive(l); }
static inline void mk_unlock(MkLock *l) { ReleaseSRWLockExclusive(l); }
#elif defined(__wasm__) && !defined(__wasm_atomics__)
typedef int MkLock;
#define MK_LOCK_INIT 0
static inline void mk_lock(MkLock *l) { (void)l; }
static inline void mk_unlock(MkLock *l) { (void)l; }
#else
#include <pthread.h>
typedef pthread_mutex_t MkLock;
#define MK_LOCK_INIT PTHREAD_MUTEX_INITIALIZER
#define MK_HAVE_PTHREAD 1
static inline void mk_lock(MkLock *l) { pthread_mutex_lock(l); }
static inline void mk_unlock(MkLock *l) { pthread_mutex_unlock(l); }
#endif

#endif
