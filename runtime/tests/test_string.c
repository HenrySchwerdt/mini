#include "harness.h"

MK_STRING_LITERAL(lit_hello, u"hello");
MK_STRING_LITERAL(lit_empty, u"");

static MkString *U(const char *s) { return mk_string_from_utf8(s, strlen(s)); }

static int units_are(const MkString *s, const MkChar *expected, size_t n) {
    if ((size_t)s->length != n) return 0;
    for (size_t i = 0; i < n; i++) if (mk_string_at(s, (MkInt)i) != expected[i]) return 0;
    return 1;
}

static void roundtrip(const char *text) {
    MkString *s = U(text);
    CHECK_STR(s, text);
}

int main(void) {
    /* literals */
    const MkString *h = (const MkString *)&lit_hello;
    CHECK_EQ_INT(h->length, 5);
    CHECK_EQ_INT(mk_string_at(h, 4), 'o');
    CHECK(mk_is_instance(&h->header, &mk_type_String));
    CHECK(mk_is_instance(&h->header, &mk_type_Any));
    mk_retain((MkObject *)&lit_hello);
    mk_release((MkObject *)&lit_hello);
    CHECK_EQ_INT(((const MkString *)&lit_empty)->length, 0);
    CHECK_EQ_INT(mk_string_hash((const MkString *)&lit_hello), 99162322);

    /* UTF-8 <-> UTF-16 */
    roundtrip("");
    roundtrip("plain ASCII 123");
    roundtrip("H\xC3\xA4llo W\xC3\xB6rld \xC3\x9C\xC3\xA9"); /* umlauts */
    roundtrip("\xE2\x82\xAC \xE4\xB8\xAD\xE6\x96\x87");       /* euro, CJK */
    roundtrip("a\xF0\x9F\x98\x80z");                          /* emoji */
    {
        MkString *s = U("\xF0\x9F\x98\x80");
        MkChar pair[] = {0xD83D, 0xDE00};
        CHECK(units_are(s, pair, 2));
        mk_release(&s->header);
        s = U("\xC3\xA4");
        MkChar ae[] = {0x00E4};
        CHECK(units_are(s, ae, 1));
        mk_release(&s->header);
        s = U("\xF4\x8F\xBF\xBF"); /* U+10FFFF */
        MkChar mx[] = {0xDBFF, 0xDFFF};
        CHECK(units_are(s, mx, 2));
        mk_release(&s->header);
    }
    /* invalid input -> U+FFFD */
    {
        const char *bad[] = {"\x80", "\xFF", "\xC0\xAF", "\xE0\x80\x80", "\xED\xA0\x80",
                             "\xF4\x90\x80\x80", "\xC3", "\xE2\x82"};
        for (size_t i = 0; i < sizeof bad / sizeof *bad; i++) {
            MkString *s = U(bad[i]);
            CHECK(s->length >= 1);
            for (MkInt k = 0; k < s->length; k++) CHECK_EQ_INT(mk_string_at(s, k), 0xFFFD);
            mk_release(&s->header);
        }
        MkString *s = mk_string_from_utf8("a\xFF" "b\xE2\x82" "c", 6);
        MkChar exp[] = {'a', 0xFFFD, 'b', 0xFFFD, 'c'}; /* truncated seq = one U+FFFD */
        CHECK(units_are(s, exp, 5));
        mk_release(&s->header);
        s = mk_string_from_utf8("abc", 2); /* explicit length, no NUL needed */
        CHECK_EQ_INT(s->length, 2);
        mk_release(&s->header);
        s = mk_string_from_utf8(NULL, 0);
        CHECK_EQ_INT(s->length, 0);
        mk_release(&s->header);
        s = mk_string_from_utf8(NULL, 3); /* NULL is empty regardless of length. */
        CHECK_EQ_INT(s->length, 0);
        mk_release(&s->header);
    }
    /* output encoding: unpaired surrogates -> U+FFFD */
    {
        MkChar lone[] = {'a', 0xD83D, 'b', 0xDE00, 0xDE00, 0xD83D};
        size_t n;
        char *u = mk_utf16_to_utf8(lone, 6, &n);
        CHECK(strcmp(u, "a\xEF\xBF\xBD" "b\xEF\xBF\xBD\xEF\xBF\xBD\xEF\xBF\xBD") == 0);
        CHECK_EQ_INT(n, strlen(u));
        free(u);
    }

    /* hash: Java String.hashCode */
    MkString *s;
    s = U(""); CHECK_EQ_INT(mk_string_hash(s), 0); mk_release(&s->header);
    s = U("a"); CHECK_EQ_INT(mk_string_hash(s), 97); mk_release(&s->header);
    s = U("hello"); CHECK_EQ_INT(mk_string_hash(s), 99162322); mk_release(&s->header);
    s = U("Kotlin"); CHECK_EQ_INT(mk_string_hash(s), -2041707231LL); mk_release(&s->header);
    s = U("\xF0\x9F\x98\x80"); /* 31*0xD83D + 0xDE00 */
    CHECK_EQ_INT(mk_string_hash(s), 31 * 0xD83D + 0xDE00);
    mk_release(&s->header);
    CHECK_EQ_INT(mk_string_hash(NULL), 0);

    /* equals */
    MkString *a = U("same"), *b = U("same"), *c = U("diff"), *d = U("sam");
    CHECK(mk_string_equals(a, b));
    CHECK(mk_string_equals(a, a));
    CHECK(!mk_string_equals(a, c));
    CHECK(!mk_string_equals(a, d));
    CHECK(!mk_string_equals(a, NULL));
    CHECK(!mk_string_equals(NULL, a));
    CHECK(mk_string_equals(NULL, NULL));
    CHECK(mk_string_equals((const MkString *)&lit_empty, (const MkString *)&lit_empty));
    mk_release(&a->header); mk_release(&b->header);
    mk_release(&c->header); mk_release(&d->header);

    /* concat */
    a = U("foo"); b = U("\xC3\xA4r");
    s = mk_string_concat(a, b);
    CHECK_STR(s, "foo\xC3\xA4r");
    s = mk_string_concat(a, NULL);
    CHECK_STR(s, "foonull");
    s = mk_string_concat((const MkString *)&lit_empty, (const MkString *)&lit_empty);
    CHECK_EQ_INT(s->length, 0);
    mk_release(&s->header);
    mk_release(&a->header); mk_release(&b->header);

    /* fused concatenation: one allocation, NULL operands are "null" */
    {
        const MkString *x = (const MkString *)&lit_hello;
        const MkString *e0 = (const MkString *)&lit_empty;
        CHECK_STR(mk_string_concat3(x, e0, x), "hellohello");
        CHECK_STR(mk_string_concat4(x, NULL, e0, x), "hellonullhello");
        CHECK_STR(mk_string_concat5(e0, x, e0, x, e0), "hellohello");
        CHECK_STR(mk_string_concat6(x, x, x, x, x, NULL), "hellohellohellohellohellonull");
    }
    /* decimal formatting in place: digit counts and signs around the powers of ten */
    {
        static const long long vals[] = {0, 9, 10, 99, 100, 101, 999, 1000, 9999, 10000, 99999, 100000, -1, -9, -10, -99, -100, 1234567890LL,
                                         -1234567890LL, 2147483647LL, -2147483648LL, 4294967296LL, 9223372036854775807LL};
        for (size_t i = 0; i < sizeof vals / sizeof vals[0]; i++) {
            char expect[32];
            snprintf(expect, sizeof expect, "%lld", vals[i]);
            MkString *t = mk_string_from_long((MkLong)vals[i]);
            CHECK_STR(t, expect);
        }
        CHECK_STR(mk_string_from_long(INT64_MIN), "-9223372036854775808");
    }

    /* simple conversions */
    CHECK_STR(mk_string_from_boolean(true), "true");
    CHECK_STR(mk_string_from_boolean(false), "false");
    CHECK_STR(mk_string_from_char('x'), "x");
    CHECK_STR(mk_string_from_char(0xE4), "\xC3\xA4");
    CHECK_STR(mk_string_from_int(0), "0");
    CHECK_STR(mk_string_from_int(-42), "-42");
    CHECK_STR(mk_string_from_int(INT32_MIN), "-2147483648");
    CHECK_STR(mk_string_from_int(INT32_MAX), "2147483647");
    CHECK_STR(mk_string_from_long(INT64_MIN), "-9223372036854775808");
    CHECK_STR(mk_string_from_long(INT64_MAX), "9223372036854775807");

    /* two representations of one string: one byte per character (Latin-1) and UTF-16 are the same string */
    {
        MkString *narrow = U("caf\xC3\xA9 ab"); /* every character below 256 */
        CHECK(mk_string_is_latin1(narrow));
        MkString *wide = mk_string_alloc(narrow->length); /* the same characters, two bytes each */
        for (MkInt k = 0; k < narrow->length; k++) wide->chars[k] = mk_string_at(narrow, k);
        CHECK(!mk_string_is_latin1(wide));
        CHECK(mk_string_equals(narrow, wide));
        CHECK(mk_string_equals(wide, narrow));
        CHECK_EQ_INT(mk_string_hash(narrow), mk_string_hash(wide));
        CHECK_EQ_INT(mk_string_compare(narrow, wide), 0);
        wide->chars[2] = 0x65E5; /* a character that does not fit one byte */
        CHECK(!mk_string_equals(narrow, wide));
        MkString *joined = mk_string_concat(narrow, wide);
        CHECK(!mk_string_is_latin1(joined));
        CHECK_EQ_INT(joined->length, 2 * narrow->length);
        CHECK_EQ_INT(mk_string_at(joined, 3), 0xE9);
        CHECK_EQ_INT(mk_string_at(joined, narrow->length + 2), 0x65E5);
        MkString *both = mk_string_concat(narrow, narrow);
        CHECK(mk_string_is_latin1(both)); /* one-byte parts give a one-byte result */
        CHECK_EQ_INT(both->length, 2 * narrow->length);
        MkString *sub = mk_string_substring(narrow, 1, 4);
        CHECK(mk_string_is_latin1(sub));
        CHECK_STR(sub, "af\xC3\xA9");
        MkString *num = mk_string_from_int(-123);
        CHECK(mk_string_is_latin1(num));
        CHECK_STR(num, "-123");
        mk_release(&joined->header);
        mk_release(&both->header);
        mk_release(&wide->header);
        mk_release(&narrow->header);
    }

    /* heap strings have refcount 1 and no user-visible flags */
    s = mk_string_from_int(1);
    CHECK_EQ_INT(s->header.refcount, 1);
    CHECK_EQ_INT(s->header.flags & (MK_FLAG_STATIC | MK_FLAG_STACK | MK_FLAG_SHARED | MK_FLAG_FREED), 0);
    mk_release(&s->header);

    TEST_DONE();
}
