// Portable family-cache coverage for native and WASM targets.
// Foreign C descriptors are covered separately by cinterop_typecheck_family.kt.
open class Base
interface RootI
interface MidI : RootI
open class Middle : Base(), MidI
class Child : Middle()
class Leaf : Base()
class Sibling : Base()
class Third : Base()

fun check(value: Base?): Int {
    var result = 0
    if (value is Leaf) result = result + 1
    if (value is Sibling) result = result + 1
    if (value is Third) result = result + 1
    if (value is Middle) result = result + 1
    if (value is Base) result = result + 1
    if (value is RootI) result = result + 1
    return result
}

fun main() {
    println(check(null))
    println(check(Base()))
    println(check(Leaf()))
    println(check(Child()))
    println(check(Sibling()))
    println(check(Third()))
    println(check(Middle()))
}
