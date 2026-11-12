// A type-safe HTML builder DSL with lambdas with receiver, @DslMarker scope control, operator overloading for text,
// attributes, nested elements and a rendering pass with indentation; plus a second DSL for building a SQL-like query.

@DslMarker
annotation class HtmlDsl

@HtmlDsl
abstract class Node {
    abstract fun render(sb: StringBuilder, indent: Int)
}

class Text(private val text: String) : Node() {
    override fun render(sb: StringBuilder, indent: Int) {
        sb.append("  ".repeat(indent)).append(text.replace("<", "&lt;")).append('\n')
    }
}

open class Element(private val name: String) : Node() {
    private val attrs = LinkedHashMap<String, String>()
    protected val children = ArrayList<Node>()

    operator fun String.unaryPlus() {
        children.add(Text(this))
    }

    fun attr(key: String, value: String) {
        attrs[key] = value
    }

    protected fun <T : Node> add(child: T, init: T.() -> Unit): T {
        child.init()
        children.add(child)
        return child
    }

    override fun render(sb: StringBuilder, indent: Int) {
        val pad = "  ".repeat(indent)
        val a = attrs.entries.joinToString("") { " ${it.key}=\"${it.value}\"" }
        if (children.isEmpty()) {
            sb.append(pad).append("<$name$a/>\n")
        } else if (children.size == 1 && children[0] is Text) {
            sb.append(pad).append("<$name$a>")
            val inner = StringBuilder()
            children[0].render(inner, 0)
            sb.append(inner.toString().trim()).append("</$name>\n")
        } else {
            sb.append(pad).append("<$name$a>\n")
            children.forEach { it.render(sb, indent + 1) }
            sb.append(pad).append("</$name>\n")
        }
    }
}

class Html : Element("html") {
    fun head(init: Head.() -> Unit) = add(Head(), init)
    fun body(init: Body.() -> Unit) = add(Body(), init)
}

class Head : Element("head") {
    fun title(text: String) = add(Element("title")) { +text }
    fun meta(name: String, content: String) = add(Element("meta")) {
        attr("name", name)
        attr("content", content)
    }
}

open class Container(name: String) : Element(name) {
    fun h1(text: String) = add(Element("h1")) { +text }
    fun p(init: Element.() -> Unit) = add(Element("p"), init)
    fun a(href: String, text: String) = add(Element("a")) {
        attr("href", href)
        +text
    }

    fun ul(init: Ul.() -> Unit) = add(Ul(), init)
    fun div(cls: String? = null, init: Div.() -> Unit) = add(Div(), init).also { if (cls != null) it.attr("class", cls) }
}

class Body : Container("body")
class Div : Container("div")

class Ul : Element("ul") {
    fun li(text: String) = add(Element("li")) { +text }
    fun li(init: Element.() -> Unit) = add(Element("li"), init)
}

fun html(init: Html.() -> Unit): String {
    val h = Html()
    h.init()
    val sb = StringBuilder()
    h.render(sb, 0)
    return sb.toString()
}

// --- a query DSL --------------------------------------------------------------------------------------------------------

class Query {
    private var table = ""
    private val columns = ArrayList<String>()
    private val conditions = ArrayList<String>()
    private var order: String? = null
    private var limit: Int? = null

    fun select(vararg cols: String) {
        columns.addAll(cols)
    }

    fun from(t: String) {
        table = t
    }

    fun where(init: Conditions.() -> Unit) {
        val c = Conditions()
        c.init()
        conditions.addAll(c.items)
    }

    fun orderBy(col: String, desc: Boolean = false) {
        order = col + if (desc) " DESC" else ""
    }

    fun limit(n: Int) {
        limit = n
    }

    class Conditions {
        val items = ArrayList<String>()
        infix fun String.eq(v: Any) = items.add("$this = ${lit(v)}")
        infix fun String.gt(v: Any) = items.add("$this > ${lit(v)}")
        infix fun String.like(v: String) = items.add("$this LIKE ${lit(v)}")
        infix fun String.within(vs: List<Any>) = items.add("$this IN (${vs.joinToString { lit(it) }})")
        private fun lit(v: Any) = if (v is String) "'${v.replace("'", "''")}'" else v.toString()
    }

    override fun toString() = buildString {
        append("SELECT ").append(if (columns.isEmpty()) "*" else columns.joinToString(", "))
        append(" FROM ").append(table)
        if (conditions.isNotEmpty()) append(" WHERE ").append(conditions.joinToString(" AND "))
        order?.let { append(" ORDER BY ").append(it) }
        limit?.let { append(" LIMIT ").append(it) }
    }
}

fun query(init: Query.() -> Unit) = Query().apply(init).toString()

fun main() {
    val items = listOf("alpha", "beta", "<gamma>")
    val page = html {
        head {
            title("Demo page")
            meta("author", "mini")
        }
        body {
            h1("Hello DSL")
            p {
                +"A paragraph with "
                +"two text nodes."
            }
            div("list") {
                ul {
                    for ((i, item) in items.withIndex()) li("${i + 1}. $item")
                    li {
                        attr("class", "last")
                        +"custom"
                    }
                }
                a("https://example.org", "example")
            }
            div { p { +"nested" } }
        }
    }
    print(page)
    println(query {
        select("id", "name")
        from("users")
        where {
            "age" gt 18
            "name" like "A%"
            "status" within listOf("new", "active")
            "city" eq "O'Hare"
        }
        orderBy("name", desc = true)
        limit(10)
    })
    println(query { from("t") })
}
