/* The C library of the cinterop_* tests (compiled and linked by the test with `--c-src`). */
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

#include "cinterop_lib.h"

static MkTypeInfo foreign_child_type = {
    "test.ForeignChild", sizeof(MkObject), NULL, NULL, NULL, 0, NULL, 0,
};
static MkObject foreign_child_object = {NULL, 0, MK_FLAG_STATIC};

MkObject *foreign_child(MkObject *value) {
    foreign_child_type.super = value->type;
    foreign_child_object.type = &foreign_child_type;
    return &foreign_child_object;
}

void rec_fill(record_t *r, int id) {
    memset(r, 0, sizeof *r);
    r->id = id;
    r->score = id * 1.5;
    snprintf(r->name, sizeof r->name, "rec-%d", id);
    r->pos.x = (int16_t)(id * 10);
    r->pos.y = (int16_t)(-id);
    r->flag = (uint8_t)(id % 2);
    r->big = (int64_t)id * 1000000007LL;
    r->label = "static label";
    r->ratio = 0.25f;
}

int rec_size(void) { return (int)sizeof(record_t); }
int rec_offset_pos(void) { return (int)offsetof(record_t, pos); }
int rec_offset_label(void) { return (int)offsetof(record_t, label); }

int sum_ints(const int *a, int n) {
    int s = 0;
    for (int i = 0; i < n; i++) s += a[i];
    return s;
}

void scale_doubles(double *a, int n, double k) {
    for (int i = 0; i < n; i++) a[i] *= k;
}

int apply_binop(binop_t f, int a, int b) { return f(a, b); }

int fold(binop_t f, const int *a, int n, int init) {
    int acc = init;
    for (int i = 0; i < n; i++) acc = f(acc, a[i]);
    return acc;
}

/* a callback that takes a context pointer, as in libuv */
void visit_range(int from, int to, visit_t cb, void *ctx) {
    for (int i = from; i < to; i++) cb(i, ctx);
}

float scale_float(float x, float k) { return x * k; }
int8_t negate_byte(int8_t b) { return (int8_t)-b; }
int16_t add_short(int16_t a, int16_t b) { return (int16_t)(a + b); }
uint8_t is_even(int x) { return (uint8_t)((x & 1) == 0); }
double mix(int a, float b, double c, int64_t d) { return a + b + c + (double)d; }

/* a function whose result the caller owns and must free */
char *make_greeting(const char *name) {
    size_t n = strlen(name) + 8;
    char *s = (char *)malloc(n);
    snprintf(s, n, "Hello, %s!", name);
    return s;
}

/* returns the address of a static, never freed */
const char *lib_version(void) { return "cinterop_lib 1.0"; }

/* a C global that Kotlin reads through a function */
static int counter_value = 100;
int counter_next(void) { return ++counter_value; }

/* a linked list built by C */
node_t *node_push(node_t *head, int value) {
    node_t *n = (node_t *)malloc(sizeof *n);
    n->value = value;
    n->next = head;
    return n;
}

void node_free_all(node_t *head) {
    while (head != NULL) {
        node_t *next = head->next;
        free(head);
        head = next;
    }
}

/* arrays: MkArray header, then the elements (minirt.h); a FloatArray holds 32-bit floats, a DoubleArray 64-bit doubles */
#include "minirt.h"
float farray_sum(void *a, int n) {
    const float *e = MK_ARRAY_ELEMS(float, a);
    float s = 0;
    for (int i = 0; i < n; i++) s += e[i];
    return s;
}
void farray_scale(void *a, int n, float k) {
    float *e = MK_ARRAY_ELEMS(float, a);
    for (int i = 0; i < n; i++) e[i] *= k;
}
double darray_sum(void *a, int n) {
    const double *e = MK_ARRAY_ELEMS(double, a);
    double s = 0;
    for (int i = 0; i < n; i++) s += e[i];
    return s;
}
