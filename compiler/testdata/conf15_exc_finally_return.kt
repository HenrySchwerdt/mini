// try/finally with return, break, continue; finally overriding results; nested
fun f1(): Int { try { return 1 } finally { println("f1 finally") } }
fun f2(): Int { try { return 1 } finally { return 2 } }
fun f3(): Int { var x = 0; try { x = 1; return x } finally { x = 5; println("x=" + x) } }
fun f4(): String { try { throw RuntimeException("a") } catch (e: RuntimeException) { return "caught" } finally { println("f4 fin") } }
fun f5(): Int { for (i in 1..3) { try { if (i == 2) continue; if (i == 3) break; println("body " + i) } finally { println("fin " + i) } }; return 0 }
fun f6(): Int { try { try { throw IllegalStateException("in") } finally { println("inner fin") } } catch (e: IllegalStateException) { println("outer " + e.message); return 6 } }
fun f7(): Int { try { try { return 7 } finally { println("a") } } finally { println("b") } }
fun f8(): String { val sb = StringBuilder(); try { sb.append("t"); throw Exception("x") } catch (e: Exception) { sb.append("c") } finally { sb.append("f") }; return sb.toString() }
fun f9(): Int { try { throw RuntimeException("first") } finally { try { println("cleanup") } catch (e: Exception) { } } }
fun f10(): Int { var r = 0; while (true) { try { r++; if (r < 3) continue; return r } finally { println("w" + r) } } }
fun main() {
    println(f1()); println(f2()); println(f3()); println(f4()); println(f5()); println(f6()); println(f7()); println(f8())
    try { f9() } catch (e: RuntimeException) { println(e.message) }
    println(f10())
}
