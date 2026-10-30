// json-like: generate a JSON document from a seeded generator, parse it into a tree (recursive descent, strings with escapes,
// objects as key/value lists), walk the tree. Kotlin/JVM and Mini Managed compile this very file.
// Output: text length, node count, checksum of the walk.
const val TOP = 60000
const val REPS = 5
const val MOD = 1000000007L

class Rng(var s: Long) {
    fun next(): Int {
        s = (s * 1103515245L + 12345L) and 0x7fffffffL
        return (s shr 8).toInt()
    }
}

fun leaf(sb: StringBuilder, rng: Rng) {
    when (rng.next() % 6) {
        0 -> sb.append("null")
        1 -> sb.append("true")
        2 -> sb.append("false")
        3 -> sb.append(rng.next() % 200000 - 100000)
        4 -> {
            sb.append("\"s")
            sb.append(rng.next() % 5000)
            sb.append('"')
        }
        else -> {
            sb.append("\"a\\nb\\\"c")
            sb.append(rng.next() % 5000)
            sb.append("\\\\\"")
        }
    }
}

fun gen(sb: StringBuilder, rng: Rng, depth: Int) {
    val k = rng.next() % 12
    if (depth >= 6 || k < 5) {
        leaf(sb, rng)
    } else if (k < 8) {
        val n = 1 + rng.next() % 4
        sb.append('{')
        for (i in 0 until n) {
            if (i > 0) sb.append(", ")
            sb.append("\"k")
            sb.append(rng.next() % 1000)
            sb.append("\": ")
            gen(sb, rng, depth + 1)
        }
        sb.append('}')
    } else {
        val n = rng.next() % 5
        sb.append('[')
        for (i in 0 until n) {
            if (i > 0) sb.append(", ")
            gen(sb, rng, depth + 1)
        }
        sb.append(']')
    }
}

fun generate(): String {
    val rng = Rng(7L)
    val sb = StringBuilder()
    sb.append('[')
    for (i in 0 until TOP) {
        if (i > 0) sb.append(", ")
        gen(sb, rng, 1)
    }
    sb.append(']')
    return sb.toString()
}

sealed class J
object JNull : J()
class JBool(val v: Boolean) : J()
class JNum(val v: Long) : J()
class JStr(val s: String) : J()
class JArr(val items: ArrayList<J>) : J()
class JObj(val keys: ArrayList<String>, val vals: ArrayList<J>) : J()

class Parser(val s: String) {
    var pos = 0

    fun skipWs() {
        while (pos < s.length && s[pos] == ' ') pos++
    }

    fun parseString(): String {
        pos++
        val start = pos
        var esc = false
        while (s[pos] != '"') {
            if (s[pos] == '\\') {
                esc = true
                pos++
            }
            pos++
        }
        val raw = s.substring(start, pos)
        pos++
        if (!esc) return raw
        val sb = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\') {
                i++
                val d = raw[i]
                if (d == 'n') sb.append('\n') else sb.append(d)
            } else {
                sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    fun parseValue(): J {
        skipWs()
        val c = s[pos]
        if (c == '{') {
            pos++
            val keys = ArrayList<String>()
            val vals = ArrayList<J>()
            skipWs()
            if (s[pos] == '}') {
                pos++
                return JObj(keys, vals)
            }
            while (true) {
                skipWs()
                keys.add(parseString())
                skipWs()
                pos++ // ':'
                vals.add(parseValue())
                skipWs()
                val d = s[pos]
                pos++
                if (d == '}') break
            }
            return JObj(keys, vals)
        }
        if (c == '[') {
            pos++
            val items = ArrayList<J>()
            skipWs()
            if (s[pos] == ']') {
                pos++
                return JArr(items)
            }
            while (true) {
                items.add(parseValue())
                skipWs()
                val d = s[pos]
                pos++
                if (d == ']') break
            }
            return JArr(items)
        }
        if (c == '"') return JStr(parseString())
        if (c == 't') {
            pos += 4
            return JBool(true)
        }
        if (c == 'f') {
            pos += 5
            return JBool(false)
        }
        if (c == 'n') {
            pos += 4
            return JNull
        }
        var neg = false
        if (c == '-') {
            neg = true
            pos++
        }
        var v = 0L
        while (pos < s.length && s[pos] >= '0' && s[pos] <= '9') {
            v = v * 10 + (s[pos] - '0')
            pos++
        }
        return JNum(if (neg) -v else v)
    }
}

class Stats(var h: Long, var n: Long) {
    fun step(x: Long) {
        h = (h * 31 + x) % MOD
        n++
    }
}

fun walk(j: J, st: Stats) {
    when (j) {
        is JNull -> st.step(1)
        is JBool -> st.step(if (j.v) 2 else 3)
        is JNum -> st.step(j.v + 100000)
        is JStr -> {
            var t = j.s.length.toLong()
            for (i in 0 until j.s.length) t += j.s[i].code
            st.step(t)
        }
        is JArr -> {
            st.step(13L + j.items.size)
            for (i in 0 until j.items.size) walk(j.items[i], st)
        }
        is JObj -> {
            st.step(11L + j.keys.size)
            for (i in 0 until j.keys.size) {
                st.step(j.keys[i].length.toLong())
                walk(j.vals[i], st)
            }
        }
    }
}

fun main() {
    val text = generate()
    var result: Stats? = null
    for (r in 0 until REPS) {
        val tree = Parser(text).parseValue()
        val st = Stats(0L, 0L)
        walk(tree, st)
        result = st
    }
    println("${text.length} ${result!!.n} ${result.h}")
}
