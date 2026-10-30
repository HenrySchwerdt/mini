// mandelbrot: N x N set, 8 pixels packed per byte, 50 iterations; output = checksum of the bitmap (not the PBM).
#include <stdio.h>

#define N 4000
#define MOD 1000000007LL

int main(void) {
    long long chk = 0;
    for (int y = 0; y < N; y++) {
        double ci = 2.0 * y / N - 1.0;
        for (int xb = 0; xb < N / 8; xb++) {
            int bits = 0;
            for (int bit = 0; bit < 8; bit++) {
                int x = xb * 8 + bit;
                double cr = 2.0 * x / N - 1.5;
                double zr = 0.0, zi = 0.0;
                int inside = 1;
                for (int it = 0; it < 50; it++) {
                    double tr = zr * zr - zi * zi + cr;
                    zi = 2.0 * zr * zi + ci;
                    zr = tr;
                    if (zr * zr + zi * zi > 4.0) { inside = 0; break; }
                }
                bits = (bits << 1) | inside;
            }
            chk = (chk * 131 + bits) % MOD;
        }
    }
    printf("%lld\n", chk);
    return 0;
}
