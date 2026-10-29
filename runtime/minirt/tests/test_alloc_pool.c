/* Pool: free-list reuse, slabs, size limits, objects, double free. */
#include "harness.h"

#include <stdint.h>

typedef struct { MkObject h; long a, b; } Obj;
static int disposed;
static void count_dispose(MkObject *s) { (void)s; disposed++; }
static const MkTypeInfo T = {"Node", sizeof(Obj), &mk_type_Any, count_dispose, 0, 0, 0, 0};

static void abort_double_free(void *arg) {
    MkObject *o = mk_alloc_with((MkAllocator *)arg, &T);
    mk_free(o);
    mk_free(o);
}

int main(void) {
    MkAllocator *pool = mk_pool_create(100, 4); /* rounded up to a multiple of the max alignment */
    CHECK(pool != NULL);
    CHECK(pool->free != NULL);
    void *a = pool->allocate(pool, 100, 8);
    void *b = pool->allocate(pool, 50, 16);
    void *c = pool->allocate(pool, 1, 1);
    CHECK(a && b && c && a != b && b != c && a != c);
    CHECK_EQ_INT(mk_pool_live_count(pool), 3);
    CHECK(((uintptr_t)a % MK_MAX_ALIGN) == 0 && ((uintptr_t)b % MK_MAX_ALIGN) == 0);
    memset(a, 1, 100); memset(b, 2, 100); memset(c, 3, 100); /* a full block is usable */
    pool->free(pool, b);
    CHECK_EQ_INT(mk_pool_live_count(pool), 2);
    CHECK(pool->allocate(pool, 100, 8) == b); /* LIFO reuse */
    CHECK(pool->allocate(pool, 100, 8) != NULL);
    CHECK(pool->allocate(pool, 100, 8) != NULL); /* 5th block: second slab */
    CHECK(pool->allocate(pool, 112, 8) != NULL); /* block size was rounded up to 112 */
    CHECK(pool->allocate(pool, 113, 8) == NULL); /* larger than the block size */
    CHECK(pool->allocate(pool, 8, 64) == NULL);  /* over-aligned */
    CHECK(pool->allocate(pool, 8, 3) == NULL);
    pool->free(pool, NULL);                      /* no-op */
    mk_pool_destroy(pool);                       /* live blocks are reclaimed, no leak */

    /* lots of blocks across many slabs, interleaved frees */
    MkAllocator *p2 = mk_pool_create(mk_object_block_size(&T), 16);
    enum { N = 1000 };
    static MkObject *objs[N];
    for (int i = 0; i < N; i++) {
        objs[i] = mk_alloc_with(p2, &T);
        CHECK(objs[i] != NULL);
        CHECK(((uintptr_t)objs[i] % MK_MAX_ALIGN) == 0);
        ((Obj *)objs[i])->a = i;
    }
    for (int i = 0; i < N; i++) CHECK(((Obj *)objs[i])->a == i);
    CHECK_EQ_INT(mk_pool_live_count(p2), N);
    for (int i = 0; i < N; i += 2) mk_release(objs[i]); /* release path */
    for (int i = 1; i < N; i += 2) mk_free(objs[i]);    /* manual path */
    CHECK_EQ_INT(disposed, N);
    CHECK_EQ_INT(mk_pool_live_count(p2), 0);
    /* reuse: addresses come from the freed set */
    MkObject *r = mk_alloc_with(p2, &T);
    CHECK(r != NULL);
    int found = 0;
    for (int i = 0; i < N; i++) found |= (r == objs[i]);
    CHECK(found);
    CHECK(((Obj *)r)->a == 0 && r->refcount == 1); /* re-zeroed */
    mk_release(r);

    CHECK_ABORTS(abort_double_free, p2, "double free of Node");
    mk_pool_destroy(p2);
    mk_pool_destroy(NULL);
    TEST_DONE();
}
