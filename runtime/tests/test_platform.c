#include "harness.h"

#include <stdlib.h>

#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <wchar.h>
#else
#include <time.h>
#endif

static MkString *U(const char *s) { return mk_string_from_utf8(s, strlen(s)); }

static const char *const roundtrip[] = {
    "", "plain", "two words", "a\"b", "ends-with-backslash\\",
    "雪だるま☃", "&|<>^%$();!", "backslash before quote\\\"tail"
};

#if !defined(_WIN32)
static int child_mode(int argc, char **argv) {
    if (argc == 2 && strcmp(argv[1], "--platform-exit7") == 0) return 7;
    CHECK_EQ_INT(argc, (int)(sizeof roundtrip / sizeof roundtrip[0]) + 2);
    for (size_t i = 0; i < sizeof roundtrip / sizeof roundtrip[0] && (int)i + 2 < argc; i++)
        CHECK_STR(U(argv[i + 2]), roundtrip[i]);
    return t_failures ? 1 : 0;
}
#endif

#if defined(_WIN32)
static int wide_equals_utf8(const wchar_t *wide, const char *utf8) {
    int n = WideCharToMultiByte(CP_UTF8, 0, wide, -1, NULL, 0, NULL, NULL);
    if (n <= 0) return 0;
    char *text = malloc((size_t)n);
    if (!text) return 0;
    int ok = WideCharToMultiByte(CP_UTF8, 0, wide, -1, text, n, NULL, NULL) == n && strcmp(text, utf8) == 0;
    free(text);
    return ok;
}

static int child_mode_w(int argc, wchar_t **argv) {
    if (argc == 2 && wcscmp(argv[1], L"--platform-exit7") == 0) return 7;
    CHECK_EQ_INT(argc, (int)(sizeof roundtrip / sizeof roundtrip[0]) + 2);
    for (size_t i = 0; i < sizeof roundtrip / sizeof roundtrip[0] && (int)i + 2 < argc; i++)
        CHECK(wide_equals_utf8(argv[i + 2], roundtrip[i]));
    return t_failures ? 1 : 0;
}
#endif

static MkString *make_argv(const MkString *exe, const char *const *args, size_t count) {
    size_t length = (size_t)exe->length;
    for (size_t i = 0; i < count; i++) {
        MkString *arg = U(args[i]);
        length += 1 + (size_t)arg->length;
        mk_release(&arg->header);
    }
    MkString *joined = mk_string_alloc((MkInt)length);
    size_t at = 0;
    memcpy(joined->chars, exe->chars, (size_t)exe->length * sizeof(MkChar));
    at = (size_t)exe->length;
    for (size_t i = 0; i < count; i++) {
        joined->chars[at++] = 1;
        MkString *arg = U(args[i]);
        memcpy(joined->chars + at, arg->chars, (size_t)arg->length * sizeof(MkChar));
        at += (size_t)arg->length;
        mk_release(&arg->header);
    }
    return joined;
}

static int run_test(const MkString *exe) {
    const char *const *args = roundtrip;
    size_t n = sizeof roundtrip / sizeof roundtrip[0];
    const char **all = malloc((n + 1) * sizeof(*all));
    CHECK(all != NULL);
    if (!all) return 1;
    all[0] = "--platform-child";
    for (size_t i = 0; i < n; i++) all[i + 1] = args[i];
    MkString *joined = make_argv(exe, all, n + 1);
    CHECK_EQ_INT(mk_platform_spawn(joined), 0);
    mk_release(&joined->header);
    free(all);

    const char *exit7[] = {"--platform-exit7"};
    joined = make_argv(exe, exit7, 1);
    CHECK_EQ_INT(mk_platform_spawn(joined), 7);
    mk_release(&joined->header);

    joined = U("minirt-no-such-executable-for-test");
    CHECK(mk_platform_spawn(joined) != 0);
    mk_release(&joined->header);

    MkString *bad = mk_string_alloc(3);
    bad->chars[0] = 'x'; bad->chars[1] = 1; bad->chars[2] = 0;
    CHECK_EQ_INT(mk_platform_spawn(bad), -1);
    mk_release(&bad->header);
    return 0;
}

#if defined(_WIN32)
int wmain(int argc, wchar_t **argv) {
    if (argc > 1) return child_mode_w(argc, argv);
    /* Preserve the Windows Unicode executable path and arguments as UTF-16. */
    int length = (int)wcslen(argv[0]);
    MkString *exe = mk_string_alloc(length);
    for (int i = 0; i < length; i++) exe->chars[i] = (MkChar)argv[0][i];
    (void)run_test(exe);
    mk_release(&exe->header);
    MkLong before = mk_platform_nanos(), after = mk_platform_nanos();
    CHECK(after >= before);
    TEST_DONE();
}
#else
int main(int argc, char **argv) {
    if (argc > 1) return child_mode(argc, argv);
    MkString *exe = U(argv[0]);
    (void)run_test(exe);
    mk_release(&exe->header);
    MkLong before = mk_platform_nanos(), after = mk_platform_nanos();
    CHECK(after >= before);
    TEST_DONE();
}
#endif
