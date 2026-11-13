// lambdas with receiver: builders, nested DSL, this labels, extension lambdas, apply/with chains, DslMarker
@DslMarker annotation class HtmlDsl
@HtmlDsl class Tag(val name: String) {
    private val children = ArrayList<Tag>(); private val attrs = LinkedHashMap<String, String>(); var text = ""
    fun tag(n: String, init: Tag.() -> Unit): Tag { val t = Tag(n); t.init(); children.add(t); return t }
    fun attr(k: String, v: String) { attrs[k] = v }
    operator fun String.unaryPlus() { text += this }
    override fun toString(): String { val a = attrs.entries.joinToString("") { " ${it.key}=\"${it.value}\"" }; return "<$name$a>" + text + children.joinToString("") + "</$name>" }
}
fun html(init: Tag.() -> Unit) = Tag("html").apply(init)
fun Tag.div(init: Tag.() -> Unit) = tag("div", init)
fun Tag.p(init: Tag.() -> Unit) = tag("p", init)
class Config { var name = ""; var port = 0; val tags = mutableListOf<String>(); fun tag(t: String) { tags.add(t) } }
fun config(init: Config.() -> Unit) = Config().also(init)
fun buildString2(init: StringBuilder.() -> Unit): String { val sb = StringBuilder(); sb.init(); return sb.toString() }
class Outer { val x = 1; inner class In { val x = 2; fun both() = "${this@Outer.x}${this.x}" } }
fun main() {
    println(html { attr("lang", "en"); div { attr("id", "a"); p { +"hello" }; p { +"world" } }; +"tail" })
    val c = config { name = "svc"; port = 80; tag("x"); tag("y") }; println("${c.name}:${c.port} ${c.tags}")
    println(buildString2 { append("a"); append(1); appendLine("!"); append('z') }); println(Outer().In().both())
    val ext: String.(Int) -> String = { n -> this.repeat(n) + length }; println("ab".ext(2)); val fn: (String, Int) -> String = ext; println(fn("c", 3))
    println(with(listOf(1, 2, 3)) { size + sum() }); println(StringBuilder().apply { append("x"); append("y") }.reversed()); println(mutableListOf(1).also { it.add(2) }.apply { add(3) }.run { size })
    val pt = object { var x = 0; var y = 0 }; with(pt) { x = 3; y = 4 }; println(pt.x * pt.y); println(listOf(1, 2).let { l -> l.map { it * 2 } }); println("abc".run { first() + last().toString() })
    val builder: MutableList<Int>.() -> Unit = { add(1); add(2) }; val ml = mutableListOf<Int>(); ml.builder(); println(ml); println(buildList { add(1); addAll(listOf(2, 3)) }); println(buildMap { put("a", 1); put("b", 2) }); println(buildString { repeat(3) { append(it) } })
}
