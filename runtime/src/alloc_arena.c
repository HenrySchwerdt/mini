/* Arena: Scoped bump allocator. Not thread-safe.
 *
 * Memory is held in chunks (header + data). Regular chunks form a list in
 * allocation order; `current` is the one being bumped. Requests that do not fit
 * a regular chunk and are large relative to the chunk size get a dedicated
 * chunk on a separate list, so the current chunk keeps serving small requests.
 *
 * No dispose is run on reset/destroy (see minirt.h): the arena keeps no
 * per-object records. Unused chunk memory and memory freed by reset is
 * poisoned under ASan, handed-out ranges are unpoisoned exactly, so overruns
 * and use-after-reset are reported. */
#include "alloc_arena.h"
#include "alloc_small.h"
#include "lock.h"

#include <stdint.h>

#define CHUNK_HEADER ARENA_CHUNK_HEADER
#define DEFAULT_INITIAL ((size_t)4096)
#define MIN_INITIAL ((size_t)256)
#define MAX_CHUNK ((size_t)4 << 20)

static unsigned char *chunk_data(ArenaChunk *c) { return (unsigned char *)c + CHUNK_HEADER; }

/* Freed regular chunks are kept for the next arena (up to CACHE_MAX bytes, process-wide): a program that opens an
 * arena per round otherwise gets fresh, page-faulting memory from the system every time. A cached chunk is dirty
 * below its high-water mark `hw`, which the zeroed allocation path already handles. Not used under ASan / with
 * MK_PLAIN_MALLOC (every chunk is then a plain malloc block that the sanitizer sees come and go). */
#define CACHE_MAX ((size_t)32 << 20)
#if MK_SMALL_ALLOC
static MkLock cache_lock = MK_LOCK_INIT;
static ArenaChunk *cache_head;
static size_t cache_bytes;
#endif

static ArenaChunk *cache_take(size_t capacity) {
#if MK_SMALL_ALLOC
    mk_lock(&cache_lock);
    for (ArenaChunk **p = &cache_head; *p != NULL; p = &(*p)->next) {
        if ((*p)->capacity == capacity) {
            ArenaChunk *c = *p;
            *p = c->next;
            cache_bytes -= c->capacity;
            mk_unlock(&cache_lock);
            c->next = NULL;
            return c;
        }
    }
    mk_unlock(&cache_lock);
#else
    (void)capacity;
#endif
    return NULL;
}

static int cache_put(ArenaChunk *c) {
#if MK_SMALL_ALLOC
    int ok = 0;
    mk_lock(&cache_lock);
    if (cache_bytes + c->capacity <= CACHE_MAX) {
        c->next = cache_head;
        cache_head = c;
        cache_bytes += c->capacity;
        ok = 1;
    }
    mk_unlock(&cache_lock);
    return ok;
#else
    (void)c;
    return 0;
#endif
}

static ArenaChunk *chunk_new(MkArena *a, size_t capacity, int regular) {
    if (capacity > SIZE_MAX - CHUNK_HEADER) return NULL;
    ArenaChunk *c = regular ? cache_take(capacity) : NULL;
    if (c == NULL) {
        c = calloc(1, CHUNK_HEADER + capacity);
        if (c == NULL) return NULL;
        c->capacity = capacity;
        MK_STAT_SMALL(MK_SMALL_STAT_ARENA_CHUNK, capacity);
    }
    c->next = NULL;
    c->used = 0;
    MK_POISON(chunk_data(c), capacity);
    a->reserved += capacity;
    return c;
}

/* `regular` chunks may go to the cache; dedicated (huge) chunks are always freed */
static void chunk_free(MkArena *a, ArenaChunk *c, int regular) {
    a->reserved -= c->capacity;
    if (regular && cache_put(c)) return;
    MK_UNPOISON(chunk_data(c), c->capacity);
    free(c);
}

/* Bumps `size` bytes aligned to `align` out of `c`, or NULL if it does not fit. With `zero` the bytes are cleared
 * (only as far as they may be dirty). */
static inline void *bump(ArenaChunk *c, size_t size, size_t align, int zero) {
    unsigned char *base = chunk_data(c);
    uintptr_t addr = (uintptr_t)(base + c->used);
    uintptr_t aligned = (addr + (align - 1)) & ~(uintptr_t)(align - 1);
    size_t offset = (size_t)(aligned - (uintptr_t)base);
    if (offset > c->capacity || size > c->capacity - offset) return NULL;
    size_t end = offset + size;
    c->used = end;
    MK_UNPOISON(base + offset, size);
    if (c->hw < end) {
        if (zero && c->hw > offset) memset(base + offset, 0, c->hw - offset);
        c->hw = end;
    } else if (zero) {
        memset(base + offset, 0, size);
    }
    return base + offset;
}

static void *arena_alloc_slow(MkArena *a, size_t size, size_t align, int zero) {
    if (align == 0 || (align & (align - 1)) != 0) return NULL;
    if (size == 0) size = 1;
    if (size > SIZE_MAX / 2 || align > SIZE_MAX / 4) return NULL;

    if (a->current != NULL) {
        void *p = bump(a->current, size, align, zero);
        if (p != NULL) return p;
    }
    /* after a reset the chunks behind `current` are empty and reused in order */
    while (a->current != NULL && a->current->next != NULL) {
        a->current = a->current->next;
        void *p = bump(a->current, size, align, zero);
        if (p != NULL) return p;
    }

    size_t need = size + align; /* worst-case padding in a fresh max-aligned chunk */
    if (need > a->next_size / 2) {
        /* huge: own chunk, exactly sized */
        ArenaChunk *c = chunk_new(a, need, 0);
        if (c == NULL) return NULL;
        c->next = a->huge;
        a->huge = c;
        return bump(c, size, align, zero);
    }
    ArenaChunk *c = chunk_new(a, a->next_size, 1);
    if (c == NULL) return NULL;
    if (a->tail != NULL) a->tail->next = c; else a->head = c;
    a->tail = c;
    a->current = c;
    if (a->next_size < MAX_CHUNK) a->next_size *= 2;
    return bump(c, size, align, zero);
}

/* Raw allocation (the `allocate` of the allocator vtable): any power-of-two alignment, contents undefined. */
void *mk_arena_allocate(MkAllocator *self, size_t size, size_t align) {
    MkArena *a = (MkArena *)self;
    ArenaChunk *c = a->current;
    if (c != NULL && size != 0 && align != 0 && (align & (align - 1)) == 0 && size <= SIZE_MAX / 2) {
        void *p = bump(c, size, align, 0);
        if (p != NULL) return p;
    }
    return arena_alloc_slow(a, size, align, 0);
}

/* Slow path of mk_arena_alloc_zeroed (alloc_arena.h): next chunk, new chunk. */
void *mk_arena_alloc_zeroed_slow(MkAllocator *arena, size_t size) {
    MK_STAT_SMALL(MK_SMALL_STAT_ARENA_SLOW, 0);
    return arena_alloc_slow((MkArena *)arena, size, MK_MAX_ALIGN, 1);
}

MkAllocator *mk_arena_create(size_t initial) {
    if (initial == 0) initial = DEFAULT_INITIAL;
    if (initial < MIN_INITIAL) initial = MIN_INITIAL;
    MkArena *a = calloc(1, sizeof *a);
    if (a == NULL) return NULL;
    a->base.allocate = mk_arena_allocate;
    a->base.free = NULL;
    a->base.traits = MK_ALLOCATOR_SCOPED;
    a->next_size = initial;
    return &a->base;
}

void mk_arena_reset(MkAllocator *arena) {
    MkArena *a = (MkArena *)arena;
    while (a->huge != NULL) {
        ArenaChunk *next = a->huge->next;
        chunk_free(a, a->huge, 0);
        a->huge = next;
    }
    for (ArenaChunk *c = a->head; c != NULL; c = c->next) {
        c->used = 0; /* c->hw stays: the memory below it is dirty */
        MK_POISON(chunk_data(c), c->capacity);
    }
    a->current = a->head;
#ifdef MK_STATS
    MK_STAT_FREE_N(a->stat_objs, a->stat_bytes);
    a->stat_objs = a->stat_bytes = 0;
#endif
}

void mk_arena_destroy(MkAllocator *arena) {
    if (arena == NULL) return;
    MkArena *a = (MkArena *)arena;
#ifdef MK_STATS
    MK_STAT_FREE_N(a->stat_objs, a->stat_bytes);
#endif
    for (ArenaChunk *c = a->head; c != NULL;) {
        ArenaChunk *next = c->next;
        chunk_free(a, c, 1);
        c = next;
    }
    for (ArenaChunk *c = a->huge; c != NULL;) {
        ArenaChunk *next = c->next;
        chunk_free(a, c, 0);
        c = next;
    }
    free(a);
}

#ifdef MK_STATS
void mk_arena_stat_alloc(MkAllocator *a, size_t bytes) {
    MkArena *ar = (MkArena *)a;
    ar->stat_objs++;
    ar->stat_bytes += bytes;
}
#endif

size_t mk_arena_used(const MkAllocator *arena) {
    const MkArena *a = (const MkArena *)arena;
    size_t total = 0;
    for (const ArenaChunk *c = a->head; c != NULL; c = c->next) total += c->used;
    for (const ArenaChunk *c = a->huge; c != NULL; c = c->next) total += c->used;
    return total;
}
size_t mk_arena_reserved(const MkAllocator *arena) { return ((const MkArena *)arena)->reserved; }
