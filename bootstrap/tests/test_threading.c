#include <unity/unity.h>
#include "mini/project.h"
#include <string.h>
void setUp(void) {}
void tearDown(void) {}
static void check(const char *text, const char *error, bool libuv) {
    MiniSource source = {"threads.mini", "main", text};
    MiniCompileResult r = mini_compile_project(&source, 1, NULL, 0);
    if (error) {
        TEST_ASSERT_FALSE_MESSAGE(r.c_source, "invalid threading program accepted");
        TEST_ASSERT_NOT_NULL(r.message);
        TEST_ASSERT_NOT_NULL_MESSAGE(strstr(r.message, error), r.message);
    } else {
        TEST_ASSERT_NOT_NULL_MESSAGE(r.c_source, r.message);
        TEST_ASSERT_EQUAL(libuv, r.requires_libuv);
    }
    mini_compile_result_destroy(&r);
}
static void test_thread_start_lambda(void) {
    check("class Main { static main() { var code:i32 = 0\n"
          "val handle = runtime.threadStart({ print_i32(42) }, &code) } }",
          NULL, true);
}
static void test_wrong_task_signature(void) {
    check("class Main { static main() { var code:i32 = 0\n"
          "val handle = runtime.threadStart({ n:i32 -> print_i32(n) }, &code) } }",
          "parameter", false);
}
static void test_wrong_status_pointer(void) {
    check("class Main { static main() { val handle = runtime.threadStart({}, 0) } }",
          "type mismatch", false);
}
static void test_optional_handle(void) {
    check("class Main { static use(h:Optional<*u8>) { runtime.threadJoin(h) } }", "type mismatch",
          false);
}
static void test_thread_join(void) {
    check("class Main { static use(h:*u8):i32 = runtime.threadJoin(h) }", NULL, true);
}
static void test_mutex_operations(void) {
    check("class Main { static use(h:*u8) { runtime.mutexLock(h)\nruntime.mutexUnlock(h)\n"
          "val tried = runtime.mutexTryLock(h)\nval closed = runtime.mutexClose(h) } }",
          NULL, true);
}
static void test_sleep(void) {
    check("class Main { static main() { runtime.threadSleep(1) } }", NULL, true);
}
static void test_no_implicit_dependency(void) {
    check("class Main { static main():i32 = 0 }", NULL, false);
}
int main(void) {
    UNITY_BEGIN();
    RUN_TEST(test_thread_start_lambda);
    RUN_TEST(test_wrong_task_signature);
    RUN_TEST(test_wrong_status_pointer);
    RUN_TEST(test_optional_handle);
    RUN_TEST(test_thread_join);
    RUN_TEST(test_mutex_operations);
    RUN_TEST(test_sleep);
    RUN_TEST(test_no_implicit_dependency);
    return UNITY_END();
}
