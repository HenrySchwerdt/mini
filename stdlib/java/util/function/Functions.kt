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

public fun interface BinaryOperator<T> : BiFunction<T, T, T>

public fun interface DoubleUnaryOperator {
    public fun applyAsDouble(operand: Double): Double
}

public fun interface DoubleBinaryOperator {
    public fun applyAsDouble(left: Double, right: Double): Double
}

public fun interface BooleanSupplier {
    public fun getAsBoolean(): Boolean
}

public fun interface IntSupplier {
    public fun getAsInt(): Int
}

public fun interface LongSupplier {
    public fun getAsLong(): Long
}

public fun interface IntConsumer {
    public fun accept(value: Int)
}

public fun interface IntFunction<R> {
    public fun apply(value: Int): R
}

public fun interface IntPredicate {
    public fun test(value: Int): Boolean
}

public fun interface ToIntFunction<T> {
    public fun applyAsInt(value: T): Int
}

public fun interface ToLongFunction<T> {
    public fun applyAsLong(value: T): Long
}

public fun interface BiPredicate<T, U> {
    public fun test(t: T, u: U): Boolean
}
