#include "harness.h"

#include <stdio.h>
#include <stdlib.h>

static MkString *U(const char *s) { return mk_string_from_utf8(s, strlen(s)); }

int main(void) {
    char path[] = "/tmp/minirt_b1_XXXXXX";
    int fd = mkstemp(path);
    CHECK(fd >= 0);
    close(fd);

    /* write + read round trip, including non-ASCII text, an embedded NUL and a surrogate pair */
    const char *text = "line 1\n\xc3\xa4\xe2\x82\xac \xf0\x9f\x98\x80\n";
    MkString *p = U(path);
    MkString *t = U(text);
    CHECK(mk_b1_write_file(p, t));
    MkString *back = mk_b1_read_file(p);
    CHECK(back != NULL);
    CHECK(back->length == t->length);
    CHECK(memcmp(back->chars, t->chars, (size_t)t->length * sizeof(MkChar)) == 0);
    mk_release(&back->header);
    mk_release(&t->header);

    /* empty file */
    MkString *empty = U("");
    CHECK(mk_b1_write_file(p, empty));
    back = mk_b1_read_file(p);
    CHECK(back != NULL && back->length == 0);
    mk_release(&back->header);
    mk_release(&empty->header);

    /* a larger file (grows the read buffer) */
    MkString *big = mk_string_alloc(100000);
    for (MkInt i = 0; i < big->length; i++) big->chars[i] = (MkChar)('a' + i % 26);
    CHECK(mk_b1_write_file(p, big));
    back = mk_b1_read_file(p);
    CHECK(back != NULL && back->length == 100000);
    CHECK(back != NULL && memcmp(back->chars, big->chars, 100000 * sizeof(MkChar)) == 0);
    mk_release(&back->header);
    mk_release(&big->header);
    remove(path);

    /* failures give NULL / false */
    MkString *missing = U("/nonexistent-dir-b1/none.txt");
    CHECK(mk_b1_read_file(missing) == NULL);
    MkString *x = U("x");
    CHECK(!mk_b1_write_file(missing, x));
    mk_release(&x->header);
    mk_release(&missing->header);
    mk_release(&p->header);

    /* arguments: program name is dropped */
    char *argv[] = {"prog", "one", "t\xc3\xa4o"};
    mk_b1_set_args(3, argv);
    MkObject *args = mk_b1_args();
    CHECK_EQ_INT(mk_array_length(args), 2);
    MkObject *a0 = mk_array_get_obj(args, 0);
    CHECK(t_str_is((MkString *)a0, "one", 0));
    mk_release(a0);
    MkObject *a1 = mk_array_get_obj(args, 1);
    CHECK(t_str_is((MkString *)a1, "t\xc3\xa4o", 0));
    mk_release(a1);
    mk_release(args);
    mk_b1_set_args(1, argv);
    args = mk_b1_args();
    CHECK_EQ_INT(mk_array_length(args), 0);
    mk_release(args);

    TEST_DONE();
}
