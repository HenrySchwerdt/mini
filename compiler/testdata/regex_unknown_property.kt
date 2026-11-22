// An unknown character property name (\p{...}, \P{...}, also inside a class) is rejected when the pattern is compiled, as java.util.regex does
// (found by the libFuzzer harness tools/fuzz/libfuzzer, regex): it used to compile and throw at the first match.
fun main() {
    for (p in listOf("\\P,", "\\p{Nope}", "[a\\p{Zzz}]", "x\\pQ", "\\p{Lu}+", "[\\P{Alpha}]")) {
        val r = try { Regex(p) } catch (e: IllegalArgumentException) { null }
        if (r == null) println("rejected " + p) else println("compiled " + p + " " + r.matches("A"))
    }
}
