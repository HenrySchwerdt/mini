/* Allocation: default (Rc) and Heap allocators, the hidden allocator prefix,
 * mk_alloc*, mk_free.
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
 * the free path.
 *
 * Pool and DebugAllocator leave the owner pointer (block+0) and the object
 * header (block+MK_MAX_ALIGN) of freed blocks intact (the Pool stores its
 * free-list link at block+sizeof(void*); the DebugAllocator quarantines the
 * block), which is what
 * lets mk_free diagnose double frees (MK_FLAG_FREED). */
#include "internal.h"

#include <string.h>

MK_TLS MkAllocCtx mk_tls_ctx;

/* malloc/free underneath both the Rc and the Heap allocator. Stateless, so
 * trivially thread-safe. */
static void *malloc_allocate(MkAllocator *self, size_t size, size_t align) {
    (void)self;
    if (align > MK_MAX_ALIGN) return NULL; /* malloc cannot over-align portably */
    return malloc(size);
}

static void malloc_free(MkAllocator *self, void *memory) {
    (void)self;
    free(memory);
}

/* Rc: reference counted. The compiler may rely on release-at-zero. */
static MkAllocator default_allocator = {malloc_allocate, malloc_free,
                                        MK_ALLOCATOR_MANAGED | MK_ALLOCATOR_THREAD_SAFE};
/* Heap: manual ownership. Same memory source, different contract. */
static MkAllocator heap_allocator = {malloc_allocate, malloc_free,
                                     MK_ALLOCATOR_DEALLOCATING | MK_ALLOCATOR_THREAD_SAFE};

MkAllocator *mk_default_allocator(void) { return &default_allocator; }
MkAllocator *mk_rc_allocator(void) { return &default_allocator; }
MkAllocator *mk_heap_allocator(void) { return &heap_allocator; }

uint32_t mk_allocator_traits(MkAllocator *allocator) {
    if (allocator == NULL) allocator = &default_allocator;
    if (allocator->traits != 0) return allocator->traits;
    return allocator->free != NULL ? MK_ALLOCATOR_DEALLOCATING : MK_ALLOCATOR_SCOPED;
}

size_t mk_object_block_size(const MkTypeInfo *type) {
    if (type == NULL || type->instance_size < sizeof(MkObject)) return 0;
    return type->instance_size + MK_PREFIX_SIZE;
}

MkObject *mk_alloc_sized_site(MkAllocator *allocator, const MkTypeInfo *type, size_t size,
                              const char *site) {
    if (type == NULL || size < sizeof(MkObject)) return NULL;
    if (allocator == NULL) allocator = &default_allocator;
    if (size > SIZE_MAX - MK_PREFIX_SIZE) return NULL;
    size_t total = size + MK_PREFIX_SIZE;
    mk_tls_ctx.type = type;
    mk_tls_ctx.site = site;
    unsigned char *block = allocator->allocate(allocator, total, MK_MAX_ALIGN);
    mk_tls_ctx.type = NULL;
    mk_tls_ctx.site = NULL;
    if (block == NULL) return NULL;
    memset(block, 0, total);
    *(MkAllocator **)(void *)block = allocator;
#ifdef MK_STATS
    /* the padding of the prefix remembers the block size for mk_free_object_memory */
    *(size_t *)(void *)(block + sizeof(void *)) = total;
    MK_STAT_ALLOC(total);
#endif
    MkObject *object = (MkObject *)(void *)(block + MK_PREFIX_SIZE);
    object->type = type;
    object->refcount = 1;
    object->flags = 0;
    return object;
}

MkObject *mk_alloc_sized(MkAllocator *allocator, const MkTypeInfo *type, size_t size) {
    return mk_alloc_sized_site(allocator, type, size, NULL);
}

MkObject *mk_alloc_with(MkAllocator *allocator, const MkTypeInfo *type) {
    if (type == NULL) return NULL;
    return mk_alloc_sized_site(allocator, type, type->instance_size, NULL);
}

MkObject *mk_alloc_with_site(MkAllocator *allocator, const MkTypeInfo *type, const char *site) {
    if (type == NULL) return NULL;
    return mk_alloc_sized_site(allocator, type, type->instance_size, site);
}

MkObject *mk_alloc(const MkTypeInfo *type) {
    MkObject *object = mk_alloc_with(&default_allocator, type);
    if (object == NULL) mk_panic("out of memory");
    return object;
}

void mk_free_object_memory(MkObject *object) {
    unsigned char *block = (unsigned char *)object - MK_PREFIX_SIZE;
    MkAllocator *owner = *(MkAllocator **)(void *)block;
#ifdef MK_STATS
    MK_STAT_FREE(*(size_t *)(void *)(block + sizeof(void *)));
#endif
    object->refcount = 0;
    object->flags |= MK_FLAG_FREED;
    if (owner->free != NULL) owner->free(owner, block);
}

static void free_with_site(MkObject *object, const char *site) {
    if (object == NULL) return;
    if (object->flags & (MK_FLAG_STATIC | MK_FLAG_STACK))
        mk_panic("mk_free: object is static or stack-allocated");
    unsigned char *block = (unsigned char *)object - MK_PREFIX_SIZE;
    MkAllocator *owner = *(MkAllocator **)(void *)block;
    if (object->flags & MK_FLAG_FREED) {
        if (mk_debug_is_debug(owner)) mk_debug_double_free(owner, block, site);
        char msg[160];
        snprintf(msg, sizeof msg, "mk_free: double free of %s",
                 object->type != NULL && object->type->name != NULL ? object->type->name : "?");
        mk_panic(msg);
    }
    if (mk_allocator_traits(owner) & MK_ALLOCATOR_SCOPED)
        mk_panic("mk_free: objects of a scoped allocator (arena) cannot be freed individually");
    for (const MkTypeInfo *t = object->type; t != NULL; t = t->super) {
        if (t->dispose != NULL) t->dispose(object);
    }
    mk_tls_ctx.site = site;
    mk_free_object_memory(object);
    mk_tls_ctx.site = NULL;
}

void mk_free(MkObject *object) { free_with_site(object, NULL); }
void mk_free_with_site(MkObject *object, const char *site) { free_with_site(object, site); }

/* ---- D2: handles for the mini.memory classes and the helpers of the Strict code generation ---- */

int64_t mk_heap_allocator_h(void) { return (int64_t)(intptr_t)mk_heap_allocator(); }

int64_t mk_arena_new_h(void) {
    MkAllocator *a = mk_arena_create(0);
    if (a == NULL) mk_panic("out of memory");
    return (int64_t)(intptr_t)a;
}

void mk_arena_close_h(int64_t arena) {
    if (arena != 0) mk_arena_destroy((MkAllocator *)(intptr_t)arena);
}

int64_t mk_pool_new_h(const MkTypeInfo *type, MkInt capacity) {
    MkAllocator *p = mk_pool_create(mk_object_block_size(type), capacity > 0 ? (size_t)capacity : 0);
    if (p == NULL) mk_panic("out of memory");
    return (int64_t)(intptr_t)p;
}

void mk_pool_close_h(int64_t pool) {
    if (pool != 0) mk_pool_destroy((MkAllocator *)(intptr_t)pool);
}

/* DebugAllocators of mini.memory have no close(): they report their leaks and are destroyed when the program ends. */
#define MK_DEBUG_MAX 64
static MkAllocator *debug_live[MK_DEBUG_MAX];
static int debug_count;

static void debug_at_exit(void) {
    for (int i = debug_count - 1; i >= 0; i--) {
        mk_debug_report_leaks(debug_live[i]);
        mk_debug_allocator_destroy(debug_live[i]);
    }
    debug_count = 0;
}

int64_t mk_debug_new_h(int64_t inner) {
    MkAllocator *d = mk_debug_allocator_create((MkAllocator *)(intptr_t)inner);
    if (d == NULL) mk_panic("out of memory");
    if (debug_count == 0) atexit(debug_at_exit);
    if (debug_count < MK_DEBUG_MAX) debug_live[debug_count++] = d;
    return (int64_t)(intptr_t)d;
}

void mk_free_dealloc(MkObject *object) {
    if (object == NULL) return;
    if (object->flags & (MK_FLAG_STATIC | MK_FLAG_STACK)) return;
    if (!(object->flags & MK_FLAG_FREED)) {
        unsigned char *block = (unsigned char *)object - MK_PREFIX_SIZE;
        MkAllocator *owner = *(MkAllocator **)(void *)block;
        if (mk_allocator_traits(owner) & MK_ALLOCATOR_MANAGED) return; /* reference counted: frees itself */
    }
    free_with_site(object, NULL);
}

void mk_free_owned(MkObject *object) {
    if (object == NULL) return;
    if (object->flags & (MK_FLAG_STATIC | MK_FLAG_STACK)) return;
    if (!(object->flags & MK_FLAG_FREED)) {
        unsigned char *block = (unsigned char *)object - MK_PREFIX_SIZE;
        MkAllocator *owner = *(MkAllocator **)(void *)block;
        if (mk_allocator_traits(owner) & MK_ALLOCATOR_MANAGED) {
            mk_release(object); /* the owner's count; anybody else holding a reference keeps the object alive */
            return;
        }
    }
    free_with_site(object, NULL);
}

void mk_discard(MkObject *object) {
    if (object == NULL) return;
    unsigned char *block = (unsigned char *)object - MK_PREFIX_SIZE;
    MkAllocator *owner = *(MkAllocator **)(void *)block;
    uint32_t traits = mk_allocator_traits(owner);
    if (traits & MK_ALLOCATOR_SCOPED) return;
    if (traits & MK_ALLOCATOR_MANAGED) mk_release(object);
    else free_with_site(object, NULL);
}
