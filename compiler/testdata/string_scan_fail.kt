// Scanning loops over String.length / String[i] whose failing paths end the function (LLVM: noreturn, so the cursor stays in registers):
// the field-held cursor is advanced inside the loop, the scan runs to the end of the string and the next read past it fails with the
// index message after the output so far.
class Scanner(val s: String) {
    var pos = 0
    fun skipTo(c: Char): Int {
        while (s[pos] != c) pos++
        return pos
    }
    fun digits(): Int {
        var n = 0
        while (pos < s.length && s[pos] >= '0' && s[pos] <= '9') { n = n * 10 + (s[pos] - '0'); pos++ }
        return n
    }
}

fun main() {
    val sc = Scanner("abc123\"tail")
    println(sc.skipTo('1'))
    println(sc.digits())
    println(sc.skipTo('"'))
    println(sc.pos)
    val e = Scanner("")
    println(e.digits())
    println("before")
    println(sc.skipTo('#'))
    println("after")
}
