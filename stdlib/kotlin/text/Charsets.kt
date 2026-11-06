/*
 * Mini stdlib: kotlin.text.Charset / Charsets (UTF-8, UTF-16, UTF-16LE, UTF-16BE, ISO-8859-1, US-ASCII) and the conversions
 * String <-> ByteArray with a charset (`"x".toByteArray(Charsets.UTF_8)`, `String(bytes, Charsets.UTF_8)`, `bytes.toString(charset)`).
 */
package kotlin.text

public abstract class Charset internal constructor(private val charsetName: String) {
    public fun name(): String = charsetName

    public override fun toString(): String = charsetName

    public override fun equals(other: Any?): Boolean = other is Charset && other.charsetName == charsetName

    public override fun hashCode(): Int = charsetName.hashCode()

    internal abstract fun encode(s: String): ByteArray

    internal abstract fun decode(bytes: ByteArray, startIndex: Int, endIndex: Int): String

    public companion object {
        public fun forName(charsetName: String): Charset {
            val n = charsetName.uppercase()
            if (n == "UTF-8" || n == "UTF8") return Charsets.UTF_8
            if (n == "UTF-16" || n == "UTF16") return Charsets.UTF_16
            if (n == "UTF-16LE") return Charsets.UTF_16LE
            if (n == "UTF-16BE") return Charsets.UTF_16BE
            if (n == "US-ASCII" || n == "ASCII") return Charsets.US_ASCII
            if (n == "ISO-8859-1" || n == "LATIN1" || n == "ISO_8859_1") return Charsets.ISO_8859_1
            throw IllegalArgumentException("Unsupported charset: " + charsetName)
        }

        public fun isSupported(charsetName: String): Boolean {
            val n = charsetName.uppercase()
            return n == "UTF-8" || n == "UTF8" || n == "UTF-16" || n == "UTF16" || n == "UTF-16LE" || n == "UTF-16BE" ||
                n == "US-ASCII" || n == "ASCII" || n == "ISO-8859-1" || n == "LATIN1" || n == "ISO_8859_1"
        }
    }
}

private class Utf8Charset : Charset("UTF-8") {
    override fun encode(s: String): ByteArray = s.encodeToByteArray()
    override fun decode(bytes: ByteArray, startIndex: Int, endIndex: Int): String = bytes.decodeToString(startIndex, endIndex)
}

private class Latin1Charset(name: String, private val limit: Int) : Charset(name) {
    override fun encode(s: String): ByteArray {
        val out = ByteArray(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i].code
            out[i] = if (c < limit) c.toByte() else '?'.code.toByte()
            i++
        }
        return out
    }

    override fun decode(bytes: ByteArray, startIndex: Int, endIndex: Int): String {
        val sb = StringBuilder()
        var i = startIndex
        while (i < endIndex) {
            val b = bytes[i].toInt() and 0xFF
            sb.append(if (b < limit) b.toChar() else '�')
            i++
        }
        return sb.toString()
    }
}

private class Utf16Charset(name: String, private val bigEndian: Boolean, private val bom: Boolean) : Charset(name) {
    override fun encode(s: String): ByteArray {
        val n = s.length * 2 + (if (bom) 2 else 0)
        val out = ByteArray(n)
        var o = 0
        if (bom) {
            out[0] = 0xFE.toByte()
            out[1] = 0xFF.toByte()
            o = 2
        }
        var i = 0
        while (i < s.length) {
            val c = s[i].code
            if (bigEndian) {
                out[o] = (c shr 8).toByte()
                out[o + 1] = c.toByte()
            } else {
                out[o] = c.toByte()
                out[o + 1] = (c shr 8).toByte()
            }
            o += 2
            i++
        }
        return out
    }

    override fun decode(bytes: ByteArray, startIndex: Int, endIndex: Int): String {
        var be = bigEndian
        var i = startIndex
        if (bom && endIndex - startIndex >= 2) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = bytes[i + 1].toInt() and 0xFF
            if (b0 == 0xFE && b1 == 0xFF) { be = true; i += 2 }
            else if (b0 == 0xFF && b1 == 0xFE) { be = false; i += 2 }
        }
        val sb = StringBuilder()
        while (i + 1 < endIndex) {
            val a = bytes[i].toInt() and 0xFF
            val b = bytes[i + 1].toInt() and 0xFF
            sb.append((if (be) (a shl 8) or b else (b shl 8) or a).toChar())
            i += 2
        }
        if (i < endIndex) sb.append('�')
        return sb.toString()
    }
}

public object Charsets {
    public val UTF_8: Charset = Utf8Charset()
    public val UTF_16: Charset = Utf16Charset("UTF-16", true, true)
    public val UTF_16BE: Charset = Utf16Charset("UTF-16BE", true, false)
    public val UTF_16LE: Charset = Utf16Charset("UTF-16LE", false, false)
    public val US_ASCII: Charset = Latin1Charset("US-ASCII", 0x80)
    public val ISO_8859_1: Charset = Latin1Charset("ISO-8859-1", 0x100)
}

public fun String.toByteArray(charset: Charset): ByteArray = charset.encode(this)

public fun ByteArray.toString(charset: Charset): String = charset.decode(this, 0, size)

public fun String(bytes: ByteArray, charset: Charset): String = charset.decode(bytes, 0, bytes.size)

public fun String(bytes: ByteArray, offset: Int, length: Int, charset: Charset): String {
    if (offset < 0 || length < 0 || offset + length > bytes.size) {
        throw IndexOutOfBoundsException("offset: " + offset + ", length: " + length + ", size: " + bytes.size)
    }
    return charset.decode(bytes, offset, offset + length)
}

public fun String(bytes: ByteArray): String = bytes.decodeToString()

public fun String(bytes: ByteArray, offset: Int, length: Int): String = String(bytes, offset, length, Charsets.UTF_8)
