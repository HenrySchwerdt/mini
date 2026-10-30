// json-like: generate a JSON document from a seeded generator, parse it into a tree (recursive descent, strings with escapes,
// objects as key/value lists), walk the tree. Output: text length, node count, checksum of the walk.
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define TOP 60000
#define REPS 5
#define MOD 1000000007LL

static int64_t seed = 7;
static int next_rand(void) {
    seed = (seed * 1103515245LL + 12345LL) & 0x7fffffffLL;
    return (int)(seed >> 8);
}

typedef struct { char *p; size_t len, cap; } Buf;
static void put(Buf *b, const char *s, size_t n) {
    if (b->len + n > b->cap) { b->cap = (b->cap + n) * 2; b->p = realloc(b->p, b->cap); }
    memcpy(b->p + b->len, s, n);
    b->len += n;
}
static void puts_(Buf *b, const char *s) { put(b, s, strlen(s)); }
static void putc_(Buf *b, char c) { put(b, &c, 1); }
static void puti(Buf *b, int v) { char t[16]; int n = snprintf(t, sizeof t, "%d", v); put(b, t, n); }

static void leaf(Buf *b) {
    switch (next_rand() % 6) {
    case 0: puts_(b, "null"); break;
    case 1: puts_(b, "true"); break;
    case 2: puts_(b, "false"); break;
    case 3: puti(b, next_rand() % 200000 - 100000); break;
    case 4: puts_(b, "\"s"); puti(b, next_rand() % 5000); putc_(b, '"'); break;
    default: puts_(b, "\"a\\nb\\\"c"); puti(b, next_rand() % 5000); puts_(b, "\\\\\""); break;
    }
}

static void gen(Buf *b, int depth) {
    int k = next_rand() % 12;
    if (depth >= 6 || k < 5) {
        leaf(b);
    } else if (k < 8) {
        int n = 1 + next_rand() % 4;
        putc_(b, '{');
        for (int i = 0; i < n; i++) {
            if (i > 0) puts_(b, ", ");
            puts_(b, "\"k"); puti(b, next_rand() % 1000); puts_(b, "\": ");
            gen(b, depth + 1);
        }
        putc_(b, '}');
    } else {
        int n = next_rand() % 5;
        putc_(b, '[');
        for (int i = 0; i < n; i++) {
            if (i > 0) puts_(b, ", ");
            gen(b, depth + 1);
        }
        putc_(b, ']');
    }
}

enum { J_NULL, J_BOOL, J_NUM, J_STR, J_ARR, J_OBJ };
typedef struct J {
    int kind;
    int64_t num;      // bool / number
    char *str;        // string
    int slen;
    int n, cap;       // array / object element count
    struct J **items; // array items / object values
    char **keys;      // object keys
    int *klens;
} J;

static J *mk(int kind) { J *j = calloc(1, sizeof(J)); j->kind = kind; return j; }
static void push(J *j, J *v, char *key, int klen) {
    if (j->n == j->cap) {
        j->cap = j->cap ? j->cap * 2 : 4;
        j->items = realloc(j->items, j->cap * sizeof(J *));
        if (j->kind == J_OBJ) { j->keys = realloc(j->keys, j->cap * sizeof(char *)); j->klens = realloc(j->klens, j->cap * sizeof(int)); }
    }
    if (j->kind == J_OBJ) { j->keys[j->n] = key; j->klens[j->n] = klen; }
    j->items[j->n++] = v;
}
static void drop(J *j) {
    for (int i = 0; i < j->n; i++) { drop(j->items[i]); if (j->kind == J_OBJ) free(j->keys[i]); }
    free(j->items); free(j->keys); free(j->klens); free(j->str); free(j);
}

typedef struct { const char *s; size_t len, pos; } Parser;

static void skip_ws(Parser *p) { while (p->pos < p->len && p->s[p->pos] == ' ') p->pos++; }

static char *parse_string(Parser *p, int *outlen) {
    p->pos++;
    size_t start = p->pos;
    int esc = 0;
    while (p->s[p->pos] != '"') {
        if (p->s[p->pos] == '\\') { esc = 1; p->pos++; }
        p->pos++;
    }
    size_t rawlen = p->pos - start;
    const char *raw = p->s + start;
    p->pos++;
    char *out = malloc(rawlen + 1);
    if (!esc) { memcpy(out, raw, rawlen); *outlen = (int)rawlen; out[rawlen] = 0; return out; }
    size_t o = 0;
    for (size_t i = 0; i < rawlen; i++) {
        char c = raw[i];
        if (c == '\\') { i++; char d = raw[i]; out[o++] = d == 'n' ? '\n' : d; } else out[o++] = c;
    }
    out[o] = 0;
    *outlen = (int)o;
    return out;
}

static J *parse_value(Parser *p) {
    skip_ws(p);
    char c = p->s[p->pos];
    if (c == '{') {
        p->pos++;
        J *j = mk(J_OBJ);
        skip_ws(p);
        if (p->s[p->pos] == '}') { p->pos++; return j; }
        for (;;) {
            skip_ws(p);
            int kl;
            char *k = parse_string(p, &kl);
            skip_ws(p);
            p->pos++;
            J *v = parse_value(p);
            push(j, v, k, kl);
            skip_ws(p);
            char d = p->s[p->pos++];
            if (d == '}') break;
        }
        return j;
    }
    if (c == '[') {
        p->pos++;
        J *j = mk(J_ARR);
        skip_ws(p);
        if (p->s[p->pos] == ']') { p->pos++; return j; }
        for (;;) {
            push(j, parse_value(p), NULL, 0);
            skip_ws(p);
            char d = p->s[p->pos++];
            if (d == ']') break;
        }
        return j;
    }
    if (c == '"') { J *j = mk(J_STR); j->str = parse_string(p, &j->slen); return j; }
    if (c == 't') { p->pos += 4; J *j = mk(J_BOOL); j->num = 1; return j; }
    if (c == 'f') { p->pos += 5; J *j = mk(J_BOOL); j->num = 0; return j; }
    if (c == 'n') { p->pos += 4; return mk(J_NULL); }
    int neg = 0;
    if (c == '-') { neg = 1; p->pos++; }
    int64_t v = 0;
    while (p->pos < p->len && p->s[p->pos] >= '0' && p->s[p->pos] <= '9') { v = v * 10 + (p->s[p->pos] - '0'); p->pos++; }
    J *j = mk(J_NUM);
    j->num = neg ? -v : v;
    return j;
}

static int64_t g_h, g_n;
static void step(int64_t x) { g_h = (g_h * 31 + x) % MOD; g_n++; }

static void walk(const J *j) {
    switch (j->kind) {
    case J_NULL: step(1); break;
    case J_BOOL: step(j->num ? 2 : 3); break;
    case J_NUM: step(j->num + 100000); break;
    case J_STR: {
        int64_t t = j->slen;
        for (int i = 0; i < j->slen; i++) t += (uint8_t)j->str[i];
        step(t);
        break;
    }
    case J_ARR:
        step(13 + j->n);
        for (int i = 0; i < j->n; i++) walk(j->items[i]);
        break;
    case J_OBJ:
        step(11 + j->n);
        for (int i = 0; i < j->n; i++) { step(j->klens[i]); walk(j->items[i]); }
        break;
    }
}

int main(void) {
    Buf b = {0};
    putc_(&b, '[');
    for (int i = 0; i < TOP; i++) {
        if (i > 0) puts_(&b, ", ");
        gen(&b, 1);
    }
    putc_(&b, ']');
    int64_t n = 0, h = 0;
    for (int r = 0; r < REPS; r++) {
        Parser p = {b.p, b.len, 0};
        J *tree = parse_value(&p);
        g_h = 0; g_n = 0;
        walk(tree);
        n = g_n; h = g_h;
        drop(tree);
    }
    printf("%zu %lld %lld\n", b.len, (long long)n, (long long)h);
    free(b.p);
    return 0;
}
