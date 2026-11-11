/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * The deprecated numeric conversions of Char (use Char.code).
 */
package kotlin

@Deprecated("Conversion of Char to Number is deprecated. Use Char.code property instead.", ReplaceWith("this.code.toByte()"))
@DeprecatedSinceKotlin(warningSince = "1.5")
public fun Char.toByte(): Byte = this.code.toByte()

@Deprecated("Conversion of Char to Number is deprecated. Use Char.code property instead.", ReplaceWith("this.code.toShort()"))
@DeprecatedSinceKotlin(warningSince = "1.5")
public fun Char.toShort(): Short = this.code.toShort()

@Deprecated("Conversion of Char to Number is deprecated. Use Char.code property instead.", ReplaceWith("this.code.toFloat()"))
@DeprecatedSinceKotlin(warningSince = "1.5")
public fun Char.toFloat(): Float = this.code.toFloat()

public fun Char.toChar(): Char = this

public fun AutoCloseable(closeAction: () -> Unit): AutoCloseable = object : AutoCloseable {
    override fun close() {
        closeAction()
    }
}
