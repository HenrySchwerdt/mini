// Callable references to the same function (and equal bound receivers) are equal and have equal hash codes.
fun topLevelFun(): String = ""
var topLevelVar: String = ""

class V {
    val memberVal: String = ""
    fun memberFun(): String = ""
    fun target(x: String = "x", y: String = "y"): String = x + y
}

fun refAgain(): Any = ::topLevelFun

private fun captureNoDefaults(f: (V, String, String) -> String): Any = f
private fun captureOneDefault(f: (V, String) -> String): Any = f
private fun captureBound(f: (String, String) -> String): Any = f

fun interface Runner {
    fun run(): String
}

fun check(name: String, ok: Boolean) {
    println(name + " " + ok)
}

fun main() {
    val v0 = V()
    val v1 = V()
    check("top", ::topLevelFun == refAgain() && refAgain() == ::topLevelFun)
    check("top hash", ::topLevelFun.hashCode() == refAgain().hashCode())
    check("member", V::memberFun == V::memberFun)
    check("bound same", v0::memberFun == v0::memberFun)
    check("bound hash", v0::memberFun.hashCode() == v0::memberFun.hashCode())
    check("bound differs", v0::memberFun != v1::memberFun)
    check("bound vs unbound", v0::memberFun != V::memberFun)
    check("two members", v0::memberFun != (v0::target as Any))
    check("property", ::topLevelVar == ::topLevelVar)
    check("member property", v0::memberVal == v0::memberVal && v0::memberVal != v1::memberVal)
    check("adapted", captureNoDefaults(V::target) == captureNoDefaults(V::target))
    check("adapted differs", captureNoDefaults(V::target) != captureOneDefault(V::target))
    check("adapted bound", captureBound(v0::target) == captureBound(v0::target) && captureBound(v0::target) != captureBound(v1::target))
    check("sam", Runner(v0::memberFun) == Runner(v0::memberFun) && Runner(v0::memberFun) != Runner(v1::memberFun))
    val lambda = { "x" }
    check("lambda", (lambda as Any) == (lambda as Any) && (lambda as Any) != ({ "x" } as Any))
}
