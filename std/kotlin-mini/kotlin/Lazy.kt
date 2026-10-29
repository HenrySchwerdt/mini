/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see std/kotlin-mini/NOTICE).
 * Reduced from kotlin-stdlib util/Lazy.kt: single-threaded, no serialization.
 */
package kotlin

public interface Lazy<out T> {
    public val value: T
    public fun isInitialized(): Boolean
}

private object UninitializedValue

private class UnsafeLazyImpl<out T>(initializer: () -> T) : Lazy<T> {
    private var initializer: (() -> T)? = initializer
    private var _value: Any? = UninitializedValue

    override val value: T
        get() {
            if (_value === UninitializedValue) {
                _value = initializer!!()
                initializer = null
            }
            @Suppress("UNCHECKED_CAST")
            return _value as T
        }

    override fun isInitialized(): Boolean = _value !== UninitializedValue

    override fun toString(): String = if (isInitialized()) value.toString() else "Lazy value not initialized yet."
}

public fun <T> lazy(initializer: () -> T): Lazy<T> = UnsafeLazyImpl(initializer)

public inline operator fun <T> Lazy<T>.getValue(thisRef: Any?, property: Any?): T = value
