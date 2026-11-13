/*
 * String.format: the java.util.Formatter subset used by everyday code, with the same output as the JVM:
 * %d %s %S %b %B %h %H %c %C %f %e %E %g %G %x %X %o %n %% with flags - 0 + space , ( #, width, precision and argument indexes (%1$s, %<s).
 * Byte/Short/Int/Long arguments, and the java.util.*Format*Exception classes (FormatExceptions.kt) for bad specifiers like the JVM.
 * Not supported: %a, %t/%T (date/time), BigInteger/BigDecimal, Formattable, locales.
 * Floating point values are formatted from their shortest decimal representation and rounded half-up, like Java.
 */
package kotlin.text

import java.util.DuplicateFormatFlagsException
import java.util.FormatFlagsConversionMismatchException
import java.util.IllegalFormatCodePointException
import java.util.IllegalFormatConversionException
import java.util.IllegalFormatFlagsException
import java.util.IllegalFormatPrecisionException
import java.util.IllegalFormatWidthException
import java.util.MissingFormatArgumentException
import java.util.MissingFormatWidthException
import java.util.UnknownFormatConversionException

public fun String.format(vararg args: Any?): String = formatImpl(this, args)

private class Spec {
    var argIndex = 0
    var relative = false
    var minus = false
    var zero = false
    var plus = false
    var space = false
    var comma = false
    var paren = false
    var alt = false
    var width = -1
    var precision = -1
    var conv = ' '
}

private fun formatImpl(fmt: String, args: Array<out Any?>): String {
    val sb = StringBuilder()
    var i = 0
    var nextArg = 0
    var lastArg = -1
    val n = fmt.length
    while (i < n) {
        val c = fmt[i]
        if (c != '%') {
            sb.append(c)
            i++
            continue
        }
        val start = i
        i++
        val sp = Spec()
        // argument index: digits followed by '$', or '<'
        var j = i
        while (j < n && fmt[j] in '0'..'9') j++
        if (j < n && j > i && fmt[j] == '$') {
            sp.argIndex = fmt.substring(i, j).toInt()
            i = j + 1
        } else if (i < n && fmt[i] == '<') {
            sp.relative = true
            i++
        }
        // flags
        var flagsDone = false
        while (i < n && !flagsDone) {
            val f = fmt[i]
            if (f == '-' || f == '0' || f == '+' || f == ' ' || f == ',' || f == '(' || f == '#') {
                if (hasFlag(sp, f)) throw DuplicateFormatFlagsException(f.toString())
                if (f == '-') sp.minus = true
                else if (f == '0') sp.zero = true
                else if (f == '+') sp.plus = true
                else if (f == ' ') sp.space = true
                else if (f == ',') sp.comma = true
                else if (f == '(') sp.paren = true
                else sp.alt = true
                i++
            } else {
                flagsDone = true
            }
        }
        // width
        j = i
        while (j < n && fmt[j] in '0'..'9') j++
        if (j > i) {
            sp.width = fmt.substring(i, j).toInt()
            i = j
        }
        // precision
        if (i < n && fmt[i] == '.') {
            i++
            j = i
            while (j < n && fmt[j] in '0'..'9') j++
            if (j == i) throw UnknownFormatConversionException(if (i < n) fmt[i].toString() else "%")
            sp.precision = fmt.substring(i, j).toInt()
            i = j
        }
        if (i >= n) throw UnknownFormatConversionException("%")
        sp.conv = fmt[i]
        i++
        val conv = sp.conv
        checkSpec(sp, fmt.substring(start, i))
        if (conv == 'n') {
            sb.append('\n')
            continue
        }
        if (conv == '%') {
            pad(sb, "%", sp)
            continue
        }
        val argPos: Int
        if (sp.relative) {
            argPos = lastArg
        } else if (sp.argIndex > 0) {
            argPos = sp.argIndex - 1
        } else {
            argPos = nextArg
            nextArg++
        }
        if (argPos < 0 || argPos >= args.size) {
            throw MissingFormatArgumentException(fmt.substring(start, i))
        }
        lastArg = argPos
        formatArg(sb, args[argPos], sp)
    }
    return sb.toString()
}

private fun hasFlag(sp: Spec, f: Char): Boolean = when (f) {
    '-' -> sp.minus
    '0' -> sp.zero
    '+' -> sp.plus
    ' ' -> sp.space
    ',' -> sp.comma
    '(' -> sp.paren
    else -> sp.alt
}

private fun flagsText(sp: Spec): String {
    val sb = StringBuilder()
    if (sp.minus) sb.append('-')
    if (sp.alt) sb.append('#')
    if (sp.plus) sb.append('+')
    if (sp.space) sb.append(' ')
    if (sp.zero) sb.append('0')
    if (sp.comma) sb.append(',')
    if (sp.paren) sb.append('(')
    return sb.toString()
}

/** FormatFlagsConversionMismatchException for the first of [bad] (in this order) that is set. */
private fun checkBad(sp: Spec, bad: String) {
    for (f in bad) {
        if (hasFlag(sp, f)) throw FormatFlagsConversionMismatchException(f.toString(), sp.conv)
    }
}

private fun checkNumeric(sp: Spec, text: String) {
    if (sp.width == -1 && (sp.minus || sp.zero)) throw MissingFormatWidthException(text)
    if ((sp.plus && sp.space) || (sp.minus && sp.zero)) throw IllegalFormatFlagsException(flagsText(sp))
}

/** the parse-time checks of java.util.Formatter: unknown conversions and flag / width / precision combinations that are never valid. */
private fun checkSpec(sp: Spec, text: String) {
    val conv = sp.conv
    when (conv) {
        's', 'S', 'b', 'B', 'h', 'H' -> {
            if (sp.alt) throw FormatFlagsConversionMismatchException("#", conv)
            if (sp.width == -1 && sp.minus) throw MissingFormatWidthException(text)
            checkBad(sp, "+ 0,(")
        }
        'c', 'C' -> {
            if (sp.precision != -1) throw IllegalFormatPrecisionException(sp.precision)
            checkBad(sp, "#+ 0,(")
            if (sp.width == -1 && sp.minus) throw MissingFormatWidthException(text)
        }
        'd', 'o', 'x', 'X' -> {
            checkNumeric(sp, text)
            if (sp.precision != -1) throw IllegalFormatPrecisionException(sp.precision)
            if (conv == 'd') checkBad(sp, "#") else checkBad(sp, ",")
        }
        'f', 'e', 'E', 'g', 'G' -> {
            checkNumeric(sp, text)
            if (conv == 'e' || conv == 'E') checkBad(sp, ",")
            else if (conv == 'g' || conv == 'G') checkBad(sp, "#")
        }
        '%' -> {
            if (sp.precision != -1) throw IllegalFormatPrecisionException(sp.precision)
            if (sp.alt || sp.plus || sp.space || sp.zero || sp.comma || sp.paren) throw IllegalFormatFlagsException(flagsText(sp))
            if (sp.width == -1 && sp.minus) throw MissingFormatWidthException(text)
        }
        'n' -> {
            if (sp.width != -1) throw IllegalFormatWidthException(sp.width)
            if (sp.precision != -1) throw IllegalFormatPrecisionException(sp.precision)
            if (sp.minus || sp.alt || sp.plus || sp.space || sp.zero || sp.comma || sp.paren) throw IllegalFormatFlagsException(flagsText(sp))
        }
        else -> throw UnknownFormatConversionException(conv.toString())
    }
}

private fun badConversion(sp: Spec, arg: Any?): Nothing {
    val name = when (arg) {
        is Byte -> "java.lang.Byte"
        is Short -> "java.lang.Short"
        is Int -> "java.lang.Integer"
        is Long -> "java.lang.Long"
        is Double -> "java.lang.Double"
        is Float -> "java.lang.Float"
        is String -> "java.lang.String"
        is Char -> "java.lang.Character"
        is Boolean -> "java.lang.Boolean"
        else -> "java.lang.Object"
    }
    throw IllegalFormatConversionException(sp.conv, name)
}

private fun pad(sb: StringBuilder, text: String, sp: Spec) {
    val w = sp.width
    if (w <= text.length) {
        sb.append(text)
    } else if (sp.minus) {
        sb.append(text)
        var k = text.length
        while (k < w) {
            sb.append(' ')
            k++
        }
    } else {
        var k = text.length
        while (k < w) {
            sb.append(' ')
            k++
        }
        sb.append(text)
    }
}

private fun formatArg(sb: StringBuilder, arg: Any?, sp: Spec) {
    val conv = sp.conv
    when (conv) {
        's', 'S' -> {
            var t = arg.toString()
            if (sp.precision >= 0 && sp.precision < t.length) t = t.substring(0, sp.precision)
            if (conv == 'S') t = t.uppercase()
            pad(sb, t, sp)
        }
        'h', 'H' -> {
            var t = if (arg == null) "null" else unsignedDigits(arg.hashCode().toLong() and 0xFFFFFFFFL, 4)
            if (sp.precision >= 0 && sp.precision < t.length) t = t.substring(0, sp.precision)
            if (conv == 'H') t = t.uppercase()
            pad(sb, t, sp)
        }
        'b', 'B' -> {
            var t = if (arg == null) "false" else if (arg is Boolean) arg.toString() else "true"
            if (sp.precision >= 0 && sp.precision < t.length) t = t.substring(0, sp.precision)
            if (conv == 'B') t = t.uppercase()
            pad(sb, t, sp)
        }
        'c', 'C' -> {
            if (arg == null) {
                pad(sb, "null", sp)
            } else {
                var t = when (arg) {
                    is Char -> arg.toString()
                    is Byte -> codePointText(arg.toInt())
                    is Short -> codePointText(arg.toInt())
                    is Int -> codePointText(arg)
                    else -> badConversion(sp, arg)
                }
                if (conv == 'C') t = t.uppercase()
                pad(sb, t, sp)
            }
        }
        'd' -> {
            if (arg == null) {
                pad(sb, "null", sp)
            } else {
                val v: Long = when (arg) {
                    is Byte -> arg.toLong()
                    is Short -> arg.toLong()
                    is Int -> arg.toLong()
                    is Long -> arg
                    else -> badConversion(sp, arg)
                }
                formatDecimalInteger(sb, v, sp)
            }
        }
        'x', 'X', 'o' -> {
            if (arg == null) {
                pad(sb, "null", sp)
            } else {
                val radixBits = if (conv == 'o') 3 else 4
                var digits: String
                if (arg is Int || arg is Byte || arg is Short || arg is Long) checkBad(sp, "( +")
                if (arg is Byte) {
                    digits = unsignedDigits(arg.toLong() and 0xFFL, radixBits)
                } else if (arg is Short) {
                    digits = unsignedDigits(arg.toLong() and 0xFFFFL, radixBits)
                } else if (arg is Int) {
                    digits = unsignedDigits(arg.toLong() and 0xFFFFFFFFL, radixBits)
                } else if (arg is Long) {
                    digits = unsignedDigits(arg, radixBits)
                } else {
                    badConversion(sp, arg)
                }
                if (conv == 'X') digits = digits.uppercase()
                var prefix = ""
                if (sp.alt) prefix = if (conv == 'o') "0" else if (conv == 'X') "0X" else "0x"
                if (sp.zero && sp.width > 0) {
                    if (prefix.length + digits.length < sp.width) digits = zeros(sp.width - prefix.length - digits.length) + digits
                }
                pad(sb, prefix + digits, sp)
            }
        }
        'f', 'e', 'E', 'g', 'G' -> {
            if (arg == null) {
                pad(sb, "null", sp)
            } else {
                val v: Double = when (arg) {
                    is Double -> arg
                    is Float -> arg.toDouble()
                    else -> badConversion(sp, arg)
                }
                formatFloating(sb, v, sp)
            }
        }
        else -> throw IllegalArgumentException("Conversion = '" + conv + "'")
    }
}

private fun codePointText(cp: Int): String {
    if (cp < 0 || cp > 0x10FFFF) throw IllegalFormatCodePointException(cp)
    if (cp < 0x10000) return cp.toChar().toString()
    val v = cp - 0x10000
    val sb = StringBuilder()
    sb.append((0xD800 + (v shr 10)).toChar())
    sb.append((0xDC00 + (v and 0x3FF)).toChar())
    return sb.toString()
}

private fun unsignedDigits(value: Long, bits: Int): String {
    if (value == 0L) return "0"
    val mask = (1L shl bits) - 1L
    val sb = StringBuilder()
    var v = value
    while (v != 0L) {
        val d = (v and mask).toInt()
        sb.append(if (d < 10) ('0'.code + d).toChar() else ('a'.code + d - 10).toChar())
        v = v ushr bits
    }
    return sb.reverse().toString()
}

private fun groupDigits(digits: String): String {
    if (digits.length <= 3) return digits
    val sb = StringBuilder()
    var first = digits.length % 3
    if (first == 0) first = 3
    sb.append(digits.substring(0, first))
    var i = first
    while (i < digits.length) {
        sb.append(',').append(digits.substring(i, i + 3))
        i += 3
    }
    return sb.toString()
}

/** sign handling, zero padding and width of an already formatted magnitude */
private fun finishNumber(sb: StringBuilder, negative: Boolean, magnitude: String, sp: Spec) {
    var prefix = ""
    var suffix = ""
    if (negative) {
        if (sp.paren) {
            prefix = "("
            suffix = ")"
        } else {
            prefix = "-"
        }
    } else if (sp.plus) {
        prefix = "+"
    } else if (sp.space) {
        prefix = " "
    }
    var body = magnitude
    if (sp.zero && sp.width > 0) {
        if (prefix.length + body.length + suffix.length < sp.width) body = zeros(sp.width - prefix.length - body.length - suffix.length) + body
    }
    pad(sb, prefix + body + suffix, sp)
}

private fun formatDecimalInteger(sb: StringBuilder, v: Long, sp: Spec) {
    val negative = v < 0L
    var digits = if (v == Long.MIN_VALUE) "9223372036854775808" else (if (negative) (-v).toString() else v.toString())
    if (sp.comma) digits = groupDigits(digits)
    finishNumber(sb, negative, digits, sp)
}

private fun formatFloating(sb: StringBuilder, v: Double, sp: Spec) {
    if (v != v) {
        pad(sb, "NaN", sp)
        return
    }
    val negative = v < 0.0 || (v == 0.0 && 1.0 / v < 0.0)
    if (v == Double.POSITIVE_INFINITY || v == Double.NEGATIVE_INFINITY) {
        var t = "Infinity"
        if (negative) {
            t = if (sp.paren) "(Infinity)" else "-Infinity"
        } else if (sp.plus) {
            t = "+Infinity"
        } else if (sp.space) {
            t = " Infinity"
        }
        pad(sb, t, sp)
        return
    }
    val a = if (negative) -v else v
    val conv = sp.conv
    var text: String
    if (conv == 'f') {
        val p = if (sp.precision < 0) 6 else sp.precision
        text = fixedText(a, p, sp.comma)
        if (sp.alt && p == 0) text += "."
    } else if (conv == 'e' || conv == 'E') {
        val p = if (sp.precision < 0) 6 else sp.precision
        text = sciText(a, p)
        if (sp.alt && p == 0) text = text.replace("e", ".e")
        if (conv == 'E') text = text.uppercase()
    } else {
        var p = if (sp.precision < 0) 6 else sp.precision
        if (p == 0) p = 1
        if (a == 0.0) {
            text = fixedText(a, p - 1, sp.comma)
        } else {
            // the decimal exponent after rounding to p significant digits
            val rounded = sciText(a, p - 1)
            val e10 = rounded.substring(rounded.indexOf('e') + 1).toInt()
            text = if (e10 >= -4 && e10 < p) fixedText(a, p - 1 - e10, sp.comma) else rounded
        }
        if (conv == 'G') text = text.uppercase()
    }
    finishNumber(sb, negative, text, sp)
}

/** shortest digits of a positive finite double: (digits without leading/trailing zeros, position of the decimal point) */
private class Dec(val digits: String, val point: Int)

private fun decimalOf(a: Double): Dec {
    if (a == 0.0) return Dec("", 0)
    val s = a.toString()
    var mant = s
    var e = 0
    val ei = s.indexOf('E')
    if (ei >= 0) {
        mant = s.substring(0, ei)
        e = s.substring(ei + 1).toInt()
    }
    val dot = mant.indexOf('.')
    val intPart = if (dot < 0) mant else mant.substring(0, dot)
    val frac = if (dot < 0) "" else mant.substring(dot + 1)
    var digits = intPart + frac
    var point = intPart.length + e
    var lead = 0
    while (lead < digits.length && digits[lead] == '0') lead++
    digits = digits.substring(lead)
    point -= lead
    var end = digits.length
    while (end > 0 && digits[end - 1] == '0') end--
    digits = digits.substring(0, end)
    if (digits.isEmpty()) return Dec("", 0)
    return Dec(digits, point)
}

/** rounds [d] half-up to [keep] significant digits; returns the new digits and adjusts the point through [pointOut] */
private fun roundDigits(d: Dec, keep: Int): Dec {
    if (keep >= d.digits.length) return d
    if (keep < 0) return Dec("", 0)
    val roundUp = d.digits[keep] >= '5'
    val kept = StringBuilder(d.digits.substring(0, keep))
    var point = d.point
    if (roundUp) {
        var k = keep - 1
        while (k >= 0 && kept[k] == '9') {
            kept[k] = '0'
            k--
        }
        if (k >= 0) {
            kept[k] = kept[k] + 1
        } else {
            kept.insert(0, '1')
            point++
        }
    }
    var s = kept.toString()
    var end = s.length
    while (end > 0 && s[end - 1] == '0') end--
    s = s.substring(0, end)
    if (s.isEmpty()) return Dec("", 0)
    return Dec(s, point)
}

private fun zeros(n: Int): String {
    val sb = StringBuilder()
    var i = 0
    while (i < n) {
        sb.append('0')
        i++
    }
    return sb.toString()
}

private fun fixedText(a: Double, precision: Int, group: Boolean): String {
    val d0 = decimalOf(a)
    val d = roundDigits(d0, d0.point + precision)
    val digits = d.digits
    val point = d.point
    var intPart: String
    var frac: String
    if (digits.isEmpty()) {
        intPart = "0"
        frac = ""
    } else if (point <= 0) {
        intPart = "0"
        frac = zeros(-point) + digits
    } else if (point >= digits.length) {
        intPart = digits + zeros(point - digits.length)
        frac = ""
    } else {
        intPart = digits.substring(0, point)
        frac = digits.substring(point)
    }
    if (frac.length < precision) frac += zeros(precision - frac.length)
    if (frac.length > precision) frac = frac.substring(0, precision)
    if (group) intPart = groupDigits(intPart)
    return if (precision > 0) intPart + "." + frac else intPart
}

private fun sciText(a: Double, precision: Int): String {
    val d0 = decimalOf(a)
    var digits: String
    var e10: Int
    if (d0.digits.isEmpty()) {
        digits = ""
        e10 = 0
    } else {
        val d = roundDigits(d0, precision + 1)
        digits = d.digits
        e10 = d.point - 1
    }
    var frac = if (digits.length > 1) digits.substring(1) else ""
    if (frac.length < precision) frac += zeros(precision - frac.length)
    val lead = if (digits.isEmpty()) "0" else digits.substring(0, 1)
    val sb = StringBuilder()
    sb.append(lead)
    if (precision > 0) sb.append('.').append(frac)
    sb.append('e')
    sb.append(if (e10 < 0) '-' else '+')
    val ae = if (e10 < 0) -e10 else e10
    if (ae < 10) sb.append('0')
    sb.append(ae)
    return sb.toString()
}
