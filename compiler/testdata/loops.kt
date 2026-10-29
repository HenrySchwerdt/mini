fun main() {
    var sum = 0
    for (i in 1..10) sum += i
    println(sum)

    for (i in 5 downTo 1) print(i)
    println()

    for (i in 0 until 5) print(i)
    println()

    for (i in 1..10 step 3) print(i)
    println()

    for (i in 10 downTo 1 step 4) print(i)
    println()

    for (i in 3..1) println("never")
    for (i in 0 until 0) println("never")

    var n = 0
    while (n < 5) {
        n++
        if (n == 2) continue
        print(n)
    }
    println()

    var k = 0
    do {
        k += 3
    } while (k < 10)
    println(k)

    var c = 0
    do {
        c++
        if (c == 2) continue
        if (c > 4) break
        print(c)
    } while (c < 100)
    println()

    outer@ for (i in 1..3) {
        for (j in 1..3) {
            if (j == 2) continue@outer
            if (i == 3) break@outer
            println("i=$i j=$j")
        }
    }

    var total = 0L
    for (i in 1..100000) total += i
    println(total)

    for (c2 in 'a'..'e') print(c2)
    println()

    for (i in 2147483645..2147483647) print(" $i")
    println()
    for (i in -2147483647 - 1 until -2147483647 - 1 + 2) print(" $i")
    println()
    for (i in 1L..3L) print(i * 1000000000L)
    println()

    var w = 0
    while (true) {
        w++
        if (w > 3) break
    }
    println(w)

    for (i in 1..3) {
        for (j in i..3) print("$i$j ")
    }
    println()
}
