// toString / equals / hashCode on CharSequence values.
fun show(c: CharSequence): String = c.toString() + "/" + c.hashCode().equals(c.hashCode()) + "/" + c.equals(c)
fun main() {
    println(show("ab"))
    val sb = StringBuilder("cd")
    println(show(sb))
    val a: CharSequence = "x"
    val b: CharSequence = "x"
    println(a == b)
    println(a.toString() == "x")
}
