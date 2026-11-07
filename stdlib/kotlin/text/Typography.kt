/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * kotlin.text.Typography (ported from kotlin-stdlib commonMain).
 */
package kotlin.text

public object Typography {

    public const val quote: Char = '\u0022'

    public const val dollar: Char = '\u0024'

    public const val amp: Char = '\u0026'

    public const val less: Char = '\u003C'

    public const val greater: Char = '\u003E'

    public const val nbsp: Char = '\u00A0'

    public const val times: Char = '\u00D7'

    public const val cent: Char = '\u00A2'

    public const val pound: Char = '\u00A3'

    public const val section: Char = '\u00A7'

    public const val copyright: Char = '\u00A9'
    public const val leftGuillemet: Char = '\u00AB'
    public const val rightGuillemet: Char = '\u00BB'

    public const val registered: Char = '\u00AE'

    public const val degree: Char = '\u00B0'

    public const val plusMinus: Char = '\u00B1'

    public const val paragraph: Char = '\u00B6'

    public const val middleDot: Char = '\u00B7'

    public const val half: Char = '\u00BD'

    public const val ndash: Char = '\u2013'

    public const val mdash: Char = '\u2014'

    public const val leftSingleQuote: Char = '\u2018'

    public const val rightSingleQuote: Char = '\u2019'

    public const val lowSingleQuote: Char = '\u201A'

    public const val leftDoubleQuote: Char = '\u201C'

    public const val rightDoubleQuote: Char = '\u201D'

    public const val lowDoubleQuote: Char = '\u201E'

    public const val dagger: Char = '\u2020'

    public const val doubleDagger: Char = '\u2021'

    public const val bullet: Char = '\u2022'

    public const val ellipsis: Char = '\u2026'

    public const val prime: Char = '\u2032'

    public const val doublePrime: Char = '\u2033'

    public const val euro: Char = '\u20AC'

    public const val tm: Char = '\u2122'

    public const val almostEqual: Char = '\u2248'

    public const val notEqual: Char = '\u2260'

    public const val lessOrEqual: Char = '\u2264'

    public const val greaterOrEqual: Char = '\u2265'

    @Deprecated("This constant has a typo in the name. Use leftGuillemet instead.", ReplaceWith("Typography.leftGuillemet"))
    @DeprecatedSinceKotlin("1.6")
    public const val leftGuillemete: Char = '\u00AB'

    @Deprecated("This constant has a typo in the name. Use rightGuillemet instead.", ReplaceWith("Typography.rightGuillemet"))
    @DeprecatedSinceKotlin("1.6")
    public const val rightGuillemete: Char = '\u00BB'
}
