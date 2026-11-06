// invoke on objects, companions of classes, nested classes with companions, and extension invoke
class A {
    companion object {
        operator fun invoke(i: Int) = i + 1
    }
}

object O

operator fun O.invoke(i: Int) = i * 2

class Outer {
    class Nested {
        companion object {
            operator fun invoke(i: Int) = i + 100
        }
    }
    fun viaScope() = Nested(1)
}

fun main() {
    println(A(41))
    println(O(21))
    println(Outer.Nested(5))
    println(Outer().viaScope())
}
