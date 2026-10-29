#include "harness.h"

#include <stdint.h>

static char order[64];
static void note(char c) {
    size_t n = strlen(order);
    order[n] = c;
    order[n + 1] = 0;
}

static void dispose_a(MkObject *s) { (void)s; note('A'); }
static void dispose_b(MkObject *s) { (void)s; note('B'); }
static void dispose_c(MkObject *s) { (void)s; note('C'); }

typedef struct { MkObject h; int payload; } Obj;
static const MkTypeInfo T_A = {"A", sizeof(Obj), &mk_type_Any, dispose_a, 0, 0, 0, 0};
static const MkTypeInfo T_B = {"B", sizeof(Obj), &T_A, dispose_b, 0, 0, 0, 0};
static const MkTypeInfo T_Mid = {"Mid", sizeof(Obj), &T_B, NULL, 0, 0, 0, 0}; /* NULL dispose skipped */
static const MkTypeInfo T_C = {"C", sizeof(Obj), &T_Mid, dispose_c, 0, 0, 0, 0};

/* I2 extends I1; D implements I2; E extends D (so E is I2 and I1) */
static const MkTypeInfo I1 = {"I1", 0, NULL, 0, 0, 0, 0, 0};
static const MkTypeInfo *const i2_ifaces[] = {&I1};
static const MkTypeInfo I2 = {"I2", 0, NULL, 0, 0, 0, i2_ifaces, 1};
static const MkTypeInfo *const d_ifaces[] = {&I2};
static const MkTypeInfo T_D = {"D", sizeof(Obj), &T_A, 0, 0, 0, d_ifaces, 1};
static const MkTypeInfo T_E = {"E", sizeof(Obj), &T_D, 0, 0, 0, 0, 0};
static const MkTypeInfo Unrelated = {"U", 0, NULL, 0, 0, 0, 0, 0};

typedef struct { MkAllocator base; int allocs, frees; void *last; size_t last_align; } Counting;
static void *c_alloc(MkAllocator *self, size_t size, size_t align) {
    Counting *c = (Counting *)self;
    c->allocs++;
    c->last_align = align;
    c->last = malloc(size);
    return c->last;
}
static void c_free(MkAllocator *self, void *m) {
    Counting *c = (Counting *)self;
    c->frees++;
    CHECK(m == c->last);
    free(m);
}

static int disposed_count;
static void dispose_count(MkObject *s) { (void)s; disposed_count++; }
static const MkTypeInfo T_Count = {"Count", sizeof(Obj), &mk_type_Any, dispose_count, 0, 0, 0, 0};

int main(void) {
    /* dispose order: most derived first, NULL skipped */
    Obj *o = (Obj *)mk_alloc(&T_C);
    CHECK(o != NULL);
    CHECK_EQ_INT(o->h.refcount, 1);
    CHECK_EQ_INT(o->h.flags, 0);
    CHECK(o->h.type == &T_C);
    CHECK(o->payload == 0);
    CHECK(((uintptr_t)o % MK_MAX_ALIGN) == 0);
    mk_retain(&o->h);
    CHECK_EQ_INT(o->h.refcount, 2);
    mk_release(&o->h);
    CHECK_EQ_INT(order[0], 0);
    mk_release(&o->h);
    CHECK(strcmp(order, "CBA") == 0);

    mk_retain(NULL);
    mk_release(NULL);
    mk_share(NULL);
    CHECK(!mk_is_instance(NULL, &mk_type_Any));

    /* static and stack objects are never freed nor counted */
    static Obj st = {MK_STATIC_HEADER(&T_Count), 0};
    for (int i = 0; i < 5; i++) mk_release(&st.h);
    mk_retain(&st.h);
    CHECK_EQ_INT(st.h.refcount, 0);
    CHECK_EQ_INT(disposed_count, 0);
    Obj sk = {{&T_Count, 1, MK_FLAG_STACK}, 0};
    mk_release(&sk.h);
    mk_release(&sk.h);
    mk_retain(&sk.h);
    mk_share(&sk.h);
    CHECK_EQ_INT(sk.h.refcount, 1);
    CHECK_EQ_INT(sk.h.flags, MK_FLAG_STACK);
    CHECK_EQ_INT(disposed_count, 0);

    /* mk_share sets the flag; shared path still counts */
    Obj *sh = (Obj *)mk_alloc(&T_Count);
    mk_share(&sh->h);
    CHECK(sh->h.flags & MK_FLAG_SHARED);
    mk_retain(&sh->h);
    CHECK_EQ_INT(sh->h.refcount, 2);
    mk_release(&sh->h);
    mk_release(&sh->h);
    CHECK_EQ_INT(disposed_count, 1);

    /* custom allocator: allocate and release go through it */
    Counting ca = {{c_alloc, c_free}, 0, 0, NULL, 0};
    Obj *co = (Obj *)mk_alloc_with(&ca.base, &T_Count);
    CHECK(co != NULL);
    CHECK_EQ_INT(ca.allocs, 1);
    CHECK(ca.last_align >= 8);
    CHECK(((uintptr_t)co % MK_MAX_ALIGN) == 0);
    CHECK(co->payload == 0 && co->h.refcount == 1);
    mk_retain(&co->h);
    mk_release(&co->h);
    CHECK_EQ_INT(ca.frees, 0);
    mk_release(&co->h);
    CHECK_EQ_INT(ca.frees, 1);
    CHECK_EQ_INT(disposed_count, 2);

    /* Scoped allocator: free == NULL, dispose still runs, memory untouched */
    Counting scoped = {{c_alloc, NULL}, 0, 0, NULL, 0};
    Obj *so = (Obj *)mk_alloc_with(&scoped.base, &T_Count);
    void *block = scoped.last;
    mk_release(&so->h);
    CHECK_EQ_INT(disposed_count, 3);
    free(block);

    MkObject *dflt = mk_alloc_with(NULL, &mk_type_Any);
    CHECK(dflt != NULL);
    mk_release(dflt);
    CHECK(mk_default_allocator() != NULL && mk_default_allocator()->free != NULL);

    /* is_instance */
    Obj *e = (Obj *)mk_alloc(&T_E);
    CHECK(mk_is_instance(&e->h, &T_E));
    CHECK(mk_is_instance(&e->h, &T_D));
    CHECK(mk_is_instance(&e->h, &T_A));
    CHECK(mk_is_instance(&e->h, &mk_type_Any));
    CHECK(mk_is_instance(&e->h, &I2));
    CHECK(mk_is_instance(&e->h, &I1));
    CHECK(!mk_is_instance(&e->h, &T_B));
    CHECK(!mk_is_instance(&e->h, &Unrelated));
    CHECK(!mk_is_instance(&e->h, NULL));
    mk_release(&e->h);

    TEST_DONE();
}
