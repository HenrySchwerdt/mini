/* DebugAllocator: wraps another allocator and keeps books.
 *
 * Design:
 *  - Site and type: mk_alloc_with_site / mk_free_with_site (and mk_alloc_sized
 *    for the type) publish them in the thread-local mk_tls_ctx for the duration
 *    of the allocate/free call. No change of the MkAllocator vtable is needed
 *    and every other allocator simply ignores the context.
 *  - Live allocations: a node per block, in an allocation-ordered doubly linked
 *    list (for leak reports) and a pointer-keyed hash table (for free lookup).
 *  - Double free: freed blocks go to a bounded quarantine (count and bytes) and
 *    are only released to the inner allocator when evicted or at destroy. Their
 *    memory stays readable, so mk_free's MK_FLAG_FREED check works and a repeated
 *    free is matched against the quarantine record (alloc + first free site).
 *  - Unknown pointer: neither live nor quarantined -> panic.
 *  - One mutex makes the wrapper thread-safe (when `inner` is). */
#include "internal.h"

#include <stdarg.h>
#include <stdint.h>
#include <string.h>

#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
typedef SRWLOCK MkLock;
static void lock_init(MkLock *l) { InitializeSRWLock(l); }
static void lock_destroy(MkLock *l) { (void)l; }
static void lock_acquire(MkLock *l) { AcquireSRWLockExclusive(l); }
static void lock_release(MkLock *l) { ReleaseSRWLockExclusive(l); }
#else
#include <pthread.h>
typedef pthread_mutex_t MkLock;
static void lock_init(MkLock *l) { pthread_mutex_init(l, NULL); }
static void lock_destroy(MkLock *l) { pthread_mutex_destroy(l); }
static void lock_acquire(MkLock *l) { pthread_mutex_lock(l); }
static void lock_release(MkLock *l) { pthread_mutex_unlock(l); }
#endif

#define QUARANTINE_MAX_COUNT 256
#define QUARANTINE_MAX_BYTES ((size_t)16 << 20)
#define SITE_MAX 200

typedef struct DebugNode {
    struct DebugNode *hash_next;
    struct DebugNode *prev, *next; /* live list (allocation order) or quarantine queue */
    void *block;
    size_t size;
    const char *type_name; /* static type info name, or "<raw>" */
    char alloc_site[SITE_MAX];
    char free_site[SITE_MAX];
} DebugNode;

typedef struct MkDebug {
    MkAllocator base;
    MkAllocator *inner;
    MkLock lock;
    DebugNode **table;
    size_t table_size; /* power of two */
    size_t live_count, live_bytes;
    DebugNode *live_head, *live_tail;
    DebugNode *q_head, *q_tail; /* oldest .. newest */
    size_t q_count, q_bytes;
    size_t allocations, frees;
} MkDebug;

static void copy_site(char *dst, const char *site) {
    if (site == NULL) site = "<unknown>";
    size_t n = strlen(site);
    if (n >= SITE_MAX) n = SITE_MAX - 1;
    memcpy(dst, site, n);
    dst[n] = '\0';
}

static size_t hash_ptr(const void *p, size_t mask) {
    uint64_t x = (uint64_t)(uintptr_t)p >> 4;
    x *= 0x9E3779B97F4A7C15ull;
    return (size_t)(x >> 32) & mask;
}

static void inner_free(MkDebug *d, void *block) {
    if (d->inner->free != NULL) d->inner->free(d->inner, block);
}

static bool table_grow(MkDebug *d) {
    size_t new_size = d->table_size ? d->table_size * 2 : 64;
    DebugNode **t = calloc(new_size, sizeof *t);
    if (t == NULL) return false;
    for (size_t i = 0; i < d->table_size; i++) {
        for (DebugNode *n = d->table[i]; n != NULL;) {
            DebugNode *next = n->hash_next;
            size_t h = hash_ptr(n->block, new_size - 1);
            n->hash_next = t[h];
            t[h] = n;
            n = next;
        }
    }
    free(d->table);
    d->table = t;
    d->table_size = new_size;
    return true;
}

static void *debug_allocate(MkAllocator *self, size_t size, size_t align) {
    MkDebug *d = (MkDebug *)self;
    /* Snapshot the context before calling into inner (which may be a debug itself). */
    const MkTypeInfo *type = mk_tls_ctx.type;
    const char *site = mk_tls_ctx.site;
    void *block = d->inner->allocate(d->inner, size, align);
    if (block == NULL) return NULL;
    DebugNode *n = malloc(sizeof *n);
    if (n == NULL) {
        inner_free(d, block);
        return NULL;
    }
    n->block = block;
    n->size = size;
    n->type_name = type != NULL && type->name != NULL ? type->name : "<raw>";
    copy_site(n->alloc_site, site);
    n->free_site[0] = '\0';

    lock_acquire(&d->lock);
    if (d->live_count + 1 > d->table_size && !table_grow(d)) {
        lock_release(&d->lock);
        free(n);
        inner_free(d, block);
        return NULL;
    }
    size_t h = hash_ptr(block, d->table_size - 1);
    n->hash_next = d->table[h];
    d->table[h] = n;
    n->prev = d->live_tail;
    n->next = NULL;
    if (d->live_tail != NULL) d->live_tail->next = n; else d->live_head = n;
    d->live_tail = n;
    d->live_count++;
    d->live_bytes += size;
    d->allocations++;
    lock_release(&d->lock);
    return block;
}

static void quarantine_evict_one(MkDebug *d) {
    DebugNode *n = d->q_head;
    d->q_head = n->next;
    if (d->q_head == NULL) d->q_tail = NULL;
    d->q_count--;
    d->q_bytes -= n->size;
    inner_free(d, n->block);
    free(n);
}

/* Formats and panics; call outside the lock. */
#if defined(__GNUC__)
__attribute__((format(printf, 1, 2)))
#endif
static _Noreturn void fail(const char *fmt, ...) {
    char msg[4 * SITE_MAX + 160];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(msg, sizeof msg, fmt, ap);
    va_end(ap);
    mk_panic(msg);
}

/* Looks up a freed block in the quarantine; returns a copy of its record. */
static bool find_quarantined(MkDebug *d, void *block, DebugNode *out) {
    for (DebugNode *n = d->q_head; n != NULL; n = n->next) {
        if (n->block == block) {
            *out = *n;
            return true;
        }
    }
    return false;
}

static void debug_free(MkAllocator *self, void *block) {
    MkDebug *d = (MkDebug *)self;
    const char *site = mk_tls_ctx.site != NULL ? mk_tls_ctx.site : "<unknown>";
    lock_acquire(&d->lock);
    DebugNode **link = NULL;
    DebugNode *n = NULL;
    if (d->table_size != 0) {
        link = &d->table[hash_ptr(block, d->table_size - 1)];
        while (*link != NULL && (*link)->block != block) link = &(*link)->hash_next;
        n = *link;
    }
    if (n == NULL) {
        DebugNode rec;
        bool known = find_quarantined(d, block, &rec);
        lock_release(&d->lock);
        if (known)
            fail("double free of %s (allocated at %s, first freed at %s), freed again at %s",
                 rec.type_name, rec.alloc_site, rec.free_site, site);
        fail("free of unknown pointer %p (not allocated by this DebugAllocator), at %s", block,
             site);
    }
    *link = n->hash_next;
    /* unlink from the live list */
    if (n->prev != NULL) n->prev->next = n->next; else d->live_head = n->next;
    if (n->next != NULL) n->next->prev = n->prev; else d->live_tail = n->prev;
    d->live_count--;
    d->live_bytes -= n->size;
    d->frees++;
    /* append to the quarantine */
    copy_site(n->free_site, site);
    n->next = NULL;
    n->prev = d->q_tail;
    if (d->q_tail != NULL) d->q_tail->next = n; else d->q_head = n;
    d->q_tail = n;
    d->q_count++;
    d->q_bytes += n->size;
    while (d->q_count > QUARANTINE_MAX_COUNT || d->q_bytes > QUARANTINE_MAX_BYTES)
        quarantine_evict_one(d);
    lock_release(&d->lock);
}

/* ---- public ------------------------------------------------------------- */

MkAllocator *mk_debug_allocator_create(MkAllocator *inner) {
    if (inner == NULL) inner = mk_heap_allocator();
    MkDebug *d = calloc(1, sizeof *d);
    if (d == NULL) return NULL;
    d->base.allocate = debug_allocate;
    d->base.free = debug_free; /* always tracks, even over a scoped inner allocator */
    d->base.traits = mk_allocator_traits(inner);
    d->inner = inner;
    lock_init(&d->lock);
    return &d->base;
}

void mk_debug_allocator_destroy(MkAllocator *debug) {
    if (debug == NULL) return;
    MkDebug *d = (MkDebug *)debug;
    while (d->q_head != NULL) quarantine_evict_one(d);
    for (DebugNode *n = d->live_head; n != NULL;) {
        DebugNode *next = n->next;
        inner_free(d, n->block);
        free(n);
        n = next;
    }
    free(d->table);
    lock_destroy(&d->lock);
    free(d);
}

void mk_debug_stats(MkAllocator *debug, MkDebugStats *out) {
    MkDebug *d = (MkDebug *)debug;
    lock_acquire(&d->lock);
    out->allocations = d->allocations;
    out->frees = d->frees;
    out->live = d->live_count;
    out->live_bytes = d->live_bytes;
    lock_release(&d->lock);
}

size_t mk_debug_report_leaks_to(MkAllocator *debug, FILE *out) {
    MkDebug *d = (MkDebug *)debug;
    size_t count = 0;
    lock_acquire(&d->lock);
    for (DebugNode *n = d->live_head; n != NULL; n = n->next) {
        fprintf(out, "leak: %s allocated at %s\n", n->type_name, n->alloc_site);
        count++;
    }
    lock_release(&d->lock);
    fflush(out);
    return count;
}

size_t mk_debug_report_leaks(MkAllocator *debug) {
    mk_runtime_flush(); /* keep stdout/stderr ordering sane */
    return mk_debug_report_leaks_to(debug, stderr);
}

bool mk_debug_is_debug(const MkAllocator *allocator) {
    return allocator != NULL && allocator->allocate == debug_allocate;
}

_Noreturn void mk_debug_double_free(MkAllocator *debug, void *block, const char *site) {
    MkDebug *d = (MkDebug *)debug;
    if (site == NULL) site = "<unknown>";
    DebugNode rec;
    lock_acquire(&d->lock);
    bool known = find_quarantined(d, block, &rec);
    lock_release(&d->lock);
    if (known)
        fail("double free of %s (allocated at %s, first freed at %s), freed again at %s",
             rec.type_name, rec.alloc_site, rec.free_site, site);
    fail("double free at %s (block no longer tracked)", site);
}
