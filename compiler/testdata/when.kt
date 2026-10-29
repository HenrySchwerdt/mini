fun name(n: Int): String = when (n) {
    1 -> "one"
    2 -> "two"
    3 -> "three"
    else -> "many"
}

fun sign(n: Int): Int {
    return when {
        n < 0 -> -1
        n == 0 -> 0
        else -> 1
    }
}

fun classify(c: Char): String = when (c) {
    'a' -> "vowel a"
    'b' -> "consonant b"
    else -> "other"
}

fun check(flag: Boolean): String = when (flag) {
    true -> "yes"
    false -> "no"
}

fun main() {
    println(name(1))
    println(name(3))
    println(name(42))
    println(sign(-5))
    println(sign(0))
    println(sign(9))
    println(classify('a'))
    println(classify('b'))
    println(classify('z'))
    println(check(true))
    println(check(1 > 2))
    val x = 7
    val kind = if (x % 2 == 0) "even" else "odd"
    println(kind)
    val big = if (x > 5) if (x > 6) "gt6" else "eq6" else "le5"
    println(big)
    when (x) {
        7 -> println("seven")
        8 -> println("eight")
    }
    when {
        x > 100 -> println("huge")
        x > 5 -> println("medium")
        else -> println("small")
    }
    val s = when (x) {
        1, 2, 3 -> "low"
        4, 5, 6 -> "mid"
        else -> "high"
    }
    println(s)
    val d = when (x) {
        7 -> 1.5
        else -> 2.5
    }
    println(d)
}
