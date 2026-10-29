/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see std/kotlin-mini/NOTICE).
 * Ported from kotlin-stdlib util/Preconditions.kt (contracts removed).
 */
package kotlin

public inline fun require(value: Boolean) {
    if (!value) {
        throw IllegalArgumentException("Failed requirement.")
    }
}

public inline fun require(value: Boolean, lazyMessage: () -> Any) {
    if (!value) {
        val message = lazyMessage()
        throw IllegalArgumentException(message.toString())
    }
}

public inline fun <T : Any> requireNotNull(value: T?): T {
    if (value == null) {
        throw IllegalArgumentException("Required value was null.")
    } else {
        return value
    }
}

public inline fun <T : Any> requireNotNull(value: T?, lazyMessage: () -> Any): T {
    if (value == null) {
        val message = lazyMessage()
        throw IllegalArgumentException(message.toString())
    } else {
        return value
    }
}

public inline fun check(value: Boolean) {
    if (!value) {
        throw IllegalStateException("Check failed.")
    }
}

public inline fun check(value: Boolean, lazyMessage: () -> Any) {
    if (!value) {
        val message = lazyMessage()
        throw IllegalStateException(message.toString())
    }
}

public inline fun <T : Any> checkNotNull(value: T?): T {
    if (value == null) {
        throw IllegalStateException("Required value was null.")
    } else {
        return value
    }
}

public inline fun <T : Any> checkNotNull(value: T?, lazyMessage: () -> Any): T {
    if (value == null) {
        val message = lazyMessage()
        throw IllegalStateException(message.toString())
    } else {
        return value
    }
}

public inline fun error(message: Any): Nothing = throw IllegalStateException(message.toString())

public fun TODO(): Nothing = throw NotImplementedError()

public fun TODO(reason: String): Nothing = throw NotImplementedError("An operation is not implemented: $reason")
