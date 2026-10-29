/* DebugAllocator: books, leak report text, double free / unknown free diagnostics. */
#include "harness.h"

#include <stdint.h>

typedef struct { MkObject h; int payload; } Obj;
static const MkTypeInfo T_Foo = {"app.Foo", sizeof(Obj), &mk_type_Any, NULL, 0, 0, 0, 0};
static const MkTypeInfo T_Bar = {"app.Bar", sizeof(Obj), &mk_type_Any, NULL, 0, 0, 0, 0};

/* Reads a leak report into a string. */
static char report_buf[2048];
static size_t report(MkAllocator *d) {
    FILE *f = tmpfile();
    size_t n = mk_debug_report_leaks_to(d, f);
    rewind(f);
    size_t len = fread(report_buf, 1, sizeof report_buf - 1, f);
    report_buf[len] = '\0';
    fclose(f);
    return n;
}

static void abort_double(void *arg) {
    MkObject *o = mk_alloc_with_site((MkAllocator *)arg, &T_Foo, "a.kt:1:1");
    mk_free_with_site(o, "b.kt:2:2");
    mk_free_with_site(o, "c.kt:3:3");
}
static void abort_double_raw(void *arg) { /* straight through the allocator vtable */
    MkAllocator *d = arg;
    void *p = d->allocate(d, 32, 8);
    d->free(d, p);
    d->free(d, p);
}
static void abort_unknown(void *arg) {
    MkAllocator *d = arg;
    char local[64];
    d->free(d, local);
}
static void abort_arena_free(void *arg) {
    mk_free_with_site(mk_alloc_with_site((MkAllocator *)arg, &T_Foo, "x.kt:9:9"), "y.kt:1:1");
}

int main(void) {
    MkAllocator *d = mk_debug_allocator_create(NULL);
    CHECK(d != NULL);
    MkDebugStats st;

    MkObject *a = mk_alloc_with_site(d, &T_Foo, "src/A.kt:12:5");
    MkObject *b = mk_alloc_with_site(d, &T_Bar, "src/B.kt:7:1");
    MkObject *c = mk_alloc_with(d, &T_Foo); /* no site */
    mk_release(&mk_string_alloc(0)->header); /* default allocator: not tracked by d */
    CHECK(a && b && c);
    mk_debug_stats(d, &st);
    CHECK_EQ_INT(st.allocations, 3);
    CHECK_EQ_INT(st.frees, 0);
    CHECK_EQ_INT(st.live, 3);
    CHECK_EQ_INT(st.live_bytes, 3 * mk_object_block_size(&T_Foo));

    CHECK_EQ_INT(report(d), 3);
    CHECK(strcmp(report_buf,
                 "leak: app.Foo allocated at src/A.kt:12:5\n"
                 "leak: app.Bar allocated at src/B.kt:7:1\n"
                 "leak: app.Foo allocated at <unknown>\n") == 0);

    mk_free_with_site(b, "src/B.kt:9:1");
    mk_release(c); /* RC path also reaches the books */
    mk_debug_stats(d, &st);
    CHECK_EQ_INT(st.frees, 2);
    CHECK_EQ_INT(st.live, 1);
    CHECK_EQ_INT(report(d), 1);
    CHECK(strcmp(report_buf, "leak: app.Foo allocated at src/A.kt:12:5\n") == 0);
    CHECK_EQ_INT(mk_debug_report_leaks(d), 1); /* public variant prints to stderr */

    /* address reuse after quarantine eviction does not confuse the books */
    for (int i = 0; i < 1000; i++) {
        MkObject *t = mk_alloc_with_site(d, &T_Bar, "loop");
        mk_free(t);
    }
    CHECK_EQ_INT(report(d), 1);

    CHECK_ABORTS(abort_double, d,
                 "double free of app.Foo (allocated at a.kt:1:1, first freed at b.kt:2:2), freed again at c.kt:3:3");
    CHECK_ABORTS(abort_double_raw, d, "double free of <raw>");
    CHECK_ABORTS(abort_unknown, d, "free of unknown pointer");

    mk_free(a);
    CHECK_EQ_INT(report(d), 0);
    CHECK_EQ_INT(strlen(report_buf), 0);
    mk_debug_allocator_destroy(d);

    /* leaks left at destroy are reclaimed (ASan would flag a leak otherwise) */
    MkAllocator *d2 = mk_debug_allocator_create(mk_heap_allocator());
    for (int i = 0; i < 5; i++) CHECK(mk_alloc_with_site(d2, &T_Foo, "leaky") != NULL);
    CHECK_EQ_INT(report(d2), 5);
    mk_debug_allocator_destroy(d2);

    /* debug over an arena: scoped traits, mk_free refused, release only updates books */
    MkAllocator *arena = mk_arena_create(0);
    MkAllocator *d3 = mk_debug_allocator_create(arena);
    MkObject *x = mk_alloc_with_site(d3, &T_Foo, "arena.kt:1:1");
    CHECK(x != NULL);
    CHECK_EQ_INT(report(d3), 1);
    CHECK_ABORTS(abort_arena_free, d3, "arena");
    mk_release(x);
    CHECK_EQ_INT(report(d3), 0);
    mk_debug_allocator_destroy(d3);
    mk_arena_destroy(arena);

    /* debug over a pool */
    MkAllocator *pool = mk_pool_create(mk_object_block_size(&T_Foo), 4);
    MkAllocator *d4 = mk_debug_allocator_create(pool);
    MkObject *y = mk_alloc_with_site(d4, &T_Bar, "pool.kt:5:5");
    CHECK_EQ_INT(report(d4), 1);
    CHECK(strcmp(report_buf, "leak: app.Bar allocated at pool.kt:5:5\n") == 0);
    mk_free(y);
    CHECK_EQ_INT(report(d4), 0);
    mk_debug_allocator_destroy(d4);
    mk_pool_destroy(pool);

    TEST_DONE();
}
