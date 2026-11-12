package parsekit

/** The result of a parser: the value and the rest of the input, or the position and the expectation that failed. */
sealed class Res<out T> {
    data class Ok<T>(val value: T, val rest: Input) : Res<T>()
    data class Fail(val pos: Int, val expected: String) : Res<Nothing>()
}

class Input(val text: String, val pos: Int = 0) {
    fun peek(): Char? = text.getOrNull(pos)
    fun advance(n: Int = 1) = Input(text, pos + n)
    fun startsWith(s: String) = text.startsWith(s, pos)
    val atEnd: Boolean get() = pos >= text.length
}

fun interface Parser<out T> {
    fun parse(input: Input): Res<T>
}

class ParseError(val pos: Int, message: String) : Exception("at $pos: $message")

fun <T> Parser<T>.run(text: String): T = when (val r = parse(Input(text))) {
    is Res.Ok -> if (r.rest.atEnd) r.value else throw ParseError(r.rest.pos, "unexpected '${r.rest.peek()}'")
    is Res.Fail -> throw ParseError(r.pos, "expected ${r.expected}")
}

fun <T> Parser<T>.tryRun(text: String): T? = try {
    run(text)
} catch (e: ParseError) {
    null
}

// --- primitives ---------------------------------------------------------------------------------------------------

fun char(c: Char) = Parser<Char> { i -> if (i.peek() == c) Res.Ok(c, i.advance()) else Res.Fail(i.pos, "'$c'") }
fun string(s: String) = Parser<String> { i -> if (i.startsWith(s)) Res.Ok(s, i.advance(s.length)) else Res.Fail(i.pos, "\"$s\"") }
fun satisfy(what: String, pred: (Char) -> Boolean) = Parser<Char> { i ->
    val c = i.peek()
    if (c != null && pred(c)) Res.Ok(c, i.advance()) else Res.Fail(i.pos, what)
}

val digit = satisfy("a digit") { it in '0'..'9' }
val letter = satisfy("a letter") { it.isLetter() }
val whitespace: Parser<Unit> = many(satisfy("whitespace") { it.isWhitespace() }).map { }

// --- combinators --------------------------------------------------------------------------------------------------

inline fun <T, R> Parser<T>.map(crossinline f: (T) -> R) = Parser<R> { i ->
    when (val r = parse(i)) {
        is Res.Ok -> Res.Ok(f(r.value), r.rest)
        is Res.Fail -> r
    }
}

fun <T, R> Parser<T>.flatMap(f: (T) -> Parser<R>) = Parser<R> { i ->
    when (val r = parse(i)) {
        is Res.Ok -> f(r.value).parse(r.rest)
        is Res.Fail -> r
    }
}

infix fun <A, B> Parser<A>.then(other: Parser<B>): Parser<Pair<A, B>> = flatMap { a -> other.map { b -> a to b } }
infix fun <A, B> Parser<A>.skipLeft(other: Parser<B>): Parser<B> = then(other).map { it.second }
infix fun <A, B> Parser<A>.skipRight(other: Parser<B>): Parser<A> = then(other).map { it.first }

infix fun <T> Parser<T>.or(other: Parser<T>) = Parser<T> { i ->
    when (val r = parse(i)) {
        is Res.Ok -> r
        is Res.Fail -> when (val r2 = other.parse(i)) {
            is Res.Ok -> r2
            is Res.Fail -> if (r2.pos >= r.pos) Res.Fail(r2.pos, if (r2.pos == r.pos) r.expected + " or " + r2.expected else r2.expected) else r
        }
    }
}

fun <T> many(p: Parser<T>) = Parser<List<T>> { i ->
    val out = ArrayList<T>()
    var cur = i
    while (true) {
        val r = p.parse(cur)
        if (r is Res.Ok && r.rest.pos > cur.pos) {
            out.add(r.value)
            cur = r.rest
        } else break
    }
    Res.Ok(out, cur)
}

fun <T> many1(p: Parser<T>) = p.then(many(p)).map { listOf(it.first) + it.second }
fun <T> optional(p: Parser<T>) = Parser<T?> { i ->
    when (val r = p.parse(i)) {
        is Res.Ok -> Res.Ok(r.value, r.rest)
        is Res.Fail -> Res.Ok(null, i)
    }
}

fun <T, S> sepBy(p: Parser<T>, sep: Parser<S>): Parser<List<T>> =
    optional(p.then(many(sep skipLeft p))).map { if (it == null) emptyList() else listOf(it.first) + it.second }

fun <T> token(p: Parser<T>): Parser<T> = p skipRight whitespace
fun symbol(s: String) = token(string(s))
fun <T> between(open: Parser<*>, p: Parser<T>, close: Parser<*>): Parser<T> = open skipLeft p skipRight close

/** Left-associative chain: term (op term)*. */
fun <T> chainl1(term: Parser<T>, op: Parser<(T, T) -> T>): Parser<T> =
    term.then(many(op.then(term))).map { (first, rest) -> rest.fold(first) { acc, (f, x) -> f(acc, x) } }

/** For recursive grammars: the parser is built on first use. */
fun <T> lazyParser(make: () -> Parser<T>): Parser<T> {
    var cached: Parser<T>? = null
    return Parser { i ->
        val p = cached ?: make().also { cached = it }
        p.parse(i)
    }
}

val integer: Parser<Int> = token(optional(char('-')).then(many1(digit)).map { (sign, ds) ->
    val n = ds.joinToString("").toInt()
    if (sign != null) -n else n
})

val identifier: Parser<String> = token(letter.then(many(letter or digit)).map { (h, t) -> h + t.joinToString("") })
