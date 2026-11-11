class Tree(val value: Int, val left: Tree? = null, val right: Tree? = null)

val depth = DeepRecursiveFunction<Tree?, Int> { t ->
    if (t == null) 0 else maxOf(callRecursive(t.left), callRecursive(t.right)) + 1
}

val sumTo = DeepRecursiveFunction<Int, Long> { n ->
    if (n == 0) 0L else callRecursive(n - 1) + n
}

val fib = DeepRecursiveFunction<Int, Long> { n ->
    if (n < 2) n.toLong() else callRecursive(n - 1) + callRecursive(n - 2)
}

// two mutually recursive functions
val isEven: DeepRecursiveFunction<Int, Boolean> = DeepRecursiveFunction { n ->
    if (n == 0) true else isOdd.callRecursive(n - 1)
}
val isOdd: DeepRecursiveFunction<Int, Boolean> = DeepRecursiveFunction { n ->
    if (n == 0) false else isEven.callRecursive(n - 1)
}

val thrower = DeepRecursiveFunction<Int, Int> { n ->
    if (n == 0) throw IllegalStateException("bottom") else callRecursive(n - 1)
}

fun main() {
    // a degenerate tree 100000 levels deep: far more than the call stack would take
    var t: Tree? = null
    for (i in 1..100000) t = Tree(i, t, null)
    println(depth(t))
    println(sumTo(200000))
    println(fib(20))
    println(isEven(100001))
    println(isOdd(100001))
    try {
        thrower(5)
    } catch (e: IllegalStateException) {
        println(e.message)
    }
}
