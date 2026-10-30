// binary-trees: allocation heavy (malloc/free per node). Same algorithm in every language.
#include <stdio.h>
#include <stdlib.h>

#define MAX_DEPTH 18

typedef struct Node { struct Node *left, *right; } Node;

static Node *bottom_up(int d) {
    Node *n = malloc(sizeof(Node));
    if (d == 0) { n->left = NULL; n->right = NULL; }
    else { n->left = bottom_up(d - 1); n->right = bottom_up(d - 1); }
    return n;
}
static int check(Node *n) { return n->left == NULL ? 1 : 1 + check(n->left) + check(n->right); }
static void drop(Node *n) { if (n->left) { drop(n->left); drop(n->right); } free(n); }

int main(void) {
    int min_depth = 4, stretch = MAX_DEPTH + 1;
    Node *t = bottom_up(stretch);
    printf("stretch tree of depth %d\t check: %d\n", stretch, check(t));
    drop(t);
    Node *long_lived = bottom_up(MAX_DEPTH);
    for (int d = min_depth; d <= MAX_DEPTH; d += 2) {
        int iters = 1 << (MAX_DEPTH - d + min_depth), chk = 0;
        for (int i = 0; i < iters; i++) { Node *x = bottom_up(d); chk += check(x); drop(x); }
        printf("%d\t trees of depth %d\t check: %d\n", iters, d, chk);
    }
    printf("long lived tree of depth %d\t check: %d\n", MAX_DEPTH, check(long_lived));
    drop(long_lived);
    return 0;
}
