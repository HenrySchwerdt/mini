/*
 * Mini stdlib: lazy(mode), lazyOf, LazyThreadSafetyMode, Any?.hashCode(). Mini programs are single threaded as far as the
 * standard library is concerned, so every mode is the same lazy.
 */
package kotlin

public enum class LazyThreadSafetyMode {
    SYNCHRONIZED,
    PUBLICATION,
    NONE,
}

public fun <T> lazy(mode: LazyThreadSafetyMode, initializer: () -> T): Lazy<T> = lazy(initializer)

public fun <T> lazy(lock: Any?, initializer: () -> T): Lazy<T> = lazy(initializer)

private class InitializedLazyImpl<out T>(override val value: T) : Lazy<T> {
    override fun isInitialized(): Boolean = true
    override fun toString(): String = value.toString()
}

public fun <T> lazyOf(value: T): Lazy<T> = InitializedLazyImpl(value)

public fun Any?.hashCode(): Int = if (this != null) this.hashCode() else 0
