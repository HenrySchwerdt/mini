/* B1: file access and program arguments for the self-hosted backend driver
 * (stdlib/mini/internal/B1Intrinsics.kt). Convention: arguments are borrowed,
 * reference results are owned (+1). */
#if !defined(_WIN32)
#define _POSIX_C_SOURCE 200809L
#endif
#include "internal.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <wchar.h>
#include "windows_path.h"
#else
#include <dirent.h>
#include <sys/stat.h>
#endif

#if defined(_WIN32)
/* MkChar and Windows WCHAR are both UTF-16 code units. Keep filesystem paths
 * wide all the way to the Win32 / UCRT call so non-ASCII names do not depend
 * on the active ANSI code page. */
_Static_assert(sizeof(wchar_t) == sizeof(MkChar), "Windows WCHAR must match UTF-16 MkChar width");

static wchar_t *path_to_wide(const MkString *path) {
    if (path == NULL) mk_panic("NullPointerException");
    if (path->length < 0 || (size_t)path->length > SIZE_MAX / sizeof(wchar_t) - 1)
        mk_panic("OutOfMemoryError");
    size_t n = (size_t)path->length;
    wchar_t *wide = malloc((n + 1) * sizeof(*wide));
    if (wide == NULL) mk_panic("OutOfMemoryError");
    for (size_t i = 0; i < n; i++) {
        MkChar c = mk_string_at(path, (MkInt)i);
        wide[i] = c == '/' ? L'\\' : (wchar_t)c;
    }
    wide[n] = L'\0';
    return wide;
}

static int win_is_directory(const wchar_t *path) {
    DWORD attributes = GetFileAttributesW(path);
    return attributes != INVALID_FILE_ATTRIBUTES && (attributes & FILE_ATTRIBUTE_DIRECTORY) != 0;
}

static int win_mkdir_one(const wchar_t *path) {
    if (CreateDirectoryW(path, NULL)) return 1;
    return GetLastError() == ERROR_ALREADY_EXISTS && win_is_directory(path);
}

typedef struct WinDirName {
    wchar_t *units;
    size_t length;
} WinDirName;

static uint32_t win_name_code_point(const wchar_t *units, size_t length, size_t *index) {
    uint32_t first = (uint16_t)units[(*index)++];
    if (first >= 0xd800 && first <= 0xdbff && *index < length) {
        uint32_t second = (uint16_t)units[*index];
        if (second >= 0xdc00 && second <= 0xdfff) {
            (*index)++;
            return 0x10000 + ((first - 0xd800) << 10) + (second - 0xdc00);
        }
    }
    return first;
}

static int win_compare_names(const void *left, const void *right) {
    const WinDirName *a = left, *b = right;
    size_t ai = 0, bi = 0;
    while (ai < a->length && bi < b->length) {
        uint32_t ac = win_name_code_point(a->units, a->length, &ai);
        uint32_t bc = win_name_code_point(b->units, b->length, &bi);
        if (ac != bc) return ac < bc ? -1 : 1;
    }
    return ai < a->length ? 1 : bi < b->length ? -1 : 0;
}

static void win_free_names(WinDirName *names, size_t count) {
    for (size_t i = 0; i < count; i++) free(names[i].units);
    free(names);
}

static MkString *win_list_dir(const MkString *path) {
    wchar_t *pattern = path_to_wide(path);
    size_t path_length = wcslen(pattern);
    if (path_length == 0 || path_length > SIZE_MAX / sizeof(*pattern) - 3) {
        free(pattern);
        return NULL;
    }
    int has_separator = mk_windows_path_is_separator(pattern[path_length - 1]);
    wchar_t *grown = realloc(pattern, (path_length + (has_separator ? 2 : 3)) * sizeof(*pattern));
    if (grown == NULL) { free(pattern); mk_panic("OutOfMemoryError"); }
    pattern = grown;
    if (!has_separator) pattern[path_length++] = L'\\';
    pattern[path_length++] = L'*';
    pattern[path_length] = L'\0';

    WIN32_FIND_DATAW entry;
    HANDLE find = FindFirstFileW(pattern, &entry);
    DWORD find_error = find == INVALID_HANDLE_VALUE ? GetLastError() : ERROR_SUCCESS;
    free(pattern);
    if (find == INVALID_HANDLE_VALUE) {
        wchar_t *directory = path_to_wide(path);
        int empty_directory = find_error == ERROR_FILE_NOT_FOUND && win_is_directory(directory);
        free(directory);
        return empty_directory ? mk_string_alloc(0) : NULL;
    }

    size_t cap = 64, count = 0, total_units = 0;
    WinDirName *names = malloc(cap * sizeof(*names));
    if (names == NULL) { FindClose(find); mk_panic("OutOfMemoryError"); }
    do {
        if (wcscmp(entry.cFileName, L".") == 0 || wcscmp(entry.cFileName, L"..") == 0) continue;
        size_t length = wcslen(entry.cFileName);
        size_t separator_units = count > 0 ? 1 : 0;
        if (total_units > (size_t)INT32_MAX || length > (size_t)INT32_MAX - total_units ||
            separator_units > (size_t)INT32_MAX - total_units - length) {
            FindClose(find);
            win_free_names(names, count);
            mk_panic("string too long");
        }
        if (count == cap) {
            if (cap > SIZE_MAX / 2 / sizeof(*names)) {
                FindClose(find);
                win_free_names(names, count);
                mk_panic("OutOfMemoryError");
            }
            cap *= 2;
            WinDirName *next = realloc(names, cap * sizeof(*names));
            if (next == NULL) {
                FindClose(find);
                win_free_names(names, count);
                mk_panic("OutOfMemoryError");
            }
            names = next;
        }
        size_t bytes = (length + 1) * sizeof(*entry.cFileName);
        wchar_t *copy = malloc(bytes);
        if (copy == NULL) {
            FindClose(find);
            win_free_names(names, count);
            mk_panic("OutOfMemoryError");
        }
        memcpy(copy, entry.cFileName, bytes);
        names[count++] = (WinDirName){copy, length};
        total_units += separator_units + length;
    } while (FindNextFileW(find, &entry));
    DWORD next_error = GetLastError();
    FindClose(find);
    if (next_error != ERROR_NO_MORE_FILES) {
        win_free_names(names, count);
        return NULL;
    }

    qsort(names, count, sizeof(*names), win_compare_names);
    MkString *result = mk_string_alloc((MkInt)total_units);
    size_t offset = 0;
    for (size_t i = 0; i < count; i++) {
        if (i > 0) result->chars[offset++] = '\n';
        for (size_t j = 0; j < names[i].length; j++) result->chars[offset++] = (MkChar)names[i].units[j];
    }
    win_free_names(names, count);
    return result;
}
#else
/* The path as a NUL-terminated UTF-8 buffer (malloc'ed). */
static char *path_to_c(const MkString *path) {
    if (path == NULL) mk_panic("NullPointerException");
    return mk_string_to_utf8(path, NULL);
}
#endif

/* Reads the whole file into a malloc'ed buffer (the caller frees it); NULL when it cannot be opened or read. */
static char *b1_slurp(const MkString *path, size_t *out_len) {
#if defined(_WIN32)
    wchar_t *p = path_to_wide(path);
    FILE *f = _wfopen(p, L"rb");
#else
    char *p = path_to_c(path);
    FILE *f = fopen(p, "rb");
#endif
    free(p);
    if (f == NULL) return NULL;
    size_t cap = 4096, len = 0;
    char *buf = malloc(cap);
    if (buf == NULL) mk_panic("OutOfMemoryError");
    for (;;) {
        if (len == cap) {
            cap *= 2;
            char *nb = realloc(buf, cap);
            if (nb == NULL) mk_panic("OutOfMemoryError");
            buf = nb;
        }
        size_t n = fread(buf + len, 1, cap - len, f);
        len += n;
        if (n == 0) break;
    }
    int failed = ferror(f);
    fclose(f);
    if (failed) {
        free(buf);
        return NULL;
    }
    *out_len = len;
    return buf;
}

MkString *mk_b1_read_file(const MkString *path) {
    size_t len = 0;
    char *buf = b1_slurp(path, &len);
    if (buf == NULL) return NULL;
    MkString *s = mk_string_from_utf8(buf, len);
    free(buf);
    return s;
}

MkObject *mk_b1_read_bytes(const MkString *path) {
    size_t len = 0;
    char *buf = b1_slurp(path, &len);
    if (buf == NULL) return NULL;
    if (len > (size_t)INT32_MAX) mk_panic("OutOfMemoryError");
    MkObject *array = mk_array_new_byte((MkInt)len);
    if (len > 0) memcpy(MK_ARRAY_ELEMS(MkByte, array), buf, len);
    free(buf);
    return array;
}

MkBoolean mk_b1_write_bytes(const MkString *path, const MkObject *bytes, MkBoolean append) {
    if (bytes == NULL) mk_panic("NullPointerException");
#if defined(_WIN32)
    wchar_t *p = path_to_wide(path);
    FILE *f = _wfopen(p, append ? L"ab" : L"wb");
#else
    char *p = path_to_c(path);
    FILE *f = fopen(p, append ? "ab" : "wb");
#endif
    free(p);
    if (f == NULL) return 0;
    size_t len = (size_t)((const MkArray *)bytes)->length;
    size_t written = len == 0 ? 0 : fwrite(MK_ARRAY_ELEMS(MkByte, bytes), 1, len, f);
    int ok = written == len;
    if (fclose(f) != 0) ok = 0;
    return ok ? 1 : 0;
}

/* what 0: size in bytes, 1: last modification time in milliseconds since the epoch; -1 when the file does not exist. */
MkLong mk_b1_file_stat(const MkString *path, MkInt what) {
#if defined(_WIN32)
    wchar_t *p = path_to_wide(path);
    WIN32_FILE_ATTRIBUTE_DATA data;
    BOOL ok = GetFileAttributesExW(p, GetFileExInfoStandard, &data);
    free(p);
    if (!ok) return -1;
    if (what == 0) return (MkLong)(((uint64_t)data.nFileSizeHigh << 32) | data.nFileSizeLow);
    uint64_t t = ((uint64_t)data.ftLastWriteTime.dwHighDateTime << 32) | data.ftLastWriteTime.dwLowDateTime;
    return (MkLong)((t - 116444736000000000ULL) / 10000ULL);
#else
    char *p = path_to_c(path);
    struct stat st;
    int rc = stat(p, &st);
    free(p);
    if (rc != 0) return -1;
    if (what == 0) return (MkLong)st.st_size;
#if defined(__APPLE__)
    return (MkLong)st.st_mtimespec.tv_sec * 1000 + (MkLong)(st.st_mtimespec.tv_nsec / 1000000);
#else
    return (MkLong)st.st_mtim.tv_sec * 1000 + (MkLong)(st.st_mtim.tv_nsec / 1000000);
#endif
#endif
}

MkBoolean mk_b1_write_file(const MkString *path, const MkString *text) {
    if (text == NULL) mk_panic("NullPointerException");
#if defined(_WIN32)
    wchar_t *p = path_to_wide(path);
    FILE *f = _wfopen(p, L"wb");
#else
    char *p = path_to_c(path);
    FILE *f = fopen(p, "wb");
#endif
    free(p);
    if (f == NULL) return 0;
    size_t len = 0;
    char *u = mk_string_to_utf8(text, &len);
    size_t written = len == 0 ? 0 : fwrite(u, 1, len, f);
    free(u);
    int ok = written == len;
    if (fclose(f) != 0) ok = 0;
    return ok ? 1 : 0;
}

MkInt mk_b1_file_kind(const MkString *path) {
#if defined(_WIN32)
    wchar_t *p = path_to_wide(path);
    DWORD attributes = GetFileAttributesW(p);
    free(p);
    if (attributes == INVALID_FILE_ATTRIBUTES) return 0;
    return (attributes & FILE_ATTRIBUTE_DIRECTORY) != 0 ? 2 : 1;
#else
    char *p = path_to_c(path);
    struct stat st;
    int rc = stat(p, &st);
    free(p);
    if (rc != 0) return 0;
    return S_ISDIR(st.st_mode) ? 2 : 1;
#endif
}

#if !defined(_WIN32)
static int b1_cmp_names(const void *a, const void *b) {
    return strcmp(*(char *const *)a, *(char *const *)b);
}
#endif

MkString *mk_b1_list_dir(const MkString *path) {
#if defined(_WIN32)
    return win_list_dir(path);
#else
    char *p = path_to_c(path);
    DIR *d = opendir(p);
    free(p);
    if (d == NULL) return NULL;
    size_t cap = 64, n = 0, total = 0;
    char **names = malloc(cap * sizeof(char *));
    if (names == NULL) mk_panic("OutOfMemoryError");
    struct dirent *e;
    while ((e = readdir(d)) != NULL) {
        if (strcmp(e->d_name, ".") == 0 || strcmp(e->d_name, "..") == 0) continue;
        if (n == cap) {
            cap *= 2;
            char **nn = realloc(names, cap * sizeof(char *));
            if (nn == NULL) mk_panic("OutOfMemoryError");
            names = nn;
        }
        size_t l = strlen(e->d_name);
        names[n] = malloc(l + 1);
        if (names[n] == NULL) mk_panic("OutOfMemoryError");
        memcpy(names[n], e->d_name, l + 1);
        total += l + 1;
        n++;
    }
    closedir(d);
    qsort(names, n, sizeof(char *), b1_cmp_names);
    char *buf = malloc(total + 1);
    if (buf == NULL) mk_panic("OutOfMemoryError");
    size_t len = 0;
    for (size_t i = 0; i < n; i++) {
        size_t l = strlen(names[i]);
        memcpy(buf + len, names[i], l);
        len += l;
        if (i + 1 < n) buf[len++] = '\n';
        free(names[i]);
    }
    free(names);
    MkString *s = mk_string_from_utf8(buf, len);
    free(buf);
    return s;
#endif
}

MkBoolean mk_b1_mkdirs(const MkString *path) {
#if defined(_WIN32)
    wchar_t *p = path_to_wide(path);
    size_t length = wcslen(p), root_end = 0;
    if (length == 0 || !mk_windows_path_root_end(p, length, &root_end)) { free(p); return 0; }
    for (size_t i = root_end; i <= length; i++) {
        if (i < length && !mk_windows_path_is_separator(p[i])) continue;
        if (i <= root_end) continue;
        wchar_t saved = p[i];
        p[i] = L'\0';
        int ok = win_mkdir_one(p);
        p[i] = saved;
        if (!ok) { free(p); return 0; }
    }
    int ok = win_is_directory(p);
    free(p);
    return ok ? 1 : 0;
#else
    char *p = path_to_c(path);
    size_t n = strlen(p);
    for (size_t i = 1; i <= n; i++) {
        if (p[i] != '/' && p[i] != '\0') continue;
        char saved = p[i];
        p[i] = '\0';
        if (mkdir(p, 0777) != 0 && errno != EEXIST) { free(p); return 0; }
        p[i] = saved;
    }
    struct stat st;
    int ok = stat(p, &st) == 0 && S_ISDIR(st.st_mode);
    free(p);
    return ok ? 1 : 0;
#endif
}

MkBoolean mk_b1_delete(const MkString *path) {
#if defined(_WIN32)
    wchar_t *p = path_to_wide(path);
    DWORD attributes = GetFileAttributesW(p);
    BOOL deleted = attributes != INVALID_FILE_ATTRIBUTES &&
                   ((attributes & FILE_ATTRIBUTE_DIRECTORY) != 0 ? RemoveDirectoryW(p) : DeleteFileW(p));
    free(p);
    return deleted ? 1 : 0;
#else
    char *p = path_to_c(path);
    int rc = remove(p);
    free(p);
    return rc == 0 ? 1 : 0;
#endif
}

static int b1_argc = 0;
static char **b1_argv = NULL;
#if defined(_WIN32)
static const MkChar *const *b1_wargv = NULL;
#endif

void mk_b1_set_args(int argc, char **argv) {
    b1_argc = argc;
    b1_argv = argv;
#if defined(_WIN32)
    b1_wargv = NULL;
#endif
}

#if defined(_WIN32)
void mk_b1_set_args_utf16(int argc, const MkChar *const *argv) {
    b1_argc = argc;
    b1_argv = NULL;
    b1_wargv = argv;
}
#endif

MkObject *mk_b1_args(void) {
    MkInt n = b1_argc > 1 ? (MkInt)(b1_argc - 1) : 0;
    MkObject *array = mk_array_new_of(n, &mk_type_String, 0);
    for (MkInt i = 0; i < n; i++) {
#if defined(_WIN32)
        if (b1_wargv != NULL) {
            const MkChar *arg = b1_wargv[i + 1];
            size_t length = 0;
            while (arg[length] != 0) length++;
            if (length > (size_t)INT32_MAX) mk_panic("string too long");
            MkString *s = mk_string_alloc((MkInt)length);
            if (length > 0) memcpy(s->chars, arg, length * sizeof(MkChar));
            mk_array_set_obj(array, i, &s->header);
            mk_release(&s->header);
            continue;
        }
#endif
        const char *a = b1_argv[i + 1];
        MkString *s = mk_string_from_utf8(a, strlen(a));
        mk_array_set_obj(array, i, &s->header);
        mk_release(&s->header);
    }
    return array;
}

/* Writes `text` (UTF-8) to standard error, after flushing the buffered standard output so the two streams stay in order. */
void mk_b1_eprint(const MkString *text) {
    if (text == NULL) mk_panic("NullPointerException");
    mk_runtime_flush();
    char *u = mk_string_to_utf8(text, NULL);
    fputs(u, stderr);
    free(u);
    fflush(stderr);
}

/* Ends the process with `status` (standard output flushed first). */
void mk_b1_exit(MkInt status) {
    mk_runtime_flush();
    fflush(stderr);
    exit((int)status);
}
