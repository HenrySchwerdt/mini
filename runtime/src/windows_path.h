/* Pure Windows UTF-16 path-root parsing. No Win32 APIs, so the grammar can
 * be tested on every host without accessing a drive or network share. */
#ifndef MINIRT_WINDOWS_PATH_H
#define MINIRT_WINDOWS_PATH_H

#include <stddef.h>
#include <wchar.h>

static inline int mk_windows_path_is_separator(wchar_t c) { return c == L'/' || c == L'\\'; }

static inline int mk_windows_ascii_equal(wchar_t actual, wchar_t expected) {
    if (actual >= L'a' && actual <= L'z') actual -= L'a' - L'A';
    return actual == expected;
}

static inline int mk_windows_unc_root_end(const wchar_t *path, size_t length, size_t start,
                                          size_t *root_end) {
    size_t i = start;
    while (i < length && !mk_windows_path_is_separator(path[i])) i++;
    if (i == start || i == length) return 0;
    size_t share = ++i;
    while (i < length && !mk_windows_path_is_separator(path[i])) i++;
    if (i == share) return 0;
    while (i < length && mk_windows_path_is_separator(path[i])) i++;
    *root_end = i;
    return 1;
}

/* Return the root prefix length, or false for an incomplete/unsupported
 * namespace. UNC roots include following separators so mkdirs skips them. */
static inline int mk_windows_path_root_end(const wchar_t *path, size_t length, size_t *root_end) {
    if (length >= 2 && mk_windows_path_is_separator(path[0]) &&
        mk_windows_path_is_separator(path[1])) {
        if (length >= 4 && (path[2] == L'?' || path[2] == L'.') &&
            mk_windows_path_is_separator(path[3])) {
            if (path[2] != L'?' || length < 8 || !mk_windows_ascii_equal(path[4], L'U') ||
                !mk_windows_ascii_equal(path[5], L'N') || !mk_windows_ascii_equal(path[6], L'C') ||
                !mk_windows_path_is_separator(path[7])) {
                if (path[2] == L'?' && length >= 7 &&
                    ((path[4] >= L'A' && path[4] <= L'Z') ||
                     (path[4] >= L'a' && path[4] <= L'z')) && path[5] == L':' &&
                    mk_windows_path_is_separator(path[6])) {
                    size_t i = 7;
                    while (i < length && mk_windows_path_is_separator(path[i])) i++;
                    *root_end = i;
                    return 1;
                }
                return 0;
            }
            return mk_windows_unc_root_end(path, length, 8, root_end);
        }
        return mk_windows_unc_root_end(path, length, 2, root_end);
    }
    if (length >= 2 && ((path[0] >= L'A' && path[0] <= L'Z') ||
                        (path[0] >= L'a' && path[0] <= L'z')) && path[1] == L':') {
        size_t i = length >= 3 && mk_windows_path_is_separator(path[2]) ? 3 : 2;
        while (i < length && i > 2 && mk_windows_path_is_separator(path[i])) i++;
        *root_end = i;
        return 1;
    }
    size_t i = length > 0 && mk_windows_path_is_separator(path[0]) ? 1 : 0;
    while (i < length && i > 0 && mk_windows_path_is_separator(path[i])) i++;
    *root_end = i;
    return 1;
}

#endif
