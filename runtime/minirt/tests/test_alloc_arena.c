/* Arena: bump allocation, chunk growth, huge chunks, reset/destroy, no dispose. */
#include "harness.h"

#include <stdint.h>

typedef struct { MkObject h; int payload; } Obj;
static int disposed;
static void count_dispose(MkObject *s) { (void)s; disposed++; }
static const MkTypeInfo T_Count = {"Count", sizeof(Obj), &mk_type_Any, count_dispose, 0, 0, 0, 0};

static void *A(MkAllocator *a, size_t size, size_t align) { return a->allocate(a, size, align); }

int main(void) {
    MkAllocator *arena = mk_arena_create(256);
    CHECK(arena != NULL);
    CHECK(arena->free == NULL);
    CHECK_EQ_INT(mk_arena_used(arena), 0);

    /* many small allocations: distinct, aligned, writable, across several chunks */
    enum { N = 2000 };
    static unsigned char *ptrs[N];
    size_t sizes[N];
    for (int i = 0; i < N; i++) {
        size_t align = (size_t)1 << (i % 5); /* 1..16 */
        sizes[i] = 1 + (size_t)(i * 7) % 61;
        ptrs[i] = A(arena, sizes[i], align);
        CHECK(ptrs[i] != NULL);
        CHECK(((uintptr_t)ptrs[i] % align) == 0);
        memset(ptrs[i], i & 0xFF, sizes[i]);
    }
    for (int i = 0; i < N; i++)
        for (size_t k = 0; k < sizes[i]; k++)
            if (ptrs[i][k] != (unsigned char)(i & 0xFF)) { CHECK(0); break; } /* no overlap */
    CHECK(mk_arena_used(arena) >= (size_t)N);
    CHECK(mk_arena_reserved(arena) > 256); /* grew beyond the first chunk */

    /* large alignments */
    for (size_t align = 32; align <= 8192; align *= 2) {
        void *p = A(arena, 24, align);
        CHECK(p != NULL && ((uintptr_t)p % align) == 0);
    }
    CHECK(A(arena, 8, 3) == NULL); /* not a power of two */
    CHECK(A(arena, 8, 0) == NULL);
    CHECK(A(arena, 0, 8) != NULL); /* zero size is allowed */
    CHECK(A(arena, SIZE_MAX, 8) == NULL);

    /* huge: own chunk */
    size_t huge_size = (size_t)3 << 20;
    size_t reserved_before_huge = mk_arena_reserved(arena);
    unsigned char *huge = A(arena, huge_size, 16);
    CHECK(huge != NULL && ((uintptr_t)huge % 16) == 0);
    memset(huge, 7, huge_size);
    CHECK(mk_arena_reserved(arena) >= reserved_before_huge + huge_size);
    CHECK(A(arena, 16, 8) != NULL); /* small requests keep working */

    /* reset: huge chunk is freed, regular chunks are kept and reused from the start */
    mk_arena_reset(arena);
    CHECK_EQ_INT(mk_arena_used(arena), 0);
    size_t kept = mk_arena_reserved(arena);
    CHECK(kept >= reserved_before_huge && kept < reserved_before_huge + huge_size);
    unsigned char *again = A(arena, sizes[0], 1);
    CHECK(again == ptrs[0]);
    memset(again, 0, sizes[0]);
    for (int i = 0; i < N; i++) CHECK(A(arena, 40, 8) != NULL);
    mk_arena_reset(arena);
    mk_arena_reset(arena); /* idempotent */
    CHECK_EQ_INT(mk_arena_used(arena), 0);

    /* objects: refcount 1, release disposes but does not free, reset/destroy never dispose */
    disposed = 0;
    Obj *o = (Obj *)mk_alloc_with(arena, &T_Count);
    CHECK(o != NULL && o->h.refcount == 1 && o->payload == 0);
    CHECK(((uintptr_t)o % MK_MAX_ALIGN) == 0);
    mk_release(&o->h);
    CHECK_EQ_INT(disposed, 1);
    for (int i = 0; i < 100; i++) CHECK(mk_alloc_with(arena, &T_Count) != NULL);
    mk_arena_reset(arena);
    CHECK_EQ_INT(disposed, 1); /* policy: no dispose on reset */
    for (int i = 0; i < 100; i++) CHECK(mk_alloc_with(arena, &T_Count) != NULL);
    CHECK(A(arena, (size_t)1 << 20, 8) != NULL);
    mk_arena_destroy(arena);
    CHECK_EQ_INT(disposed, 1); /* ... nor on destroy */
    mk_arena_destroy(NULL);

    /* default initial size and tiny initial size */
    MkAllocator *a0 = mk_arena_create(0);
    MkAllocator *a1 = mk_arena_create(1);
    CHECK(A(a0, 100, 8) != NULL && A(a1, 100, 8) != NULL);
    mk_arena_destroy(a0);
    mk_arena_destroy(a1);

    TEST_DONE();
}
