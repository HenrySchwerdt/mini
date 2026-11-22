// sealed generic hierarchy, covariant sealed result, extension on generics, fold/map functions, Either-style code
sealed class Res<out T, out E> {
    data class Ok<T>(val v: T) : Res<T, Nothing>()
    data class Err<E>(val e: E) : Res<Nothing, E>()
    fun <R> map(f: (T) -> R): Res<R, E> = when (this) { is Ok -> Ok(f(v)); is Err -> this }
    fun <R> flatMap(f: (T) -> Res<R, @UnsafeVariance E>): Res<R, E> = when (this) { is Ok -> f(v); is Err -> this }
    fun getOr(d: @UnsafeVariance T): T = when (this) { is Ok -> v; is Err -> d }
    fun <R> fold(ok: (T) -> R, err: (E) -> R): R = when (this) { is Ok -> ok(v); is Err -> err(e) }
}
fun parse(s: String): Res<Int, String> = s.toIntOrNull()?.let { Res.Ok(it) } ?: Res.Err("bad: $s")
fun half(n: Int): Res<Int, String> = if (n % 2 == 0) Res.Ok(n / 2) else Res.Err("odd: $n")
sealed interface Opt<out T> { object None : Opt<Nothing> { override fun toString() = "None" }; data class Some<T>(val v: T) : Opt<T> }
fun <T> Opt<T>.orElse(d: T): T = when (this) { Opt.None -> d; is Opt.Some -> v }
fun <T, R> Opt<T>.map(f: (T) -> R): Opt<R> = when (this) { Opt.None -> Opt.None; is Opt.Some -> Opt.Some(f(v)) }
fun main() {
    println(parse("10").flatMap { half(it) }); println(parse("7").flatMap { half(it) }); println(parse("x").map { it * 2 }); println(parse("4").map { it + 1 }.getOr(0)); println(parse("q").getOr(-1))
    println(parse("8").fold({ "ok $it" }, { "err $it" })); println(parse("z").fold({ "ok $it" }, { "err $it" })); val list: List<Res<Int, String>> = listOf(parse("1"), parse("a"), parse("3")); println(list.filterIsInstance<Res.Ok<Int>>().sumOf { it.v }); println(list.count { it is Res.Err })
    println(Opt.Some(3).map { it * 2 }.orElse(0)); println(Opt.None.orElse("d")); val o: Opt<String> = Opt.Some("x"); println(o.map { it.length }); println((Opt.None as Opt<Int>).map { it + 1 })
    val x: Res<Int, Nothing> = Res.Ok(1); val y: Res<Int, String> = x; println(y); val e: Res<Nothing, String> = Res.Err("e"); val z: Res<Int, String> = e; println(z); println(listOf(x, e).size)
}
