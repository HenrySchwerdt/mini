#include "harness.h"

#include <stdint.h>

/* class Base { open fun f() = 1 }   class Derived : Base, I { override f() = 2 }
 * interface I { fun g() }   class Point overrides toString/equals/hashCode */
typedef struct { MkObject h; int x; MkObject *ref; } Base;

static int disposed;
static void base_dispose(MkObject *s) { disposed++; mk_release(((Base *)s)->ref); }

static MkInt base_f(MkObject *self) { (void)self; return 1; }
static MkInt derived_f(MkObject *self) { (void)self; return 2; }
static MkInt derived_g(MkObject *self) { (void)self; return 42; }
static MkString *point_to_string(MkObject *self) {
    (void)self;
    return mk_string_from_utf8("P!", 2);
}
static bool point_equals(MkObject *a, MkObject *b) { return ((Base *)a)->x == ((Base *)b)->x; }
static MkInt point_hash(MkObject *a) { return ((Base *)a)->x; }

static const MkTypeInfo I_iface = {"I", 0, NULL, NULL, NULL, 0, NULL, 0};
static const MkTypeInfo J_iface = {"J", 0, NULL, NULL, NULL, 0, NULL, 0};

static void *const base_vt[] = {(void *)mk_any_to_string, (void *)mk_any_equals, (void *)mk_any_hash_code, (void *)base_f};
static const MkTypeInfo T_Base = {"Base", sizeof(Base), &mk_type_Any, base_dispose, base_vt, 4, NULL, 0};

static void *const i_table[] = {(void *)derived_g};
static void *const derived_vt[] = {(void *)mk_any_to_string, (void *)mk_any_equals, (void *)mk_any_hash_code,
                                   (void *)derived_f, (void *)i_table};
static const MkTypeInfo *const derived_ifaces[] = {&I_iface};
static const MkTypeInfo T_Derived = {"Derived", sizeof(Base), &T_Base, NULL, derived_vt, 5, derived_ifaces, 1};

static void *const point_vt[] = {(void *)point_to_string, (void *)point_equals, (void *)point_hash};
static const MkTypeInfo T_Point = {"pkg.Point", sizeof(Base), &mk_type_Any, NULL, point_vt, 3, NULL, 0};

typedef MkInt (*IntFn)(MkObject *);

int main(void) {
    MkObject *d = mk_alloc(&T_Derived);
    ((Base *)d)->x = 7;
    ((Base *)d)->ref = mk_alloc(&T_Point);
    MkObject *inner = ((Base *)d)->ref;
    mk_retain(inner); /* keep alive to observe the release by dispose */

    /* virtual call through the vtable */
    CHECK_EQ_INT(((IntFn)T_Derived.vtable[MK_VSLOT_FIRST_USER])(d), 2);
    CHECK_EQ_INT(((IntFn)T_Base.vtable[MK_VSLOT_FIRST_USER])(d), 1);

    /* interface table */
    void *const *it = mk_itable(d, &I_iface);
    CHECK(it != NULL);
    CHECK_EQ_INT(((IntFn)it[0])(d), 42);
    CHECK(mk_itable(d, &J_iface) == NULL);
    MkObject *b = mk_alloc(&T_Base);
    CHECK(mk_itable(b, &I_iface) == NULL);

    /* defaults: identity equals, Name@hex toString */
    CHECK(mk_equals(d, d));
    CHECK(!mk_equals(d, b));
    CHECK(!mk_equals(d, NULL) && !mk_equals(NULL, d) && mk_equals(NULL, NULL));
    CHECK_EQ_INT(mk_hash_code(NULL), 0);
    CHECK(mk_hash_code(d) == mk_hash_code(d));
    MkString *s = mk_to_string(d);
    size_t n;
    char *u = mk_utf16_to_utf8(s->chars, (size_t)s->length, &n);
    CHECK(strncmp(u, "Derived@", 8) == 0);
    free(u);
    mk_release(&s->header);
    CHECK_STR(mk_to_string(NULL), "null");

    /* overridden members */
    MkObject *p = mk_alloc(&T_Point);
    MkObject *q = mk_alloc(&T_Point);
    CHECK_STR(mk_to_string(p), "P!");
    CHECK(mk_equals(p, q)); /* both x == 0 */
    ((Base *)q)->x = 5;
    CHECK(!mk_equals(p, q));
    CHECK_EQ_INT(mk_hash_code(q), 5);

    /* strings and boxes through the generic helpers */
    MkString *s1 = mk_string_from_utf8("abc", 3);
    MkString *s2 = mk_string_from_utf8("abc", 3);
    CHECK(mk_equals(&s1->header, &s2->header));
    CHECK_EQ_INT(mk_hash_code(&s1->header), mk_string_hash(s1));
    MkString *s3 = mk_to_string(&s1->header);
    CHECK(s3 == s1); /* retained, same object */
    mk_release(&s3->header);
    mk_release(&s1->header);
    mk_release(&s2->header);

    MkObject *i1 = mk_box_int(7), *i2 = mk_box_int(7), *l1 = mk_box_long(7);
    CHECK(mk_equals(i1, i2));
    CHECK(!mk_equals(i1, l1));
    CHECK_EQ_INT(mk_hash_code(i1), 7);
    CHECK_EQ_INT(mk_unbox_int(i1), 7);
    CHECK_STR(mk_to_string(i1), "7");
    MkObject *bt = mk_box_boolean(true);
    CHECK(mk_unbox_boolean(bt));
    CHECK_EQ_INT(mk_hash_code(bt), 1231);
    CHECK_STR(mk_to_string(bt), "true");
    MkObject *dd = mk_box_double(1.5), *nan1 = mk_box_double(0.0 / 0.0), *nan2 = mk_box_double(0.0 / 0.0);
    MkObject *z1 = mk_box_double(0.0), *z2 = mk_box_double(-0.0);
    CHECK(mk_unbox_double(dd) == 1.5);
    CHECK(mk_equals(nan1, nan2));
    CHECK(!mk_equals(z1, z2));
    MkObject *ch = mk_box_char('x');
    CHECK(mk_unbox_char(ch) == 'x');
    CHECK_STR(mk_to_string(ch), "x");
    CHECK(mk_is_instance(i1, &mk_type_Int) && !mk_is_instance(i1, &mk_type_Long));
    CHECK_EQ_INT(mk_unbox_long(l1), 7);

    /* casts */
    CHECK(mk_cast(d, &T_Base) == d);
    CHECK(mk_cast(NULL, &T_Base) == NULL);
    CHECK(mk_cast(d, &I_iface) == d);
    CHECK(mk_not_null(d) == d);

    /* dispose chain */
    mk_release(d);
    CHECK_EQ_INT(disposed, 1);
    CHECK_EQ_INT(inner->refcount, 1);
    mk_release(inner);
    mk_release(b);
    CHECK_EQ_INT(disposed, 2);
    mk_release(p);
    mk_release(q);
    mk_release(i1);
    mk_release(i2);
    mk_release(l1);
    mk_release(bt);
    mk_release(dd);
    mk_release(nan1);
    mk_release(nan2);
    mk_release(z1);
    mk_release(z2);
    mk_release(ch);
    TEST_DONE();
}
