package tmpl

/** A small template engine: `{{name}}`, `{{name|upper}}`, `{{#each items}}...{{/each}}`, `{{#if flag}}...{{else}}...{{/if}}`. */
class TemplateError(message: String) : Exception(message)

typealias Filter = (String) -> String

class Context(private val values: Map<String, Any?>, private val parent: Context? = null) {
    operator fun get(name: String): Any? = if (name in values) values[name] else parent?.get(name)
    fun child(extra: Map<String, Any?>) = Context(extra, this)
}

private sealed class Node {
    class Text(val s: String) : Node()
    class Var(val name: String, val filters: List<String>) : Node()
    class Each(val name: String, val body: List<Node>) : Node()
    class If(val name: String, val yes: List<Node>, val no: List<Node>) : Node()
}

class Template private constructor(private val nodes: List<Node>) {
    fun render(ctx: Map<String, Any?>, filters: Map<String, Filter> = DEFAULT_FILTERS): String {
        val sb = StringBuilder()
        render(nodes, Context(ctx), filters, sb)
        return sb.toString()
    }

    private fun render(ns: List<Node>, ctx: Context, filters: Map<String, Filter>, sb: StringBuilder) {
        for (n in ns) when (n) {
            is Node.Text -> sb.append(n.s)
            is Node.Var -> {
                var v = (ctx[n.name] ?: throw TemplateError("unknown variable '${n.name}'")).toString()
                for (f in n.filters) v = (filters[f] ?: throw TemplateError("unknown filter '$f'"))(v)
                sb.append(v)
            }
            is Node.Each -> {
                val items = ctx[n.name] as? List<*> ?: throw TemplateError("'${n.name}' is not a list")
                for ((i, item) in items.withIndex()) render(n.body, ctx.child(mapOf("item" to item, "index" to i, "first" to (i == 0), "last" to (i == items.size - 1))), filters, sb)
            }
            is Node.If -> {
                val v = ctx[n.name]
                val truthy = v != null && v != false && v != "" && v != 0 && (v as? Collection<*>)?.isNotEmpty() != false
                render(if (truthy) n.yes else n.no, ctx, filters, sb)
            }
        }
    }

    companion object {
        val DEFAULT_FILTERS: Map<String, Filter> = mapOf(
            "upper" to { s: String -> s.uppercase() },
            "lower" to { s: String -> s.lowercase() },
            "trim" to { s: String -> s.trim() },
            "title" to { s: String -> s.split(" ").joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercase() } } },
            "len" to { s: String -> s.length.toString() },
        )

        fun parse(source: String): Template {
            val tokens = tokenize(source)
            val (nodes, end) = parseNodes(tokens, 0, emptySet())
            if (end != tokens.size) throw TemplateError("unexpected tag")
            return Template(nodes)
        }

        private fun tokenize(s: String): List<String> {
            val out = ArrayList<String>()
            var i = 0
            while (i < s.length) {
                val open = s.indexOf("{{", i)
                if (open < 0) {
                    out.add(s.substring(i))
                    break
                }
                if (open > i) out.add(s.substring(i, open))
                val close = s.indexOf("}}", open)
                if (close < 0) throw TemplateError("unclosed tag at $open")
                out.add(s.substring(open, close + 2))
                i = close + 2
            }
            return out
        }

        private fun parseNodes(t: List<String>, start: Int, stops: Set<String>): Pair<List<Node>, Int> {
            val nodes = ArrayList<Node>()
            var i = start
            while (i < t.size) {
                val tok = t[i]
                if (!tok.startsWith("{{")) {
                    nodes.add(Node.Text(tok))
                    i++
                    continue
                }
                val inner = tok.substring(2, tok.length - 2).trim()
                if (inner in stops) return nodes to i
                when {
                    inner.startsWith("#each ") -> {
                        val (body, end) = parseNodes(t, i + 1, setOf("/each"))
                        if (end >= t.size) throw TemplateError("missing {{/each}}")
                        nodes.add(Node.Each(inner.removePrefix("#each ").trim(), body))
                        i = end + 1
                    }
                    inner.startsWith("#if ") -> {
                        val (yes, e1) = parseNodes(t, i + 1, setOf("else", "/if"))
                        if (e1 >= t.size) throw TemplateError("missing {{/if}}")
                        var no: List<Node> = emptyList()
                        var e2 = e1
                        if (t[e1].substring(2, t[e1].length - 2).trim() == "else") {
                            val r = parseNodes(t, e1 + 1, setOf("/if"))
                            no = r.first
                            e2 = r.second
                            if (e2 >= t.size) throw TemplateError("missing {{/if}}")
                        }
                        nodes.add(Node.If(inner.removePrefix("#if ").trim(), yes, no))
                        i = e2 + 1
                    }
                    inner.startsWith("/") || inner == "else" -> throw TemplateError("unexpected {{$inner}}")
                    else -> {
                        val parts = inner.split("|").map { it.trim() }
                        nodes.add(Node.Var(parts[0], parts.drop(1)))
                        i++
                    }
                }
            }
            return nodes to i
        }
    }
}

inline fun template(source: String, build: MutableMap<String, Any?>.() -> Unit): String {
    val m = LinkedHashMap<String, Any?>()
    m.build()
    return Template.parse(source).render(m)
}
