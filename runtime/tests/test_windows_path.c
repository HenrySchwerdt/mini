#include "harness.h"

#include "../src/windows_path.h"

static void check_root(const wchar_t *path, const wchar_t *expected_prefix) {
    size_t root = SIZE_MAX;
    if (!mk_windows_path_root_end(path, wcslen(path), &root)) {
        CHECK(0);
        return;
    }
    size_t expected_length = wcslen(expected_prefix);
    if (root != expected_length) {
        CHECK_EQ_INT(root, expected_length);
        return;
    }
    CHECK_EQ_INT(root, expected_length);
    CHECK(wcsncmp(path, expected_prefix, root) == 0);
}

static void check_bad_root(const wchar_t *path) {
    size_t root = SIZE_MAX;
    CHECK(!mk_windows_path_root_end(path, wcslen(path), &root));
}

int main(void) {
    check_root(L"C:\\", L"C:\\");
    check_root(L"C:/folder/file", L"C:/");
    check_root(L"C:\\\\folder", L"C:\\\\");
    check_root(L"C:", L"C:");
    check_root(L"C:folder\\file", L"C:");
    check_root(L"\\folder\\file", L"\\");
    check_root(L"relative/folder", L"");

    const wchar_t *unc = L"\\\\server\\share\\folder\\file";
    check_root(unc, L"\\\\server\\share\\");
    check_root(L"\\\\server\\share", L"\\\\server\\share");
    check_root(L"\\\\server\\share\\\\", L"\\\\server\\share\\\\");
    check_root(L"//server\\share/folder", L"//server\\share/");

    check_root(L"\\\\?\\C:\\folder", L"\\\\?\\C:\\");
    check_root(L"\\\\?\\C:\\\\folder", L"\\\\?\\C:\\\\");
    check_root(L"\\\\?\\UNC\\server\\share\\folder", L"\\\\?\\UNC\\server\\share\\");
    check_root(L"\\\\?\\UNC\\server\\share\\\\folder", L"\\\\?\\UNC\\server\\share\\\\");
    check_root(L"\\\\?\\unc\\server\\share", L"\\\\?\\unc\\server\\share");

    check_bad_root(L"\\\\server");
    check_bad_root(L"\\\\server\\");
    check_bad_root(L"\\\\server\\\\share");
    check_bad_root(L"\\\\server//share");
    check_bad_root(L"\\\\?\\UNC\\server");
    check_bad_root(L"\\\\.\\pipe\\name");
    check_bad_root(L"\\\\?\\GLOBALROOT\\Device");
    TEST_DONE();
}
