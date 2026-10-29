#include "harness.h"

/* Exceptions (error-return ABI), checked casts, arrays and compareTo of the v1-L block. */

static const MkString *utf8(const char *s) { return mk_string_from_utf8(s, strlen(s)); }

/* class Mine : Exception with one extra field, as generated code would lay it out */
typedef struct { MkThrowable base; MkInt code; } Mine;
static void *const mine_vt[] = {(void *)mk_throwable_to_string, (void *)mk_any_equals, (void *)mk_any_hash_code,
                                (void *)mk_throwable_message};
static const MkTypeInfo Mine_ti = {"Mine", sizeof(Mine), &MkException_ti, NULL, mine_vt, 4, NULL, 0};

static void test_throw_catch(void) {
    CHECK(mk_pending_exception == NULL);
    const MkString *m = utf8("boom");
    MkObject *e = MkIllegalStateException_new(m, NULL);
    mk_release((MkObject *)m);
    mk_throw(e);
    mk_release(e); /* the pending slot holds its own reference */
    CHECK(mk_pending_exception != NULL);
    MkObject *c = mk_catch();
    CHECK(mk_pending_exception == NULL);
    CHECK(c == e);
    CHECK(mk_is_instance(c, &MkIllegalStateException_ti));
    CHECK(mk_is_instance(c, &MkRuntimeException_ti));
    CHECK(mk_is_instance(c, &MkException_ti));
    CHECK(mk_is_instance(c, &MkThrowable_ti));
    CHECK(!mk_is_instance(c, &MkError_ti));
    CHECK(!mk_is_instance(c, &MkIllegalArgumentException_ti));
    CHECK_STR((MkString *)mk_throwable_message(c), "boom");
    CHECK_STR(mk_to_string(c), "java.lang.IllegalStateException: boom");
    mk_release(c);
}

static void test_user_subclass(void) {
    Mine *x = (Mine *)mk_alloc(&Mine_ti);
    mk_throwable_init(&x->base.header, NULL, NULL);
    x->code = 7;
    CHECK_STR(mk_to_string(&x->base.header), "Mine");
    const MkString *m = utf8("custom");
    mk_throwable_init(&x->base.header, m, NULL);
    mk_release((MkObject *)m);
    CHECK_STR(mk_to_string(&x->base.header), "Mine: custom");
    CHECK(mk_is_instance(&x->base.header, &MkException_ti));
    mk_release(&x->base.header);
}

static void test_cause(void) {
    MkObject *inner = MkArithmeticException_new(NULL, NULL);
    MkObject *outer = MkRuntimeException_new(NULL, inner);
    MkObject *c = mk_throwable_cause(outer);
    CHECK(c == inner);
    mk_release(c);
    mk_release(outer);
    mk_release(inner);
    const MkString *cm = mk_throwable_cause_message(NULL);
    CHECK(cm == NULL);
}

static void test_raise(void) {
    mk_raise_arith("/ by zero");
    MkObject *e = mk_catch();
    CHECK(mk_is_instance(e, &MkArithmeticException_ti));
    CHECK_STR(mk_to_string(e), "java.lang.ArithmeticException: / by zero");
    mk_release(e);

    CHECK(mk_checked_not_null(NULL) == NULL);
    e = mk_catch();
    CHECK(mk_is_instance(e, &MkNullPointerException_ti));
    CHECK_STR(mk_to_string(e), "java.lang.NullPointerException");
    mk_release(e);

    MkObject *box = mk_box_int(5);
    CHECK_EQ_INT(mk_checked_unbox_int(box), 5);
    CHECK(mk_pending_exception == NULL);
    (void)mk_checked_unbox_long(box);
    e = mk_catch();
    CHECK(mk_is_instance(e, &MkClassCastException_ti));
    mk_release(e);
    CHECK(mk_checked_cast(box, &MkIntArray_ti) == NULL);
    e = mk_catch();
    CHECK(mk_is_instance(e, &MkClassCastException_ti));
    mk_release(e);
    CHECK(mk_checked_cast(box, &mk_type_Int) == box);
    CHECK(mk_checked_cast(NULL, &mk_type_Int) == NULL);
    CHECK(mk_pending_exception == NULL);
    mk_release(box);

    mk_raise_uninitialized("x");
    e = mk_catch();
    CHECK_STR(mk_to_string(e), "kotlin.UninitializedPropertyAccessException: lateinit property x has not been initialized");
    mk_release(e);

    /* a second raise replaces (and releases) the pending exception */
    mk_raise_illegal_state("one");
    mk_raise_illegal_argument("two");
    e = mk_catch();
    CHECK(mk_is_instance(e, &MkIllegalArgumentException_ti));
    mk_release(e);
    CHECK(mk_catch() == NULL);

    /* rethrow transfers the caller's reference */
    MkObject *r = MkError_new(NULL, NULL);
    mk_rethrow(r);
    CHECK(mk_pending_exception == r);
    mk_release(mk_catch());
}

static void test_arrays(void) {
    MkObject *a = mk_array_new_int(3);
    CHECK_EQ_INT(mk_array_length(a), 3);
    CHECK_EQ_INT(mk_array_get_int(a, 1), 0);
    mk_array_set_int(a, 1, 42);
    CHECK_EQ_INT(mk_array_get_int(a, 1), 42);
    CHECK_EQ_INT(mk_array_get_int(a, 3), 0);
    MkObject *e = mk_catch();
    CHECK(e != NULL && mk_is_instance(e, &MkArrayIndexOutOfBoundsException_ti));
    CHECK_STR(mk_to_string(e), "java.lang.ArrayIndexOutOfBoundsException: Index 3 out of bounds for length 3");
    mk_release(e);
    mk_array_set_int(a, -1, 1);
    mk_release(mk_catch());
    mk_release(a);

    MkObject *neg = mk_array_new_double(-2);
    CHECK(neg == NULL);
    e = mk_catch();
    CHECK(mk_is_instance(e, &MkNegativeArraySizeException_ti));
    mk_release(e);

    MkObject *d = mk_array_new_double(2);
    mk_array_set_double(d, 0, 1.5);
    CHECK(mk_array_get_double(d, 0) == 1.5);
    mk_release(d);
    MkObject *c = mk_array_new_char(1);
    mk_array_set_char(c, 0, 'x');
    CHECK_EQ_INT(mk_array_get_char(c, 0), 'x');
    mk_release(c);
    MkObject *l = mk_array_new_long(1);
    mk_array_set_long(l, 0, 1LL << 40);
    CHECK(mk_array_get_long(l, 0) == (1LL << 40));
    mk_release(l);
    MkObject *b = mk_array_new_boolean(1);
    mk_array_set_boolean(b, 0, true);
    CHECK(mk_array_get_boolean(b, 0));
    mk_release(b);

    /* object arrays own their elements */
    MkObject *oa = mk_array_new_obj(2);
    MkObject *s = mk_box_int(9);
    mk_array_set_obj(oa, 0, s);
    mk_release(s); /* the array keeps it alive */
    MkObject *got = mk_array_get_obj(oa, 0);
    CHECK(got != NULL && mk_unbox_int(got) == 9);
    mk_release(got);
    CHECK(mk_array_get_obj(oa, 1) == NULL);
    CHECK(mk_pending_exception == NULL);
    mk_array_set_obj(oa, 0, NULL);
    mk_release(oa); /* leaves nothing for LeakSanitizer */
}

static void test_array_helpers(void) {
    MkObject *a = mk_array_new_int(2), *b = mk_array_new_int(5);
    mk_array_set_int(a, 0, 7);
    mk_array_set_int(a, 1, 8);
    mk_array_copy_into(b, 2, a);
    CHECK_STR((MkString *)mk_array_content_to_string(b), "[0, 0, 7, 8, 0]");
    mk_array_copy_into(b, 4, a); /* does not fit */
    MkObject *e = mk_catch();
    CHECK(e != NULL && mk_is_instance(e, &MkArrayIndexOutOfBoundsException_ti));
    mk_release(e);
    mk_release(a);
    mk_release(b);

    MkObject *oa = mk_array_new_obj(2), *ob = mk_array_new_obj(3);
    MkObject *x = mk_box_int(1), *y = mk_box_int(2);
    mk_array_set_obj(oa, 0, x);
    mk_array_set_obj(oa, 1, y);
    mk_release(x);
    mk_release(y);
    mk_array_copy_into(ob, 1, oa);
    CHECK_STR((MkString *)mk_array_content_to_string(ob), "[null, 1, 2]");
    mk_release(oa);
    mk_release(ob);
    CHECK_STR((MkString *)mk_array_content_to_string(NULL), "null");

    const MkString *s = utf8("abc");
    CHECK_EQ_INT(mk_string_get_char(s, 1), 'b');
    CHECK_EQ_INT(mk_string_get_char(s, 3), 0);
    e = mk_catch();
    CHECK(e != NULL && mk_is_instance(e, &MkStringIndexOutOfBoundsException_ti));
    CHECK(mk_is_instance(e, &MkIndexOutOfBoundsException_ti));
    mk_release(e);
    mk_release((MkObject *)s);
}

static void test_compare(void) {
    MkObject *a = mk_box_int(1), *b = mk_box_int(2);
    CHECK(mk_compare_to(a, b, NULL) < 0);
    CHECK(mk_compare_to(b, a, NULL) > 0);
    CHECK_EQ_INT(mk_compare_to(a, a, NULL), 0);
    mk_release(a);
    mk_release(b);
    const MkString *x = utf8("apple"), *y = utf8("pear");
    CHECK(mk_compare_to((MkObject *)x, (MkObject *)y, NULL) < 0);
    CHECK_EQ_INT(mk_compare_to((MkObject *)x, (MkObject *)x, NULL), 0);
    mk_release((MkObject *)x);
    mk_release((MkObject *)y);
    MkObject *d1 = mk_box_double(-0.0), *d2 = mk_box_double(0.0);
    CHECK(mk_compare_to(d1, d2, NULL) < 0);
    mk_release(d1);
    mk_release(d2);
    MkObject *any = mk_any_new();
    (void)mk_compare_to(any, any, NULL);
    mk_release(mk_catch());
    mk_release(any);
}

int main(void) {
    test_throw_catch();
    test_user_subclass();
    test_cause();
    test_raise();
    test_arrays();
    test_array_helpers();
    test_compare();
    TEST_DONE();
}
