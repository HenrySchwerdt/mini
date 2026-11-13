/*
 * The exceptions of java.util.Formatter that String.format throws (same class names, hierarchy and messages as the JVM).
 */
package java.util

public abstract class IllegalFormatException internal constructor(message: String?) : IllegalArgumentException(message)

public class UnknownFormatConversionException(public val conversion: String) :
    IllegalFormatException("Conversion = '" + conversion + "'")

public class MissingFormatArgumentException(public val formatSpecifier: String) :
    IllegalFormatException("Format specifier '" + formatSpecifier + "'")

/** [argumentClassName] is the JVM class name of the argument (`java.lang.String`, ...); Mini has no `Class` objects. */
public class IllegalFormatConversionException(public val conversion: Char, public val argumentClassName: String) :
    IllegalFormatException(conversion.toString() + " != " + argumentClassName)

public class IllegalFormatPrecisionException(public val precision: Int) : IllegalFormatException(precision.toString())

public class IllegalFormatWidthException(public val width: Int) : IllegalFormatException(width.toString())

public class MissingFormatWidthException(public val formatSpecifier: String) : IllegalFormatException(formatSpecifier)

public class FormatFlagsConversionMismatchException(public val flags: String, public val conversion: Char) :
    IllegalFormatException("Conversion = " + conversion + ", Flags = " + flags)

public class IllegalFormatFlagsException(public val flags: String) : IllegalFormatException("Flags = '" + flags + "'")

public class DuplicateFormatFlagsException(public val flags: String) : IllegalFormatException("Flags = '" + flags + "'")

public class IllegalFormatCodePointException(public val codePoint: Int) :
    IllegalFormatException("Code point = 0x" + codePoint.toString(16))

public class UnknownFormatFlagsException(public val flags: String) : IllegalFormatException("Flags = " + flags)
