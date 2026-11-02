#include "harness.h"

typedef struct { MkObject header; } Tracked;

static int disposed;

static void tracked_dispose(MkObject *self) {
    (void)self;
    disposed++;
}

static const MkTypeInfo tracked_type = {
    "Tracked", sizeof(Tracked), &mk_type_Any, tracked_dispose, NULL, 0, NULL, 0,
};

int main(void) {
    disposed = 0;
    MkObject *array = mk_ref_array_new(8);
    MkObject *child = mk_alloc(&tracked_type);
    mk_ref_array_set_o(array, 2, child); /* the array takes the child's sole reference */

    static MkObject static_item = MK_STATIC_HEADER(&tracked_type);
    mk_ref_array_set(array, 6, &static_item); /* retain/release of static items are no-ops */
    CHECK_EQ_INT(((MkObject *)child)->refcount, 1);
    CHECK_EQ_INT(disposed, 0);

#ifdef MK_STATS
    MkStats before, after;
    mk_stats_get(&before);
#endif
    mk_release(array);
    CHECK_EQ_INT(disposed, 1); /* live child is released exactly once */

#ifdef MK_STATS
    mk_stats_get(&after);
    CHECK_EQ_INT(after.allocs, before.allocs);
    CHECK_EQ_INT(after.frees, before.frees + 2); /* array and child */
    CHECK_EQ_INT(after.live, before.live - 2);
#ifndef EXPECTED_REF_ARRAY_NOOP_RELEASES
#define EXPECTED_REF_ARRAY_NOOP_RELEASES 1
#endif
    CHECK_EQ_INT(after.releases_noop - before.releases_noop, EXPECTED_REF_ARRAY_NOOP_RELEASES);
    CHECK_EQ_INT(after.releases - before.releases, EXPECTED_REF_ARRAY_NOOP_RELEASES + 2);
#endif

    /* Exercise inline storage large enough to use the allocator's malloc path. */
    MkObject *large = mk_ref_array_new(128);
    CHECK_EQ_INT(mk_ref_array_length(large), 128);
    CHECK(mk_ref_array_get_b(large, 0) == NULL);
    CHECK(mk_ref_array_get_b(large, 127) == NULL);
    MkObject *large_child = mk_alloc(&tracked_type);
    mk_ref_array_set_o(large, 127, large_child);
    CHECK_EQ_INT(large_child->refcount, 1);
    mk_release(large);
    CHECK_EQ_INT(disposed, 2);

    TEST_DONE();
}
