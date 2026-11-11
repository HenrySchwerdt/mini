/* Reference counting, type tests and the built-in Any type. */
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include "alloc_small.h"

/* The nesting of releases is bounded by a counter instead of the stack pointer on WebAssembly (and with -DMK_RELEASE_COUNTER). */
#if defined(__wasm__) && !defined(MK_RELEASE_COUNTER)
#define MK_RELEASE_COUNTER 1
#endif
#include "internal.h"
#include "lock.h"

#if defined(_WIN32)
#include <windows.h>
#elif !defined(__wasm__)
#include <pthread.h>
#endif

#if defined(__GNUC__) || defined(__clang__)
#define MK_UNLIKELY(x) __builtin_expect((x) != 0, 0)
#define MK_INLINE static inline __attribute__((always_inline))
#define MK_NOINLINE __attribute__((noinline))
#else
#define MK_UNLIKELY(x) (x)
#define MK_INLINE static inline
#define MK_NOINLINE
#endif

const MkTypeInfo mk_type_Any = {
    "kotlin.Any", sizeof(MkObject), NULL, NULL, NULL, 0, NULL, 0, NULL, NULL,
};

static bool is_plain(const MkObject *o) {
    return (o->flags & (MK_FLAG_STATIC | MK_FLAG_STACK)) != 0;
}

void mk_retain(MkObject *object) {
    if (object == NULL || is_plain(object)) {
        MK_STAT_RETAIN(0);
        return;
    }
    if ((object->flags & MK_FLAG_SHARED) || __atomic_load_n(&mk_threads_active, __ATOMIC_RELAXED)) {
        MK_STAT_RETAIN(2);
        uint32_t old = mk_atomic_fetch_add(&object->refcount, 1u);
        if (old == 0u || old == UINT32_MAX) mk_panic("reference count overflow or resurrection");
    } else {
        MK_STAT_RETAIN(1);
        if (object->refcount == 0u || object->refcount == UINT32_MAX)
            mk_panic("reference count overflow or resurrection");
        object->refcount++;
    }
}

/* Dropping the head of a long singly linked list releases the next node from the dispose of the previous one: recursion as
 * deep as the list. The nesting is bounded by the stack: below `release_floor` (half of the thread's stack, found on the
 * first use) a dead object is queued instead of disposed at once, and a release that runs above the floor again works the
 * queue off (the objects are disposed in a different order, which nobody can observe: dispose only releases). The test is a
 * compare of the stack pointer with a thread-local word: no counter is written on the common path. */
#define MK_PENDING_INLINE 64
static MK_TLS bool draining;
static MK_TLS MkObject *pending_inline[MK_PENDING_INLINE];
static MK_TLS MkObject **pending_heap;
static MK_TLS size_t pending_n, pending_cap;

#if defined(MK_RELEASE_COUNTER)
/* WebAssembly: the call stack of the engine is not the linear-memory stack (a frame address says nothing about it) and its size is
 * unknown; single threaded. The nesting of releases of armed objects is counted instead (a plain counter: no threads). */
#define MK_RELEASE_DEPTH 200
static unsigned release_depth;
#define MK_DEEP() (release_depth >= MK_RELEASE_DEPTH)
#else
static MK_TLS uintptr_t release_floor = UINTPTR_MAX; /* UINTPTR_MAX: not computed yet (every release takes the slow path once) */
#define MK_DEEP() (MK_SP() < release_floor)
#if defined(_MSC_VER) && !defined(__clang__)
#define MK_SP() ((uintptr_t)_AddressOfReturnAddress())
#elif defined(__x86_64__) && (defined(__GNUC__) || defined(__clang__)) /* (a frame address would force a frame pointer into mk_release) */
static inline __attribute__((always_inline)) uintptr_t mk_sp(void) { uintptr_t sp; __asm__ volatile("mov %%rsp, %0" : "=r"(sp)); return sp; }
#define MK_SP() mk_sp()
#elif defined(__aarch64__) && (defined(__GNUC__) || defined(__clang__))
static inline __attribute__((always_inline)) uintptr_t mk_sp(void) { uintptr_t sp; __asm__ volatile("mov %0, sp" : "=r"(sp)); return sp; }
#define MK_SP() mk_sp()
#else
#define MK_SP() ((uintptr_t)__builtin_frame_address(0))
#endif

/* where the queueing starts: the middle of this thread's stack (the part below it stays free for the chain of disposes that
 * is already running), or a fixed distance below the current frame where the platform does not say */
MK_NOINLINE static uintptr_t release_floor_init(void) {
    uintptr_t sp = MK_SP(), lo = 0, size = 0;
#if defined(_WIN32)
    ULONG_PTR l = 0, h = 0;
    GetCurrentThreadStackLimits(&l, &h);
    lo = (uintptr_t)l;
    size = (uintptr_t)(h - l);
#elif defined(__APPLE__)
    pthread_t self = pthread_self();
    size = (uintptr_t)pthread_get_stacksize_np(self);
    lo = (uintptr_t)pthread_get_stackaddr_np(self) - size;
#elif defined(__linux__)
    pthread_attr_t attr;
    if (pthread_getattr_np(pthread_self(), &attr) == 0) {
        void *addr = NULL;
        size_t sz = 0;
        if (pthread_attr_getstack(&attr, &addr, &sz) == 0) { lo = (uintptr_t)addr; size = (uintptr_t)sz; }
        pthread_attr_destroy(&attr);
    }
#endif
    if (size >= ((uintptr_t)64 << 10) && sp > lo && sp - lo < size) {
        uintptr_t mid = lo + size / 2;
        return mid < sp ? mid : lo + (sp - lo) / 2;
    }
    return sp > ((uintptr_t)256 << 10) ? sp - ((uintptr_t)256 << 10) : 0;
}

#endif

MK_NOINLINE static void pending_push(MkObject *o) {
    if (pending_n < MK_PENDING_INLINE) { pending_inline[pending_n++] = o; return; }
    if (pending_n - MK_PENDING_INLINE == pending_cap) {
        pending_cap = pending_cap ? pending_cap * 2 : 1024;
        pending_heap = (MkObject **)realloc(pending_heap, pending_cap * sizeof(MkObject *));
        if (pending_heap == NULL) mk_panic("out of memory");
    }
    pending_heap[pending_n++ - MK_PENDING_INLINE] = o;
}

MK_NOINLINE static void pending_drain(bool force);

/* the dispose chain of an object whose count reached zero, then its memory. Objects that the disposes queued (the stack was
 * deep) are worked off from here when this frame is above the floor again (before the block is freed: a tail call). */
MK_INLINE void free_dead(MkObject *object, bool buffered, bool watch) {
    for (const MkTypeInfo *t = object->type; t != NULL; t = t->super) {
        if (t->dispose != NULL) t->dispose(object);
    }
    if (watch && MK_UNLIKELY(pending_n != 0)) pending_drain(false);
    if (MK_UNLIKELY(buffered)) mk_cycles_hooks.forget(object); /* (its slot in the candidate buffer) */
#if MK_SMALL_ALLOC
    if (!(object->flags & MK_FLAG_PREFIXED)) {
        mk_small_free_object(object);
        return;
    }
#endif
    mk_free_object_memory(object);
}

#if !defined(MK_RELEASE_COUNTER)
/* Slow path of the stack test (the stack pointer is below the floor, or the floor is not known yet): true when `object` was queued. */
MK_NOINLINE static bool release_defer(MkObject *object) {
    if (release_floor == UINTPTR_MAX) {
        release_floor = release_floor_init();
        if (MK_SP() >= release_floor) return false;
    }
    pending_push(object);
    return true;
}
#endif

/* Works the queue off from a frame above the floor (nested releases of a running drain leave it to the loop). */
MK_NOINLINE static void pending_drain(bool force) {
    if (draining || (!force && MK_DEEP())) return;
    draining = true;
    while (pending_n > 0) {
        size_t i = --pending_n;
        MkObject *o = i < MK_PENDING_INLINE ? pending_inline[i] : pending_heap[i - MK_PENDING_INLINE];
        free_dead(o, (o->flags & MK_FLAG_BUFFERED) != 0, true);
    }
    if (pending_heap != NULL) { free(pending_heap); pending_heap = NULL; pending_cap = 0; } /* a thread that ends leaves nothing behind */
    draining = false;
}

/* The end of main and of a thread: nothing stays queued. */
void mk_release_drain_armed(void) {
    if (pending_n != 0) pending_drain(true);
}

void mk_release_drain(void) {
    if (mk_cycles_hooks.drain != NULL) mk_cycles_hooks.drain(); /* (nothing is ever queued before the collector is installed) */
}

/* A buffered object is pointed to by the candidate buffer: its block is freed by the collector, not here; it is DYING
 * from now on, so that a collection that runs before its dispose is done leaves it alone. */
MK_NOINLINE static void release_buffered(MkObject *object) {
    mk_cycles_hooks.state(object, MK_CYCLES_DYING);
#if defined(MK_RELEASE_COUNTER)
    if (MK_DEEP()) { pending_push(object); return; }
    release_depth++;
    free_dead(object, true, false);
    if (--release_depth == 0 && pending_n != 0) pending_drain(true);
#else
    if (MK_DEEP() && release_defer(object)) return;
    free_dead(object, true, true);
#endif
}

/* The count of an object of a class that can be part of a cycle (and so of a long chain) reached zero: the stack is watched.
 * Objects of the other classes (strings, boxes, plain data) cannot nest deeply and skip this (mk_release). */
void mk_release_armed(MkObject *object, uint32_t flags) {
    bool watch = false;
    if (flags & MK_FLAG_BUFFERED) {
        release_buffered(object);
        return;
    }
#if defined(MK_RELEASE_COUNTER)
    if (MK_DEEP()) { pending_push(object); return; }
    release_depth++;
    free_dead(object, false, false);
    if (--release_depth == 0 && pending_n != 0) pending_drain(true);
    return;
#else
    if (MK_DEEP() && release_defer(object)) return; /* a long chain (linked list, deep tree): not on the C stack */
    watch = true;
#endif
    free_dead(object, false, watch);
}

MkCyclesHooks mk_cycles_hooks;

/* (generated code calls this when a release leaves an ARMED object alive) */
void mk_cycles_candidate(MkObject *object) { mk_cycles_hooks.candidate(object); }

void mk_release(MkObject *object) {
    if (object == NULL) {
        MK_STAT_RELEASE(0);
        return;
    }
    uint32_t flags = object->flags;
    if (flags & (MK_FLAG_STATIC | MK_FLAG_STACK)) {
        MK_STAT_RELEASE(0);
        return;
    }
    uint32_t old;
    if ((flags & MK_FLAG_SHARED) || __atomic_load_n(&mk_threads_active, __ATOMIC_RELAXED)) {
        MK_STAT_RELEASE(2);
        /* Once the count is dropped another thread may release the last reference and free the object, so nothing may touch it
         * afterwards unless this call turned out to hold the last reference itself. The cycle candidate is therefore recorded
         * BEFORE the decrement, while this reference still keeps the object alive (when the decrement then reaches zero the
         * object is a buffered root that dies, which the zero path below handles like any buffered object). */
        if ((__atomic_load_n(&object->flags, __ATOMIC_RELAXED) & MK_FLAG_ARMED) && __atomic_load_n(&object->refcount, __ATOMIC_RELAXED) > 1u)
            mk_cycles_hooks.candidate(object);
        old = mk_atomic_fetch_add(&object->refcount, UINT32_MAX); /* -1 */
        if (old != 1u) {
            if (old == 0u) mk_panic("reference count underflow");
            return;
        }
        flags = __atomic_load_n(&object->flags, __ATOMIC_RELAXED); /* (other threads record candidates meanwhile) */
    } else {
        MK_STAT_RELEASE(1);
        old = object->refcount;
        if (old != 0u) object->refcount = old - 1u;
    }
    if (old != 1u) {
        if (old == 0u) mk_panic("reference count underflow");
        if (flags & MK_FLAG_ARMED) mk_cycles_hooks.candidate(object); /* a possible root of a garbage cycle */
        return;
    }
    if (MK_UNLIKELY((flags & (MK_FLAG_ARMED | MK_FLAG_BUFFERED)) != 0)) {
        mk_cycles_hooks.release_armed(object, flags); /* (object.c, below: only linked with the collector) */
        return;
    }
#if MK_SMALL_ALLOC
    /* strings and boxed Ints (the temporaries of every collection loop) have no dispose and own a small block: free it directly */
    if ((object->type == &mk_type_String || object->type == &mk_type_Int) && !(flags & MK_FLAG_PREFIXED)) {
        mk_small_free_object(object);
        return;
    }
#endif
    free_dead(object, false, false);
}

void mk_share(MkObject *object) {
    if (object == NULL || is_plain(object)) return;
    mk_atomic_fetch_or(&object->flags, MK_FLAG_SHARED);
}

static bool type_is(const MkTypeInfo *t, const MkTypeInfo *target) {
    for (; t != NULL; t = t->super) {
        if (t == target) return true;
        /* (the interface itself is usually listed directly: no need to search the super-interfaces of the others first) */
        for (size_t i = 0; i < t->interface_count; i++) {
            if (t->interfaces[i] == target) return true;
        }
        for (size_t i = 0; i < t->interface_count; i++) {
            if (t->interfaces[i] != NULL && type_is(t->interfaces[i], target)) return true;
        }
    }
    return false;
}

bool mk_is_instance(const MkObject *object, const MkTypeInfo *type) {
    if (object == NULL || type == NULL) return false;
    const MkTypeInfo *t = object->type;
    if (t == type) return true;
    if (type->instance_size != 0) {
        /* a class (interfaces have no instance size): only the superclass chain can contain it */
        for (t = t->super; t != NULL; t = t->super) {
            if (t == type) return true;
        }
        return false;
    }
    return type_is(t, type);
}

/* ---- thread-safe lazy initialisation of objects and top-level properties ---- */

typedef struct MkInitFrame {
    MkObject **slot;
    MkObject *obj;
    struct MkInitFrame *prev;
} MkInitFrame;

static MkLock mk_init_lock = MK_LOCK_INIT; /* one lock for all initialisers: they may nest (the owner is tracked below) */
static MkInitFrame *mk_init_top;           /* the initialisers running, innermost first; guarded by mk_init_lock */
static _Thread_local int mk_init_depth;    /* > 0: this thread owns mk_init_lock (a lock is not recursive on every platform) */

/* initialisers that failed (an exception left the constructor): a later access raises NoClassDefFoundError, as the JVM and
 * Kotlin/Native do. Only the slow path looks here (the slot of a failed initialiser stays NULL); guarded by mk_init_lock. */
typedef struct MkFailedInit {
    MkObject **slot;
    struct MkFailedInit *next;
} MkFailedInit;
static MkFailedInit *mk_init_failed;

MkObject *mk_object_init(MkObject **slot, const MkTypeInfo *type, void (*ctor)(MkObject *)) {
    bool owner = mk_init_depth > 0;
    if (!owner) mk_lock(&mk_init_lock);
    MkObject *o = __atomic_load_n(slot, __ATOMIC_ACQUIRE);
    if (o == NULL) {
        for (MkInitFrame *f = mk_init_top; f != NULL; f = f->prev) {
            if (f->slot == slot) { o = f->obj; break; } /* cyclic initialisation on this thread: the partial object */
        }
    }
    if (o == NULL) {
        for (MkFailedInit *f = mk_init_failed; f != NULL; f = f->next) {
            if (f->slot == slot) {
                char msg[256];
                snprintf(msg, sizeof msg, "Could not initialize class %s", type->name);
                mk_raise(&MkNoClassDefFoundError_ti, msg);
                if (!owner) mk_unlock(&mk_init_lock);
                return NULL;
            }
        }
        o = mk_alloc(type);
        MkInitFrame frame = {slot, o, mk_init_top};
        mk_init_top = &frame;
        mk_init_depth++;
        ctor(o);
        mk_init_depth--;
        mk_init_top = frame.prev;
        if (mk_pending_exception != NULL) {
            /* the initialiser failed: an Exception becomes the cause of an ExceptionInInitializerError, an Error passes as it is */
            MkObject *ex = mk_catch();
            if (!mk_is_instance(ex, &MkError_ti)) {
                MkObject *wrapped = MkExceptionInInitializerError_new(NULL, ex);
                mk_release(ex);
                ex = wrapped;
            }
            mk_rethrow(ex);
            MkFailedInit *f = (MkFailedInit *)malloc(sizeof *f);
            if (f != NULL) {
                f->slot = slot;
                f->next = mk_init_failed;
                mk_init_failed = f;
            }
            mk_release(o);
            if (!owner) mk_unlock(&mk_init_lock);
            return NULL;
        }
        __atomic_store_n(slot, o, __ATOMIC_RELEASE);
    }
    if (!owner) mk_unlock(&mk_init_lock);
    return o;
}
