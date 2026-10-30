#include "harness.h"

static MkString *U(const char *s) { return mk_string_from_utf8(s, strlen(s)); }

static int is_double(const char *text) {
    MkString *s = U(text);
    int r = mk_string_is_double(s);
    mk_release(&s->header);
    return r;
}

static double parse_double(const char *text) {
    MkString *s = U(text);
    double r = mk_string_parse_double(s);
    mk_release(&s->header);
    return r;
}

int main(void) {
    /* strings */
    MkString *hello = U("hello");
    CHECK_EQ_INT(mk_string_char_at(hello, 1), 'e');
    MkString *sub = mk_string_substring(hello, 1, 4);
    CHECK_STR(sub, "ell");
    MkString *empty = mk_string_substring(hello, 5, 5);
    CHECK_STR(empty, "");
    MkString *abc = U("abc");
    MkString *abd = U("abd");
    MkString *ab = U("ab");
    CHECK(mk_string_compare(abc, abd) < 0);
    CHECK(mk_string_compare(abd, abc) > 0);
    CHECK_EQ_INT(mk_string_compare(abc, abc), 0);
    CHECK_EQ_INT(mk_string_compare(abc, ab), 1);
    mk_release(&hello->header);
    mk_release(&abc->header);
    mk_release(&abd->header);
    mk_release(&ab->header);

    /* doubles */
    CHECK(is_double("1.5"));
    CHECK(is_double("  -2e3 "));
    CHECK(is_double(".5"));
    CHECK(is_double("5."));
    CHECK(is_double("NaN"));
    CHECK(is_double("-Infinity"));
    CHECK(is_double("1.5f"));
    CHECK(!is_double(""));
    CHECK(!is_double("abc"));
    CHECK(!is_double("1e"));
    CHECK(!is_double("."));
    CHECK(!is_double("1.5x"));
    CHECK(!is_double("0x10"));
    CHECK(parse_double("1.5") == 1.5);
    CHECK(parse_double("-2e3") == -2000.0);
    CHECK(parse_double("12") == 12.0);
    CHECK(parse_double("1.25d") == 1.25);

    /* StringBuilder */
    MkObject *sb = mk_sb_new();
    MkString *part = U("hello");
    mk_sb_append_string(sb, part);
    mk_release(&part->header);
    mk_sb_append_char(sb, ' ');
    for (int i = 0; i < 1000; i++) mk_sb_append_char(sb, (MkChar)('a' + i % 26));
    CHECK_EQ_INT(mk_sb_length(sb), 1006);
    CHECK_EQ_INT(mk_sb_char_at(sb, 0), 'h');
    CHECK_EQ_INT(mk_sb_char_at(sb, 6), 'a');
    mk_sb_set_char_at(sb, 0, 'J');
    mk_sb_set_length(sb, 5);
    MkString *r = mk_sb_to_string(sb);
    CHECK_STR(r, "Jello");
    mk_sb_set_length(sb, 7);
    CHECK_EQ_INT(mk_sb_char_at(sb, 6), 0);
    mk_release(sb);

    /* StringBuilder.append(Int / Long) writes the digits into the buffer */
    MkObject *nb = mk_sb_new();
    mk_sb_append_int(nb, 0);
    mk_sb_append_char(nb, ',');
    mk_sb_append_int(nb, -42);
    mk_sb_append_char(nb, ',');
    mk_sb_append_int(nb, INT32_MIN);
    mk_sb_append_char(nb, ',');
    mk_sb_append_int(nb, INT32_MAX);
    mk_sb_append_char(nb, ',');
    mk_sb_append_long(nb, INT64_MIN);
    mk_sb_append_char(nb, ',');
    mk_sb_append_long(nb, INT64_MAX);
    for (int i = 0; i < 100; i++) mk_sb_append_long(nb, (MkLong)i * 1000003);
    MkString *ns = mk_sb_to_string(nb);
    char expect[4096];
    size_t en = (size_t)snprintf(expect, sizeof expect, "0,-42,-2147483648,2147483647,-9223372036854775808,9223372036854775807");
    for (int i = 0; i < 100; i++) en += (size_t)snprintf(expect + en, sizeof expect - en, "%lld", (long long)i * 1000003);
    CHECK_STR(ns, expect);
    mk_release(nb);

    /* reference arrays */
    MkObject *arr = mk_ref_array_new(3);
    CHECK_EQ_INT(mk_ref_array_length(arr), 3);
    CHECK(mk_ref_array_get(arr, 0) == NULL);
    MkString *e = U("elem");
    mk_ref_array_set(arr, 1, &e->header);
    mk_release(&e->header); /* the array keeps it alive */
    MkObject *got = mk_ref_array_get(arr, 1);
    CHECK(got != NULL);
    CHECK_STR((MkString *)got, "elem");
    MkObject *boxed = mk_box_int(42);
    mk_ref_array_set(arr, 2, boxed);
    mk_release(boxed);
    mk_ref_array_set(arr, 1, NULL);
    MkObject *b2 = mk_ref_array_get(arr, 2);
    CHECK_EQ_INT(mk_unbox_int(b2), 42);
    mk_release(b2);
    mk_release(arr);
    MkObject *empty_arr = mk_ref_array_new(0);
    CHECK_EQ_INT(mk_ref_array_length(empty_arr), 0);
    mk_release(empty_arr);

    /* type name / identity hash */
    MkObject *bx = mk_box_int(1);
    MkString *tn = mk_type_name(bx);
    CHECK_STR(tn, "kotlin.Int");
    CHECK(mk_identity_hash(bx) >= 0);
    CHECK_EQ_INT(mk_identity_hash(bx), mk_identity_hash(bx));
    mk_release(bx);

    TEST_DONE();
}
