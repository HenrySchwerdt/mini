// spectral-norm: dense double loops. Same algorithm in every language; output = the norm * 1e9, rounded.
#include <math.h>
#include <stdio.h>
#include <stdlib.h>

#define N 5000

static double a(int i, int j) { return 1.0 / (double)((i + j) * (i + j + 1) / 2 + i + 1); }

static void mul_av(const double *v, double *out) {
    for (int i = 0; i < N; i++) {
        double s = 0.0;
        for (int j = 0; j < N; j++) s += a(i, j) * v[j];
        out[i] = s;
    }
}
static void mul_atv(const double *v, double *out) {
    for (int i = 0; i < N; i++) {
        double s = 0.0;
        for (int j = 0; j < N; j++) s += a(j, i) * v[j];
        out[i] = s;
    }
}
static void mul_atav(const double *v, double *out, double *tmp) {
    mul_av(v, tmp);
    mul_atv(tmp, out);
}

int main(void) {
    double *u = malloc(N * sizeof(double)), *v = malloc(N * sizeof(double)), *tmp = malloc(N * sizeof(double));
    for (int i = 0; i < N; i++) u[i] = 1.0;
    for (int i = 0; i < 10; i++) {
        mul_atav(u, v, tmp);
        mul_atav(v, u, tmp);
    }
    double vbv = 0.0, vv = 0.0;
    for (int i = 0; i < N; i++) {
        vbv += u[i] * v[i];
        vv += v[i] * v[i];
    }
    printf("%lld\n", (long long)(sqrt(vbv / vv) * 1e9 + 0.5));
    free(u); free(v); free(tmp);
    return 0;
}
