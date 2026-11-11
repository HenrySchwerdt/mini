// A JSON parser and serializer: sealed value tree, recursive descent with error positions, compact and pretty output, queries.

sealed class Json {
    object Null : Json()
    data class Bool(val value: Boolean) : Json()
    data class Num(val value: Double) : Json()
    data class Str(val value: String) : Json()
    data class Arr(val items: List<Json>) : Json()
    data class Obj(val fields: Map<String, Json>) : Json()
}

class JsonError(message: String, val pos: Int) : Exception("$message at $pos")

class Parser(private val src: String) {
    private var pos = 0

    fun parse(): Json {
        val v = value()
        skipWs()
        if (pos != src.length) throw JsonError("trailing characters", pos)
        return v
    }

    private fun skipWs() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
    }

    private fun expect(c: Char) {
        if (pos >= src.length || src[pos] != c) throw JsonError("expected '$c'", pos)
        pos++
    }

    private fun value(): Json {
        skipWs()
        if (pos >= src.length) throw JsonError("unexpected end", pos)
        return when (val c = src[pos]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> Json.Str(str())
            't' -> literal("true", Json.Bool(true))
            'f' -> literal("false", Json.Bool(false))
            'n' -> literal("null", Json.Null)
            else -> if (c == '-' || c.isDigit()) num() else throw JsonError("unexpected '$c'", pos)
        }
    }

    private fun literal(word: String, v: Json): Json {
        if (!src.startsWith(word, pos)) throw JsonError("bad literal", pos)
        pos += word.length
        return v
    }

    private fun num(): Json {
        val start = pos
        if (src[pos] == '-') pos++
        while (pos < src.length && (src[pos].isDigit() || src[pos] in ".eE+-")) pos++
        return Json.Num(src.substring(start, pos).toDoubleOrNull() ?: throw JsonError("bad number", start))
    }

    private fun str(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            if (pos >= src.length) throw JsonError("unterminated string", pos)
            val c = src[pos++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> {
                    val e = src[pos++]
                    when (e) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'u' -> {
                            sb.append(src.substring(pos, pos + 4).toInt(16).toChar())
                            pos += 4
                        }
                        else -> sb.append(e)
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    private fun arr(): Json {
        expect('[')
        val items = ArrayList<Json>()
        skipWs()
        if (pos < src.length && src[pos] == ']') {
            pos++
            return Json.Arr(items)
        }
        while (true) {
            items.add(value())
            skipWs()
            if (pos < src.length && src[pos] == ',') pos++ else break
        }
        expect(']')
        return Json.Arr(items)
    }

    private fun obj(): Json {
        expect('{')
        val fields = LinkedHashMap<String, Json>()
        skipWs()
        if (pos < src.length && src[pos] == '}') {
            pos++
            return Json.Obj(fields)
        }
        while (true) {
            skipWs()
            val k = str()
            skipWs()
            expect(':')
            fields[k] = value()
            skipWs()
            if (pos < src.length && src[pos] == ',') pos++ else break
        }
        expect('}')
        return Json.Obj(fields)
    }
}

fun quote(s: String): String = buildString {
    append('"')
    for (c in s) when (c) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\t' -> append("\\t")
        else -> if (c < ' ') append("\\u" + c.code.toString(16).padStart(4, '0')) else append(c)
    }
    append('"')
}

fun number(d: Double): String = if (d == kotlin.math.floor(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString()

fun Json.compact(): String = when (this) {
    Json.Null -> "null"
    is Json.Bool -> value.toString()
    is Json.Num -> number(value)
    is Json.Str -> quote(value)
    is Json.Arr -> items.joinToString(",", "[", "]") { it.compact() }
    is Json.Obj -> fields.entries.joinToString(",", "{", "}") { quote(it.key) + ":" + it.value.compact() }
}

fun Json.pretty(indent: Int = 0): String {
    val pad = "  ".repeat(indent)
    val inner = "  ".repeat(indent + 1)
    return when (this) {
        is Json.Arr -> if (items.isEmpty()) "[]" else items.joinToString(",\n", "[\n", "\n$pad]") { inner + it.pretty(indent + 1) }
        is Json.Obj -> if (fields.isEmpty()) "{}" else fields.entries.joinToString(",\n", "{\n", "\n$pad}") { inner + quote(it.key) + ": " + it.value.pretty(indent + 1) }
        else -> compact()
    }
}

operator fun Json.get(key: String): Json? = (this as? Json.Obj)?.fields?.get(key)
operator fun Json.get(i: Int): Json? = (this as? Json.Arr)?.items?.getOrNull(i)

fun Json.query(path: String): Json? {
    var cur: Json? = this
    for (part in path.split('.')) {
        val idx = part.toIntOrNull()
        cur = if (idx != null) cur?.get(idx) else cur?.get(part)
    }
    return cur
}

fun Json.depth(): Int = when (this) {
    is Json.Arr -> 1 + (items.maxOfOrNull { it.depth() } ?: 0)
    is Json.Obj -> 1 + (fields.values.maxOfOrNull { it.depth() } ?: 0)
    else -> 0
}

fun toJson(x: Any?): Json = when (x) {
    null -> Json.Null
    is Boolean -> Json.Bool(x)
    is Int -> Json.Num(x.toDouble())
    is Double -> Json.Num(x)
    is String -> Json.Str(x)
    is List<*> -> Json.Arr(x.map { toJson(it) })
    is Map<*, *> -> Json.Obj(x.entries.associate { it.key.toString() to toJson(it.value) })
    else -> Json.Str(x.toString())
}

fun main() {
    val text = """
    {
      "name": "mini",
      "version": 1.5,
      "tags": ["native", "kotlin", "compiler"],
      "active": true,
      "owner": {"id": 42, "email": null, "langs": ["en", "de"]},
      "escape": "line1\nline2 \"quoted\" A",
      "empty": {}, "none": []
    }
    """
    val doc = Parser(text).parse()
    println(doc.compact())
    println(doc.pretty())
    println("depth = ${doc.depth()}")
    println(doc.query("owner.langs.1"))
    println(doc.query("tags.0"))
    println(doc.query("owner.missing"))
    println((doc["version"] as Json.Num).value * 2)
    val roundTrip = Parser(doc.pretty()).parse()
    println("round trip equal: ${roundTrip == doc}")
    val built = toJson(mapOf("list" to listOf(1, 2.5, "three", null, true), "nested" to mapOf("k" to "v")))
    println(built.compact())
    for (bad in listOf("{\"a\": }", "[1, 2", "{\"a\" 1}", "tru", "[1] x", "\"abc")) {
        try {
            Parser(bad).parse()
            println("parsed: $bad")
        } catch (e: JsonError) {
            println("error: ${e.message}")
        }
    }
}
