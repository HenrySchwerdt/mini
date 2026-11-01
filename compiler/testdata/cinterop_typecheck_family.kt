// Repeated type tests use a bounded local family cache; unknown C type descriptors still fall back.
@file:CInclude("cinterop_lib.h")

import kotlinx.cinterop.CInclude
import mini.internal.CSymbol

open class Base
interface RootI
interface MidI : RootI
open class Middle : Base(), MidI
class Child : Middle()
class Leaf : Base()
class Sibling : Base()
class Third : Base()

@CSymbol("foreign_child") external fun foreignChild(value: Leaf): Base

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
    val leaf = Leaf()
    println(check(null))
    println(check(leaf))
    println(check(Child()))
    println(check(Sibling()))
    // The C descriptor has Leaf as its superclass but is not a locally emitted type-info global.
    println(check(foreignChild(leaf)))
}
