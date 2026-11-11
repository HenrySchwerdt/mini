/* The functional interfaces of java.util.function that concurrent code uses. */
package java.util.function

public fun interface Supplier<T> {
    public fun get(): T
}

public fun interface Consumer<T> {
    public fun accept(t: T)
}

public fun interface BiConsumer<T, U> {
    public fun accept(t: T, u: U)
}

public fun interface Function<T, R> {
    public fun apply(t: T): R
}

public fun interface BiFunction<T, U, R> {
    public fun apply(t: T, u: U): R
}

public fun interface Predicate<T> {
    public fun test(t: T): Boolean
}

public fun interface IntUnaryOperator {
    public fun applyAsInt(operand: Int): Int
}

public fun interface IntBinaryOperator {
    public fun applyAsInt(left: Int, right: Int): Int
}

public fun interface LongUnaryOperator {
    public fun applyAsLong(operand: Long): Long
}

public fun interface LongBinaryOperator {
    public fun applyAsLong(left: Long, right: Long): Long
}

public fun interface UnaryOperator<T> : Function<T, T>
