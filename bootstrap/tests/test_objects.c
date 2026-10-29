#include <unity/unity.h>
#include "mini/project.h"
#include <string.h>
void setUp(void) {}
void tearDown(void) {}
static const char *memory = "package mini.memory\npublic enum AllocationError { OutOfMemory }\n"
                            "public interface Allocator { allocate(bytes: usize): Result<*u8, "
                            "AllocationError>\nrelease(memory: *u8) }";
static void check(const char *text, const char *error) {
    MiniSource sources[] = {{"objects.mini", "main", text}, {"memory.mini", "main", memory}};
    MiniCompileResult r = mini_compile_project(sources, 2, NULL, 0);
    if (error) {
        TEST_ASSERT_FALSE_MESSAGE(r.c_source, "invalid object program accepted");
        TEST_ASSERT_NOT_NULL(r.message);
        TEST_ASSERT_NOT_NULL_MESSAGE(strstr(r.message, error), r.message);
    } else
        TEST_ASSERT_NOT_NULL_MESSAGE(r.c_source, r.message);
    mini_compile_result_destroy(&r);
}
static void test_implicit_object(void) {
    check("class A {} class Main { static view(a: *Object) {} static main() { val a = A "
          "{}\nMain.view(&a) } }",
          NULL);
}
static void test_allocatable_root(void) {
    check("class A : AllocatableObject {} class Main { static main() { val allocation = "
          "A.allocate(A {})\nmatch allocation { Ok(p) => A.free(p), Err(e) => print_i32(0) } } }",
          NULL);
}
static void test_custom_allocator(void) {
    check("import mini.memory.Allocator\nclass A : AllocatableObject {} class Main { static use(a: "
          "*Allocator) { val allocation = A.allocate(A {}, a)\nmatch allocation { Ok(p) => "
          "A.free(p, a), Err(e) => print_i32(0) } } }",
          NULL);
}
static void test_inherited_allocation(void) {
    check("open class A : AllocatableObject {} class B : A {} class Main { static use() { val r = "
          "B.allocate(B {})\nmatch r { Ok(p) => B.free(p), Err(e) => print_i32(0) } } }",
          NULL);
}
static void test_stack_only_allocation(void) {
    check("class A {} class Main { static use() { val r = A.allocate(A {}) } }",
          "AllocatableObject");
}
static void test_embedded_stack_only(void) {
    check("class Stack {} class A : AllocatableObject { val inner: Stack } class Main { static "
          "use() { val r = A.allocate(A { inner: Stack {} }) } }",
          "stack-only");
}
static void test_embedded_variant(void) {
    check("class Stack {} class A : AllocatableObject { val inner: Optional<Stack> } class Main { "
          "static use() { val r = A.allocate(A { inner: Optional.None }) } }",
          "stack-only");
}
static void test_raw_cast_bypass(void) {
    check("class Stack {} class Main { static use(p: *u8) { val s = cast<*Stack>(p) } }",
          "stack-only");
}
static void test_stack_only_pointer_field(void) {
    check("class Stack {} class A : AllocatableObject { val inner: *Stack } class Main { static "
          "use() { val stack = Stack {}\nval r = A.allocate(A { inner: &stack })\nmatch r { Ok(p) "
          "=> A.free(p), Err(e) => "
          "print_i32(0) } } }",
          NULL);
}
static void test_auto_borrow(void) {
    check("class A { var x:i32\nbump():i32 { this.x = this.x + 1\nreturn this.x } } class Main { "
          "static main():i32 { val a = A { x:41 }\nreturn a.bump() } }",
          NULL);
}
static void test_borrow_temporary(void) {
    check("class A { value():i32 = 42 } class Main { static main():i32 = (A {}).value() }", NULL);
}
static void test_borrow_uninitialized(void) {
    check("class A { value():i32 = 42 } class Main { static main():i32 { val a:A\nreturn a.value() "
          "} }",
          "uninitialized");
}
static void test_class_bound(void) {
    check("class A : AllocatableObject {} class Util { static id<T: AllocatableObject>(value:T):T "
          "= value } class Main { static use() { val a = Util.id<A>(A {}) } }",
          NULL);
}
static void test_class_bound_rejection(void) {
    check("class A {} class Util { static id<T: AllocatableObject>(value:T):T = value } class Main "
          "{ static use() { val a = Util.id<A>(A {}) } }",
          "bound");
}
static void test_reserved_root(void) {
    check("class Object {}", "reserved");
}
static void test_reserved_factory(void) {
    check("class A : AllocatableObject { static allocate() {} }", "reserved");
}
static void test_generic_allocation(void) {
    check("class Box<T> : AllocatableObject { val value:T } class Main { static use() { "
          "val r = Box<i32>.allocate(Box<i32> { value:42 })\n"
          "match r { Ok(p) => Box<i32>.free(p), Err(e) => print_i32(0) } } }",
          NULL);
}
static void test_exact_allocation_type(void) {
    check("open class A : AllocatableObject {} class B : A {} class Main { static use() { "
          "val r = A.allocate(B {}) } }",
          "exact concrete");
}
static void test_exact_free_type(void) {
    check("open class A : AllocatableObject {} class B : A {} class Main { "
          "static use(p:*B) { A.free(p) } }",
          "exact concrete");
}
static void test_allocation_argument_count(void) {
    check("class A : AllocatableObject {} class Main { static use() { A.allocate() } }", "require");
}
static void test_nested_raw_cast_bypass(void) {
    check("class Stack {} class Main { static use(p:*u8) { val a = cast<*Optional<Stack>>(p) } }",
          "stack-only");
}
static void test_explicit_root_import(void) {
    check("import mini.core.Object\nimport mini.core.AllocatableObject\nclass A : "
          "AllocatableObject {}",
          NULL);
}
static void test_stack_pointer_upcast(void) {
    check("class A {} class Main { static use(p:*A):*Object = cast<*Object>(p) }", NULL);
}
static void test_interface_only_root(void) {
    check("interface I {} class A : I {} class Main { static view(p:*Object) {} "
          "static use() { val a = A {}\nMain.view(&a) } }",
          NULL);
}
static void test_recursive_heap_layout(void) {
    check("class Loop : AllocatableObject { val nested:Loop } class Main { "
          "static use(p:*u8) { val loop = cast<*Loop>(p) } }",
          "recursive");
}
int main(void) {
    UNITY_BEGIN();
    RUN_TEST(test_implicit_object);
    RUN_TEST(test_allocatable_root);
    RUN_TEST(test_custom_allocator);
    RUN_TEST(test_inherited_allocation);
    RUN_TEST(test_stack_only_allocation);
    RUN_TEST(test_embedded_stack_only);
    RUN_TEST(test_embedded_variant);
    RUN_TEST(test_raw_cast_bypass);
    RUN_TEST(test_stack_only_pointer_field);
    RUN_TEST(test_auto_borrow);
    RUN_TEST(test_borrow_temporary);
    RUN_TEST(test_borrow_uninitialized);
    RUN_TEST(test_class_bound);
    RUN_TEST(test_class_bound_rejection);
    RUN_TEST(test_reserved_root);
    RUN_TEST(test_reserved_factory);
    RUN_TEST(test_generic_allocation);
    RUN_TEST(test_exact_allocation_type);
    RUN_TEST(test_exact_free_type);
    RUN_TEST(test_allocation_argument_count);
    RUN_TEST(test_nested_raw_cast_bypass);
    RUN_TEST(test_explicit_root_import);
    RUN_TEST(test_stack_pointer_upcast);
    RUN_TEST(test_interface_only_root);
    RUN_TEST(test_recursive_heap_layout);
    return UNITY_END();
}
