// Regex-driven log processing: parsing access-log lines with named groups, extracting fields, replacing with lambdas,
// splitting, validating input formats and summarizing traffic.

val ACCESS = Regex("""^(?<ip>\d{1,3}(?:\.\d{1,3}){3}) - (?<user>\S+) \[(?<ts>[^\]]+)] "(?<method>[A-Z]+) (?<path>\S+) HTTP/(?<ver>[\d.]+)" (?<status>\d{3}) (?<bytes>\d+|-)$""")

data class Entry(val ip: String, val user: String, val ts: String, val method: String, val path: String, val status: Int, val bytes: Long)

fun parse(line: String): Entry? {
    val m = ACCESS.matchEntire(line) ?: return null
    val g = m.groups
    return Entry(g["ip"]!!.value, g["user"]!!.value, g["ts"]!!.value, g["method"]!!.value, g["path"]!!.value, g["status"]!!.value.toInt(),
        g["bytes"]!!.value.toLongOrNull() ?: 0L)
}

val LOG = """
10.0.0.1 - alice [10/Oct/2024:13:55:36 +0000] "GET /index.html HTTP/1.1" 200 2326
10.0.0.2 - - [10/Oct/2024:13:55:40 +0000] "GET /missing HTTP/1.1" 404 512
10.0.0.1 - alice [10/Oct/2024:13:56:01 +0000] "POST /api/login?next=%2Fhome HTTP/1.1" 302 -
192.168.1.77 - bob [10/Oct/2024:13:57:12 +0000] "GET /api/items?id=42&sort=asc HTTP/2" 200 10240
this line is garbage
10.0.0.3 - - [10/Oct/2024:14:01:00 +0000] "DELETE /api/items/42 HTTP/1.1" 500 128
10.0.0.2 - - [10/Oct/2024:14:02:30 +0000] "GET /index.html HTTP/1.1" 304 0
999.1.1.1 x [bad] "oops"
""".trimIndent()

fun query(path: String): Map<String, String> =
    path.substringAfter('?', "").split('&').filter { it.contains('=') }.associate { it.substringBefore('=') to it.substringAfter('=') }

fun unescape(s: String) = Regex("%([0-9A-Fa-f]{2})").replace(s) { it.groupValues[1].toInt(16).toChar().toString() }

fun main() {
    val lines = LOG.lines()
    val entries = lines.mapNotNull { parse(it) }
    println("parsed ${entries.size} of ${lines.size} lines")
    for (e in entries) println("${e.ip.padEnd(13)} ${e.method.padEnd(6)} ${e.path.substringBefore('?').padEnd(18)} ${e.status} ${e.bytes}")
    println("errors: " + entries.filter { it.status >= 400 }.map { "${it.status}:${it.path}" })
    println("bytes by ip: " + entries.groupBy { it.ip }.mapValues { (_, v) -> v.sumOf { it.bytes } }.toSortedMap())
    println("status classes: " + entries.groupingBy { it.status / 100 }.eachCount().toSortedMap())
    println("users: " + entries.map { it.user }.filter { it != "-" }.distinct())
    entries.filter { '?' in it.path }.forEach { e ->
        println("query of ${e.path.substringBefore('?')}: " + query(e.path).mapValues { unescape(it.value) })
    }
    println("-- regex API")
    val r = Regex("(\\w+)@(\\w+)\\.(com|org)")
    val text = "mail bob@example.com or alice@site.org, not eve@bad.net"
    println(r.findAll(text).map { it.value }.toList())
    println(r.findAll(text).map { it.groupValues.drop(1) }.toList())
    println(r.find(text)?.range)
    println(r.replace(text) { "<${it.groupValues[1].uppercase()}>" })
    println(r.replace(text, "[$2]"))
    println(r.containsMatchIn("nothing here") to r.matches("a@b.com"))
    println("2024-10-10".split('-').map { it.toInt() })
    println(Regex("\\s*[,;]\\s*").split("a , b;c ,d"))
    println(Regex("[aeiou]").replace("regular expressions", "*"))
    println("a1b22c333".split(Regex("\\d+")) to Regex("\\d+").findAll("a1b22c333").map { it.value.length }.toList())
    println("CamelCaseString".replace(Regex("(?<=[a-z])(?=[A-Z])"), "_"))
    println(Regex("^[A-Z][a-z]+$").matches("Hello") to Regex("^[A-Z][a-z]+$").matches("hello"))
    val phone = Regex("""\(?(\d{3})\)?[ -]?(\d{3})-(\d{4})""")
    for (s in listOf("(555) 123-4567", "555-123-4567", "5551234567", "555 123-45")) {
        println("$s -> " + (phone.matchEntire(s)?.destructured?.let { (a, b, c) -> "$a.$b.$c" } ?: "invalid"))
    }
    val dateRe = Regex("(\\d{4})-(\\d{2})-(\\d{2})")
    println(dateRe.replace("from 2024-01-05 to 2024-12-31") { m -> "${m.groupValues[3]}/${m.groupValues[2]}/${m.groupValues[1]}" })
    println(Regex("a+?").find("aaa")?.value + " " + Regex("a+").find("aaa")?.value + " " + Regex("(a|b)*c").matchEntire("ababc")?.groupValues)
    println(Regex("hello", RegexOption.IGNORE_CASE).containsMatchIn("Say HELLO") to Regex("^b", RegexOption.MULTILINE).findAll("a\nb\nb").count())
}
