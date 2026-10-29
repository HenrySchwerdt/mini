/* Double -> String matching Kotlin/JVM Double.toString. */
#include "internal.h"

#include <math.h>
#include <stdio.h>
#include <string.h>

size_t mk_format_double(MkDouble v, char *buf, size_t cap) {
    if (isnan(v)) {
        strcpy(buf, "NaN");
        return 3;
    }
    if (isinf(v)) {
        const char *s = v < 0 ? "-Infinity" : "Infinity";
        strcpy(buf, s);
        return strlen(s);
    }
    size_t n = 0;
    if (signbit(v)) buf[n++] = '-';
    double a = fabs(v);
    if (a == 0.0) {
        memcpy(buf + n, "0.0", 4);
        return n + 3;
    }

    /* Shortest round-trip digits: smallest precision whose %e output parses back. */
    char tmp[48];
    for (int prec = 0; prec <= 16; prec++) {
        snprintf(tmp, sizeof tmp, "%.*e", prec, a);
        if (strtod(tmp, NULL) == a) {
            /* JDK rule: if one digit suffices, pick the 2-digit decimal closest
             * to the exact value (e.g. 4.9E-324 for Double.MIN_VALUE). */
            if (prec == 0) snprintf(tmp, sizeof tmp, "%.1e", a);
            break;
        }
    }
    /* tmp = d[.ddd]e[+-]XX. Collect the digits (skipping any locale-specific
     * decimal separator) and the decimal exponent. */
    char digits[24];
    size_t nd = 0;
    const char *p = tmp;
    for (; *p && *p != 'e'; p++)
        if (*p >= '0' && *p <= '9' && nd < sizeof digits) digits[nd++] = *p;
    int exp10 = atoi(p + 1);
    while (nd > 1 && digits[nd - 1] == '0') nd--;

    if (a >= 1e-3 && a < 1e7) {
        if (exp10 >= 0) {
            size_t int_len = (size_t)exp10 + 1;
            for (size_t i = 0; i < int_len; i++) buf[n++] = i < nd ? digits[i] : '0';
            buf[n++] = '.';
            if (nd > int_len) {
                for (size_t i = int_len; i < nd; i++) buf[n++] = digits[i];
            } else {
                buf[n++] = '0';
            }
        } else {
            buf[n++] = '0';
            buf[n++] = '.';
            for (int i = 0; i < -exp10 - 1; i++) buf[n++] = '0';
            for (size_t i = 0; i < nd; i++) buf[n++] = digits[i];
        }
        buf[n] = '\0';
    } else {
        buf[n++] = digits[0];
        buf[n++] = '.';
        if (nd > 1) {
            for (size_t i = 1; i < nd; i++) buf[n++] = digits[i];
        } else {
            buf[n++] = '0';
        }
        n += (size_t)snprintf(buf + n, cap - n, "E%d", exp10);
    }
    return n;
}
