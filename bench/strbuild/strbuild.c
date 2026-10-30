// string-builder / concat: (A) one big growable buffer filled with ints, chars and literals, (B) many short strings made by
// concatenation (snprintf into a malloc'ed buffer, freed again). Output: lengths and checksums.
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define APPENDS 12000000
#define CONCATS 6000000
#define MOD 1000000007LL

int main(void) {
    size_t cap = 1 << 16, len = 0;
    char *sb = malloc(cap);
    for (int i = 0; i < APPENDS; i++) {
        if (len + 32 > cap) { cap *= 2; sb = realloc(sb, cap); }
        char tmp[12];
        int n = 0, v = i;
        do { tmp[n++] = (char)('0' + v % 10); v /= 10; } while (v > 0);
        while (n > 0) sb[len++] = tmp[--n];
        sb[len++] = ',';
        if (i % 5 == 0) { memcpy(sb + len, "item", 4); len += 4; }
    }
    int64_t chkA = 0;
    for (size_t idx = 0; idx < len; idx += 7) chkA = (chkA * 31 + (uint8_t)sb[idx]) % MOD;
    int64_t sumB = 0, chkB = 0;
    for (int i = 0; i < CONCATS; i++) {
        char *s = malloc(40);
        int n = snprintf(s, 40, "item-%d:%d", i, i * 7);
        sumB += n;
        chkB = (chkB * 31 + (uint8_t)s[n - 1] + (uint8_t)s[5]) % MOD;
        free(s);
    }
    printf("%zu %lld %lld %lld\n", len, (long long)chkA, (long long)sumB, (long long)chkB);
    free(sb);
    return 0;
}
