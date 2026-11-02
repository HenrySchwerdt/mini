#include "harness.h"

#include <stdio.h>
#include <stdlib.h>
#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <wchar.h>
void mk_b1_set_args_utf16(int argc, const MkChar *const *argv);
#else
#include <sys/stat.h>
#endif

static MkString *U(const char *s) { return mk_string_from_utf8(s, strlen(s)); }

static char *make_temp_directory(void) {
#if defined(_WIN32)
    wchar_t temp[MAX_PATH], path[MAX_PATH];
    DWORD n = GetTempPathW(MAX_PATH, temp);
    if (n == 0 || n >= MAX_PATH || GetTempFileNameW(temp, L"min", 0, path) == 0) return NULL;
    if (!DeleteFileW(path) || !CreateDirectoryW(path, NULL)) return NULL;
    int bytes = WideCharToMultiByte(CP_UTF8, 0, path, -1, NULL, 0, NULL, NULL);
    if (bytes <= 0) { RemoveDirectoryW(path); return NULL; }
    char *utf8 = malloc((size_t)bytes);
    if (utf8 == NULL) { RemoveDirectoryW(path); return NULL; }
    if (WideCharToMultiByte(CP_UTF8, 0, path, -1, utf8, bytes, NULL, NULL) != bytes) {
        free(utf8);
        RemoveDirectoryW(path);
        return NULL;
    }
    return utf8;
#else
    char path[80];
    snprintf(path, sizeof(path), "/tmp/minirt_b1_%ld", (long)getpid());
    if (mkdir(path, 0700) != 0) return NULL;
    size_t n = strlen(path) + 1;
    char *copy = malloc(n);
    if (copy != NULL) memcpy(copy, path, n);
    else remove(path);
    return copy;
#endif
}

static MkString *join_path(const MkString *base, const char *suffix) {
    char *base_utf8 = mk_utf16_to_utf8(base->chars, (size_t)base->length, NULL);
    if (base_utf8 == NULL) abort();
    size_t n = strlen(base_utf8), m = strlen(suffix);
    if (m > SIZE_MAX - 2 || n > SIZE_MAX - m - 2) abort();
    char *path = malloc(n + m + 2);
    if (path == NULL) abort();
    snprintf(path, n + m + 2, "%s/%s", base_utf8, suffix);
    MkString *result = U(path);
    free(path);
    free(base_utf8);
    return result;
}

#if defined(_WIN32)
static MkString *join_units(const MkString *base, const MkChar *suffix, size_t suffix_length) {
    size_t prefix = (size_t)base->length;
    if (prefix >= (size_t)INT32_MAX || suffix_length > (size_t)INT32_MAX - prefix - 1) abort();
    MkString *result = mk_string_alloc((MkInt)(prefix + 1 + suffix_length));
    if (prefix > 0) memcpy(result->chars, base->chars, prefix * sizeof(MkChar));
    result->chars[prefix] = '/';
    if (suffix_length > 0)
        memcpy(result->chars + prefix + 1, suffix, suffix_length * sizeof(MkChar));
    return result;
}

static wchar_t *to_wide_units(const MkString *path) {
    size_t n = (size_t)path->length;
    wchar_t *wide = malloc((n + 1) * sizeof(*wide));
    if (wide == NULL) abort();
    for (size_t i = 0; i < n; i++) wide[i] = path->chars[i] == '/' ? L'\\' : (wchar_t)path->chars[i];
    wide[n] = L'\0';
    return wide;
}
#endif

int main(void) {
    char *temp = make_temp_directory();
    CHECK(temp != NULL);
    if (temp == NULL) { TEST_DONE(); }
    MkString *root = U(temp);
    free(temp);
    MkString *nested = join_path(root, "nested one");
#if defined(_WIN32)
    MkString *child = join_path(nested, "\xe5\xad\x90\\inner"); /* mix '/' and '\\' */
#else
    MkString *child = join_path(nested, "\xe5\xad\x90/inner");
#endif
    MkString *empty_dir = join_path(root, "empty");
    MkString *path = join_path(child, "\xe2\x82\xac data.txt");
    MkString *top_file = join_path(root, "\xe2\x82\xac top.txt");
    CHECK(mk_b1_mkdirs(child));
    CHECK(mk_b1_mkdirs(empty_dir));
    CHECK(mk_b1_mkdirs(child)); /* existing nested path is idempotent */
    CHECK_EQ_INT(mk_b1_file_kind(root), 2);
    CHECK_EQ_INT(mk_b1_file_kind(nested), 2);
    CHECK_EQ_INT(mk_b1_file_kind(child), 2);
    CHECK_EQ_INT(mk_b1_file_kind(path), 0);

#if defined(_WIN32)
    MkString *unc_server_only = U("\\\\minirt-invalid-unc-server-only");
    CHECK(!mk_b1_mkdirs(unc_server_only)); /* never treat a server name as a directory to create */
    mk_release(&unc_server_only->header);
#endif

    /* UTF-8 contents stay byte-for-byte independent of the wide filesystem path. */
    const char text[] = "line 1\n\xc3\xa4\xe2\x82\xac \xf0\x9f\x98\x80\n\0after";
    MkString *t = mk_string_from_utf8(text, sizeof(text) - 1);
    CHECK(mk_b1_write_file(path, t));
    MkString *back = mk_b1_read_file(path);
    CHECK(back != NULL);
    if (back != NULL) {
        CHECK(back->length == t->length);
        CHECK(memcmp(back->chars, t->chars, (size_t)t->length * sizeof(MkChar)) == 0);
        mk_release(&back->header);
    }
    mk_release(&t->header);

    /* empty file */
    MkString *empty = U("");
    CHECK(mk_b1_write_file(path, empty));
    back = mk_b1_read_file(path);
    CHECK(back != NULL && back->length == 0);
    if (back != NULL) mk_release(&back->header);
    mk_release(&empty->header);

    /* a larger file (grows the read buffer) */
    MkString *big = mk_string_alloc(100000);
    for (MkInt i = 0; i < big->length; i++) big->chars[i] = (MkChar)('a' + i % 26);
    CHECK(mk_b1_write_file(path, big));
    back = mk_b1_read_file(path);
    CHECK(back != NULL && back->length == 100000);
    if (back != NULL) {
        CHECK(memcmp(back->chars, big->chars, 100000 * sizeof(MkChar)) == 0);
        mk_release(&back->header);
    }
    mk_release(&big->header);

    /* Unicode directory entries are UTF-8, sorted deterministically; empty and
     * missing directories remain distinct. */
    MkString *top = U("top");
    CHECK(mk_b1_write_file(top_file, top));
    mk_release(&top->header);
    MkString *listed = mk_b1_list_dir(root);
    CHECK(listed != NULL);
    if (listed != NULL) CHECK_STR(listed, "empty\nnested one\n\xe2\x82\xac top.txt");
    listed = mk_b1_list_dir(empty_dir);
    CHECK(listed != NULL);
    if (listed != NULL) CHECK_STR(listed, "");
    MkString *child_list = mk_b1_list_dir(child);
    CHECK(child_list != NULL);
    if (child_list != NULL) CHECK_STR(child_list, "\xe2\x82\xac data.txt");

#if defined(_WIN32)
    /* Win32 permits UTF-16 filenames containing an unpaired surrogate. The
     * directory listing must preserve that code unit rather than UTF-8-recode it. */
    MkString *surrogate_dir = join_path(root, "surrogate");
    CHECK(mk_b1_mkdirs(surrogate_dir));
    const MkChar raw_name[] = {'f', 0xd800, 'x'};
    MkString *raw_path = join_units(surrogate_dir, raw_name, sizeof raw_name / sizeof *raw_name);
    wchar_t *raw_wide_path = to_wide_units(raw_path);
    HANDLE raw_file = CreateFileW(raw_wide_path, GENERIC_WRITE, 0, NULL, CREATE_NEW,
                                  FILE_ATTRIBUTE_NORMAL, NULL);
    free(raw_wide_path);
    CHECK(raw_file != INVALID_HANDLE_VALUE);
    if (raw_file != INVALID_HANDLE_VALUE) {
        const char raw_content[] = "raw";
        DWORD written = 0;
        CHECK(WriteFile(raw_file, raw_content, sizeof raw_content - 1, &written, NULL));
        CHECK_EQ_INT(written, sizeof raw_content - 1);
        CHECK(CloseHandle(raw_file));
    }
    const MkChar bmp_name[] = {0xe000};
    const MkChar supplementary_name[] = {0xd800, 0xdc00};
    MkString *bmp_path = join_units(surrogate_dir, bmp_name, sizeof bmp_name / sizeof *bmp_name);
    MkString *supplementary_path = join_units(surrogate_dir, supplementary_name,
                                               sizeof supplementary_name / sizeof *supplementary_name);
    wchar_t *bmp_wide_path = to_wide_units(bmp_path);
    wchar_t *supplementary_wide_path = to_wide_units(supplementary_path);
    HANDLE bmp_file = CreateFileW(bmp_wide_path, GENERIC_WRITE, 0, NULL, CREATE_NEW,
                                  FILE_ATTRIBUTE_NORMAL, NULL);
    HANDLE supplementary_file = CreateFileW(supplementary_wide_path, GENERIC_WRITE, 0, NULL,
                                             CREATE_NEW, FILE_ATTRIBUTE_NORMAL, NULL);
    free(bmp_wide_path);
    free(supplementary_wide_path);
    CHECK(bmp_file != INVALID_HANDLE_VALUE);
    CHECK(supplementary_file != INVALID_HANDLE_VALUE);
    if (bmp_file != INVALID_HANDLE_VALUE) CHECK(CloseHandle(bmp_file));
    if (supplementary_file != INVALID_HANDLE_VALUE) CHECK(CloseHandle(supplementary_file));
    CHECK_EQ_INT(mk_b1_file_kind(raw_path), 1);
    MkString *raw_list = mk_b1_list_dir(surrogate_dir);
    CHECK(raw_list != NULL);
    if (raw_list != NULL) {
        const MkChar expected_names[] = {'f', 0xd800, 'x', '\n', 0xe000, '\n', 0xd800, 0xdc00};
        CHECK_EQ_INT(raw_list->length, sizeof expected_names / sizeof *expected_names);
        for (size_t i = 0; i < sizeof expected_names / sizeof *expected_names; i++)
            CHECK_EQ_INT(raw_list->chars[i], expected_names[i]);
        mk_release(&raw_list->header);
    }
    back = mk_b1_read_file(raw_path);
    CHECK(back != NULL);
    if (back != NULL) {
        CHECK_EQ_INT(back->length, 3);
        CHECK_EQ_INT(back->chars[0], 'r');
        CHECK_EQ_INT(back->chars[1], 'a');
        CHECK_EQ_INT(back->chars[2], 'w');
        mk_release(&back->header);
    }
    CHECK(mk_b1_delete(raw_path));
    CHECK(mk_b1_delete(bmp_path));
    CHECK(mk_b1_delete(supplementary_path));
    CHECK(mk_b1_delete(surrogate_dir));
    mk_release(&bmp_path->header);
    mk_release(&supplementary_path->header);
    mk_release(&raw_path->header);
    mk_release(&surrogate_dir->header);
#endif

    /* failures give NULL / false */
    MkString *missing_dir = join_path(root, "missing");
    MkString *missing = join_path(missing_dir, "none.txt");
    CHECK(mk_b1_read_file(missing) == NULL);
    MkString *x = U("x");
    CHECK(!mk_b1_write_file(missing, x));
    CHECK(mk_b1_list_dir(missing_dir) == NULL);
    mk_release(&x->header);
    mk_release(&missing->header);
    mk_release(&missing_dir->header);

    CHECK(mk_b1_delete(path));
    CHECK_EQ_INT(mk_b1_file_kind(path), 0);
    CHECK(mk_b1_delete(top_file));
    CHECK(mk_b1_delete(empty_dir));
    MkString *middle = join_path(nested, "\xe5\xad\x90");
    CHECK(mk_b1_delete(child));
    CHECK(mk_b1_delete(middle));
    CHECK(mk_b1_delete(nested));
    CHECK(mk_b1_delete(root));
    mk_release(&middle->header);
    mk_release(&top_file->header);
    mk_release(&empty_dir->header);
    mk_release(&path->header);
    mk_release(&child->header);
    mk_release(&nested->header);
    mk_release(&root->header);

    /* arguments: program name is dropped */
    char *argv[] = {"prog", "one", "t\xc3\xa4o"};
    mk_b1_set_args(3, argv);
    MkObject *args = mk_b1_args();
    CHECK_EQ_INT(mk_array_length(args), 2);
    MkObject *a0 = mk_array_get_obj(args, 0);
    CHECK(t_str_is((MkString *)a0, "one", 0));
    mk_release(a0);
    MkObject *a1 = mk_array_get_obj(args, 1);
    CHECK(t_str_is((MkString *)a1, "t\xc3\xa4o", 0));
    mk_release(a1);
    mk_release(args);
    mk_b1_set_args(1, argv);
    args = mk_b1_args();
    CHECK_EQ_INT(mk_array_length(args), 0);
    mk_release(args);

#if defined(_WIN32)
    const MkChar wide_program[] = {'p', 0};
    const MkChar wide_argument[] = {'w', 0xd83d, 0xde00, 0xd800, 'z', 0};
    const MkChar *wide_argv[] = {wide_program, wide_argument};
    mk_b1_set_args_utf16(2, wide_argv);
    args = mk_b1_args();
    CHECK_EQ_INT(mk_array_length(args), 1);
    MkObject *wide_value = mk_array_get_obj(args, 0);
    CHECK(wide_value != NULL);
    if (wide_value != NULL) {
        MkString *wide_string = (MkString *)wide_value;
        CHECK_EQ_INT(wide_string->length, 5);
        CHECK_EQ_INT(wide_string->chars[0], 'w');
        CHECK_EQ_INT(wide_string->chars[1], 0xd83d);
        CHECK_EQ_INT(wide_string->chars[2], 0xde00);
        CHECK_EQ_INT(wide_string->chars[3], 0xd800); /* unpaired UTF-16 unit survives */
        CHECK_EQ_INT(wide_string->chars[4], 'z');
        mk_release(wide_value);
    }
    mk_release(args);
    mk_b1_set_args(3, argv); /* UTF-8 setter leaves its existing behavior intact. */
    args = mk_b1_args();
    CHECK_EQ_INT(mk_array_length(args), 2);
    wide_value = mk_array_get_obj(args, 1);
    CHECK(wide_value != NULL && t_str_is((MkString *)wide_value, "t\xc3\xa4o", 0));
    mk_release(wide_value);
    mk_release(args);
#endif

    TEST_DONE();
}
