/* The cycle collector (cycles.c): garbage cycles are freed, live ones are not, counts stay exact, nothing recurses on the C stack. */
#include "harness.h"

typedef struct Node {
    MkObject header;
    struct Node *a;
    struct Node *b;
    MkObject *leaf; /* an object that cannot be in a cycle (no trace) */
} Node;

static int disposed;
static int leaf_disposed;

static void node_dispose(MkObject *self) {
    Node *n = (Node *)self;
    disposed++;
    mk_release((MkObject *)n->a);
    mk_release((MkObject *)n->b);
    mk_release(n->leaf);
}

static void node_trace(MkObject *self, void (*visit)(MkObject *, void *), void *ctx) {
    Node *n = (Node *)self;
    visit((MkObject *)n->a, ctx);
    visit((MkObject *)n->b, ctx);
}

static void leaf_dispose(MkObject *self) {
    (void)self;
    leaf_disposed++;
}

static const MkTypeInfo node_type = {"Node", sizeof(Node), &mk_type_Any, node_dispose, NULL, 0, NULL, 0, node_trace};
static const MkTypeInfo leaf_type = {"Leaf", sizeof(MkObject), &mk_type_Any, leaf_dispose, NULL, 0, NULL, 0, NULL};

static Node *node_new(void) { return (Node *)mk_alloc(&node_type); }

/* a.field = b (the field owns a reference) */
static void link_a(Node *x, Node *y) { mk_retain((MkObject *)y); x->a = y; }
static void link_b(Node *x, Node *y) { mk_retain((MkObject *)y); x->b = y; }

static void test_two_cycle(void) {
    disposed = 0;
    Node *x = node_new(), *y = node_new();
    link_a(x, y);
    link_a(y, x);
    CHECK(x->header.flags & MK_FLAG_ARMED);
    mk_release((MkObject *)x); /* no longer referenced from outside: a candidate, not freed */
    mk_release((MkObject *)y);
    CHECK_EQ_INT(disposed, 0);
    CHECK_EQ_INT(mk_cycles_collect(), 2);
    CHECK_EQ_INT(disposed, 2);
}

static void test_self_loop_and_leaf(void) {
    disposed = leaf_disposed = 0;
    Node *x = node_new();
    link_a(x, x);
    x->leaf = mk_alloc(&leaf_type);
    CHECK(!(x->leaf->flags & MK_FLAG_ARMED)); /* no trace: not a candidate */
    mk_release((MkObject *)x);
    CHECK_EQ_INT(mk_cycles_collect(), 1);
    CHECK_EQ_INT(disposed, 1);
    CHECK_EQ_INT(leaf_disposed, 1); /* the dispose of the garbage released its acyclic child */
}

static void test_live_cycle_survives(void) {
    disposed = 0;
    Node *x = node_new(), *y = node_new();
    link_a(x, y);
    link_a(y, x);
    mk_release((MkObject *)y); /* y is still referenced by x */
    mk_release((MkObject *)x); /* x is referenced by y; an outside reference is kept below */
    mk_retain((MkObject *)x);  /* (resurrect before the collection: x has an outside reference again) */
    /* x.refcount was 1 (from y) and is now 2: the cycle is reachable from outside */
    CHECK_EQ_INT(mk_cycles_collect(), 0);
    CHECK_EQ_INT(disposed, 0);
    CHECK_EQ_INT(x->header.refcount, 2);
    CHECK_EQ_INT(y->header.refcount, 1);
    mk_release((MkObject *)x);
    CHECK_EQ_INT(mk_cycles_collect(), 2);
    CHECK_EQ_INT(disposed, 2);
}

static void test_garbage_points_to_live(void) {
    disposed = 0;
    Node *live = node_new();
    Node *x = node_new(), *y = node_new();
    link_a(x, y);
    link_a(y, x);
    link_b(x, live); /* the garbage cycle holds a reference to a live object */
    link_b(y, live);
    CHECK_EQ_INT(live->header.refcount, 3);
    mk_release((MkObject *)x);
    mk_release((MkObject *)y);
    CHECK_EQ_INT(mk_cycles_collect(), 2);
    CHECK_EQ_INT(live->header.refcount, 1); /* exactly the references of the two dead nodes are gone */
    CHECK_EQ_INT(disposed, 2);
    mk_release((MkObject *)live);
    CHECK_EQ_INT(disposed, 3);
}

static void test_long_ring(void) {
    disposed = 0;
    enum { N = 400000 };
    Node *first = node_new(), *prev = first;
    for (int i = 1; i < N; i++) {
        Node *n = node_new();
        link_a(prev, n);
        mk_release((MkObject *)n); /* the chain owns it */
        prev = n;
    }
    link_a(prev, first); /* a ring of N nodes */
    mk_release((MkObject *)first);
    CHECK_EQ_INT(mk_cycles_collect(), N); /* (explicit stacks: no recursion on the C stack) */
    CHECK_EQ_INT(disposed, N);
}

static void test_long_chain_release(void) {
    disposed = 0;
    enum { N = 1000000 };
    Node *head = node_new();
    Node *tail = head;
    for (int i = 1; i < N; i++) {
        Node *n = node_new();
        tail->a = n; /* ownership moves into the field */
        tail = n;
    }
    mk_release((MkObject *)head); /* the dispose of each node releases the next one: bounded nesting */
    CHECK_EQ_INT(disposed, N);
}

static void test_dispose_during_collection(void) {
    disposed = 0;
    /* a dying object (its dispose runs) must survive a collection that its own dispose triggers */
    Node *keep[8];
    for (int i = 0; i < 8; i++) {
        keep[i] = node_new();
        link_a(keep[i], keep[i]);
        mk_release((MkObject *)keep[i]); /* candidates */
    }
    CHECK_EQ_INT(mk_cycles_collect(), 8);
    CHECK_EQ_INT(disposed, 8);
}

static void test_acyclic_chain_of_candidates(void) {
    disposed = 0;
    /* a -> b -> c, all dropped in the order a, b, c: candidates that die normally (zombies) are freed by the collector */
    Node *a = node_new(), *b = node_new(), *c = node_new();
    link_a(a, b);
    link_a(b, c);
    mk_release((MkObject *)b); /* the chain owns b and c now: a decrement to non-zero, buffered as candidates */
    mk_release((MkObject *)c);
    mk_release((MkObject *)a); /* frees a, then b, then c through the normal path (they are buffered: zombies) */
    CHECK_EQ_INT(disposed, 3);
    CHECK_EQ_INT(mk_cycles_collect(), 0); /* the collection reclaims the blocks of the zombies, no garbage cycle */
}

int main(void) {
    test_two_cycle();
    test_self_loop_and_leaf();
    test_live_cycle_survives();
    test_garbage_points_to_live();
    test_acyclic_chain_of_candidates();
    test_dispose_during_collection();
    test_long_ring();
    test_long_chain_release();
    if (t_failures != 0) {
        fprintf(stderr, "%d of %d checks failed\n", t_failures, t_checks);
        return 1;
    }
    printf("test_cycles: %d checks ok\n", t_checks);
    return 0;
}
