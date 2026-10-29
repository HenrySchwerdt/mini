package mkt.test

import mkt.*
import mkt.collections.*
import mkt.text.*
import mkt.comparisons.*
import mkt.ranges.*
import mkt.math.*

fun testSubstring() {
    eq("ell", "hello".substring(1, 4))
    eq("llo", "hello".substring(2))
    eq("", "hello".substring(5))
    throwsA("IndexOutOfBoundsException") { "hello".substring(3, 9) }
    eq("he", "hello".take(2))
    eq("hello", "hello".take(10))
    eq("lo", "hello".takeLast(2))
    eq("llo", "hello".drop(2))
    eq("hel", "hello".dropLast(2))
    eq("ab", "ab:cd".substringBefore(":"))
    eq("cd", "ab:cd".substringAfter(":"))
    eq("ab:cd", "ab:cd".substringBefore("#"))
    eq("x", "ab:cd".substringAfter("#", "x"))
    eq("a:b", "a:b:c".substringBeforeLast(":"))
    eq("c", "a:b:c".substringAfterLast(":"))
}

fun testStartsEndsContains() {
    isTrue("hello".startsWith("he"))
    isFalse("hello".startsWith("lo"))
    isTrue("hello".startsWith("ll", 2))
    isTrue("hello".startsWith('h'))
    isTrue("hello".endsWith("llo"))
    isFalse("hello".endsWith("hel"))
    isTrue("hello".endsWith('o'))
    isTrue("hello".startsWith(""))
    isFalse("he".startsWith("hello"))
    isTrue("hello".contains("ell"))
    isTrue("hello".contains('e'))
    isFalse("hello".contains("xyz"))
    eq("llo", "hello".removePrefix("he"))
    eq("hello", "hello".removePrefix("xx"))
    eq("hel", "hello".removeSuffix("lo"))
}

fun testIndexOf() {
    eq(1, "hello".indexOf('e'))
    eq(-1, "hello".indexOf('z'))
    eq(3, "hello".indexOf('l', 3))
    eq(2, "hello".indexOf("ll"))
    eq(-1, "hello".indexOf("lo", 4))
    eq(0, "hello".indexOf(""))
    eq(3, "hello".lastIndexOf('l'))
    eq(2, "hello".lastIndexOf("ll"))
    eq(-1, "hello".lastIndexOf("z"))
}

fun testTrimAndPad() {
    eq("a b", "  a b \n".trim())
    eq("a b \n", "  a b \n".trimStart())
    eq("  a b", "  a b \n".trimEnd())
    eq("", "   ".trim())
    eq("x", "aaxaa".trim { it == 'a' })
    eq("  42", "42".padStart(4))
    eq("0042", "42".padStart(4, '0'))
    eq("42", "42".padStart(1))
    eq("42..", "42".padEnd(4, '.'))
    eq("hello", "hello".padEnd(3))
    isTrue("  \t".isBlank())
    isFalse(" a ".isBlank())
    isTrue("".isEmpty())
    isTrue("a".isNotEmpty())
}

fun testRepeatReversedCase() {
    eq("ababab", "ab".repeat(3))
    eq("", "ab".repeat(0))
    throwsA("IllegalArgumentException") { "ab".repeat(-1) }
    eq("olleh", "hello".reversed())
    eq("hello world", "Hello World".lowercase())
    eq("HELLO WORLD", "Hello World".uppercase())
    eq('A', 'a'.uppercaseChar())
    eq('z', 'Z'.lowercaseChar())
    eq('1', '1'.uppercaseChar())
}

fun testReplace() {
    eq("hexxo", "hello".replace('l', 'x'))
    eq("heLLo", "hello".replace("l", "L"))
    eq("a-b-c", "a b c".replace(" ", "-"))
    eq("hello", "hello".replace("zz", "y"))
    eq("heyyo", "hello".replace("ll", "yy"))
    eq("-a-b-", "ab".replace("", "-"))
    eq("xaxa", "aa".replace("a", "xa"))
}

fun testSplit() {
    eq("[a, b, c]", "a,b,c".split(",").toString())
    eq("[a, b, c]", "a,b,c".split(',').toString())
    eq("[, a, , b, ]", ",a,,b,".split(",").toString())
    eq("[abc]", "abc".split(",").toString())
    eq("[]", "".split(",").filter { it.isNotEmpty() }.toString())
    eq("[a, b]", "a--b".split("--").toString())
    eq(3, "a b c".split(" ").size)
}

fun testLines() {
    eq("[a, b, c]", "a\nb\nc".lines().toString())
    eq("[a, b]", "a\r\nb".lines().toString())
    eq("[a, , b, ]", "a\n\nb\n".lines().toString())
    eq("[]", "".lines().filter { it.isNotEmpty() }.toString())
    eq("[]".length, "[]".length)
}

fun testNumberParsing() {
    eq(42, "42".toInt())
    eq(-17, "-17".toInt())
    eq(5, "+5".toInt())
    eq(null, "".toIntOrNull())
    eq(null, "abc".toIntOrNull())
    eq(null, "-".toIntOrNull())
    eq(null, "2147483648".toIntOrNull())
    eq(Int.MAX_VALUE, "2147483647".toInt())
    eq(Int.MIN_VALUE, "-2147483648".toInt())
    eq(255, "ff".toInt(16))
    eq(Long.MAX_VALUE, "9223372036854775807".toLong())
    eq(Long.MIN_VALUE, "-9223372036854775808".toLong())
    eq(null, "9223372036854775808".toLongOrNull())
    eq(1.5, "1.5".toDouble())
    eq(null, "x".toDoubleOrNull())
    isTrue("true".toBoolean())
    isTrue("TRUE".toBoolean())
    isFalse("yes".toBoolean())
    throwsA("NumberFormatException") { "12x".toInt() }
    eq("ff", 255.toString(16))
    eq("-101", (-5).toString(2))
    eq("0", 0.toString(2))
}

fun testChars() {
    isTrue('5'.isDigit())
    isFalse('x'.isDigit())
    isTrue('x'.isLetter())
    isTrue('Q'.isLetter())
    isFalse('5'.isLetter())
    isTrue('5'.isLetterOrDigit())
    isTrue(' '.isWhitespace())
    isTrue('\n'.isWhitespace())
    isFalse('a'.isWhitespace())
    isTrue('A'.isUpperCase())
    isTrue('a'.isLowerCase())
    eq(7, '7'.digitToInt())
    eq(11, 'b'.digitToInt(16))
    throwsA("IllegalArgumentException") { 'b'.digitToInt() }
    eq('7', 7.digitToChar())
    eq("A", 'a'.uppercase())
}

fun testStringBuilder() {
    val sb = StringBuilder()
    sb.append("abc").append('d').append(1).append(2L).append(true).append(1.5).append(null as String?)
    eq("abcd12true1.5null", sb.toString())
    eq(17, sb.length)
    sb.clear()
    eq("", sb.toString())
    sb.append("hello")
    eq('e', sb[1])
    sb[0] = 'J'
    eq("Jello", sb.toString())
    sb.insert(1, "--")
    eq("J--ello", sb.toString())
    sb.deleteAt(1)
    eq("J-ello", sb.toString())
    sb.delete(0, 2)
    eq("ello", sb.toString())
    sb.reverse()
    eq("olle", sb.toString())
    sb.setLength(2)
    eq("ol", sb.toString())
    eq("ol!", sb.append('!').toString())
    eq("x\n", StringBuilder("x").appendLine().toString())
    eq("a\nb\n", StringBuilder().appendLine("a").appendLine("b").toString())
    eq("ell", StringBuilder("hello").substring(1, 4))
    eq("abc", StringBuilder("abc").toString())
    throwsA("IndexOutOfBoundsException") { StringBuilder("abc")[5] }
}

fun testBuildString() {
    val s = buildString {
        append("x=")
        append(1)
        for (i in 0 until 3) append(i)
    }
    eq("x=1012", s)
}

fun testTrimIndent() {
    eq("a\n  b\nc", "\n    a\n      b\n    c\n".trimIndent())
    eq("x", "x".trimIndent())
}
