#include <unity/unity.h>
#include "mini/project.h"
#include <string.h>
void setUp(void) {}
void tearDown(void) {}
static void check(const char *text, const char *error) {
    MiniSource source = {"memory.mini", "main", text};
    MiniCompileResult r = mini_compile_project(&source, 1, NULL, 0);
    if (error) {
        TEST_ASSERT_FALSE_MESSAGE(r.c_source, "invalid nullable code accepted");
        TEST_ASSERT_NOT_NULL(r.message);
        TEST_ASSERT_NOT_NULL_MESSAGE(strstr(r.message, error), r.message);
    } else
        TEST_ASSERT_NOT_NULL_MESSAGE(r.c_source, r.message);
    mini_compile_result_destroy(&r);
}
static void test_null_literal_rejected(void) {
    check("class Main { static main() { val p:*u8 = null } }", "Optional");
}
static void test_null_comparison_rejected(void) {
    check("class Main { static use(p:*u8):bool = p == null }", "Optional");
}
static void test_null_optional_payload_rejected(void) {
    check("class Main { static main() { val p:Optional<*u8> = Optional.Some(null) } }", "Optional");
}
static void test_allocation_returns_optional(void) {
    check("class Main { static main() { val p:Optional<*u8> = heap.alloc(8)\n"
          "match p { Some(memory) => heap.free(memory), None => print_i32(0) } } }",
          NULL);
}
static void test_allocation_requires_unwrap(void) {
    check("class Main { static main() { val p = heap.alloc(8)\n*p = 1 } }", "pointer");
}
static void test_optional_cannot_be_freed(void) {
    check("class Main { static main() { heap.free(heap.alloc(8)) } }", "type mismatch");
}
static void test_reallocation_returns_optional(void) {
    check("class Main { static use(p:*u8):Optional<*u8> = heap.realloc(p, 16) }", NULL);
}
static void test_optional_pointer_field(void) {
    check("class A { val pointer:Optional<*u8> } class Main { static main() { "
          "val a = A { pointer: Optional.None } } }",
          NULL);
}
static void test_borrowed_pointer_is_present(void) {
    check("class Main { static main() { var n:i32 = 42\nval p:Optional<*i32> = Optional.Some(&n)\n"
          "val value = match p { Some(pointer) => *pointer, None => 0 } } }",
          NULL);
}
static void test_zero_pointer_cast_rejected(void) {
    check("class Main { static main() { val p = cast<*u8>(cast<usize>(0)) } }", "zero address");
}
int main(void) {
    UNITY_BEGIN();
    RUN_TEST(test_null_literal_rejected);
    RUN_TEST(test_null_comparison_rejected);
    RUN_TEST(test_null_optional_payload_rejected);
    RUN_TEST(test_allocation_returns_optional);
    RUN_TEST(test_allocation_requires_unwrap);
    RUN_TEST(test_optional_cannot_be_freed);
    RUN_TEST(test_reallocation_returns_optional);
    RUN_TEST(test_optional_pointer_field);
    RUN_TEST(test_borrowed_pointer_is_present);
    RUN_TEST(test_zero_pointer_cast_rejected);
    return UNITY_END();
}
