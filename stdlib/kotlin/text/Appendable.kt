/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * kotlin.text.Appendable (ported from kotlin-stdlib commonMain; StringBuilder implements it).
 */

package kotlin.text

public interface Appendable {
    public fun append(value: Char): Appendable

    public fun append(value: CharSequence?): Appendable

    public fun append(value: CharSequence?, startIndex: Int, endIndex: Int): Appendable
}

public fun <T : Appendable> T.appendRange(value: CharSequence, startIndex: Int, endIndex: Int): T {
    @Suppress("UNCHECKED_CAST")
    return append(value, startIndex, endIndex) as T
}

public fun <T : Appendable> T.append(vararg value: CharSequence?): T {
    for (item in value)
        append(item)
    return this
}

public fun Appendable.appendLine(): Appendable = append('\n')

public fun Appendable.appendLine(value: CharSequence?): Appendable = append(value).appendLine()

public fun Appendable.appendLine(value: Char): Appendable = append(value).appendLine()

internal fun <T> Appendable.appendElement(element: T, transform: ((T) -> CharSequence)?) {
    when {
        transform != null -> append(transform(element))
        element is CharSequence? -> append(element)
        element is Char -> append(element)
        else -> append(element.toString())
    }
}
