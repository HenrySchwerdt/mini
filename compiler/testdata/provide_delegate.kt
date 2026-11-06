import kotlin.reflect.KProperty

var log = ""

class Prov(val name: String) {
    operator fun provideDelegate(thisRef: Any?, p: KProperty<*>): Holder {
        log += "provide($name,${p.name});"
        return Holder(name + "!")
    }
}

class Holder(val v: String) {
    operator fun getValue(thisRef: Any?, p: KProperty<*>): String {
        log += "get($v);"
        return v
    }
}

operator fun String.provideDelegate(thisRef: Any?, p: KProperty<*>): String {
    log += "tdf($this);"
    return this
}

operator fun String.getValue(thisRef: Any?, p: KProperty<*>): String {
    log += "sget($this);"
    return this
}

fun trace(s: String): String {
    log += s
    return s
}

class Test {
    val a by Prov("a")
    val b by trace("b;")
    val c = trace("c;")
}

fun main() {
    val unused: () -> Unit = { log += "" }
    unused()
    val t = Test()
    println(log)
    println(t.a + t.b)
    val local by Prov("local")
    println(local)
    println(log)
}
