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

typedef struct ArenaChunk {
    struct ArenaChunk *next;
    size_t capacity; /* data bytes */
    size_t used;
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
    size_t used_total;                 /* bytes handed out since the last reset */
    size_t reserved;
#ifdef MK_STATS
    size_t stat_objs, stat_bytes; /* objects allocated through mk_alloc_with since the last reset (statistics) */
#endif
} MkArena;

static unsigned char *chunk_data(ArenaChunk *c) { return (unsigned char *)c + CHUNK_HEADER; }

static ArenaChunk *chunk_new(MkArena *a, size_t capacity) {
    if (capacity > SIZE_MAX - CHUNK_HEADER) return NULL;
    ArenaChunk *c = malloc(CHUNK_HEADER + capacity);
    if (c == NULL) return NULL;
    c->next = NULL;
    c->capacity = capacity;
    c->used = 0;
    MK_POISON(chunk_data(c), capacity);
    a->reserved += capacity;
    return c;
}

static void chunk_free(MkArena *a, ArenaChunk *c) {
    a->reserved -= c->capacity;
    MK_UNPOISON(chunk_data(c), c->capacity);
    free(c);
}

/* Bumps `size` bytes aligned to `align` out of `c`, or NULL if it does not fit. */
static void *bump(MkArena *a, ArenaChunk *c, size_t size, size_t align) {
    unsigned char *base = chunk_data(c);
    uintptr_t addr = (uintptr_t)(base + c->used);
    uintptr_t aligned = (addr + (align - 1)) & ~(uintptr_t)(align - 1);
    size_t offset = (size_t)(aligned - (uintptr_t)base);
    if (offset > c->capacity || size > c->capacity - offset) return NULL;
    a->used_total += offset + size - c->used;
    c->used = offset + size;
    MK_UNPOISON(base + offset, size);
    return base + offset;
}

static void *arena_allocate(MkAllocator *self, size_t size, size_t align) {
    MkArena *a = (MkArena *)self;
    if (align == 0 || (align & (align - 1)) != 0) return NULL;
    if (size == 0) size = 1;
    if (size > SIZE_MAX / 2 || align > SIZE_MAX / 4) return NULL;

    for (; a->current != NULL; a->current = a->current->next) {
        void *p = bump(a, a->current, size, align);
        if (p != NULL) return p;
        if (a->current->next == NULL) break;
    }

    size_t need = size + align; /* worst-case padding in a fresh max-aligned chunk */
    if (need > a->next_size / 2) {
        /* huge: own chunk, exactly sized */
        ArenaChunk *c = chunk_new(a, need);
        if (c == NULL) return NULL;
        c->next = a->huge;
        a->huge = c;
        return bump(a, c, size, align);
    }
    ArenaChunk *c = chunk_new(a, a->next_size);
    if (c == NULL) return NULL;
    if (a->tail != NULL) a->tail->next = c; else a->head = c;
    a->tail = c;
    a->current = c;
    if (a->next_size < MAX_CHUNK) a->next_size *= 2;
    return bump(a, c, size, align);
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
        c->used = 0;
        MK_POISON(chunk_data(c), c->capacity);
    }
    a->current = a->head;
    a->used_total = 0;
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

#ifdef MK_STATS
int mk_arena_is(const MkAllocator *a) { return a != NULL && a->allocate == arena_allocate; }

void mk_arena_stat_alloc(MkAllocator *a, size_t bytes) {
    MkArena *ar = (MkArena *)a;
    ar->stat_objs++;
    ar->stat_bytes += bytes;
}
#endif

size_t mk_arena_used(const MkAllocator *arena) { return ((const MkArena *)arena)->used_total; }
size_t mk_arena_reserved(const MkAllocator *arena) { return ((const MkArena *)arena)->reserved; }
