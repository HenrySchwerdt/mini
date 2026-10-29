// Functions, default and named arguments, when, loops, ranges, string templates.

fun greet(name: String, greeting: String = "Hello", punctuation: Char = '!'): String =
    "$greeting, $name$punctuation"

fun classify(n: Int): String = when {
    n < 0 -> "negative"
    n == 0 -> "zero"
    n % 2 == 0 -> "even"
    else -> "odd"
}

fun fizz(i: Int): String = when {
    i % 15 == 0 -> "FizzBuzz"
    i % 3 == 0 -> "Fizz"
    i % 5 == 0 -> "Buzz"
    else -> i.toString()
}

fun main() {
    println(greet("Mini"))
    println(greet("Mini", punctuation = '?'))
    println(greet(greeting = "Hi", name = "Kotlin"))

    for (n in listOf(-5, 0, 4, 7)) println("$n is ${classify(n)}")

    var sum = 0
    for (i in 1..10 step 3) sum += i        // 1 + 4 + 7 + 10
    println("sum = $sum")

    for (i in 3 downTo 1) print("$i ")
    println("liftoff")

    println((1..15).joinToString(" ") { fizz(it) })

    val x = 42
    println(if (x in 0..100) "in range" else "out of range")

    outer@ for (i in 1..3) {
        for (j in 1..3) {
            if (i * j == 4) break@outer
            print("$i*$j;")
        }
    }
    println()
}
