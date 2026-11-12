// Huffman compression: frequency table, tree construction with a priority queue, code assignment, bit packing into bytes,
// decoding, compression ratio, and a run-length encoder / decoder for comparison.

sealed class HNode : Comparable<HNode> {
    abstract val weight: Int
    abstract val order: Int
    override fun compareTo(other: HNode): Int = if (weight != other.weight) weight.compareTo(other.weight) else order.compareTo(other.order)

    class Leaf(val ch: Char, override val weight: Int, override val order: Int) : HNode()
    class Branch(val left: HNode, val right: HNode, override val order: Int) : HNode() {
        override val weight = left.weight + right.weight
    }
}

fun buildTree(text: String): HNode? {
    val freq = text.groupingBy { it }.eachCount()
    val queue = ArrayList<HNode>()
    var order = 0
    for ((c, n) in freq.entries.sortedBy { it.key }) queue.add(HNode.Leaf(c, n, order++))
    if (queue.isEmpty()) return null
    while (queue.size > 1) {
        queue.sort()
        val a = queue.removeAt(0)
        val b = queue.removeAt(0)
        queue.add(HNode.Branch(a, b, order++))
    }
    return queue[0]
}

fun codes(node: HNode?, prefix: String = "", out: MutableMap<Char, String> = LinkedHashMap()): Map<Char, String> {
    when (node) {
        null -> {}
        is HNode.Leaf -> out[node.ch] = prefix.ifEmpty { "0" }
        is HNode.Branch -> {
            codes(node.left, prefix + "0", out)
            codes(node.right, prefix + "1", out)
        }
    }
    return out
}

class BitWriter {
    private val bytes = ArrayList<Byte>()
    private var cur = 0
    private var n = 0
    var bitCount = 0
        private set

    fun write(bits: String) {
        for (b in bits) {
            cur = (cur shl 1) or (b - '0')
            n++
            bitCount++
            if (n == 8) {
                bytes.add(cur.toByte())
                cur = 0
                n = 0
            }
        }
    }

    fun finish(): ByteArray {
        if (n > 0) bytes.add((cur shl (8 - n)).toByte())
        return bytes.toByteArray()
    }
}

fun encode(text: String, table: Map<Char, String>): Pair<ByteArray, Int> {
    val w = BitWriter()
    for (c in text) w.write(table.getValue(c))
    return w.finish() to w.bitCount
}

fun decode(data: ByteArray, bitCount: Int, root: HNode?): String {
    if (root == null) return ""
    val sb = StringBuilder()
    var node: HNode = root
    for (i in 0 until bitCount) {
        val bit = (data[i / 8].toInt() shr (7 - i % 8)) and 1
        if (node is HNode.Branch) node = if (bit == 0) node.left else node.right
        if (node is HNode.Leaf) {
            sb.append(node.ch)
            node = root
        }
    }
    return sb.toString()
}

fun rle(s: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < s.length) {
        var j = i
        while (j < s.length && s[j] == s[i]) j++
        sb.append(j - i).append(s[i])
        i = j
    }
    return sb.toString()
}

fun unrle(s: String): String {
    val sb = StringBuilder()
    var n = 0
    for (c in s) {
        if (c.isDigit()) n = n * 10 + (c - '0') else {
            repeat(n) { sb.append(c) }
            n = 0
        }
    }
    return sb.toString()
}

fun hex(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

fun main() {
    for (text in listOf("abracadabra", "mississippi river", "aaaaaaaaaabbbbbcc", "x", "")) {
        println("== '$text'")
        val tree = buildTree(text)
        val table = codes(tree)
        if (table.isNotEmpty()) println(table.entries.joinToString(" ") { "'${it.key}'=${it.value}" })
        val (data, bits) = encode(text, table)
        println("${text.length * 8} bits -> $bits bits in ${data.size} bytes: ${hex(data)}")
        val back = decode(data, bits, tree)
        println("round trip ok: ${back == text}" + if (text.isNotEmpty()) "  ratio ${"%.2f".format(bits / (text.length * 8.0))}" else "")
    }
    val long = "the rain in spain stays mainly in the plain ".repeat(5)
    val tree = buildTree(long)
    val (data, bits) = encode(long, codes(tree))
    println("long text: ${long.length} chars, ${data.size} bytes (${"%.1f".format(100.0 * data.size / long.length)}%), ok=${decode(data, bits, tree) == long}")
    println("-- run length")
    for (s in listOf("WWWWWWWWWWWWBWWWWWWWWWWWWBBBWWWWWWWWWWWWWWWWWWWWWWWWBWWWWWWWWWWWWWW", "abc", "aabbbcccc", "")) {
        val e = rle(s)
        println("${s.take(20)}${if (s.length > 20) "..." else ""} -> $e (${unrle(e) == s})")
    }
}
