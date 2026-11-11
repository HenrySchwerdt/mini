/* Cycle collector for Managed (reference counted) objects: synchronous trial deletion (Bacon & Rajan, "Concurrent Cycle
 * Collection in Reference Counted Systems", the synchronous variant). Design notes: docs/memory-model.md, "Cycles".
 *
 * Candidates: a Managed object whose class can be part of a cycle (MkTypeInfo.trace != NULL) is born MK_FLAG_ARMED. The first
 * release that leaves it alive (slow path of mk_release, reached from the inline fast path because of the ARMED bit) buffers it
 * as a possible root: ARMED is replaced by BUFFERED + the purple colour. Objects of acyclic classes (strings, boxes,
 * primitive arrays, plain data) and objects of Strict / arena / Heap allocators never carry the bit: no overhead for them.
 *
 * Collection (mk_cycles_collect): MarkRoots (trial-delete the internal references of the subgraph of every root: gray), Scan
 * (gray objects whose count stays positive are referenced from outside: restore them black, the others are white), CollectWhite
 * (the white objects are garbage). Garbage is freed by running the dispose chain with the objects pinned by a huge count (so the
 * releases between garbage objects are harmless) and then returning the memory.
 *
 * Threads: a collection runs only while no Kotlin thread is running (everything is then single threaded; the counts of shared
 * objects are exact). While threads run, candidates are still recorded under a spin lock (bounded) and collected after the last
 * one ended. */
#include "internal.h"
#include "alloc_small.h"

#include <string.h>

#define COLOR_MASK (7u << MK_COLOR_SHIFT)
/* (A buffered object that dies is freed at once, not as a zombie of the next collection: its slot in the candidate buffer is found
 * through `slots` below and cleared.) */
#define UNBUFFER (MK_FLAG_BUFFERED | COLOR_MASK)
#define COLOR(o) (((o)->flags >> MK_COLOR_SHIFT) & 7u)
#define SET_COLOR(o, c) ((o)->flags = ((o)->flags & ~COLOR_MASK) | ((uint32_t)(c) << MK_COLOR_SHIFT))
enum { BLACK = 0, GRAY = 1, WHITE = 2, PURPLE = 3, DEAD = 4 /* = MK_CYCLES_ZOMBIE, also: a collected garbage object */, DYING = 5 };

#define PIN 0x40000000u /* count of a garbage object while its dispose runs */
#define MIN_THRESHOLD 32768u
#define MAX_THRESHOLD (1u << 21)
#define MIN_ALLOC_LIMIT ((size_t)32 << 20) /* bytes of new candidate-capable objects between two collections, at least */
#define MAX_THREADED_ROOTS (1u << 20)
#define MAX_SLOTS ((1u << 21) - 4096)


static MkObject **roots;
static size_t roots_n, roots_cap;
static size_t threshold = MIN_THRESHOLD;

static size_t alloc_limit = MIN_ALLOC_LIMIT;
static uint64_t work_bytes;
static int trace_enabled = -1;
static unsigned backoff = 1; /* grows while collections find (almost) nothing to free, back to 1 when they do */
static bool in_collect;
static volatile int roots_lock;

/* Where every buffered object sits in `roots` (so that one that dies can clear its slot): open addressing, linear probing, backward
 * shift deletion; guarded by roots_lock when threads run. Empty while no candidates are buffered. An entry is 8 bytes: the low 32 bits
 * of the object's address (>> 4) and index + 1 into `roots`; two objects can share the tag, so a hit is confirmed against
 * `roots[index]`. (The table is the big memory consumer of a collector that buffers a million candidates: 8 bytes per entry at a load
 * of at most 3/4 instead of 12 bytes at 1/2.) */
static struct { uint64_t *e; size_t cap, n; } slots;

static inline uint32_t slot_tag(const MkObject *o) { return (uint32_t)((uintptr_t)o >> 4); }
static inline size_t slot_home_tag(uint32_t tag, size_t cap) {
    return (size_t)(((uint64_t)tag * 0x9E3779B97F4A7C15ull) >> 24) & (cap - 1);
}

static void slots_put(MkObject *o, uint32_t idx) {
    if (slots.n * 4 >= slots.cap * 3) { /* grow (or create) */
        size_t ncap = slots.cap ? slots.cap * 2 : 4096;
        uint64_t *ne = (uint64_t *)calloc(ncap, sizeof(uint64_t));
        if (ne == NULL) mk_panic("out of memory");
        for (size_t i = 0; i < slots.cap; i++) {
            if (slots.e[i] == 0) continue;
            size_t h = slot_home_tag((uint32_t)(slots.e[i] >> 32), ncap);
            while (ne[h] != 0) h = (h + 1) & (ncap - 1);
            ne[h] = slots.e[i];
        }
        free(slots.e);
        slots.e = ne;
        slots.cap = ncap;
    }
    uint32_t tag = slot_tag(o);
    size_t h = slot_home_tag(tag, slots.cap);
    while (slots.e[h] != 0) h = (h + 1) & (slots.cap - 1);
    slots.e[h] = ((uint64_t)tag << 32) | (uint64_t)(idx + 1u);
    slots.n++;
}

static bool slots_take(const MkObject *o, uint32_t *idx) {
    if (slots.n == 0) return false;
    size_t mask = slots.cap - 1;
    uint32_t tag = slot_tag(o);
    size_t h = slot_home_tag(tag, slots.cap);
    for (;;) {
        uint64_t e = slots.e[h];
        if (e == 0) return false;
        if ((uint32_t)(e >> 32) == tag && roots[(uint32_t)e - 1u] == o) break;
        h = (h + 1) & mask;
    }
    *idx = (uint32_t)slots.e[h] - 1u;
    /* backward shift: close the hole so that no probe sequence is cut */
    size_t hole = h, j = h;
    for (;;) {
        j = (j + 1) & mask;
        if (slots.e[j] == 0) break;
        size_t home = slot_home_tag((uint32_t)(slots.e[j] >> 32), slots.cap);
        if (((j - home) & mask) >= ((j - hole) & mask)) {
            slots.e[hole] = slots.e[j];
            hole = j;
        }
    }
    slots.e[hole] = 0;
    slots.n--;
    return true;
}

static void slots_clear(void) {
    if (slots.n != 0) memset(slots.e, 0, slots.cap * sizeof(uint64_t));
    slots.n = 0;
}

typedef struct { MkObject **v; size_t n, cap; } Stack;
static Stack stack_a, stack_b, garbage, carry;
static Stack *cs = &stack_a; /* where visit() pushes */

#if defined(__GNUC__) || defined(__clang__)
#define MK_CYC_NOINLINE __attribute__((noinline))
#else
#define MK_CYC_NOINLINE
#endif
MK_CYC_NOINLINE static void stack_grow(Stack *s) {
    s->cap = s->cap ? s->cap * 2 : 1024;
    s->v = (MkObject **)realloc(s->v, s->cap * sizeof(MkObject *));
    if (s->v == NULL) mk_panic("out of memory");
}
static inline void stack_push(Stack *s, MkObject *o) {
    if (s->n == s->cap) stack_grow(s);
    s->v[s->n++] = o;
}

/* A child takes part in cycle collection when it is a Managed object of a class that can be in a cycle. */
static inline bool cyclic(const MkObject *o) { return o != NULL && (o->flags & (MK_FLAG_ARMED | MK_FLAG_BUFFERED)) != 0; }

/* ---- the phases (explicit stacks: the graphs can be millions of objects deep) ---- */

static uint64_t work;

/* One visitor per phase (the trace functions call it through a pointer for every reference field: no switch per child). */
static void visit_mark(MkObject *c, void *ctx) { /* trial-delete the reference, colour gray */
    (void)ctx;
    if (!cyclic(c)) return;
    c->refcount--;
    if (COLOR(c) != GRAY) { SET_COLOR(c, GRAY); stack_push(cs, c); }
}

static void visit_restore(MkObject *c, void *ctx) { /* scan black: give the reference back */
    (void)ctx;
    if (!cyclic(c)) return;
    c->refcount++;
    if (COLOR(c) != BLACK) { SET_COLOR(c, BLACK); stack_push(cs, c); }
}

static void visit_scan(MkObject *c, void *ctx) { /* children of a white object */
    (void)ctx;
    if (cyclic(c) && COLOR(c) == GRAY) stack_push(cs, c);
}

static void visit_unmark(MkObject *c, void *ctx) { /* garbage about to be disposed: give back the trial deletion of its references to
                                                      * live objects (the dispose releases them for real) */
    (void)ctx;
    if (cyclic(c)) c->refcount++;
}

static void visit_collect(MkObject *c, void *ctx) { /* collect white */
    (void)ctx;
    if (!cyclic(c)) return;
    if (COLOR(c) == WHITE) {
        SET_COLOR(c, DEAD);
        c->flags &= ~(MK_FLAG_ARMED | MK_FLAG_BUFFERED);
        stack_push(cs, c);
        stack_push(&garbage, c);
    }
}

static inline size_t block_bytes(const MkObject *o) {
#if MK_SMALL_ALLOC
    if (!(o->flags & MK_FLAG_PREFIXED)) return (size_t)((o->flags >> SMALL_CLASS_SHIFT) & SMALL_CLASS_MASK) * MK_SMALL_GRAN;
#endif
    return *(const size_t *)(const void *)((const unsigned char *)o - MK_PREFIX_SIZE + sizeof(void *));
}

static inline void trace_with(MkObject *o, void (*visit)(MkObject *, void *)) {
    if (o->type->trace != NULL) o->type->trace(o, visit, NULL);
}

static void run_stack_mark(Stack *st) { /* (the mark phase expands every visited object once: it also measures the work) */
    cs = st;
    while (st->n > 0) {
        MkObject *o = st->v[--st->n];
        work++;
        work_bytes += block_bytes(o);
        trace_with(o, visit_mark);
    }
}

static void run_stack(Stack *st, void (*visit)(MkObject *, void *)) {
    cs = st;
    while (st->n > 0) trace_with(st->v[--st->n], visit);
}

static void mark_gray(MkObject *s) {
    SET_COLOR(s, GRAY);
    stack_push(&stack_a, s);
    run_stack_mark(&stack_a);
}

static void scan_black(MkObject *s) {
    SET_COLOR(s, BLACK);
    stack_push(&stack_b, s);
    run_stack(&stack_b, visit_restore);
}

static void scan(MkObject *s) {
    stack_push(&stack_a, s);
    while (stack_a.n > 0) {
        MkObject *o = stack_a.v[--stack_a.n];
        if (COLOR(o) != GRAY) continue;
        if (o->refcount > 0) {
            scan_black(o);
        } else {
            SET_COLOR(o, WHITE);
            cs = &stack_a;
            trace_with(o, visit_scan);
        }
    }
}

static void collect_white(MkObject *s) {
    SET_COLOR(s, DEAD);
    s->flags &= ~(MK_FLAG_ARMED | MK_FLAG_BUFFERED);
    stack_push(&stack_a, s);
    stack_push(&garbage, s);
    run_stack(&stack_a, visit_collect);
}

static void run_dispose(MkObject *o) {
    for (const MkTypeInfo *t = o->type; t != NULL; t = t->super) {
        if (t->dispose != NULL) t->dispose(o);
    }
}

size_t mk_cycles_collect(void) {
    mk_cycles_ensure(); /* (an explicit collection of a program that allocates traced types through mk_alloc) */
    if (in_collect || __atomic_load_n(&mk_threads_running, __ATOMIC_ACQUIRE) != 0 || roots_n == 0) return 0;
    in_collect = true;
    MkObject **list = roots;
    size_t n = roots_n, list_cap = roots_cap;
    roots = NULL; roots_n = roots_cap = 0; /* candidates that appear during the collection go to a fresh buffer */
    slots_clear();
    work = 0;
    work_bytes = 0;
    carry.n = 0;

    /* MarkRoots: purple roots with a positive count start a trial deletion; the others leave the buffer */
    size_t keep = 0;
    for (size_t i = 0; i < n; i++) {
        MkObject *s = list[i];
        if (s == NULL) { /* freed while buffered */
            continue;
        } else if (COLOR(s) == DYING) { /* its dispose is running (it triggered this collection): stays buffered */
            stack_push(&carry, s);
        } else if (COLOR(s) == PURPLE) {
            mark_gray(s);
            list[keep++] = s;
        } else { /* gray: part of the subgraph of an earlier root, handled with it */
            s->flags = (s->flags & ~MK_FLAG_BUFFERED) | MK_FLAG_ARMED;
        }
    }
    n = keep;
    for (size_t i = 0; i < n; i++) scan(list[i]);
    /* CollectWhite, after the survivors are re-armed */
    garbage.n = 0;
    for (size_t i = 0; i < n; i++) {
        MkObject *s = list[i];
        if (COLOR(s) == WHITE) {
            collect_white(s);
        } else if (COLOR(s) != DEAD) {
            s->flags = (s->flags & ~UNBUFFER) | MK_FLAG_ARMED;
        }
    }
    for (size_t i = 0; i < carry.n; i++) { /* (appended to the buffer of the candidates that the collection itself produces) */
        if (roots_n == roots_cap) {
            roots_cap = roots_cap ? roots_cap * 2 : 1024;
            roots = (MkObject **)realloc(roots, roots_cap * sizeof(MkObject *));
            if (roots == NULL) mk_panic("out of memory");
        }
        slots_put(carry.v[i], (uint32_t)roots_n);
        roots[roots_n++] = carry.v[i];
    }
    carry.n = 0;
    if (roots == NULL) { roots = list; roots_cap = list_cap; roots_n = 0; } else free(list);
    /* the garbage: pinned, disposed (releases the live objects it holds), freed */
    for (size_t i = 0; i < garbage.n; i++) garbage.v[i]->refcount = PIN;
    for (size_t i = 0; i < garbage.n; i++) trace_with(garbage.v[i], visit_unmark);
    for (size_t i = 0; i < garbage.n; i++) run_dispose(garbage.v[i]);
    uint64_t garbage_bytes = 0;
    for (size_t i = 0; i < garbage.n; i++) {
        garbage.v[i]->flags &= ~COLOR_MASK;
        garbage_bytes += block_bytes(garbage.v[i]);
        mk_free_object_memory(garbage.v[i]);
    }
    size_t freed = garbage.n;
    size_t freed_now = freed;
    garbage.n = 0;
    /* adaptive trigger: the next collection comes after as many new candidates as a quarter of the work of this one, so the
     * cost per candidate stays bounded however large the live graph is */
    /* collections that free next to nothing (a big live graph that is only retained and released) are made rarer, up to 64 times */
    backoff = freed * 8 < work ? (backoff < 64 ? backoff * 8 : backoff) : 1;
    size_t live = (size_t)(work > freed ? work - freed : 0);
    size_t t = live / 4;
    threshold = (t > MAX_THRESHOLD ? MAX_THRESHOLD : t > MIN_THRESHOLD ? t : MIN_THRESHOLD) * backoff;
    if (threshold > MAX_SLOTS) threshold = MAX_SLOTS; /* (the slot index has 21 bits) */
    /* allocation volume: the next collection after twice the bytes that survived this one (the live heap triples at most) */
    uint64_t live_bytes = work_bytes > garbage_bytes ? work_bytes - garbage_bytes : 0;
    alloc_limit = (live_bytes * 3 > MIN_ALLOC_LIMIT ? (size_t)(live_bytes * 3) : MIN_ALLOC_LIMIT) * backoff;
    __atomic_store_n(&mk_cycles_alloc_bytes, 0, __ATOMIC_RELAXED);
    if (trace_enabled < 0) { const char *e = getenv("MINI_GC_TRACE"); trace_enabled = e != NULL && e[0] != '\0' && e[0] != '0'; }
    if (trace_enabled) fprintf(stderr, "[cycles] roots=%zu visited=%llu (%llu bytes survive) freed=%zu next: %zu roots or %zu bytes\n", n, (unsigned long long)work, (unsigned long long)live_bytes, freed_now, threshold, alloc_limit);
    in_collect = false;
    return freed;
}

/* ---- candidates ---- */

static inline void roots_lock_acquire(void) {
    while (__atomic_exchange_n(&roots_lock, 1, __ATOMIC_ACQUIRE) != 0) {}
}
static inline void roots_lock_release(void) { __atomic_store_n(&roots_lock, 0, __ATOMIC_RELEASE); }

static void cycles_candidate(MkObject *o) {
    bool threaded = __atomic_load_n(&mk_threads_active, __ATOMIC_RELAXED) != 0;
    if (threaded) roots_lock_acquire();
    if (__atomic_load_n(&o->flags, __ATOMIC_RELAXED) & MK_FLAG_ARMED) { /* (re-checked under the lock) */
        if (!(threaded && roots_n >= MAX_THREADED_ROOTS) && roots_n < MAX_SLOTS) {
            if (roots_n == roots_cap) {
                roots_cap = roots_cap ? roots_cap * 2 : 1024;
                roots = (MkObject **)realloc(roots, roots_cap * sizeof(MkObject *));
                if (roots == NULL) mk_panic("out of memory");
            }
            slots_put(o, (uint32_t)roots_n);
            roots[roots_n++] = o;
            if (threaded) {
                __atomic_fetch_and(&o->flags, ~(MK_FLAG_ARMED | COLOR_MASK), __ATOMIC_ACQ_REL);
                __atomic_fetch_or(&o->flags, MK_FLAG_BUFFERED | ((uint32_t)PURPLE << MK_COLOR_SHIFT), __ATOMIC_ACQ_REL);
            } else {
                o->flags = (o->flags & ~(MK_FLAG_ARMED | COLOR_MASK)) | MK_FLAG_BUFFERED | ((uint32_t)PURPLE << MK_COLOR_SHIFT);
            }
        }
    }
    if (threaded) roots_lock_release();
    /* (a collection needs that no Kotlin thread runs: test that first, so the buffer counters are read only when nobody can change them) */
    if (__atomic_load_n(&mk_threads_running, __ATOMIC_ACQUIRE) == 0 &&
        ((roots_n >= threshold && backoff == 1) || __atomic_load_n(&mk_cycles_alloc_bytes, __ATOMIC_RELAXED) >= alloc_limit))
        mk_cycles_collect();
}

/* Called by mk_release when the count of a BUFFERED object reached zero: it is DYING while its dispose runs (a collection that
 * its dispose triggers leaves it alone); mk_cycles_forget then clears its slot in the buffer and the caller frees the block. */
static void cycles_state(MkObject *o, unsigned state) {
    if (__atomic_load_n(&mk_threads_active, __ATOMIC_RELAXED)) {
        __atomic_fetch_and(&o->flags, ~COLOR_MASK, __ATOMIC_ACQ_REL);
        __atomic_fetch_or(&o->flags, (uint32_t)state << MK_COLOR_SHIFT, __ATOMIC_ACQ_REL);
    } else {
        SET_COLOR(o, state);
    }
}

static void cycles_forget(MkObject *o) {
    bool threaded = __atomic_load_n(&mk_threads_active, __ATOMIC_RELAXED) != 0;
    if (threaded) roots_lock_acquire();
    uint32_t idx;
    if (slots_take(o, &idx)) roots[idx] = NULL;
    if (threaded) roots_lock_release();
}

/* Frees the garbage cycles that remain at the end of main (freeing a cycle can make another one garbage: repeat). The process
 * is about to end and the system takes everything back, so this only runs where somebody looks for leaks: in sanitizer builds
 * (LeakSanitizer must not see garbage cycles) and with MINI_GC_AT_EXIT=1 (valgrind and friends). */
static size_t cycles_at_exit(void) {
    size_t total = 0, n;
    if (__atomic_load_n(&mk_threads_running, __ATOMIC_ACQUIRE) != 0) return 0;
#if defined(__SANITIZE_ADDRESS__)
    int wanted = 1;
#elif defined(__has_feature)
#if __has_feature(address_sanitizer)
    int wanted = 1;
#else
    int wanted = 0;
#endif
#else
    int wanted = 0;
#endif
    if (!wanted) {
        const char *e = getenv("MINI_GC_AT_EXIT");
        wanted = e != NULL && e[0] != '\0' && e[0] != '0';
    }
    if (!wanted) return 0;
    while ((n = mk_cycles_collect()) > 0) total += n;
    return total;
}

void mk_cycles_install(void) {
    mk_cycles_hooks.forget = cycles_forget;
    mk_cycles_hooks.state = cycles_state;
    mk_cycles_hooks.at_exit = cycles_at_exit;
    mk_cycles_hooks.release_armed = mk_release_armed;
    mk_cycles_hooks.drain = mk_release_drain_armed;
    mk_cycles_hooks.candidate = cycles_candidate; /* (last: it is the test of mk_cycles_armed_alloc) */
}
