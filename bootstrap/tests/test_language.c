#include <unity/unity.h>
#include "mini/project.h"
#include <string.h>
void setUp(void) {}
void tearDown(void) {}
static void accepts(const char *text) {
    MiniSource source = {"test.mini", "main", text};
    MiniCompileResult r = mini_compile_project(&source, 1, NULL, 0);
    TEST_ASSERT_TRUE_MESSAGE(r.c_source != NULL, r.message);
    mini_compile_result_destroy(&r);
}
static void rejects(const char *text, const char *reason) {
    MiniSource source = {"test.mini", "main", text};
    MiniCompileResult r = mini_compile_project(&source, 1, NULL, 0);
    TEST_ASSERT_TRUE_MESSAGE(r.c_source == NULL, "invalid program was accepted");
    TEST_ASSERT_NOT_NULL(r.message);
    TEST_ASSERT_NOT_NULL_MESSAGE(strstr(r.message, reason), r.message);
    mini_compile_result_destroy(&r);
}
static void test_generic_class_interface(void) {
    accepts("interface Value<T>{get()->T;} class Box<T>:Value<T>{value:T,override get()->T{return "
            "this.value;}} class Main{static id<T>(x:T)->T{return x;}static main()->i32{let "
            "b:Box<i32>=Box<i32>{value:42};return Main.id::<i32>((&b).get());}}");
}
static void test_virtual_inheritance(void) {
    accepts("open class Base{open value()->i32{return 1;}}class Child:Base{override "
            "value()->i32{return super.value()+41;}}class Main{static read(x:*Base)->i32{return "
            "x.value();}static main()->i32{let c:Child=Child{};return Main.read(&c);}}");
}
static void test_final_base(void) {
    rejects("class A{}class B:A{}", "final class");
}
static void test_missing_override(void) {
    rejects("open class A{open f(){}}class B:A{f(){}}", "requires override");
}
static void test_final_method(void) {
    rejects("open class A{f(){}}class B:A{override f(){}}", "final method");
}
static void test_final_override(void) {
    rejects("open class A{open f(){}}open class B:A{final override f(){}}class D:B{override f(){}}",
            "final method");
}
static void test_missing_interface(void) {
    rejects("interface I{f();}class A:I{}", "does not implement");
}
static void test_bad_signature(void) {
    rejects("open class A{open f()->i32{return 1;}}class B:A{override f()->bool{return true;}}",
            "signature");
}
static void test_cycle(void) {
    rejects("open class A:B{}open class B:A{}", "cyclic inheritance");
}
static void test_abstract(void) {
    rejects("abstract class A{abstract f();}class Main{static main(){let a:A=A{};}}",
            "cannot be stored by value");
}
static void test_abstract_implemented(void) {
    accepts("abstract class A{abstract f()->i32;}class B:A{override f()->i32{return 42;}}class "
            "Main{static main()->i32{let b:B=B{};return (&b).f();}}");
}
static void test_wrong_arity(void) {
    rejects("class Box<T>{value:T,}class Main{static main(){let b:Box=Box{};}}", "type arguments");
}
static void test_generic_type_mismatch(void) {
    rejects("class Main{static id<T>(x:T)->T{return x;}static main()->i32{return "
            "Main.id::<bool>(42);}}",
            "type mismatch");
}
static void test_generic_constraint(void) {
    accepts("interface I{n()->i32;}class C:I{override n()->i32{return 7;}}class Main{static "
            "get<T:I>(v:*T)->i32{return v.n();}static main()->i32{let c:C=C{};return "
            "Main.get::<C>(&c);}}");
}
static void test_unsatisfied_constraint(void) {
    rejects("interface I{n()->i32;}class C{}class Main{static get<T:I>(v:*T)->i32{return "
            "v.n();}static main(){let c:C=C{};Main.get::<C>(&c);}}",
            "bound");
}
static void test_nested_generics(void) {
    accepts("class Box<T>{value:T,}class Main{static main()->i32{let "
            "b:Box<Box<i32>>=Box<Box<i32>>{value:Box<i32>{value:42}};return b.value.value;}}");
}
static void test_unused_generic_unknown_type(void) {
    rejects("class Box<T>{value:Missing,}", "unknown declaration");
}
static void test_unused_generic_unknown_variable(void) {
    rejects("class C{static bad<T>(v:T)->T{return missing;}}", "unknown variable");
}
static void test_top_level_forbidden(void) {
    rejects("main()->i32{return 0;}", "inside a class");
}
static void test_top_generic_forbidden(void) {
    rejects("id<T>(x:T)->T{return x;}", "inside a class");
}
static void test_legacy_fn_inside_class(void) {
    accepts("class Main{static fn main()->i32{return 0;}}");
}
static void test_implicit_this(void) {
    accepts("class Box{value:i32,get()->i32{return this.value;}set(value:i32){this.value=value;}}");
}
static void test_this_in_static(void) {
    rejects("class C{static f()->i32{return this.value;}}", "this");
}
static void test_this_reassignment(void) {
    rejects("class C{f(other:*C){this=other;}}", "writable place");
}
static void test_private_access(void) {
    rejects("class C{private f(){}}class Main{static main(){let c:C=C{};(&c).f();}}",
            "private member");
}
static void test_private_inside_class(void) {
    accepts(
        "class C{private n:i32,private get()->i32{return this.n;}read()->i32{return this.get();}}");
}
static void test_protected_access(void) {
    rejects("open class C{protected f(){}}class Main{static main(){let c:C=C{};(&c).f();}}",
            "protected member");
}
static void test_protected_subclass(void) {
    accepts("open class C{protected n:i32,protected f()->i32{return this.n;}}class "
            "D:C{get()->i32{return this.f();}}");
}
static void test_override_visibility(void) {
    rejects("open class C{open f(){}}class D:C{protected override f(){}}", "visibility");
}
static void test_default_conflict(void) {
    rejects("interface A{f()->i32{return 1;}}interface B{f()->i32{return 2;}}class C:A,B{}",
            "conflicting interface defaults");
}
static void test_explicit_default(void) {
    accepts("interface A{f()->i32{return 1;}}interface B{f()->i32{return 2;}}class C:A,B{override "
            "f()->i32{return super<A>.f();}}");
}
static void test_uninitialized(void) {
    rejects("class Main{static main()->i32{let x:i32;return x;}}", "uninitialized");
}
static void test_uninitialized_pointer_write(void) {
    rejects("class Main{static main(){let p:*i32;*p=42;}}", "uninitialized");
}
static void test_branch_initialization(void) {
    accepts("class Main{static main()->i32{let x:i32;if true{x=1;}else{x=2;}return x;}}");
}
static void test_one_branch_initialization(void) {
    rejects("class Main{static main()->i32{let x:i32;if true{x=1;}return x;}}", "uninitialized");
}
static void test_missing_return(void) {
    rejects("class Main{static main()->i32{let x:i32=1;}}", "not all paths");
}
static void test_optional_match(void) {
    accepts("class Main{static main()->i32{let x:Optional<i32>=Optional.Some(42);return match "
            "x{Some(value)=>value,None=>0};}}");
}
static void test_result_match(void) {
    accepts("class Main{static make()->Result<i32,bool>{return Result.Ok(42);}static "
            "main()->i32{return match Main.make(){Ok(value)=>value,Err(error)=>0};}}");
}
static void test_nested_payload(void) {
    accepts("class Main{static main()->i32{let "
            "r:Result<Optional<i32>,bool>=Result.Ok(Optional.Some(42));return match "
            "r{Ok(Some(v))=>v,Ok(None)=>0,Err(error)=>1};}}");
}
static void test_non_exhaustive(void) {
    rejects("class Main{static main()->i32{let x:Optional<i32>=Optional.None;return match "
            "x{Some(v)=>v};}}",
            "exhaustive");
}
static void test_nested_non_exhaustive(void) {
    rejects("class Main{static main()->i32{let x:Optional<bool>=Optional.Some(true);return match "
            "x{Some(true)=>1,None=>0};}}",
            "exhaustive");
}
static void test_duplicate_arm(void) {
    rejects("class Main{static main()->i32{let x:Optional<i32>=Optional.None;return match "
            "x{Some(v)=>v,Some(w)=>w,None=>0};}}",
            "unreachable");
}
static void test_wrong_payload(void) {
    rejects("class Main{static main(){let x:Optional<bool>=Optional.Some(42);}}", "payload");
}
static void test_binding_scope(void) {
    rejects("class Main{static main()->i32{let x:Optional<i32>=Optional.None;let n:i32=match "
            "x{Some(v)=>v,None=>0};return v;}}",
            "unknown variable");
}
static void test_invalid_annotation(void) {
    rejects("@Missing class C{}", "unknown declaration");
}
static void test_annotation(void) {
    accepts("annotation class Marker{value:i32,}@Marker(1)class C{@Deprecated(\"use other\")static "
            "old()->i32{return 1;}static other()->i32{return 2;}}");
}
static void test_annotation_type(void) {
    rejects("annotation class Marker{value:bool,}@Marker(42)class C{}",
            "matching primitive literals");
}
static void test_deprecated_warning(void) {
    MiniSource s = {"warning.mini", "main",
                    "class C{@Deprecated(\"use newer\")static old()->i32{return 1;}static "
                    "newer()->i32{return C.old();}}"};
    MiniCompileResult r = mini_compile_project(&s, 1, NULL, 0);
    TEST_ASSERT_TRUE_MESSAGE(r.c_source != NULL, r.message);
    TEST_ASSERT_NOT_NULL(r.warnings);
    TEST_ASSERT_NOT_NULL(strstr(r.warnings, "use newer"));
    mini_compile_result_destroy(&r);
}
static void test_public_api_leak(void) {
    rejects("internal class Secret{}class C{public static get(x:*Secret){}}", "less-visible");
}
static void test_packages(void) {
    MiniSource ss[] = {
        {"lib.mini", "main",
         "package demo.lib;public class Numbers{internal static offset()->i32{return 2;}public "
         "static answer()->i32{return 40+Numbers.offset();}}"},
        {"app.mini", "main",
         "package demo.app;import demo.lib.Numbers as Math;class Main{static main()->i32{return "
         "Math.answer();}}"}};
    MiniCompileResult r = mini_compile_project(ss, 2, NULL, 0);
    TEST_ASSERT_TRUE_MESSAGE(r.c_source != NULL, r.message);
    mini_compile_result_destroy(&r);
}
static void test_internal_boundary(void) {
    const char *deps[] = {"lib"};
    MiniModule ms[] = {{"app", deps, 1}, {"lib", NULL, 0}};
    MiniSource ss[] = {{"lib.mini", "lib", "package lib;internal class Secret{}"},
                       {"app.mini", "app", "import lib.Secret;class C{}"}};
    MiniCompileResult r = mini_compile_project(ss, 2, ms, 2);
    TEST_ASSERT_TRUE(r.c_source == NULL);
    TEST_ASSERT_NOT_NULL(strstr(r.message, "visible"));
    mini_compile_result_destroy(&r);
}
static void test_module_cycle(void) {
    const char *a[] = {"b"}, *b[] = {"a"};
    MiniModule ms[] = {{"a", a, 1}, {"b", b, 1}};
    MiniSource s = {"test.mini", "a", "class C{}"};
    MiniCompileResult r = mini_compile_project(&s, 1, ms, 2);
    TEST_ASSERT_TRUE(r.c_source == NULL);
    TEST_ASSERT_NOT_NULL(strstr(r.message, "cyclic module"));
    mini_compile_result_destroy(&r);
}
int main(void) {
    UNITY_BEGIN();
#define RUN(name) RUN_TEST(test_##name)
    RUN(generic_class_interface);
    RUN(virtual_inheritance);
    RUN(final_base);
    RUN(missing_override);
    RUN(final_method);
    RUN(final_override);
    RUN(missing_interface);
    RUN(bad_signature);
    RUN(cycle);
    RUN(abstract);
    RUN(abstract_implemented);
    RUN(wrong_arity);
    RUN(generic_type_mismatch);
    RUN(generic_constraint);
    RUN(unsatisfied_constraint);
    RUN(nested_generics);
    RUN(unused_generic_unknown_type);
    RUN(unused_generic_unknown_variable);
    RUN(top_level_forbidden);
    RUN(top_generic_forbidden);
    RUN(legacy_fn_inside_class);
    RUN(implicit_this);
    RUN(this_in_static);
    RUN(this_reassignment);
    RUN(private_access);
    RUN(private_inside_class);
    RUN(protected_access);
    RUN(protected_subclass);
    RUN(override_visibility);
    RUN(default_conflict);
    RUN(explicit_default);
    RUN(uninitialized);
    RUN(uninitialized_pointer_write);
    RUN(branch_initialization);
    RUN(one_branch_initialization);
    RUN(missing_return);
    RUN(optional_match);
    RUN(result_match);
    RUN(nested_payload);
    RUN(non_exhaustive);
    RUN(nested_non_exhaustive);
    RUN(duplicate_arm);
    RUN(wrong_payload);
    RUN(binding_scope);
    RUN(invalid_annotation);
    RUN(annotation);
    RUN(annotation_type);
    RUN(deprecated_warning);
    RUN(public_api_leak);
    RUN(packages);
    RUN(internal_boundary);
    RUN(module_cycle);
    return UNITY_END();
}
