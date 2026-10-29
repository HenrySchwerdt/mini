/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see std/kotlin-mini/NOTICE).
 * Ported from kotlin-stdlib util/Preconditions.kt (contracts removed).
 */
package kotlin

public inline fun require(value: Boolean) {
    if (!value) {
        mini.internal.throwIllegalArgument("Failed requirement.")
    }
}

public inline fun require(value: Boolean, lazyMessage: () -> Any) {
    if (!value) {
        val message = lazyMessage()
        mini.internal.throwIllegalArgument(message.toString())
    }
}

public inline fun <T : Any> requireNotNull(value: T?): T {
    if (value == null) {
        mini.internal.throwIllegalArgument("Required value was null.")
    } else {
        return value
    }
}

public inline fun <T : Any> requireNotNull(value: T?, lazyMessage: () -> Any): T {
    if (value == null) {
        val message = lazyMessage()
        mini.internal.throwIllegalArgument(message.toString())
    } else {
        return value
    }
}

public inline fun check(value: Boolean) {
    if (!value) {
        mini.internal.throwIllegalState("Check failed.")
    }
}

public inline fun check(value: Boolean, lazyMessage: () -> Any) {
    if (!value) {
        val message = lazyMessage()
        mini.internal.throwIllegalState(message.toString())
    }
}

public inline fun <T : Any> checkNotNull(value: T?): T {
    if (value == null) {
        mini.internal.throwIllegalState("Required value was null.")
    } else {
        return value
    }
}

public inline fun <T : Any> checkNotNull(value: T?, lazyMessage: () -> Any): T {
    if (value == null) {
        val message = lazyMessage()
        mini.internal.throwIllegalState(message.toString())
    } else {
        return value
    }
}

public inline fun error(message: Any): Nothing = mini.internal.throwIllegalState(message.toString())

public fun TODO(): Nothing = mini.internal.throwNotImplemented(null)

public fun TODO(reason: String): Nothing = mini.internal.throwNotImplemented("An operation is not implemented: $reason")
