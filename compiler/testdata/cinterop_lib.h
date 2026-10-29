/* The header of cinterop_lib.c (`@file:CInclude("cinterop_lib.h")` in the cinterop_* tests). */
#ifndef CINTEROP_LIB_H
#define CINTEROP_LIB_H
#include <stddef.h>
#include <stdint.h>

typedef struct {
    int16_t x;
    int16_t y;
} point_t;

typedef struct {
    int32_t id;
    double score;
    char name[16];
    point_t pos;
    uint8_t flag;
    int64_t big;
    const char *label;
    float ratio;
} record_t;

void rec_fill(record_t *r, int id);
int rec_size(void);
int rec_offset_pos(void);
int rec_offset_label(void);
int sum_ints(const int *a, int n);
void scale_doubles(double *a, int n, double k);
typedef int (*binop_t)(int, int);
int apply_binop(binop_t f, int a, int b);
int fold(binop_t f, const int *a, int n, int init);
typedef void (*visit_t)(int value, void *ctx);
void visit_range(int from, int to, visit_t cb, void *ctx);
float scale_float(float x, float k);
int8_t negate_byte(int8_t b);
int16_t add_short(int16_t a, int16_t b);
uint8_t is_even(int x);
double mix(int a, float b, double c, int64_t d);
char *make_greeting(const char *name);
const char *lib_version(void);
int counter_next(void);

typedef struct node {
    int value;
    struct node *next;
} node_t;
node_t *node_push(node_t *head, int value);
void node_free_all(node_t *head);

#endif
