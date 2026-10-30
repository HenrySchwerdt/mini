// fannkuch-redux: integer array permutations and flips. Same algorithm in every language.
#include <stdio.h>
#include <stdlib.h>

#define N 11

int main(void) {
    int perm[N], perm1[N], count[N];
    int maxFlips = 0, permCount = 0, checksum = 0;
    for (int i = 0; i < N; i++) perm1[i] = i;
    int r = N;
    for (;;) {
        while (r != 1) { count[r - 1] = r; r--; }
        for (int i = 0; i < N; i++) perm[i] = perm1[i];
        int flips = 0, k;
        while ((k = perm[0]) != 0) {
            int k2 = (k + 1) >> 1;
            for (int i = 0; i < k2; i++) { int t = perm[i]; perm[i] = perm[k - i]; perm[k - i] = t; }
            flips++;
        }
        if (flips > maxFlips) maxFlips = flips;
        checksum += (permCount % 2 == 0) ? flips : -flips;
        for (;;) {
            if (r == N) { printf("%d\nPfannkuchen(%d) = %d\n", checksum, N, maxFlips); return 0; }
            int p0 = perm1[0];
            for (int i = 0; i < r; i++) perm1[i] = perm1[i + 1];
            perm1[r] = p0;
            count[r]--;
            if (count[r] > 0) break;
            r++;
        }
        permCount++;
    }
}
