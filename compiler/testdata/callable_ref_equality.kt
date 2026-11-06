// equality and hash codes of function and property references: the same target and the same bound receiver

class V {
    val memberVal: String = ""
    var memberVar: Int = 0
    fun memberFun(): String = ""
    fun other(): String = ""
}

val topLevelVal: String = ""
fun topLevelFun(): String = ""
fun withArgs(a: Int, b: String) = a.toString() + b

fun refTopLevelFun() = ::topLevelFun
fun refTopLevelVal() = ::topLevelVal

fun same(x: Any, y: Any): Boolean = x == y && y == x && x.hashCode() == y.hashCode()
fun differ(x: Any, y: Any): Boolean = x != y && y != x

fun main() {
    val v0 = V()
    val v1 = V()
    println(same(::topLevelFun, ::topLevelFun))
    println(same(::topLevelFun, refTopLevelFun()))
    println(same(::topLevelVal, refTopLevelVal()))
    println(same(V::memberFun, V::memberFun))
    println(same(v0::memberFun, v0::memberFun))
    println(same(V::memberVal, V::memberVal))
    println(same(v0::memberVal, v0::memberVal))
    println(same(v0::memberVar, v0::memberVar))
    println(same(::withArgs, ::withArgs))
    println(differ(v0::memberFun, V::memberFun))
    println(differ(v0::memberVal, V::memberVal))
    println(differ(v0::memberFun, v1::memberFun))
    println(differ(v0::memberFun, v0::other))
    println(differ(::topLevelFun, ::withArgs))
    println(differ(v0::memberVar, v1::memberVar))
    fun bar() {}
    fun baz() {}
    println(same(::bar, ::bar))
    println(differ(::bar, ::baz))
    val set = HashSet<Any>()
    set.add(::topLevelFun)
    set.add(refTopLevelFun())
    set.add(v0::memberFun)
    set.add(v0::memberFun)
    println(set.size)
}
