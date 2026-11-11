/*
 * kotlin.text.Regex: a backtracking engine written in Kotlin with the java.util.regex syntax that everyday code uses:
 * literals and escapes, classes (nested, ranges, &&, \d \w \s \p{..}), . ^ $ \A \z \Z \b \B, groups (capturing, named, (?:..)),
 * atomic groups, alternation, greedy / lazy / possessive quantifiers, backreferences, lookahead and lookbehind, inline flags (?imsx).
 */
package kotlin.text

public enum class RegexOption {
    IGNORE_CASE, MULTILINE, LITERAL, UNIX_LINES, COMMENTS, DOT_MATCHES_ALL, CANON_EQ
}

public class MatchGroup(public val value: String, public val range: IntRange) {
    public operator fun component1(): String = value
    public operator fun component2(): IntRange = range
    override fun toString(): String = "MatchGroup(value=" + value + ", range=" + range + ")"
    override fun equals(other: Any?): Boolean = other is MatchGroup && other.value == value && other.range == range
    override fun hashCode(): Int = value.hashCode() * 31 + range.hashCode()
}

public interface MatchGroupCollection : Collection<MatchGroup?> {
    public operator fun get(index: Int): MatchGroup?
}

public interface MatchNamedGroupCollection : MatchGroupCollection {
    public operator fun get(name: String): MatchGroup?
}

public operator fun MatchGroupCollection.get(name: String): MatchGroup? {
    val namedGroups = this as? MatchNamedGroupCollection ?: throw UnsupportedOperationException("Retrieving groups by name is not supported on this platform.")
    return namedGroups[name]
}

public interface MatchResult {
    public val range: IntRange
    public val value: String
    public val groups: MatchGroupCollection
    public val groupValues: List<String>
    public val destructured: MatchResult.Destructured
    public fun next(): MatchResult?

    public class Destructured internal constructor(public val match: MatchResult) {
        public operator fun component1(): String = match.groupValues[1]
        public operator fun component2(): String = match.groupValues[2]
        public operator fun component3(): String = match.groupValues[3]
        public operator fun component4(): String = match.groupValues[4]
        public operator fun component5(): String = match.groupValues[5]
        public operator fun component6(): String = match.groupValues[6]
        public operator fun component7(): String = match.groupValues[7]
        public operator fun component8(): String = match.groupValues[8]
        public operator fun component9(): String = match.groupValues[9]
        public operator fun component10(): String = match.groupValues[10]
        public fun toList(): List<String> = match.groupValues.subList(1, match.groupValues.size)
    }
}

public class Regex(public val pattern: String, public val options: Set<RegexOption>) {
    private val prog: RegexProgram = RegexParser(pattern, options).parse()

    public constructor(pattern: String) : this(pattern, emptySet<RegexOption>())

    public constructor(pattern: String, option: RegexOption) : this(pattern, setOf(option))

    public infix fun matches(input: String): Boolean = matchEntire(input) != null

    public fun matchesAt(input: String, index: Int): Boolean = matchAt(input, index) != null

    public fun containsMatchIn(input: String): Boolean = find(input, 0) != null

    public fun matchEntire(input: String): MatchResult? {
        val m = RegexMatcher(prog, input)
        return if (m.matchFrom(0, true)) RegexMatchResult(this, input, m.snapshot()) else null
    }

    public fun matchAt(input: String, index: Int): MatchResult? {
        if (index < 0 || index > input.length) throw IndexOutOfBoundsException("index " + index + ", length " + input.length)
        val m = RegexMatcher(prog, input)
        return if (m.matchFrom(index, false)) RegexMatchResult(this, input, m.snapshot()) else null
    }

    public fun find(input: String, startIndex: Int = 0): MatchResult? {
        if (startIndex < 0 || startIndex > input.length) throw IndexOutOfBoundsException("Start index out of bounds: " + startIndex + ", input length: " + input.length)
        val m = RegexMatcher(prog, input)
        return if (m.find(startIndex)) RegexMatchResult(this, input, m.snapshot()) else null
    }

    public fun findAll(input: String, startIndex: Int = 0): Sequence<MatchResult> {
        if (startIndex < 0 || startIndex > input.length) throw IndexOutOfBoundsException("Start index out of bounds: " + startIndex + ", input length: " + input.length)
        return generateSequence({ find(input, startIndex) }, { match -> match.next() })
    }

    public fun replace(input: String, replacement: String): String {
        var match = find(input, 0)
        if (match == null) return input
        val sb = StringBuilder()
        var last = 0
        while (match != null) {
            sb.append(input, last, match.range.start)
            appendReplacement(sb, match, replacement)
            last = match.range.endInclusive + 1
            match = match.next()
        }
        if (last < input.length) sb.append(input, last, input.length)
        return sb.toString()
    }

    public fun replace(input: String, transform: (MatchResult) -> CharSequence): String {
        var match = find(input, 0)
        if (match == null) return input
        val sb = StringBuilder()
        var last = 0
        while (match != null) {
            sb.append(input, last, match.range.start)
            sb.append(transform(match).toString())
            last = match.range.endInclusive + 1
            match = match.next()
        }
        if (last < input.length) sb.append(input, last, input.length)
        return sb.toString()
    }

    public fun replaceFirst(input: String, replacement: String): String {
        val match = find(input, 0) ?: return input
        val sb = StringBuilder()
        sb.append(input, 0, match.range.start)
        appendReplacement(sb, match, replacement)
        sb.append(input, match.range.endInclusive + 1, input.length)
        return sb.toString()
    }

    public fun split(input: String, limit: Int = 0): List<String> {
        require(limit >= 0) { "Limit must be non-negative, but was " + limit + "." }
        var match = find(input, 0)
        if (match == null || limit == 1) return listOf(input)
        val result = ArrayList<String>()
        var last = 0
        while (match != null) {
            if (limit > 0 && result.size == limit - 1) break
            result.add(input.substring(last, match.range.start))
            last = match.range.endInclusive + 1
            match = match.next()
        }
        result.add(input.substring(last, input.length))
        return result
    }

    override fun toString(): String = pattern

    public companion object {
        public fun fromLiteral(literal: String): Regex = Regex(escape(literal))

        public fun escape(literal: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < literal.length) {
                val c = literal[i]
                if ("\\.[]{}()*+-?^$|".indexOf(c) >= 0) sb.append('\\')
                sb.append(c)
                i++
            }
            return sb.toString()
        }

        public fun escapeReplacement(literal: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i < literal.length) {
                val c = literal[i]
                if (c == '\\' || c == '$') sb.append('\\')
                sb.append(c)
                i++
            }
            return sb.toString()
        }
    }

    internal fun groupCount(): Int = prog.groupCount

    internal fun groupIndexOf(name: String): Int = prog.names[name] ?: -1

    private fun appendReplacement(sb: StringBuilder, match: MatchResult, replacement: String) {
        val gc = prog.groupCount
        var i = 0
        while (i < replacement.length) {
            val c = replacement[i]
            if (c == '\\') {
                i++
                if (i == replacement.length) throw IllegalArgumentException("character to be escaped is missing")
                sb.append(replacement[i])
                i++
            } else if (c == '$') {
                i++
                if (i == replacement.length) throw IllegalArgumentException("Illegal group reference: group index is missing")
                if (replacement[i] == '{') {
                    val close = replacement.indexOf('}', i)
                    if (close < 0) throw IllegalArgumentException("Named capturing group reference is missing trailing '}'")
                    val name = replacement.substring(i + 1, close)
                    val idx = prog.names[name] ?: throw IllegalArgumentException("No group with name {" + name + "}")
                    val g = match.groups[idx]
                    if (g != null) sb.append(g.value)
                    i = close + 1
                } else {
                    val d = replacement[i]
                    if (d !in '0'..'9') throw IllegalArgumentException("Illegal group reference")
                    var num = d.code - '0'.code
                    if (num > gc) throw IndexOutOfBoundsException("No group " + num)
                    i++
                    while (i < replacement.length && replacement[i] in '0'..'9') {
                        val nn = num * 10 + (replacement[i].code - '0'.code)
                        if (nn > gc) break
                        num = nn
                        i++
                    }
                    val g = match.groups[num]
                    if (g != null) sb.append(g.value)
                }
            } else {
                sb.append(c)
                i++
            }
        }
    }
}

public fun String.toRegex(): Regex = Regex(this)

public fun String.toRegex(option: RegexOption): Regex = Regex(this, option)

public fun String.toRegex(options: Set<RegexOption>): Regex = Regex(this, options)

public infix fun String.matches(regex: Regex): Boolean = regex.matches(this)

public operator fun String.contains(regex: Regex): Boolean = regex.containsMatchIn(this)

public fun String.replace(regex: Regex, replacement: String): String = regex.replace(this, replacement)

public fun String.replace(regex: Regex, transform: (MatchResult) -> CharSequence): String = regex.replace(this, transform)

public fun String.replaceFirst(regex: Regex, replacement: String): String = regex.replaceFirst(this, replacement)

public fun String.split(regex: Regex, limit: Int = 0): List<String> = regex.split(this, limit)

public fun String.splitToSequence(regex: Regex, limit: Int = 0): Sequence<String> = regex.split(this, limit).asSequence()

public fun String.findAll(regex: Regex): Sequence<MatchResult> = regex.findAll(this)

// ---- match results ------------------------------------------------------------------------------------

private class GroupsImpl(private val groupList: List<MatchGroup?>, private val regex: Regex) : AbstractList<MatchGroup?>(), MatchNamedGroupCollection {
    override val size: Int
        get() = groupList.size

    override fun get(index: Int): MatchGroup? = if (index >= 0 && index < groupList.size) groupList[index] else null

    override fun get(name: String): MatchGroup? {
        val idx = regex.groupIndexOf(name)
        if (idx < 0) throw IllegalArgumentException("No group with name " + name)
        return get(idx)
    }
}

private class RegexMatchResult(private val regex: Regex, private val input: String, private val spans: IntArray) : MatchResult {
    private val gl: List<MatchGroup?> = buildGroups()

    private fun buildGroups(): List<MatchGroup?> {
        val r = ArrayList<MatchGroup?>()
        var g = 0
        while (g * 2 < spans.size) {
            val s = spans[g * 2]
            val e = spans[g * 2 + 1]
            if (s < 0 || e < 0) r.add(null) else r.add(MatchGroup(input.substring(s, e), IntRange(s, e - 1)))
            g++
        }
        return r
    }

    override val range: IntRange
        get() = IntRange(spans[0], spans[1] - 1)

    override val value: String
        get() = input.substring(spans[0], spans[1])

    override val groups: MatchGroupCollection
        get() = GroupsImpl(gl, regex)

    override val groupValues: List<String>
        get() {
            val r = ArrayList<String>()
            for (g in gl) r.add(if (g == null) "" else g.value)
            return r
        }

    override val destructured: MatchResult.Destructured
        get() = MatchResult.Destructured(this)

    override fun next(): MatchResult? {
        val nextIndex = if (spans[1] == spans[0]) spans[1] + 1 else spans[1]
        if (nextIndex > input.length) return null
        return regex.find(input, nextIndex)
    }
}

// ---- engine -----------------------------------------------------------------------------------------------

internal class RegexProgram(val root: RNode, val groupCount: Int, val names: Map<String, Int>, val loopCount: Int, val lookbehindCount: Int)

internal class RegexMatcher(val prog: RegexProgram, val input: String) {
    val len: Int = input.length
    val starts: IntArray = IntArray(prog.groupCount + 1)
    val ends: IntArray = IntArray(prog.groupCount + 1)
    val tmpStarts: IntArray = IntArray(prog.groupCount + 1)
    val loopCnt: IntArray = IntArray(prog.loopCount + 1)
    val loopPos: IntArray = IntArray(prog.loopCount + 1)
    val lbTarget: IntArray = IntArray(prog.lookbehindCount + 1)
    var matchEnd: Int = -1
    var wholeInput: Boolean = false

    fun reset() {
        var i = 0
        while (i < starts.size) {
            starts[i] = -1
            ends[i] = -1
            tmpStarts[i] = -1
            i++
        }
        matchEnd = -1
    }

    fun matchFrom(start: Int, whole: Boolean): Boolean {
        reset()
        wholeInput = whole
        if (prog.root.match(this, start)) {
            starts[0] = start
            ends[0] = matchEnd
            return true
        }
        return false
    }

    fun find(from: Int): Boolean {
        var s = from
        while (s <= len) {
            if (matchFrom(s, false)) return true
            s++
        }
        return false
    }

    fun snapshot(): IntArray {
        val r = IntArray((starts.size) * 2)
        var i = 0
        while (i < starts.size) {
            r[i * 2] = starts[i]
            r[i * 2 + 1] = ends[i]
            i++
        }
        return r
    }
}

internal abstract class RNode {
    var next: RNode = AcceptNode
    abstract fun match(m: RegexMatcher, i: Int): Boolean
}

internal object AcceptNode : RNode() {
    override fun match(m: RegexMatcher, i: Int): Boolean {
        if (m.wholeInput && i != m.len) return false
        m.matchEnd = i
        return true
    }
}

/** ends a sub-match that is continued by someone else (lookaround, atomic group): always succeeds and records the end */
internal class SubEndNode : RNode() {
    var end: Int = -1
    override fun match(m: RegexMatcher, i: Int): Boolean {
        end = i
        return true
    }
}

internal class LookbehindEndNode(val id: Int) : RNode() {
    override fun match(m: RegexMatcher, i: Int): Boolean = i == m.lbTarget[id]
}

internal abstract class CharPred {
    abstract fun test(c: Char): Boolean
}

internal class SingleCharPred(val ch: Char, val ignoreCase: Boolean) : CharPred() {
    override fun test(c: Char): Boolean = c == ch || (ignoreCase && (c.uppercaseChar() == ch.uppercaseChar() || c.lowercaseChar() == ch.lowercaseChar()))
}

internal class AnyCharPred(val dotAll: Boolean, val unixLines: Boolean) : CharPred() {
    override fun test(c: Char): Boolean = dotAll || (c != '\n' && (unixLines || (c != '\r' && c.code != 0x85 && c.code != 0x2028 && c.code != 0x2029)))
}

internal class RangePred(val lo: Char, val hi: Char, val ignoreCase: Boolean) : CharPred() {
    override fun test(c: Char): Boolean {
        if (c >= lo && c <= hi) return true
        if (ignoreCase) {
            val u = c.uppercaseChar()
            val l = c.lowercaseChar()
            return (u >= lo && u <= hi) || (l >= lo && l <= hi)
        }
        return false
    }
}

/** kind: d D w W s S h H v V, or a posix / unicode class name */
internal class NamedPred(val kind: String, val negate: Boolean, val ignoreCase: Boolean) : CharPred() {
    override fun test(c: Char): Boolean {
        val r = base(c)
        return if (negate) !r else r
    }

    private fun base(c: Char): Boolean {
        return when (kind) {
            "d", "Digit", "Nd", "N", "IsDigit" -> c in '0'..'9'
            "w" -> c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'
            "s", "Space", "IsWhite_Space", "javaWhitespace" -> c == ' ' || c == '\t' || c == '\n' || c == '\r' || c.code == 0x0B || c.code == 0x0C
            "h" -> c == ' ' || c == '\t' || c.code == 0xA0
            "v" -> c == '\n' || c == '\r' || c.code == 0x0B || c.code == 0x0C || c.code == 0x85 || c.code == 0x2028 || c.code == 0x2029
            "Alpha" -> c in 'a'..'z' || c in 'A'..'Z'
            "Alnum" -> c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'
            "Upper" -> if (ignoreCase) c.isLetter() else c in 'A'..'Z'
            "Lower" -> if (ignoreCase) c.isLetter() else c in 'a'..'z'
            "Punct", "P", "IsPunctuation" -> c.code in 33..47 || c.code in 58..64 || c.code in 91..96 || c.code in 123..126
            "Blank" -> c == ' ' || c == '\t'
            "Cntrl" -> c.code < 32 || c.code == 127
            "XDigit" -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
            "Graph" -> c.code in 33..126
            "Print" -> c.code in 32..126
            "ASCII" -> c.code < 128
            "L", "IsAlphabetic", "IsLetter", "javaLetter", "Alphabetic" -> c.isLetter()
            "Lu", "IsUppercase", "javaUpperCase" -> c.isUpperCase()
            "Ll", "IsLowercase", "javaLowerCase" -> c.isLowerCase()
            "Z", "Zs" -> c == ' ' || c.code == 0xA0
            "javaLetterOrDigit" -> c.isLetterOrDigit()
            else -> throw IllegalArgumentException("Unknown character property name {" + kind + "}")
        }
    }
}

internal class ClassPred(val items: List<CharPred>, val negate: Boolean) : CharPred() {
    var intersect: CharPred? = null
    override fun test(c: Char): Boolean {
        var r = false
        for (p in items) {
            if (p.test(c)) {
                r = true
                break
            }
        }
        val x = intersect
        if (r && x != null) r = x.test(c)
        return if (negate) !r else r
    }
}

internal class CharNode(val pred: CharPred) : RNode() {
    override fun match(m: RegexMatcher, i: Int): Boolean = i < m.len && pred.test(m.input[i]) && next.match(m, i + 1)
}

internal class StringNode(val text: String, val ignoreCase: Boolean) : RNode() {
    override fun match(m: RegexMatcher, i: Int): Boolean {
        if (i + text.length > m.len) return false
        var k = 0
        while (k < text.length) {
            val a = m.input[i + k]
            val b = text[k]
            if (a != b && !(ignoreCase && (a.uppercaseChar() == b.uppercaseChar() || a.lowercaseChar() == b.lowercaseChar()))) return false
            k++
        }
        return next.match(m, i + text.length)
    }
}

/** quantifier over a single-character atom; mode 0 greedy, 1 lazy, 2 possessive */
internal class CharLoopNode(val pred: CharPred, val min: Int, val max: Int, val mode: Int) : RNode() {
    override fun match(m: RegexMatcher, i: Int): Boolean {
        val limit = if (max < 0 || max > m.len - i) m.len - i else max
        if (mode == 1) {
            var n = 0
            while (n < min) {
                if (n >= limit || !pred.test(m.input[i + n])) return false
                n++
            }
            while (true) {
                if (next.match(m, i + n)) return true
                if (n >= limit || !pred.test(m.input[i + n])) return false
                n++
            }
        }
        var n = 0
        while (n < limit && pred.test(m.input[i + n])) n++
        if (n < min) return false
        if (mode == 2) return next.match(m, i + n)
        while (n >= min) {
            if (next.match(m, i + n)) return true
            n--
        }
        return false
    }
}

internal class BeginLineNode(val multiline: Boolean) : RNode() {
    override fun match(m: RegexMatcher, i: Int): Boolean {
        val ok = i == 0 || (multiline && i < m.len && m.input[i - 1] == '\n')
        return ok && next.match(m, i)
    }
}

internal class EndLineNode(val multiline: Boolean) : RNode() {
    override fun match(m: RegexMatcher, i: Int): Boolean {
        var ok: Boolean
        if (i == m.len) {
            ok = true
        } else if (multiline) {
            ok = m.input[i] == '\n' || m.input[i] == '\r'
        } else {
            ok = (i == m.len - 1 && m.input[i] == '\n') || (i == m.len - 2 && m.input[i] == '\r' && m.input[i + 1] == '\n')
        }
        return ok && next.match(m, i)
    }
}

/** kind 0 = \A, 1 = \z, 2 = \Z */
internal class InputBoundNode(val kind: Int) : RNode() {
    override fun match(m: RegexMatcher, i: Int): Boolean {
        val ok = when (kind) {
            0 -> i == 0
            1 -> i == m.len
            else -> i == m.len || (i == m.len - 1 && m.input[i] == '\n')
        }
        return ok && next.match(m, i)
    }
}

internal class WordBoundaryNode(val negate: Boolean) : RNode() {
    private fun isWord(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'
    override fun match(m: RegexMatcher, i: Int): Boolean {
        val before = i > 0 && isWord(m.input[i - 1])
        val after = i < m.len && isWord(m.input[i])
        val atBoundary = before != after
        return (atBoundary != negate) && next.match(m, i)
    }
}

internal class GroupHeadNode(val idx: Int) : RNode() {
    override fun match(m: RegexMatcher, i: Int): Boolean {
        val save = m.tmpStarts[idx]
        m.tmpStarts[idx] = i
        if (next.match(m, i)) return true
        m.tmpStarts[idx] = save
        return false
    }
}

internal class GroupTailNode(val idx: Int) : RNode() {
    override fun match(m: RegexMatcher, i: Int): Boolean {
        val ss = m.starts[idx]
        val se = m.ends[idx]
        m.starts[idx] = m.tmpStarts[idx]
        m.ends[idx] = i
        if (next.match(m, i)) return true
        m.starts[idx] = ss
        m.ends[idx] = se
        return false
    }
}

internal class BackrefNode(var idx: Int, val name: String?, val ignoreCase: Boolean) : RNode() {
    override fun match(m: RegexMatcher, i: Int): Boolean {
        val s = m.starts[idx]
        val e = m.ends[idx]
        if (s < 0 || e < 0) return false
        val n = e - s
        if (i + n > m.len) return false
        var k = 0
        while (k < n) {
            val a = m.input[s + k]
            val b = m.input[i + k]
            if (a != b && !(ignoreCase && a.uppercaseChar() == b.uppercaseChar())) return false
            k++
        }
        return next.match(m, i + n)
    }
}

internal class AltNode(val branches: List<RNode>) : RNode() {
    override fun match(m: RegexMatcher, i: Int): Boolean {
        for (b in branches) if (b.match(m, i)) return true
        return false
    }
}

/** joins the end of every alternative to what follows the alternation */
internal class JoinNode : RNode() {
    override fun match(m: RegexMatcher, i: Int): Boolean = next.match(m, i)
}

internal class LookaheadNode(val body: RNode, val end: SubEndNode, val negate: Boolean) : RNode() {
    override fun match(m: RegexMatcher, i: Int): Boolean {
        val ss = m.starts.copyOf()
        val se = m.ends.copyOf()
        val ok = body.match(m, i)
        if (negate) {
            restore(m, ss, se)
            return !ok && next.match(m, i)
        }
        if (!ok) return false
        if (next.match(m, i)) return true
        restore(m, ss, se)
        return false
    }

    private fun restore(m: RegexMatcher, ss: IntArray, se: IntArray) {
        var k = 0
        while (k < ss.size) {
            m.starts[k] = ss[k]
            m.ends[k] = se[k]
            k++
        }
    }
}

internal class LookbehindNode(val body: RNode, val id: Int, val negate: Boolean) : RNode() {
    override fun match(m: RegexMatcher, i: Int): Boolean {
        val ss = m.starts.copyOf()
        val se = m.ends.copyOf()
        val saveTarget = m.lbTarget[id]
        m.lbTarget[id] = i
        var found = false
        var j = i
        while (j >= 0 && !found) {
            if (body.match(m, j)) found = true
            j--
        }
        m.lbTarget[id] = saveTarget
        if (negate) {
            var k = 0
            while (k < ss.size) {
                m.starts[k] = ss[k]
                m.ends[k] = se[k]
                k++
            }
            return !found && next.match(m, i)
        }
        if (!found) return false
        return next.match(m, i)
    }
}

internal class AtomicNode(val body: RNode, val end: SubEndNode) : RNode() {
    override fun match(m: RegexMatcher, i: Int): Boolean {
        if (!body.match(m, i)) return false
        return next.match(m, end.end)
    }
}

/** quantifier over a general sub-pattern; mode 0 greedy, 1 lazy */
internal class LoopNode(val id: Int, val min: Int, val max: Int, val mode: Int) : RNode() {
    var body: RNode = AcceptNode

    override fun match(m: RegexMatcher, i: Int): Boolean {
        val saveC = m.loopCnt[id]
        val saveP = m.loopPos[id]
        m.loopCnt[id] = 0
        m.loopPos[id] = -1
        val r = step(m, i)
        m.loopCnt[id] = saveC
        m.loopPos[id] = saveP
        return r
    }

    private fun iterate(m: RegexMatcher, i: Int, c: Int): Boolean {
        val sp = m.loopPos[id]
        m.loopCnt[id] = c + 1
        m.loopPos[id] = i
        if (body.match(m, i)) return true
        m.loopCnt[id] = c
        m.loopPos[id] = sp
        return false
    }

    fun step(m: RegexMatcher, i: Int): Boolean {
        val c = m.loopCnt[id]
        if (c < min) return iterate(m, i, c)
        val canIterate = (max < 0 || c < max) && !(c > 0 && m.loopPos[id] == i)
        if (mode == 0) {
            if (canIterate && iterate(m, i, c)) return true
            return exit(m, i, c)
        }
        if (exit(m, i, c)) return true
        return canIterate && iterate(m, i, c)
    }

    private fun exit(m: RegexMatcher, i: Int, c: Int): Boolean {
        val sc = m.loopCnt[id]
        val sp = m.loopPos[id]
        if (next.match(m, i)) return true
        m.loopCnt[id] = sc
        m.loopPos[id] = sp
        return false
    }
}

internal class LoopBackNode(val loop: LoopNode) : RNode() {
    override fun match(m: RegexMatcher, i: Int): Boolean = loop.step(m, i)
}

// ---- parser ---------------------------------------------------------------------------------------------------

internal class Frag(val head: RNode, val tail: RNode)

internal class RegexParser(val pattern: String, options: Set<RegexOption>) {
    var pos = 0
    var groupCount = 0
    var loopCount = 0
    var lookbehindCount = 0
    val names = HashMap<String, Int>()
    var ignoreCase = options.contains(RegexOption.IGNORE_CASE)
    var multiline = options.contains(RegexOption.MULTILINE)
    var dotAll = options.contains(RegexOption.DOT_MATCHES_ALL)
    var comments = options.contains(RegexOption.COMMENTS)
    var unixLines = options.contains(RegexOption.UNIX_LINES)
    val literal = options.contains(RegexOption.LITERAL)
    val backrefs = ArrayList<BackrefNode>()

    fun fail(msg: String): Nothing = throw IllegalArgumentException(msg + " near index " + pos + "\n" + pattern)

    fun parse(): RegexProgram {
        val root: Frag
        if (literal) {
            root = literalFrag(pattern)
        } else {
            root = parseAlternation()
            if (pos < pattern.length) {
                if (pattern[pos] == ')') fail("Unmatched closing ')'")
                fail("Unexpected character")
            }
        }
        for (b in backrefs) {
            if (b.name != null) b.idx = names[b.name] ?: fail("named capturing group <" + b.name + "> does not exist")
            if (b.idx > groupCount) fail("No such group yet exists at this point in the pattern")
        }
        return RegexProgram(root.head, groupCount, names, loopCount, lookbehindCount)
    }

    private fun literalFrag(text: String): Frag {
        val n = StringNode(text, ignoreCase)
        return Frag(n, n)
    }

    private fun parseAlternation(): Frag {
        val first = parseSequence()
        if (pos >= pattern.length || pattern[pos] != '|') return first
        val branches = ArrayList<RNode>()
        val join = JoinNode()
        first.tail.next = join
        branches.add(first.head)
        while (pos < pattern.length && pattern[pos] == '|') {
            pos++
            val f = parseSequence()
            f.tail.next = join
            branches.add(f.head)
        }
        val alt = AltNode(branches)
        return Frag(alt, join)
    }

    private fun parseSequence(): Frag {
        val head = JoinNode()
        var tail: RNode = head
        while (pos < pattern.length) {
            val c = pattern[pos]
            if (c == '|' || c == ')') break
            if (comments) {
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++
                    continue
                }
                if (c == '#') {
                    while (pos < pattern.length && pattern[pos] != '\n') pos++
                    continue
                }
            }
            val atom = parseAtom() ?: continue
            val q = parseQuantifier(atom)
            tail.next = q.head
            tail = q.tail
        }
        return Frag(head, tail)
    }

    private fun parseQuantifier(atom: Frag): Frag {
        if (pos >= pattern.length) return atom
        val c = pattern[pos]
        var min: Int
        var max: Int
        if (c == '*') {
            min = 0
            max = -1
            pos++
        } else if (c == '+') {
            min = 1
            max = -1
            pos++
        } else if (c == '?') {
            min = 0
            max = 1
            pos++
        } else if (c == '{') {
            val close = pattern.indexOf('}', pos)
            if (close < 0) fail("Unclosed counted closure")
            val body = pattern.substring(pos + 1, close)
            val comma = body.indexOf(',')
            if (body.isEmpty()) fail("Illegal repetition")
            if (comma < 0) {
                min = body.toIntOrNull() ?: fail("Illegal repetition")
                max = min
            } else {
                min = body.substring(0, comma).toIntOrNull() ?: fail("Illegal repetition")
                val hi = body.substring(comma + 1)
                max = if (hi.isEmpty()) -1 else (hi.toIntOrNull() ?: fail("Illegal repetition"))
            }
            if (max >= 0 && max < min) fail("Illegal repetition range")
            pos = close + 1
        } else {
            return atom
        }
        var mode = 0
        if (pos < pattern.length && pattern[pos] == '?') {
            mode = 1
            pos++
        } else if (pos < pattern.length && pattern[pos] == '+') {
            mode = 2
            pos++
        }
        val single = atom.head
        if (single is CharNode && single === atom.tail) {
            return charLoop(single.pred, min, max, mode)
        }
        if (single is StringNode && single === atom.tail && single.text.length == 1) {
            return charLoop(SingleCharPred(single.text[0], single.ignoreCase), min, max, mode)
        }
        if (mode == 2) {
            // possessive: an atomic group around the greedy loop
            val inner = makeLoop(atom, min, max, 0)
            val end = SubEndNode()
            inner.tail.next = end
            val at = AtomicNode(inner.head, end)
            return Frag(at, at)
        }
        return makeLoop(atom, min, max, mode)
    }

    private fun charLoop(pred: CharPred, min: Int, max: Int, mode: Int): Frag {
        val n = CharLoopNode(pred, min, max, mode)
        return Frag(n, n)
    }

    private fun makeLoop(atom: Frag, min: Int, max: Int, mode: Int): Frag {
        val loop = LoopNode(loopCount, min, max, mode)
        loopCount++
        loop.body = atom.head
        atom.tail.next = LoopBackNode(loop)
        return Frag(loop, loop)
    }

    private fun single(n: RNode): Frag = Frag(n, n)

    private fun charAtom(pred: CharPred): Frag = single(CharNode(pred))

    private fun parseAtom(): Frag? {
        val c = pattern[pos]
        when (c) {
            '(' -> return parseGroup()
            '[' -> {
                pos++
                return charAtom(parseClass())
            }
            '.' -> {
                pos++
                return charAtom(AnyCharPred(dotAll, unixLines))
            }
            '^' -> {
                pos++
                return single(BeginLineNode(multiline))
            }
            '$' -> {
                pos++
                return single(EndLineNode(multiline))
            }
            '\\' -> return parseEscape()
            '*', '+', '?' -> fail("Dangling meta character '" + c + "'")
            else -> {
                pos++
                return single(StringNode(c.toString(), ignoreCase))
            }
        }
    }

    private fun parseGroup(): Frag? {
        val saveI = ignoreCase
        val saveM = multiline
        val saveS = dotAll
        val saveX = comments
        val r = parseGroupInner()
        if (r != null) {
            ignoreCase = saveI
            multiline = saveM
            dotAll = saveS
            comments = saveX
        }
        return r
    }

    private fun parseGroupInner(): Frag? {
        pos++ // (
        if (pos < pattern.length && pattern[pos] == '?') {
            pos++
            if (pos >= pattern.length) fail("Unknown group type")
            val k = pattern[pos]
            if (k == ':') {
                pos++
                return finishGroup(parseAlternation())
            }
            if (k == '=' || k == '!') {
                pos++
                val body = parseAlternation()
                val end = SubEndNode()
                body.tail.next = end
                expectClose()
                return single(LookaheadNode(body.head, end, k == '!'))
            }
            if (k == '>') {
                pos++
                val body = parseAlternation()
                val end = SubEndNode()
                body.tail.next = end
                expectClose()
                return single(AtomicNode(body.head, end))
            }
            if (k == '<') {
                pos++
                if (pos < pattern.length && (pattern[pos] == '=' || pattern[pos] == '!')) {
                    val neg = pattern[pos] == '!'
                    pos++
                    val id = lookbehindCount
                    lookbehindCount++
                    val body = parseAlternation()
                    body.tail.next = LookbehindEndNode(id)
                    expectClose()
                    return single(LookbehindNode(body.head, id, neg))
                }
                val close = pattern.indexOf('>', pos)
                if (close < 0) fail("named capturing group is missing trailing '>'")
                val name = pattern.substring(pos, close)
                pos = close + 1
                groupCount++
                val idx = groupCount
                if (names.containsKey(name)) fail("Named capturing group <" + name + "> is already defined")
                names[name] = idx
                return captureGroup(idx)
            }
            // inline flags: (?i) (?i:...) (?-i) ...
            val saveI = ignoreCase
            val saveM = multiline
            val saveS = dotAll
            val saveX = comments
            var on = true
            while (pos < pattern.length && pattern[pos] != ')' && pattern[pos] != ':') {
                val f = pattern[pos]
                if (f == '-') on = false
                else if (f == 'i') ignoreCase = on
                else if (f == 'm') multiline = on
                else if (f == 's') dotAll = on
                else if (f == 'x') comments = on
                else if (f == 'd' || f == 'u' || f == 'U') {
                } else fail("Unknown inline modifier")
                pos++
            }
            if (pos >= pattern.length) fail("Unclosed group")
            if (pattern[pos] == ')') {
                pos++
                return null
            }
            pos++ // ':'
            val body = parseAlternation()
            expectClose()
            ignoreCase = saveI
            multiline = saveM
            dotAll = saveS
            comments = saveX
            return body
        }
        groupCount++
        return captureGroup(groupCount)
    }

    private fun captureGroup(idx: Int): Frag {
        val body = parseAlternation()
        expectClose()
        val head = GroupHeadNode(idx)
        val tail = GroupTailNode(idx)
        head.next = body.head
        body.tail.next = tail
        return Frag(head, tail)
    }

    private fun finishGroup(body: Frag): Frag {
        expectClose()
        return body
    }

    private fun expectClose() {
        if (pos >= pattern.length || pattern[pos] != ')') fail("Unclosed group")
        pos++
    }

    private fun hexValue(s: String): Int {
        return s.toIntOrNull(16) ?: fail("Illegal hexadecimal escape sequence")
    }

    private fun parseEscape(): Frag {
        pos++
        if (pos >= pattern.length) fail("Unexpected internal error")
        val c = pattern[pos]
        pos++
        when (c) {
            'd', 'D', 'w', 'W', 's', 'S', 'h', 'H', 'v', 'V' -> {
                val kind = c.lowercaseChar().toString()
                return charAtom(NamedPred(kind, c.isUpperCase(), ignoreCase))
            }
            'p', 'P' -> return charAtom(parseProperty(c == 'P'))
            'b' -> return single(WordBoundaryNode(false))
            'B' -> return single(WordBoundaryNode(true))
            'A' -> return single(InputBoundNode(0))
            'z' -> return single(InputBoundNode(1))
            'Z' -> return single(InputBoundNode(2))
            'G' -> return single(InputBoundNode(0))
            'Q' -> {
                val end = pattern.indexOf("\\E", pos)
                val text = if (end < 0) pattern.substring(pos) else pattern.substring(pos, end)
                pos = if (end < 0) pattern.length else end + 2
                if (text.isEmpty()) return single(JoinNode())
                return literalFrag(text)
            }
            'k' -> {
                if (pos >= pattern.length || pattern[pos] != '<') fail("\\k is not followed by '<' for named capturing group")
                val close = pattern.indexOf('>', pos)
                if (close < 0) fail("named capturing group is missing trailing '>'")
                val name = pattern.substring(pos + 1, close)
                pos = close + 1
                val b = BackrefNode(0, name, ignoreCase)
                backrefs.add(b)
                return single(b)
            }
            in '1'..'9' -> {
                var num = c.code - '0'.code
                while (pos < pattern.length && pattern[pos] in '0'..'9') {
                    val nn = num * 10 + (pattern[pos].code - '0'.code)
                    if (nn > groupCount) break
                    num = nn
                    pos++
                }
                val b = BackrefNode(num, null, ignoreCase)
                backrefs.add(b)
                return single(b)
            }
            else -> {
                pos--
                val ch = parseEscapedChar()
                return single(StringNode(ch.toString(), ignoreCase))
            }
        }
    }

    /** at pos: the character after a backslash that denotes a single literal character */
    private fun parseEscapedChar(): Char {
        val c = pattern[pos]
        pos++
        return when (c) {
            't' -> '\t'
            'n' -> '\n'
            'r' -> '\r'
            'f' -> '\u000C'
            'a' -> '\u0007'
            'e' -> '\u001B'
            '0' -> {
                var v = 0
                var cnt = 0
                while (pos < pattern.length && cnt < 3 && pattern[pos] in '0'..'7') {
                    v = v * 8 + (pattern[pos].code - '0'.code)
                    pos++
                    cnt++
                }
                v.toChar()
            }
            'x' -> {
                if (pos < pattern.length && pattern[pos] == '{') {
                    val close = pattern.indexOf('}', pos)
                    if (close < 0) fail("Unclosed hexadecimal escape sequence")
                    val v = hexValue(pattern.substring(pos + 1, close))
                    pos = close + 1
                    v.toChar()
                } else {
                    if (pos + 2 > pattern.length) fail("Illegal hexadecimal escape sequence")
                    val v = hexValue(pattern.substring(pos, pos + 2))
                    pos += 2
                    v.toChar()
                }
            }
            'u' -> {
                if (pos + 4 > pattern.length) fail("Illegal Unicode escape sequence")
                val v = hexValue(pattern.substring(pos, pos + 4))
                pos += 4
                v.toChar()
            }
            else -> {
                if (c.isLetter() || c in '0'..'9') fail("Illegal/unsupported escape sequence")
                c
            }
        }
    }

    private fun parseProperty(negate: Boolean): CharPred {
        if (pos >= pattern.length) fail("Illegal character property")
        var name: String
        if (pattern[pos] == '{') {
            val close = pattern.indexOf('}', pos)
            if (close < 0) fail("Unclosed character family")
            name = pattern.substring(pos + 1, close)
            pos = close + 1
        } else {
            name = pattern[pos].toString()
            pos++
        }
        if (name.startsWith("Is") && name != "IsAlphabetic" && name != "IsLetter" && name != "IsDigit" && name != "IsUppercase" &&
            name != "IsLowercase" && name != "IsPunctuation" && name != "IsWhite_Space") name = name.substring(2)
        if (name.startsWith("In")) name = name.substring(2)
        return NamedPred(name, negate, ignoreCase)
    }

    private fun parseClass(): CharPred {
        var negate = false
        if (pos < pattern.length && pattern[pos] == '^') {
            negate = true
            pos++
        }
        val items = ArrayList<CharPred>()
        var first = true
        while (true) {
            if (pos >= pattern.length) fail("Unclosed character class")
            val c = pattern[pos]
            if (c == ']' && !first) {
                pos++
                break
            }
            first = false
            if (c == '[') {
                pos++
                items.add(parseClass())
                continue
            }
            if (c == '&' && pos + 1 < pattern.length && pattern[pos + 1] == '&') {
                pos += 2
                // the rest of the class is intersected with what we have so far
                val restPred = parseClassRest()
                val inter = IntersectPred(ClassPred(items, false), restPred)
                return if (negate) NegPred(inter) else inter
            }
            var lo: Char
            if (c == '\\') {
                pos++
                if (pos >= pattern.length) fail("Unclosed character class")
                val e = pattern[pos]
                if (e == 'd' || e == 'D' || e == 'w' || e == 'W' || e == 's' || e == 'S' || e == 'h' || e == 'H' || e == 'v' || e == 'V') {
                    pos++
                    items.add(NamedPred(e.lowercaseChar().toString(), e.isUpperCase(), ignoreCase))
                    continue
                }
                if (e == 'p' || e == 'P') {
                    pos++
                    items.add(parseProperty(e == 'P'))
                    continue
                }
                if (e == 'Q') {
                    pos++
                    val end = pattern.indexOf("\\E", pos)
                    val text = if (end < 0) pattern.substring(pos) else pattern.substring(pos, end)
                    pos = if (end < 0) pattern.length else end + 2
                    for (ch in text) items.add(SingleCharPred(ch, ignoreCase))
                    continue
                }
                if (e == 'b') {
                    pos++
                    lo = '\b'
                } else {
                    lo = parseEscapedChar()
                }
            } else {
                lo = c
                pos++
            }
            if (pos + 1 < pattern.length && pattern[pos] == '-' && pattern[pos + 1] != ']') {
                pos++
                var hi: Char
                if (pattern[pos] == '\\') {
                    pos++
                    hi = parseEscapedChar()
                } else if (pattern[pos] == '[') {
                    // "a-[" is a literal '-' followed by a nested class
                    items.add(SingleCharPred(lo, ignoreCase))
                    items.add(SingleCharPred('-', ignoreCase))
                    continue
                } else {
                    hi = pattern[pos]
                    pos++
                }
                if (hi < lo) fail("Illegal character range")
                items.add(RangePred(lo, hi, ignoreCase))
            } else {
                items.add(SingleCharPred(lo, ignoreCase))
            }
        }
        return ClassPred(items, negate)
    }

    /** the part of a class after `&&`, up to and including the closing bracket */
    private fun parseClassRest(): CharPred {
        // reuse parseClass: the remaining text looks like a class body that ends with ']'
        val saved = pos
        val p = parseClassBody()
        if (p == null) pos = saved
        return p ?: ClassPred(ArrayList<CharPred>(), false)
    }

    private fun parseClassBody(): CharPred? {
        // a nested class "[...]" directly after && is parsed as such; otherwise the items up to ']' form the class
        if (pos < pattern.length && pattern[pos] == '[') {
            pos++
            val inner = parseClass()
            if (pos < pattern.length && pattern[pos] == ']') pos++
            return inner
        }
        // "[a-z&&aeiou]": the rest is a plain class body; parse as if it started right after '['
        return parseClass()
    }
}

internal class IntersectPred(val a: CharPred, val b: CharPred) : CharPred() {
    override fun test(c: Char): Boolean = a.test(c) && b.test(c)
}

internal class NegPred(val a: CharPred) : CharPred() {
    override fun test(c: Char): Boolean = !a.test(c)
}
