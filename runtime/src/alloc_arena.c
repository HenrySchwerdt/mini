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
#include "internal.h"

#include <stdint.h>
#include <string.h>

/* Allocation is a pointer bump: `used` is the offset of the next free byte. Chunks come from calloc and `hw` is the
 * high-water mark of what has ever been handed out of the chunk: everything at or above it is still zero, so the
 * zeroed allocation of an object (mk_alloc_sized_site) only clears the part below `hw` (memory reused after a reset)
 * and costs no memset at all in fresh chunks. */
typedef struct ArenaChunk {
    struct ArenaChunk *next;
    size_t capacity; /* data bytes */
    size_t used;
    size_t hw;
} ArenaChunk;

#define CHUNK_HEADER ((sizeof(ArenaChunk) + MK_MAX_ALIGN - 1) / MK_MAX_ALIGN * MK_MAX_ALIGN)
#define DEFAULT_INITIAL ((size_t)4096)
#define MIN_INITIAL ((size_t)256)
#define MAX_CHUNK ((size_t)4 << 20)

typedef struct MkArena {
    MkAllocator base;
    ArenaChunk *head, *tail, *current; /* regular chunks */
    ArenaChunk *huge;                  /* dedicated chunks */
    size_t next_size;                  /* data size of the next regular chunk */
    size_t reserved;
#ifdef MK_STATS
    size_t stat_objs, stat_bytes; /* objects allocated through mk_alloc_with since the last reset (statistics) */
#endif
} MkArena;

static unsigned char *chunk_data(ArenaChunk *c) { return (unsigned char *)c + CHUNK_HEADER; }

static ArenaChunk *chunk_new(MkArena *a, size_t capacity) {
    if (capacity > SIZE_MAX - CHUNK_HEADER) return NULL;
    ArenaChunk *c = calloc(1, CHUNK_HEADER + capacity);
    if (c == NULL) return NULL;
    c->next = NULL;
    c->capacity = capacity;
    c->used = 0;
    c->hw = 0;
    MK_STAT_SMALL(MK_SMALL_STAT_ARENA_CHUNK, capacity);
    MK_POISON(chunk_data(c), capacity);
    a->reserved += capacity;
    return c;
}

static void chunk_free(MkArena *a, ArenaChunk *c) {
    a->reserved -= c->capacity;
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
        ArenaChunk *c = chunk_new(a, need);
        if (c == NULL) return NULL;
        c->next = a->huge;
        a->huge = c;
        return bump(c, size, align, zero);
    }
    ArenaChunk *c = chunk_new(a, a->next_size);
    if (c == NULL) return NULL;
    if (a->tail != NULL) a->tail->next = c; else a->head = c;
    a->tail = c;
    a->current = c;
    if (a->next_size < MAX_CHUNK) a->next_size *= 2;
    return bump(c, size, align, zero);
}

/* Fast path: the current chunk has room. */
static inline void *arena_alloc(MkArena *a, size_t size, size_t align, int zero) {
    ArenaChunk *c = a->current;
    if (c != NULL && size != 0 && align != 0 && (align & (align - 1)) == 0 && size <= SIZE_MAX / 2) {
        void *p = bump(c, size, align, zero);
        if (p != NULL) return p;
    }
    return arena_alloc_slow(a, size, align, zero);
}

static void *arena_allocate(MkAllocator *self, size_t size, size_t align) {
    return arena_alloc((MkArena *)self, size, align, 0);
}

/* Zeroed block of `size` bytes aligned to MK_MAX_ALIGN: what mk_alloc_sized_site needs, without the indirect call
 * through `allocate` and without clearing fresh chunk memory a second time. */
void *mk_arena_alloc_zeroed(MkAllocator *arena, size_t size) {
    MK_STAT_SMALL(MK_SMALL_STAT_ARENA_ALLOC, 0);
    return arena_alloc((MkArena *)arena, size, MK_MAX_ALIGN, 1);
}

MkAllocator *mk_arena_create(size_t initial) {
    if (initial == 0) initial = DEFAULT_INITIAL;
    if (initial < MIN_INITIAL) initial = MIN_INITIAL;
    MkArena *a = calloc(1, sizeof *a);
    if (a == NULL) return NULL;
    a->base.allocate = arena_allocate;
    a->base.free = NULL;
    a->base.traits = MK_ALLOCATOR_SCOPED;
    a->next_size = initial;
    return &a->base;
}

void mk_arena_reset(MkAllocator *arena) {
    MkArena *a = (MkArena *)arena;
    while (a->huge != NULL) {
        ArenaChunk *next = a->huge->next;
        chunk_free(a, a->huge);
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
        chunk_free(a, c);
        c = next;
    }
    for (ArenaChunk *c = a->huge; c != NULL;) {
        ArenaChunk *next = c->next;
        chunk_free(a, c);
        c = next;
    }
    free(a);
}

int mk_arena_is(const MkAllocator *a) { return a != NULL && a->allocate == arena_allocate; }

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
