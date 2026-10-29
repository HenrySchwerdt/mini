/* Pool: Deallocating fixed-size free-list allocator. Not thread-safe.
 *
 * Blocks are carved lazily from slabs. A freed block is pushed on the free
 * list through a link stored at block+sizeof(void*): the first word (the
 * owner pointer of a heap object) and the object header behind it stay intact
 * so mk_free can recognise a second free (MK_FLAG_FREED). */
#include "internal.h"

typedef struct PoolSlab {
    struct PoolSlab *next;
} PoolSlab;

#define SLAB_HEADER ((sizeof(PoolSlab) + MK_MAX_ALIGN - 1) / MK_MAX_ALIGN * MK_MAX_ALIGN)

typedef struct MkPool {
    MkAllocator base;
    size_t block_size;
    size_t slab_blocks;
    PoolSlab *slabs;
    unsigned char *bump, *bump_end;
    void *free_list;
    size_t live;
} MkPool;

static void *pool_allocate(MkAllocator *self, size_t size, size_t align) {
    MkPool *p = (MkPool *)self;
    if (size > p->block_size || align > MK_MAX_ALIGN || align == 0 || (align & (align - 1)) != 0)
        return NULL;
    void *block;
    if (p->free_list != NULL) {
        block = p->free_list;
        p->free_list = *(void **)(void *)((unsigned char *)block + sizeof(void *));
    } else {
        if (p->bump == p->bump_end) {
            PoolSlab *slab = malloc(SLAB_HEADER + p->block_size * p->slab_blocks);
            if (slab == NULL) return NULL;
            slab->next = p->slabs;
            p->slabs = slab;
            p->bump = (unsigned char *)slab + SLAB_HEADER;
            p->bump_end = p->bump + p->block_size * p->slab_blocks;
        }
        block = p->bump;
        p->bump += p->block_size;
    }
    p->live++;
    return block;
}

static void pool_free(MkAllocator *self, void *memory) {
    MkPool *p = (MkPool *)self;
    if (memory == NULL) return;
    if (p->live == 0) mk_panic("pool: free with no live blocks (double free?)");
    *(void **)(void *)((unsigned char *)memory + sizeof(void *)) = p->free_list;
    p->free_list = memory;
    p->live--;
}

MkAllocator *mk_pool_create(size_t block_size, size_t blocks_per_slab) {
    if (blocks_per_slab == 0) blocks_per_slab = 64;
    if (block_size < 2 * sizeof(void *)) block_size = 2 * sizeof(void *);
    if (block_size > SIZE_MAX / 2 / blocks_per_slab) return NULL;
    block_size = (block_size + MK_MAX_ALIGN - 1) / MK_MAX_ALIGN * MK_MAX_ALIGN;
    MkPool *p = calloc(1, sizeof *p);
    if (p == NULL) return NULL;
    p->base.allocate = pool_allocate;
    p->base.free = pool_free;
    p->base.traits = MK_ALLOCATOR_DEALLOCATING;
    p->block_size = block_size;
    p->slab_blocks = blocks_per_slab;
    return &p->base;
}

void mk_pool_destroy(MkAllocator *pool) {
    if (pool == NULL) return;
    MkPool *p = (MkPool *)pool;
    for (PoolSlab *s = p->slabs; s != NULL;) {
        PoolSlab *next = s->next;
        free(s);
        s = next;
    }
    free(p);
}

size_t mk_pool_live_count(const MkAllocator *pool) { return ((const MkPool *)pool)->live; }
