import { newCell, type Notebook } from './notebook.ts'

/** The notebook that opens when there is no saved or shared one. */
export function starterNotebook(): Notebook {
  return {
    title: 'A tour of Mini',
    cells: [
      newCell(
        'markdown',
        `# A tour of Mini

Mini is Kotlin compiled to native code. This notebook walks through the language and then the memory model.

**How cells work.** Every code cell is compiled together with the *declarations* (functions, classes, imports, top-level \`val\`/\`var\`) of the cells above it. The *statements* of the cell you run become its \`main\`. Each run is a fresh program, so values do not survive from one run to the next; declarations do. Use **Run** on a cell, or **Run all** to go top to bottom. \`Ctrl/Cmd+Enter\` runs the focused cell.`,
      ),
      newCell(
        'code',
        `val limit = 5   // a top-level val is a declaration: later cells see it

fun greet(name: String, greeting: String = "Hello") = "$greeting, $name!"

fun classify(n: Int) = when {
    n < 0 -> "negative"
    n == 0 -> "zero"
    n % 2 == 0 -> "even"
    else -> "odd"
}

println(greet("Mini"))
for (n in listOf(-5, 0, 4, 7)) println("$n is \${classify(n)}")
println("limit = $limit")`,
      ),
      newCell(
        'code',
        `sealed interface Shape
data class Circle(val r: Double) : Shape
data class Rect(val w: Double, val h: Double) : Shape

fun area(s: Shape): Double = when (s) {
    is Circle -> 3.0 * s.r * s.r
    is Rect -> s.w * s.h
}

// Declarations and statements can be mixed in one cell. The declarations stay available to the cells below.
for (s in listOf(Circle(2.0), Rect(3.0, 4.0))) println("$s has area \${area(s)}")`,
      ),
      newCell(
        'code',
        `fun longWords(words: List<String>) = words.filter { it.length > limit - 1 }.map { it.uppercase() }

println(longWords(listOf("memory", "model", "mini", "arena")))
println(listOf("memory", "model", "mini", "arena").groupBy { it.length })`,
      ),
      newCell(
        'markdown',
        `## The memory model

Mini has three tiers, chosen per file:

1. **Managed** (the default): ordinary Kotlin, objects are reference counted. Nothing to write.
2. **Allocators**: \`Arena\`, \`Heap\` and \`Pool\` chosen with \`context(...)\`.
3. **Strict**: every allocation needs a proven owner; leaks and use-after-free are compile errors.

Read the [memory guide](/memory/) for the details.`,
      ),
      newCell(
        'code',
        `class Node(val value: Int, val next: Node?)

fun sum(n: Node?): Int = if (n == null) 0 else n.value + sum(n.next)

println(sum(Node(1, Node(2, Node(3, null)))))
// No free() anywhere: reference counting releases the nodes when the last reference is gone.`,
      ),
      newCell(
        'code',
        `import mini.memory.*

class Point(val x: Int, val y: Int)

context(a: Arena)
fun diagonal(n: Int): Point = Point(n, n)

// An arena frees everything it allocated at once. The Int may leave the block, the Points may not.
println(Arena().use { arena ->
    context(arena) {
        var t = 0
        for (i in 1..3) t += diagonal(i).x + diagonal(i).y
        t
    }
})`,
      ),
      newCell(
        'markdown',
        `### Strict mode

The next cell turns on \`@file:MemoryMode(Mode.Strict)\`. Because declarations accumulate, **every cell below it is checked in Strict mode too**. The first version has a leak on one path, and the checker reports it as [M0102](/reference/diagnostics/M0102) when you run it. Hover the underlined code.`,
      ),
      newCell(
        'code',
        `@file:MemoryMode(Mode.Strict)

import mini.memory.*

class Buffer(val size: Int) : AutoCloseable {
    override fun close() = println("close $size")
}

fun fill(b: Buffer): Boolean = b.size > 100

context(h: Heap)
fun load(n: Int): @Owned Buffer? {
    val buf = Buffer(n)
    if (!fill(buf)) return null    // leaks \`buf\`
    return buf
}

context(Heap()) { load(8).free() }`,
      ),
      newCell(
        'markdown',
        `The checker is right: when \`fill\` fails, \`buf\` is never freed. Fix it in the cell above by releasing it on that path, then run it again:

\`\`\`kotlin
if (!fill(buf)) {
    buf.free()      // release on this path too
    return null
}
\`\`\`

Note that a declaration with an error stays part of every later program, so fix the cell instead of defining a second copy.`,
      ),
    ],
  }
}
