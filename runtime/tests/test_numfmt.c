#include "harness.h"

#include <math.h>

static void D(double v, const char *expected) {
    MkString *s = mk_string_from_double(v);
    t_checks++;
    if (!t_str_is(s, expected, 1)) {
        t_failures++;
        fprintf(stderr, "  for value %.17g\n", v);
    }
}

static void F(float v, const char *expected) {
    MkString *s = mk_string_from_float(v);
    t_checks++;
    if (!t_str_is(s, expected, 1)) {
        t_failures++;
        fprintf(stderr, "  for float %.9g\n", (double)v);
    }
}

static void floats(void) {
    F(0.1f, "0.1");
    F(1.0f, "1.0");
    F(-1.5f, "-1.5");
    F(0.0f, "0.0");
    F(-0.0f, "-0.0");
    F(NAN, "NaN");
    F(INFINITY, "Infinity");
    F(-INFINITY, "-Infinity");
    F(1.0e10f, "1.0E10");
    F(3.4028235e38f, "3.4028235E38");
    F(-3.4028235e38f, "-3.4028235E38");
    F(1.4e-45f, "1.4E-45");
    F(2.8e-45f, "2.8E-45");
    F(1.17549435e-38f, "1.1754944E-38"); /* shortest round-trip, JDK 19+ */
    F(3.14159f, "3.14159");
    F(16777216.0f, "1.6777216E7");
    F(9999999.0f, "9999999.0");
    F(1.0f / 3, "0.33333334");
    F(0.001f, "0.001");
    F(0.0001f, "1.0E-4");
    F(123456.789f, "123456.79");
    F(100.0f, "100.0");
    F(0.3f, "0.3");
    F(0.1f + 0.2f, "0.3");
    /* byte and short go through the int path */
    CHECK_STR(mk_string_from_byte(-128), "-128");
    CHECK_STR(mk_string_from_byte(127), "127");
    CHECK_STR(mk_string_from_short(-32768), "-32768");
    CHECK_STR(mk_string_from_short(32767), "32767");
}

int main(void) {
    floats();
    D(NAN, "NaN");
    D(INFINITY, "Infinity");
    D(-INFINITY, "-Infinity");
    D(0.0, "0.0");
    D(-0.0, "-0.0");
    D(1.0, "1.0");
    D(-1.0, "-1.0");
    D(0.1, "0.1");
    D(0.5, "0.5");
    D(100.0, "100.0");
    D(123.456, "123.456");
    D(-123.456, "-123.456");
    D(1.0 / 3, "0.3333333333333333");
    D(2.0 / 3, "0.6666666666666666");
    D(0.1 + 0.2, "0.30000000000000004");
    D(9999999.0, "9999999.0");
    D(1234567.5, "1234567.5");
    D(1e7, "1.0E7");
    D(1.5e7, "1.5E7");
    D(12345678.9, "1.23456789E7");
    D(-1e7, "-1.0E7");
    D(0.001, "0.001");
    D(0.0012345, "0.0012345");
    D(0.0001, "1.0E-4");
    D(0.00099999, "9.9999E-4");
    D(1.234e-5, "1.234E-5");
    D(1e21, "1.0E21");
    D(1e100, "1.0E100");
    D(1e-300, "1.0E-300");
    D(1.7976931348623157e308, "1.7976931348623157E308");
    D(4.9e-324, "4.9E-324");
    D(2.2250738585072014e-308, "2.2250738585072014E-308");
    D(9007199254740993.0, "9.007199254740992E15");
    D(3.14159, "3.14159");
    D(100.5, "100.5");
    D(5e-3, "0.005");
    D(1234567.0, "1234567.0");
    D((double)INT64_MIN, "-9.223372036854776E18");
    D((double)INT32_MIN, "-2.147483648E9");

    TEST_DONE();
}
