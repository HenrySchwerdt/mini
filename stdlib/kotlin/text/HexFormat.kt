/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license (see stdlib/NOTICE).
 * kotlin.text.HexFormat (ported from kotlin-stdlib commonMain).
 */
package kotlin.text

public class HexFormat internal constructor(

    public val upperCase: Boolean,

    public val bytes: HexFormat.BytesHexFormat,

    public val number: HexFormat.NumberHexFormat
) {

    override fun toString(): String = buildString {
        append("HexFormat(").appendLine()
        append("    upperCase = ").append(upperCase).appendLine(",")
        append("    bytes = BytesHexFormat(").appendLine()
        bytes.appendOptionsTo(this, indent = "        ").appendLine()
        append("    ),").appendLine()
        append("    number = NumberHexFormat(").appendLine()
        number.appendOptionsTo(this, indent = "        ").appendLine()
        append("    )").appendLine()
        append(")")
    }

    public class BytesHexFormat internal constructor(

        public val bytesPerLine: Int,

        public val bytesPerGroup: Int,

        public val groupSeparator: String,

        public val byteSeparator: String,

        public val bytePrefix: String,

        public val byteSuffix: String
    ) {

        internal val noLineAndGroupSeparator: Boolean =
            bytesPerLine == Int.MAX_VALUE && bytesPerGroup == Int.MAX_VALUE

        internal val shortByteSeparatorNoPrefixAndSuffix: Boolean =
            bytePrefix.isEmpty() && byteSuffix.isEmpty() && byteSeparator.length <= 1

        internal val ignoreCase: Boolean =
            groupSeparator.isCaseSensitive() ||
                    byteSeparator.isCaseSensitive() ||
                    bytePrefix.isCaseSensitive() ||
                    byteSuffix.isCaseSensitive()

        override fun toString(): String = buildString {
            append("BytesHexFormat(").appendLine()
            appendOptionsTo(this, indent = "    ").appendLine()
            append(")")
        }

        internal fun appendOptionsTo(sb: StringBuilder, indent: String): StringBuilder {
            sb.append(indent).append("bytesPerLine = ").append(bytesPerLine).appendLine(",")
            sb.append(indent).append("bytesPerGroup = ").append(bytesPerGroup).appendLine(",")
            sb.append(indent).append("groupSeparator = \"").append(groupSeparator).appendLine("\",")
            sb.append(indent).append("byteSeparator = \"").append(byteSeparator).appendLine("\",")
            sb.append(indent).append("bytePrefix = \"").append(bytePrefix).appendLine("\",")
            sb.append(indent).append("byteSuffix = \"").append(byteSuffix).append("\"")
            return sb
        }

        public class Builder internal constructor() {

            public var bytesPerLine: Int = Default.bytesPerLine
                set(value) {
                    if (value <= 0)
                        throw IllegalArgumentException("Non-positive values are prohibited for bytesPerLine, but was $value")
                    field = value
                }

            public var bytesPerGroup: Int = Default.bytesPerGroup
                set(value) {
                    if (value <= 0)
                        throw IllegalArgumentException("Non-positive values are prohibited for bytesPerGroup, but was $value")
                    field = value
                }

            public var groupSeparator: String = Default.groupSeparator

            public var byteSeparator: String = Default.byteSeparator
                set(value) {
                    if (value.contains('\n') || value.contains('\r'))
                        throw IllegalArgumentException("LF and CR characters are prohibited in byteSeparator, but was $value")
                    field = value
                }

            public var bytePrefix: String = Default.bytePrefix
                set(value) {
                    if (value.contains('\n') || value.contains('\r'))
                        throw IllegalArgumentException("LF and CR characters are prohibited in bytePrefix, but was $value")
                    field = value
                }

            public var byteSuffix: String = Default.byteSuffix
                set(value) {
                    if (value.contains('\n') || value.contains('\r'))
                        throw IllegalArgumentException("LF and CR characters are prohibited in byteSuffix, but was $value")
                    field = value
                }

            internal fun build(): BytesHexFormat {
                return BytesHexFormat(bytesPerLine, bytesPerGroup, groupSeparator, byteSeparator, bytePrefix, byteSuffix)
            }
        }

        internal companion object {
            internal val Default = BytesHexFormat(
                bytesPerLine = Int.MAX_VALUE,
                bytesPerGroup = Int.MAX_VALUE,
                groupSeparator = "  ",
                byteSeparator = "",
                bytePrefix = "",
                byteSuffix = ""
            )
        }
    }

    public class NumberHexFormat internal constructor(

        public val prefix: String,

        public val suffix: String,

        public val removeLeadingZeros: Boolean,
        public val minLength: Int
    ) {

        internal val isDigitsOnly: Boolean = prefix.isEmpty() && suffix.isEmpty()

        internal val isDigitsOnlyAndNoPadding: Boolean = isDigitsOnly && minLength == 1

        internal val ignoreCase: Boolean = prefix.isCaseSensitive() || suffix.isCaseSensitive()

        override fun toString(): String = buildString {
            append("NumberHexFormat(").appendLine()
            appendOptionsTo(this, indent = "    ").appendLine()
            append(")")
        }

        internal fun appendOptionsTo(sb: StringBuilder, indent: String): StringBuilder {
            sb.append(indent).append("prefix = \"").append(prefix).appendLine("\",")
            sb.append(indent).append("suffix = \"").append(suffix).appendLine("\",")
            sb.append(indent).append("removeLeadingZeros = ").append(removeLeadingZeros).appendLine(',')
            sb.append(indent).append("minLength = ").append(minLength)
            return sb
        }

        public class Builder internal constructor() {

            public var prefix: String = Default.prefix
                set(value) {
                    if (value.contains('\n') || value.contains('\r'))
                        throw IllegalArgumentException("LF and CR characters are prohibited in prefix, but was $value")
                    field = value
                }

            public var suffix: String = Default.suffix
                set(value) {
                    if (value.contains('\n') || value.contains('\r'))
                        throw IllegalArgumentException("LF and CR characters are prohibited in suffix, but was $value")
                    field = value
                }

            public var removeLeadingZeros: Boolean = Default.removeLeadingZeros
            public var minLength: Int = Default.minLength
                set(value) {
                    require(value > 0) { "Non-positive values are prohibited for minLength, but was $value" }
                    field = value
                }

            internal fun build(): NumberHexFormat {
                return NumberHexFormat(prefix, suffix, removeLeadingZeros, minLength)
            }
        }

        internal companion object {
            internal val Default = NumberHexFormat(
                prefix = "",
                suffix = "",
                removeLeadingZeros = false,
                minLength = 1
            )
        }
    }

    public class Builder @PublishedApi internal constructor() {

        public var upperCase: Boolean = Default.upperCase

        public val bytes: BytesHexFormat.Builder
            get() {
                if (_bytes == null) {
                    _bytes = BytesHexFormat.Builder()
                }
                return _bytes!!
            }

        private var _bytes: BytesHexFormat.Builder? = null

        public val number: NumberHexFormat.Builder
            get() {
                if (_number == null) {
                    _number = NumberHexFormat.Builder()
                }
                return _number!!
            }

        private var _number: NumberHexFormat.Builder? = null
        public inline fun bytes(builderAction: BytesHexFormat.Builder.() -> Unit) {
            bytes.builderAction()
        }
        public inline fun number(builderAction: NumberHexFormat.Builder.() -> Unit) {
            number.builderAction()
        }

        @PublishedApi
        internal fun build(): HexFormat {
            return HexFormat(
                upperCase,
                _bytes?.build() ?: BytesHexFormat.Default,
                _number?.build() ?: NumberHexFormat.Default
            )
        }
    }

    public companion object {

        public val Default: HexFormat = HexFormat(
            upperCase = false,
            bytes = BytesHexFormat.Default,
            number = NumberHexFormat.Default,
        )

        public val UpperCase: HexFormat = HexFormat(
            upperCase = true,
            bytes = BytesHexFormat.Default,
            number = NumberHexFormat.Default,
        )
    }
}
public inline fun HexFormat(builderAction: HexFormat.Builder.() -> Unit): HexFormat {
    return HexFormat.Builder().apply(builderAction).build()
}

private fun String.isCaseSensitive(): Boolean {
    return this.any { it >= '\u0080' || it.isLetter() }
}
