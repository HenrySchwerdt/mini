// enum initialization order: an entry reads itself in its initializers, entries read earlier entries, the companion is initialized
// after the entries (also when it is the first thing used) and valueOf initializes the class

var log = ""

enum class X {
    B {
        override val value2 = "K"
        override val value = "O" + B.value2
    },
    C {
        override val value2 = "L"
        override val value = B.value + C.value2 + B.name + C.ordinal
    };

    abstract val value2: String
    abstract val value: String
}

enum class Zzz {
    Z {
        init {
            log += Z.name + ";"
        }
    }
}

enum class Chain(val prev: Chain?) {
    A(null), B(A), C(B);

    fun depth(): Int = if (prev == null) 0 else 1 + prev.depth()
}

enum class Foo {
    FOO,
    BAR;

    init {
        log += "Foo.$name;"
    }

    companion object {
        init {
            log += "Foo.CO;"
        }

        val boo = 22
    }
}

enum class F(a: String) {
    X("x"),
    Y("y");

    init {
        log += "F.init($a);"
    }

    companion object {
        init {
            log += "F.companion.init;"
        }

        fun foo() {
            log += "F.foo();$X;"
        }
    }
}

enum class G(a: String) {
    X("x"),
    Y("y");

    init {
        log += "G.init($a);"
    }

    object O {
        init {
            log += "G.O.init;"
        }

        fun foo() {
            log += "G.O.foo();$X;"
        }
    }
}

fun main() {
    println(X.B.value)
    println(X.C.value)
    println(Zzz.Z)
    println(log)
    log = ""
    println(Chain.C.depth().toString() + " " + Chain.B.prev + " " + Chain.A.prev)
    try {
        Foo.valueOf("NO")
    } catch (e: Throwable) {
        log += "caught;"
    }
    println(log)
    log = ""
    F.foo()
    println(log)
    log = ""
    G.O.foo()
    println(log)
}
