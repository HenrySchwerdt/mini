// A Markdown to HTML converter: headings, paragraphs, emphasis, code, links, lists (nested by indentation), block quotes and rules.

fun escapeHtml(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

val CODE = Regex("`([^`]+)`")
val BOLD = Regex("\\*\\*(.+?)\\*\\*")
val ITALIC = Regex("\\*(.+?)\\*")
val LINK = Regex("\\[([^\\]]+)]\\(([^)]+)\\)")

fun inline(text: String): String {
    var s = escapeHtml(text)
    s = CODE.replace(s) { "<code>${it.groupValues[1]}</code>" }
    s = BOLD.replace(s) { "<strong>${it.groupValues[1]}</strong>" }
    s = ITALIC.replace(s) { "<em>${it.groupValues[1]}</em>" }
    s = LINK.replace(s) { "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>" }
    return s
}

sealed class Block {
    data class Heading(val level: Int, val text: String) : Block()
    data class Paragraph(val lines: List<String>) : Block()
    data class CodeBlock(val lang: String, val lines: List<String>) : Block()
    data class Quote(val lines: List<String>) : Block()
    data class ListBlock(val ordered: Boolean, val items: List<Pair<Int, String>>) : Block()
    object Rule : Block()
}

val ORDERED = Regex("^(\\s*)\\d+\\.\\s+(.*)$")
val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")

fun parseBlocks(source: String): List<Block> {
    val lines = source.lines()
    val blocks = ArrayList<Block>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.isBlank() -> i++
            line.startsWith("```") -> {
                val lang = line.removePrefix("```").trim()
                val body = ArrayList<String>()
                i++
                while (i < lines.size && !lines[i].startsWith("```")) body.add(lines[i++])
                i++
                blocks.add(Block.CodeBlock(lang, body))
            }
            line.startsWith("#") -> {
                val level = line.takeWhile { it == '#' }.length
                blocks.add(Block.Heading(minOf(level, 6), line.drop(level).trim()))
                i++
            }
            line.trim().matches(Regex("(-\\s*){3,}|(\\*\\s*){3,}")) -> {
                blocks.add(Block.Rule)
                i++
            }
            line.startsWith(">") -> {
                val q = ArrayList<String>()
                while (i < lines.size && lines[i].startsWith(">")) q.add(lines[i++].removePrefix(">").trim())
                blocks.add(Block.Quote(q))
            }
            ORDERED.matches(line) || BULLET.matches(line) -> {
                val ordered = ORDERED.matches(line)
                val items = ArrayList<Pair<Int, String>>()
                while (i < lines.size) {
                    val m = (if (ordered) ORDERED else BULLET).matchEntire(lines[i]) ?: break
                    items.add(m.groupValues[1].length / 2 to m.groupValues[2])
                    i++
                }
                blocks.add(Block.ListBlock(ordered, items))
            }
            else -> {
                val p = ArrayList<String>()
                while (i < lines.size && lines[i].isNotBlank() && !lines[i].startsWith("#") && !lines[i].startsWith(">") &&
                    !lines[i].startsWith("```") && !BULLET.matches(lines[i]) && !ORDERED.matches(lines[i])
                ) p.add(lines[i++].trim())
                blocks.add(Block.Paragraph(p))
            }
        }
    }
    return blocks
}

fun renderList(b: Block.ListBlock): String {
    val tag = if (b.ordered) "ol" else "ul"
    val sb = StringBuilder()
    var depth = -1
    val open = ArrayList<Int>()
    for ((level, text) in b.items) {
        if (level > depth) {
            while (depth < level) {
                sb.append("<$tag>")
                depth++
                open.add(depth)
            }
        } else {
            sb.append("</li>")
            while (depth > level) {
                sb.append("</$tag></li>")
                depth--
                open.removeAt(open.size - 1)
            }
        }
        sb.append("<li>").append(inline(text))
    }
    while (depth >= 0) {
        sb.append("</li></$tag>")
        depth--
    }
    return sb.toString()
}

fun render(b: Block): String = when (b) {
    is Block.Heading -> "<h${b.level}>${inline(b.text)}</h${b.level}>"
    is Block.Paragraph -> "<p>${inline(b.lines.joinToString(" "))}</p>"
    is Block.CodeBlock -> "<pre><code${if (b.lang.isNotEmpty()) " class=\"language-${b.lang}\"" else ""}>${escapeHtml(b.lines.joinToString("\n"))}</code></pre>"
    is Block.Quote -> "<blockquote>${b.lines.joinToString(" ") { inline(it) }}</blockquote>"
    is Block.ListBlock -> renderList(b)
    Block.Rule -> "<hr/>"
}

fun toHtml(md: String): String = parseBlocks(md).joinToString("\n") { render(it) }

val DOC = """
# Mini *Markdown*

A paragraph with **bold**, *italic* and `code <b>` text,
continued on a second line, plus a [link](https://example.org/a?b=1&c=2).

## Lists

- first
- second with `code`
  - nested one
  - nested two
- third

1. one
2. two

> quoted text
> over two lines

---

```kotlin
fun main() { println("<hi>") }
```

###### deep heading
"""

fun main() {
    val blocks = parseBlocks(DOC)
    println("blocks: " + blocks.joinToString { it::class.simpleName ?: "?" })
    println(toHtml(DOC))
    println("headings: " + blocks.filterIsInstance<Block.Heading>().map { it.level to it.text })
}
