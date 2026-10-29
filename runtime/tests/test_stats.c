/* D1: runtime statistics. Built with -DMK_STATS by run_tests.sh; without the macro this is a no-op
 * (the runtime then has no counters at all). */
#include "harness.h"

#ifndef MK_STATS
int main(void) {
    fprintf(stderr, "test_stats: built without MK_STATS, nothing to check\n");
    return 0;
}
#else
typedef struct { MkObject h; MkObject *child; } Pair;
static void pair_dispose(MkObject *s) { mk_release(((Pair *)s)->child); }
static const MkTypeInfo T_Pair = {"Pair", sizeof(Pair), &mk_type_Any, pair_dispose, 0, 0, 0, 0};

static MkStats snap(void) {
    MkStats s;
    mk_stats_get(&s);
    return s;
}

int main(void) {
    MkStats base = snap();
    CHECK_EQ_INT(base.live, 0); /* nothing allocated before the test */
    mk_stats_reset();

    /* allocation */
    MkObject *a = mk_alloc(&T_Pair);
    MkObject *b = mk_alloc(&T_Pair);
    MkStats s = snap();
    CHECK_EQ_INT(s.allocs, 2);
    CHECK_EQ_INT(s.live, 2);
    CHECK_EQ_INT(s.peak_live, 2);
    CHECK_EQ_INT(s.bytes_total, 2 * (sizeof(Pair) + MK_PREFIX_SIZE));
    CHECK_EQ_INT(s.bytes_live, s.bytes_total);

    /* plain retain/release, no-ops on NULL */
    mk_retain(a);
    mk_retain(a);
    mk_release(a);
    mk_retain(NULL);
    mk_release(NULL);
    s = snap();
    CHECK_EQ_INT(s.retains, 3);
    CHECK_EQ_INT(s.retains_noop, 1);
    CHECK_EQ_INT(s.retains_atomic, 0);
    CHECK_EQ_INT(s.releases, 2);
    CHECK_EQ_INT(s.releases_noop, 1);
    CHECK_EQ_INT(s.releases_atomic, 0);

    /* shared objects use atomic operations */
    mk_share(b);
    mk_retain(b);
    mk_release(b);
    s = snap();
    CHECK_EQ_INT(s.retains_atomic, 1);
    CHECK_EQ_INT(s.releases_atomic, 1);

    /* static objects are no-ops */
    MkObject st = MK_STATIC_HEADER(&T_Pair);
    mk_retain(&st);
    mk_release(&st);
    s = snap();
    CHECK_EQ_INT(s.retains_noop, 2);
    CHECK_EQ_INT(s.releases_noop, 2);

    /* freeing: a's refcount is 2 now, b's 1; dispose of a releases its child */
    Pair *pa = (Pair *)a;
    pa->child = mk_alloc(&T_Pair);
    CHECK_EQ_INT(snap().live, 3);
    mk_release(a); /* 2 -> 1 */
    CHECK_EQ_INT(snap().frees, 0);
    mk_release(a); /* 1 -> 0, frees a and its child */
    s = snap();
    CHECK_EQ_INT(s.frees, 2);
    CHECK_EQ_INT(s.live, 1);
    CHECK_EQ_INT(s.peak_live, 3);
    CHECK_EQ_INT(s.bytes_live, sizeof(Pair) + MK_PREFIX_SIZE);
    CHECK_EQ_INT(s.bytes_peak, 3 * (sizeof(Pair) + MK_PREFIX_SIZE));
    mk_release(b);
    s = snap();
    CHECK_EQ_INT(s.live, 0);
    CHECK_EQ_INT(s.bytes_live, 0);

    /* variable-sized objects (strings) are accounted with their real size */
    MkStats before = snap();
    MkString *str = mk_string_from_utf8("hello stats", 11);
    CHECK(str != NULL);
    MkStats mid = snap();
    CHECK_EQ_INT(mid.allocs, before.allocs + 1);
    CHECK(mid.bytes_live > before.bytes_live);
    mk_release(&str->header);
    s = snap();
    CHECK_EQ_INT(s.bytes_live, 0);
    CHECK_EQ_INT(s.live, 0);

    /* mk_free (manual mode) counts as a free */
    MkObject *m = mk_alloc_with(mk_heap_allocator(), &T_Pair);
    CHECK_EQ_INT(snap().live, 1);
    mk_free(m);
    CHECK_EQ_INT(snap().live, 0);

    /* reset keeps live/peak consistent */
    mk_stats_reset();
    s = snap();
    CHECK_EQ_INT(s.allocs, 0);
    CHECK_EQ_INT(s.retains, 0);
    CHECK_EQ_INT(s.peak_live, 0);

    mk_stats_dump(); /* smoke: prints to stderr (and again at exit) */
    TEST_DONE();
}
#endif
