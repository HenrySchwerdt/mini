/* D2: allocator handles for the mini.memory classes and the helpers of the Strict code generation. */
#include "harness.h"

#include <stdint.h>

typedef struct { MkObject h; MkObject *child; int payload; } Node;

static int disposed;
static void dispose_node(MkObject *s) {
    disposed++;
    mk_free_owned(((Node *)s)->child);
}
static const MkTypeInfo T_Node = {"Node", sizeof(Node), &mk_type_Any, dispose_node, 0, 0, 0, 0};

static void check_handles(void) {
    int64_t heap = mk_heap_allocator_h();
    CHECK(heap != 0);
    CHECK((MkAllocator *)(intptr_t)heap == mk_heap_allocator());

    int64_t arena = mk_arena_new_h();
    CHECK(arena != 0);
    MkObject *a = mk_alloc_with((MkAllocator *)(intptr_t)arena, &T_Node);
    CHECK(a != NULL);
    CHECK_EQ_INT(mk_allocator_traits((MkAllocator *)(intptr_t)arena), MK_ALLOCATOR_SCOPED);
    mk_free_dealloc(NULL);   /* ignored */
    mk_arena_close_h(arena); /* the arena takes its objects with it, no dispose */
    CHECK_EQ_INT(disposed, 0);
    mk_arena_close_h(0);     /* ignored */

    int64_t pool = mk_pool_new_h(&T_Node, 2);
    CHECK(pool != 0);
    MkObject *p1 = mk_alloc_with((MkAllocator *)(intptr_t)pool, &T_Node);
    MkObject *p2 = mk_alloc_with((MkAllocator *)(intptr_t)pool, &T_Node);
    CHECK(p1 != NULL && p2 != NULL);
    mk_free_dealloc(p1);
    CHECK_EQ_INT(disposed, 1);
    mk_free_dealloc(p2);
    CHECK_EQ_INT(disposed, 2);
    mk_pool_close_h(pool);

    int64_t dbg = mk_debug_new_h(heap);
    CHECK(dbg != 0);
    MkObject *d = mk_alloc_with((MkAllocator *)(intptr_t)dbg, &T_Node);
    mk_free_dealloc(d);
    CHECK_EQ_INT(disposed, 3);
    /* no destroy: mini.memory DebugAllocators are closed at exit (mk_debug_new_h registers them) */
}

static void check_free_semantics(void) {
    disposed = 0;
    /* mk_free_dealloc leaves reference counted objects to their count */
    MkObject *rc = mk_alloc(&T_Node);
    mk_free_dealloc(rc);
    CHECK_EQ_INT(disposed, 0);
    mk_release(rc);
    CHECK_EQ_INT(disposed, 1);

    /* mk_free_owned (the glue of an owning field) drops the owner's count of a reference counted object ... */
    MkObject *shared = mk_alloc(&T_Node);
    mk_retain(shared); /* somebody else holds it, too */
    mk_free_owned(shared);
    CHECK_EQ_INT(disposed, 1);
    mk_release(shared);
    CHECK_EQ_INT(disposed, 2);

    /* ... and frees the objects of a Deallocating allocator, recursively through dispose */
    MkObject *parent = mk_alloc_with(mk_heap_allocator(), &T_Node);
    MkObject *child = mk_alloc_with(mk_heap_allocator(), &T_Node);
    ((Node *)parent)->child = child;
    mk_free_dealloc(parent);
    CHECK_EQ_INT(disposed, 4);
    mk_free_owned(NULL);

    /* a failed constructor: released / freed / left to the arena */
    MkObject *r = mk_alloc(&T_Node);
    mk_discard(r);
    CHECK_EQ_INT(disposed, 5);
    MkObject *h = mk_alloc_with(mk_heap_allocator(), &T_Node);
    mk_discard(h);
    CHECK_EQ_INT(disposed, 6);
    int64_t arena = mk_arena_new_h();
    MkObject *ao = mk_alloc_with((MkAllocator *)(intptr_t)arena, &T_Node);
    mk_discard(ao);
    CHECK_EQ_INT(disposed, 6);
    mk_arena_close_h(arena);
    mk_discard(NULL);
}

int main(void) {
    check_handles();
    check_free_semantics();
    TEST_DONE();
}
