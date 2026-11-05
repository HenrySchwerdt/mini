/* Reference counting, type tests and the built-in Any type. */
#include "internal.h"
#include "lock.h"

const MkTypeInfo mk_type_Any = {
    "kotlin.Any", sizeof(MkObject), NULL, NULL, NULL, 0, NULL, 0,
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

/* Dropping the last reference to the head of a long linked list releases the next node from the dispose of the previous
 * one: recursion as deep as the list. The nesting is bounded: beyond MK_RELEASE_DEPTH nested releases the dead object is
 * queued, and the outermost release works the queue off (the objects are disposed in a different order, which nobody can
 * observe: dispose only releases). */
#define MK_RELEASE_DEPTH 200
#define MK_PENDING_INLINE 64
static MK_TLS unsigned release_depth;
static MK_TLS MkObject *pending_inline[MK_PENDING_INLINE];
static MK_TLS MkObject **pending_heap;
static MK_TLS size_t pending_n, pending_cap;

static void pending_push(MkObject *o) {
    if (pending_n < MK_PENDING_INLINE) { pending_inline[pending_n++] = o; return; }
    if (pending_n - MK_PENDING_INLINE == pending_cap) {
        pending_cap = pending_cap ? pending_cap * 2 : 1024;
        pending_heap = (MkObject **)realloc(pending_heap, pending_cap * sizeof(MkObject *));
        if (pending_heap == NULL) mk_panic("out of memory");
    }
    pending_heap[pending_n++ - MK_PENDING_INLINE] = o;
}

/* the dispose chain of an object whose count reached zero, then its memory (or, when the candidate buffer points to it, its state) */
static void free_dead(MkObject *object) {
    for (const MkTypeInfo *t = object->type; t != NULL; t = t->super) {
        if (t->dispose != NULL) t->dispose(object);
    }
    if (object->flags & MK_FLAG_BUFFERED) {
        mk_cycles_state(object, MK_CYCLES_ZOMBIE);
        return;
    }
    mk_free_object_memory(object);
}

static void pending_drain(void) {
    while (pending_n > 0) {
        size_t i = --pending_n;
        free_dead(i < MK_PENDING_INLINE ? pending_inline[i] : pending_heap[i - MK_PENDING_INLINE]);
    }
    if (pending_heap != NULL) { free(pending_heap); pending_heap = NULL; pending_cap = 0; } /* a thread that ends leaves nothing behind */
}

void mk_release(MkObject *object) {
    if (object == NULL || is_plain(object)) {
        MK_STAT_RELEASE(0);
        return;
    }
    uint32_t old;
    if ((object->flags & MK_FLAG_SHARED) || __atomic_load_n(&mk_threads_active, __ATOMIC_RELAXED)) {
        MK_STAT_RELEASE(2);
        old = mk_atomic_fetch_add(&object->refcount, UINT32_MAX); /* -1 */
    } else {
        MK_STAT_RELEASE(1);
        old = object->refcount;
        if (old != 0u) object->refcount = old - 1u;
    }
    if (old == 0u) mk_panic("reference count underflow");
    if (old != 1u) {
        if (object->flags & MK_FLAG_ARMED) mk_cycles_candidate(object); /* a possible root of a garbage cycle */
        return;
    }
    /* a buffered object is pointed to by the candidate buffer: its block is freed by the collector, not here; it is DYING
     * from now on, so that a collection that runs before its dispose is done leaves it alone */
    if (object->flags & MK_FLAG_BUFFERED) mk_cycles_state(object, MK_CYCLES_DYING);
    if (release_depth >= MK_RELEASE_DEPTH) { /* a long chain (linked list, deep tree): not on the C stack */
        pending_push(object);
        return;
    }
    release_depth++;
    free_dead(object);
    if (release_depth == 1) pending_drain();
    release_depth--;
}

void mk_share(MkObject *object) {
    if (object == NULL || is_plain(object)) return;
    mk_atomic_fetch_or(&object->flags, MK_FLAG_SHARED);
}

static bool type_is(const MkTypeInfo *t, const MkTypeInfo *target) {
    for (; t != NULL; t = t->super) {
        if (t == target) return true;
        for (size_t i = 0; i < t->interface_count; i++) {
            if (t->interfaces[i] != NULL && type_is(t->interfaces[i], target)) return true;
        }
    }
    return false;
}

bool mk_is_instance(const MkObject *object, const MkTypeInfo *type) {
    if (object == NULL || type == NULL) return false;
    return type_is(object->type, type);
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
        o = mk_alloc(type);
        MkInitFrame frame = {slot, o, mk_init_top};
        mk_init_top = &frame;
        mk_init_depth++;
        ctor(o);
        mk_init_depth--;
        mk_init_top = frame.prev;
        __atomic_store_n(slot, o, __ATOMIC_RELEASE);
    }
    if (!owner) mk_unlock(&mk_init_lock);
    return o;
}
