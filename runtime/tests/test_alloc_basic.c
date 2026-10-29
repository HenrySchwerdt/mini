/* Traits, Heap/Rc allocators, alignment for every allocator, mk_free semantics. */
#include "harness.h"

#include <stdint.h>

typedef struct { MkObject h; int payload; } Obj;

static char order[16];
static void note(char c) { size_t n = strlen(order); order[n] = c; order[n + 1] = 0; }
static void dispose_a(MkObject *s) { (void)s; note('A'); }
static void dispose_b(MkObject *s) { (void)s; note('B'); }
static const MkTypeInfo T_A = {"A", sizeof(Obj), &mk_type_Any, dispose_a, 0, 0, 0, 0};
static const MkTypeInfo T_B = {"B", sizeof(Obj), &T_A, dispose_b, 0, 0, 0, 0};
static const MkTypeInfo T_Plain = {"Plain", sizeof(Obj), &mk_type_Any, NULL, 0, 0, 0, 0};
static const MkTypeInfo T_Iface = {"Iface", 0, NULL, NULL, 0, 0, 0, 0};

/* legacy allocators: positional {allocate, free}, traits left 0 */
static void *l_alloc(MkAllocator *s, size_t size, size_t a) { (void)s; (void)a; return malloc(size); }
static void l_free(MkAllocator *s, void *m) { (void)s; free(m); }

static void check_traits(void) {
    uint32_t def = MK_ALLOCATOR_MANAGED | MK_ALLOCATOR_THREAD_SAFE;
    uint32_t heap = MK_ALLOCATOR_DEALLOCATING | MK_ALLOCATOR_THREAD_SAFE;
    CHECK_EQ_INT(mk_allocator_traits(mk_default_allocator()), def);
    CHECK_EQ_INT(mk_allocator_traits(NULL), def);
    CHECK(mk_rc_allocator() == mk_default_allocator());
    CHECK_EQ_INT(mk_allocator_traits(mk_heap_allocator()), heap);
    CHECK(mk_heap_allocator() != mk_default_allocator());
    MkAllocator legacy = {l_alloc, l_free, 0};
    MkAllocator legacy_scoped = {l_alloc, NULL, 0};
    CHECK_EQ_INT(mk_allocator_traits(&legacy), MK_ALLOCATOR_DEALLOCATING);
    CHECK_EQ_INT(mk_allocator_traits(&legacy_scoped), MK_ALLOCATOR_SCOPED);
    MkAllocator explicit_t = {l_alloc, l_free, MK_ALLOCATOR_MANAGED};
    CHECK_EQ_INT(mk_allocator_traits(&explicit_t), MK_ALLOCATOR_MANAGED);
    MkAllocator *arena = mk_arena_create(0);
    MkAllocator *pool = mk_pool_create(64, 4);
    MkAllocator *dbg = mk_debug_allocator_create(mk_heap_allocator());
    MkAllocator *dbg_arena = mk_debug_allocator_create(arena);
    CHECK_EQ_INT(mk_allocator_traits(arena), MK_ALLOCATOR_SCOPED);
    CHECK_EQ_INT(mk_allocator_traits(pool), MK_ALLOCATOR_DEALLOCATING);
    CHECK_EQ_INT(mk_allocator_traits(dbg), heap);
    CHECK_EQ_INT(mk_allocator_traits(dbg_arena), MK_ALLOCATOR_SCOPED);
    mk_debug_allocator_destroy(dbg_arena);
    mk_debug_allocator_destroy(dbg);
    mk_pool_destroy(pool);
    mk_arena_destroy(arena);
}

/* Raw alignment/size sweep plus object blocks of many sizes. */
static void exercise(MkAllocator *a, size_t max_block) {
    for (size_t size = 1; size <= 256 && size <= max_block; size++) {
        for (size_t align = 1; align <= 16; align *= 2) {
            unsigned char *p = a->allocate(a, size, align);
            CHECK(p != NULL);
            if (p == NULL) return;
            CHECK(((uintptr_t)p % align) == 0);
            memset(p, 0xAB, size); /* ASan: the whole block must be writable */
            if (a->free != NULL) a->free(a, p);
        }
    }
    /* objects: every instance size from sizeof(MkObject) up must be max-aligned */
    for (size_t size = sizeof(MkObject); size + MK_MAX_ALIGN <= max_block && size <= 300; size++) {
        MkObject *o = mk_alloc_sized(a, &T_Plain, size);
        CHECK(o != NULL);
        if (o == NULL) return;
        CHECK(((uintptr_t)o % MK_MAX_ALIGN) == 0);
        CHECK_EQ_INT(o->refcount, 1);
        memset((unsigned char *)o + sizeof(MkObject), 0x5A, size - sizeof(MkObject));
        mk_release(o);
    }
}

static void check_alignment(void) {
    exercise(mk_default_allocator(), SIZE_MAX);
    exercise(mk_heap_allocator(), SIZE_MAX);
    CHECK(mk_heap_allocator()->allocate(mk_heap_allocator(), 8, 64) == NULL); /* over-aligned */

    MkAllocator *arena = mk_arena_create(256);
    exercise(arena, SIZE_MAX);
    mk_arena_destroy(arena);

    MkAllocator *pool = mk_pool_create(512, 8);
    exercise(pool, 512);
    CHECK_EQ_INT(mk_pool_live_count(pool), 0);
    mk_pool_destroy(pool);

    MkAllocator *dbg = mk_debug_allocator_create(NULL);
    exercise(dbg, SIZE_MAX);
    MkDebugStats st;
    mk_debug_stats(dbg, &st);
    CHECK_EQ_INT(st.live, 0);
    CHECK(st.allocations > 0 && st.allocations == st.frees);
    mk_debug_allocator_destroy(dbg);

    /* big blocks */
    size_t big = (size_t)8 << 20;
    unsigned char *p = mk_heap_allocator()->allocate(mk_heap_allocator(), big, 16);
    CHECK(p != NULL && ((uintptr_t)p % 16) == 0);
    memset(p, 1, big);
    mk_heap_allocator()->free(mk_heap_allocator(), p);
}

static void check_alloc_with(void) {
    CHECK(mk_alloc_with(NULL, NULL) == NULL);
    CHECK(mk_alloc_with(mk_heap_allocator(), NULL) == NULL);
    CHECK(mk_alloc_with(mk_heap_allocator(), &T_Iface) == NULL); /* instance_size 0 */
    CHECK(mk_alloc_with_site(mk_heap_allocator(), NULL, "x") == NULL);
    CHECK(mk_alloc_with_site(NULL, &T_Iface, "x") == NULL);
    CHECK_EQ_INT(mk_object_block_size(&T_Iface), 0);
    CHECK_EQ_INT(mk_object_block_size(&T_Plain), sizeof(Obj) + MK_MAX_ALIGN);
    Obj *o = (Obj *)mk_alloc_with_site(mk_heap_allocator(), &T_Plain, "site.kt:1:1"); /* ignored */
    CHECK(o != NULL && o->h.refcount == 1 && o->payload == 0);
    mk_free(&o->h);
}

/* ---- mk_free ---- */
static int disposed;
static void count_dispose(MkObject *s) { (void)s; disposed++; }
static const MkTypeInfo T_Count = {"Count", sizeof(Obj), &mk_type_Any, count_dispose, 0, 0, 0, 0};

typedef struct { MkAllocator base; void *last_free; size_t frees; } Spy;
static void *spy_alloc(MkAllocator *s, size_t size, size_t a) { (void)s; (void)a; return malloc(size); }
static void spy_free(MkAllocator *s, void *m) { Spy *p = (Spy *)s; p->last_free = m; p->frees++; free(m); }

static void abort_static(void *arg) { (void)arg; static Obj st = {MK_STATIC_HEADER(&T_Count), 0}; mk_free(&st.h); }
static void abort_stack(void *arg) { (void)arg; Obj sk = {{&T_Count, 1, MK_FLAG_STACK}, 0}; mk_free(&sk.h); }
static void abort_arena(void *arg) { MkObject *o = mk_alloc_with((MkAllocator *)arg, &T_Count); mk_free(o); }
static void abort_double(void *arg) {
    MkObject *o = mk_alloc_with((MkAllocator *)arg, &T_Count);
    mk_free(o);
    mk_free(o);
}
static void abort_release_after_free(void *arg) {
    MkObject *o = mk_alloc_with((MkAllocator *)arg, &T_Count);
    mk_free(o);
    mk_release(o);
}

static void check_free(void) {
    /* dispose chain, regardless of refcount */
    Obj *o = (Obj *)mk_alloc_with(mk_heap_allocator(), &T_B);
    mk_retain(&o->h);
    mk_retain(&o->h);
    CHECK_EQ_INT(o->h.refcount, 3);
    mk_free(&o->h);
    CHECK(strcmp(order, "BA") == 0);

    mk_free(NULL);

    /* free gets the block pointer, not the object pointer */
    Spy spy = {{spy_alloc, spy_free, MK_ALLOCATOR_DEALLOCATING}, NULL, 0};
    MkObject *so = mk_alloc_with(&spy.base, &T_Count);
    mk_free(so);
    CHECK_EQ_INT(spy.frees, 1);
    CHECK((unsigned char *)spy.last_free == (unsigned char *)so - MK_MAX_ALIGN);
    CHECK_EQ_INT(disposed, 1);

    /* mk_free also works for the default (Rc) allocator: forced release */
    MkObject *d = mk_alloc(&T_Count);
    mk_retain(d);
    mk_free(d);
    CHECK_EQ_INT(disposed, 2);

    CHECK_ABORTS(abort_static, NULL, "static or stack");
    CHECK_ABORTS(abort_stack, NULL, "static or stack");

    MkAllocator *arena = mk_arena_create(0);
    CHECK_ABORTS(abort_arena, arena, "arena");
    MkObject *ao = mk_alloc_with(arena, &T_Count); /* release: dispose, no free */
    mk_release(ao);
    CHECK_EQ_INT(disposed, 3);
    mk_arena_destroy(arena);

    MkAllocator *pool = mk_pool_create(mk_object_block_size(&T_Count), 4);
    CHECK_ABORTS(abort_double, pool, "double free of Count");
    CHECK_ABORTS(abort_release_after_free, pool, "underflow");
    mk_pool_destroy(pool);
}

int main(void) {
    check_traits();
    check_alignment();
    check_alloc_with();
    check_free();
    TEST_DONE();
}
