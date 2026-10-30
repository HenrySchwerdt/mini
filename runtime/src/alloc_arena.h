/* Arena layout and the inline fast path of a zeroed object allocation (used by mk_alloc_sized_site in alloc.c and by
 * alloc_arena.c). Internal: generated code only sees the MkAllocator base. */
#ifndef MINIRT_ALLOC_ARENA_H
#define MINIRT_ALLOC_ARENA_H

#include "internal.h"

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

#define ARENA_CHUNK_HEADER ((sizeof(ArenaChunk) + MK_MAX_ALIGN - 1) / MK_MAX_ALIGN * MK_MAX_ALIGN)

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

void *mk_arena_allocate(MkAllocator *self, size_t size, size_t align); /* the `allocate` of every arena */
void *mk_arena_alloc_zeroed_slow(MkAllocator *arena, size_t size);

/* Is `a` (not NULL) an arena? */
static inline int mk_arena_is_fast(const MkAllocator *a) { return a->allocate == mk_arena_allocate; }

/* Zeroed block of `size` (> 0) bytes aligned to MK_MAX_ALIGN: bump in the current chunk, else the slow path. The chunk
 * base is MK_MAX_ALIGN-aligned, so aligning the offset aligns the address. */
static inline void *mk_arena_alloc_zeroed(MkAllocator *arena, size_t size) {
    MkArena *a = (MkArena *)arena;
    ArenaChunk *c = a->current;
    if (c != NULL && size <= c->capacity) {
        size_t off = (c->used + (MK_MAX_ALIGN - 1)) & ~(MK_MAX_ALIGN - 1);
        if (off <= c->capacity - size) {
            unsigned char *p = (unsigned char *)c + ARENA_CHUNK_HEADER + off;
            size_t end = off + size;
            c->used = end;
            MK_UNPOISON(p, size);
            size_t hw = c->hw;
            if (hw > off) {
                size_t dirty = hw - off;
                memset(p, 0, dirty < size ? dirty : size);
            }
            if (end > hw) c->hw = end;
            return p;
        }
    }
    return mk_arena_alloc_zeroed_slow(arena, size);
}

#endif
