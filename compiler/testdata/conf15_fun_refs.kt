// function references: top-level, member, bound, constructor, extension, property, overloaded
fun twice(x: Int) = x * 2
fun twice(s: String) = s + s
class Greeter(val greeting: String) { fun greet(n: String) = "$greeting, $n"; val len get() = greeting.length; companion object { fun make() = Greeter("hey") } }
fun String.shout() = uppercase() + "!"
data class W(val v: Int)
fun apply1(f: (Int) -> Int, x: Int) = f(x)
fun <T, R> mapAll(l: List<T>, f: (T) -> R) = l.map(f)
object Singleton { fun hello() = "hello from object" }
fun main() {
    println(listOf(1, 2, 3).map(::twice)); println(listOf("a", "b").map(::twice)); val f: (String) -> String = ::twice; println(f("z"))
    println(apply1(::twice, 4)); val g = Greeter("hi"); val b = g::greet; println(b("bob")); println(listOf("x", "y").map(g::greet))
    val unbound = Greeter::greet; println(unbound(g, "amy")); println(Greeter::len.invoke(g)); println(listOf(g, Greeter("hello")).map(Greeter::len))
    println(mapAll(listOf(1, 2), ::W)); println(listOf(1, 2).map(::W).map(W::v)); println(listOf("a").map(String::shout)); println("q".let(String::shout))
    val make = Greeter.Companion::make; println(make().greeting); val sing = Singleton::hello; println(sing())
    val len = String::length; println(len("four")); println(listOf("aa", "b").map(String::length)); println(listOf("b", "a").sortedBy(String::toString))
    val p = g::len; println(p.get()); val ref: (Int, Int) -> Int = Int::plus; println(ref(2, 3)); println(listOf(1, 2, 3).reduce(Int::plus)); println(listOf(3, 1, 2).sortedWith(Int::compareTo))
    val cons: (String) -> Greeter = ::Greeter; println(cons("yo").greet("z")); val pr: (String) -> Unit = ::println; pr("via ref")
    println(listOf("1", "2").map(String::toInt)); println(listOf(1, 2).map(Int::toString)); println(listOf(1.5, 2.5).map(Double::toInt)); println(setOf(1, 2).map(Any::toString))
    println(listOf(1, 2, 3).filter(::isOdd)); println((1..5).filterNot(::isOdd)); println(listOf("a", "b").forEach(::print))
    println(listOf(W(1), W(2)).sumOf(W::v)); println(listOf(W(3)).maxByOrNull(W::v)); println(W::class.simpleName)
}
fun isOdd(x: Int) = x % 2 == 1
