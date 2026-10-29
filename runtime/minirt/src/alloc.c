/* Allocation: default allocator and the hidden allocator prefix.
 *
 * Block layout for every heap object:
 *
 *   block                      object (= MkObject*)
 *   |<---- MK_MAX_ALIGN ------>|
 *   [ MkAllocator *owner | pad ][ MkObject header | fields ... ]
 *
 * The prefix is exactly MK_MAX_ALIGN bytes so the object stays max-aligned.
 * `allocator->free` receives the block pointer (what `allocate` returned).
 * Static and stack objects have no prefix; their flags keep them away from
 * the free path. */
#include "internal.h"

#include <string.h>

static void *default_allocate(MkAllocator *self, size_t size, size_t align) {
    (void)self;
    if (align > MK_MAX_ALIGN) return NULL; /* malloc cannot over-align portably */
    return malloc(size);
}

static void default_free(MkAllocator *self, void *memory) {
    (void)self;
    free(memory);
}

/* Stateless, so trivially thread-safe. */
static MkAllocator default_allocator = {default_allocate, default_free};

MkAllocator *mk_default_allocator(void) { return &default_allocator; }

MkObject *mk_alloc_sized(MkAllocator *allocator, const MkTypeInfo *type, size_t size) {
    if (type == NULL || size < sizeof(MkObject)) return NULL;
    if (allocator == NULL) allocator = &default_allocator;
    if (size > SIZE_MAX - MK_MAX_ALIGN) return NULL;
    size_t total = size + MK_MAX_ALIGN;
    unsigned char *block = allocator->allocate(allocator, total, MK_MAX_ALIGN);
    if (block == NULL) return NULL;
    memset(block, 0, total);
    *(MkAllocator **)(void *)block = allocator;
    MkObject *object = (MkObject *)(void *)(block + MK_MAX_ALIGN);
    object->type = type;
    object->refcount = 1;
    object->flags = 0;
    return object;
}

MkObject *mk_alloc_with(MkAllocator *allocator, const MkTypeInfo *type) {
    if (type == NULL) return NULL;
    return mk_alloc_sized(allocator, type, type->instance_size);
}

MkObject *mk_alloc(const MkTypeInfo *type) {
    MkObject *object = mk_alloc_with(&default_allocator, type);
    if (object == NULL) mk_panic("out of memory");
    return object;
}

void mk_free_object_memory(MkObject *object) {
    unsigned char *block = (unsigned char *)object - MK_MAX_ALIGN;
    MkAllocator *owner = *(MkAllocator **)(void *)block;
    if (owner->free != NULL) owner->free(owner, block);
}
