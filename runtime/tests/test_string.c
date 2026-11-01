#include "harness.h"

MK_STRING_LITERAL(lit_hello, u"hello");
MK_STRING_LITERAL(lit_empty, u"");

static MkString *U(const char *s) { return mk_string_from_utf8(s, strlen(s)); }

static int units_are(const MkString *s, const MkChar *expected, size_t n) {
    return (size_t)s->length == n && memcmp(s->chars, expected, n * sizeof(MkChar)) == 0;
}

static void roundtrip(const char *text) {
    MkString *s = U(text);
    CHECK_STR(s, text);
}

int main(void) {
    /* literals */
    const MkString *h = (const MkString *)&lit_hello;
    CHECK_EQ_INT(h->length, 5);
    CHECK_EQ_INT(h->chars[4], 'o');
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
            for (MkInt k = 0; k < s->length; k++) CHECK_EQ_INT(s->chars[k], 0xFFFD);
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

    /* heap strings have refcount 1 and no flags */
    s = mk_string_from_int(1);
    CHECK_EQ_INT(s->header.refcount, 1);
    CHECK_EQ_INT(s->header.flags, 0);
    mk_release(&s->header);

    TEST_DONE();
}
