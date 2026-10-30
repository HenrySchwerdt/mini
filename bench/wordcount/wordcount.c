// word-count: generate a text of pseudo-random words, split it and count the words in a hash map (own open-addressing table
// holding pointers into the text). Output: total, distinct, max count, order-independent checksum.
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define WORDS 20000000
#define MOD 1000000007LL
#define CAP (1 << 18)

static int64_t seed = 42;
static int next_rand(void) {
    seed = (seed * 1103515245LL + 12345LL) & 0x7fffffffLL;
    return (int)(seed >> 8);
}

typedef struct { const char *p; int len; int count; } Entry;

int main(void) {
    size_t cap = 1 << 20, len = 0;
    char *text = malloc(cap);
    for (int i = 0; i < WORDS; i++) {
        int a = next_rand() % 50000, b = next_rand() % 50000;
        int x = (int)((int64_t)a * b / 50000), n = 0;
        if (len + 16 > cap) { cap *= 2; text = realloc(text, cap); }
        do { text[len++] = (char)('a' + x % 26); x /= 26; n++; } while (x > 0 || n < 3);
        text[len++] = ' ';
    }
    Entry *table = calloc(CAP, sizeof(Entry));
    int distinct = 0, total = 0;
    size_t pos = 0;
    while (pos < len) {
        const char *w = text + pos;
        int wl = 0;
        uint32_t h = 2166136261u;
        while (w[wl] != ' ') { h = (h ^ (uint8_t)w[wl]) * 16777619u; wl++; }
        size_t i = h & (CAP - 1);
        while (table[i].p && !(table[i].len == wl && memcmp(table[i].p, w, wl) == 0)) i = (i + 1) & (CAP - 1);
        if (!table[i].p) { table[i].p = w; table[i].len = wl; distinct++; }
        table[i].count++;
        total++;
        pos += wl + 1;
    }
    int max_count = 0;
    int64_t chk = 0;
    for (size_t i = 0; i < CAP; i++) {
        if (!table[i].p) continue;
        if (table[i].count > max_count) max_count = table[i].count;
        int64_t hh = 0;
        for (int k = 0; k < table[i].len; k++) hh = (hh * 31 + (uint8_t)table[i].p[k]) % MOD;
        chk = (chk + hh * table[i].count) % MOD;
    }
    printf("%d %d %d %lld\n", total, distinct, max_count, (long long)chk);
    free(table);
    free(text);
    return 0;
}
