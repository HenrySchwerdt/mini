/*
 * C interop: C strings (docs/plan-kotlin.md section 7, compiler/README.md "C interop").
 * `"text".cstr` is a NUL-terminated UTF-8 copy placed in a scope (`memScoped { f("x".cstr.ptr) }`),
 * `CPointer<ByteVar>.toKString()` decodes one.
 */
package kotlinx.cinterop

/** A value that is copied into C memory when it is given a place (`getPointer(scope)`). */
public abstract class CValues<T : CVariable> {
    public abstract val size: Int
    public abstract val align: Int
    public abstract fun place(placement: CPointer<T>): CPointer<T>

    public fun getPointer(scope: AutofreeScope): CPointer<T> {
        val mem = scope.alloc(size.toLong(), align)
        return place(mkCPointer<T>(mem.rawPtr))
    }
}

internal class CStringValues(private val text: String) : CValues<ByteVar>() {
    override val size: Int = utf8Length(text) + 1
    override val align: Int get() = 1

    override fun place(placement: CPointer<ByteVar>): CPointer<ByteVar> {
        var n = 0
        var i = 0
        while (i < text.length) {
            var c = text[i].code
            i++
            if (c in 0xD800..0xDBFF && i < text.length && text[i].code in 0xDC00..0xDFFF) {
                c = 0x10000 + ((c - 0xD800) shl 10) + (text[i].code - 0xDC00)
                i++
            } else if (c in 0xD800..0xDFFF) {
                c = 0xFFFD
            }
            if (c < 0x80) {
                placement[n++] = c.toByte()
            } else if (c < 0x800) {
                placement[n++] = (0xC0 or (c shr 6)).toByte()
                placement[n++] = (0x80 or (c and 63)).toByte()
            } else if (c < 0x10000) {
                placement[n++] = (0xE0 or (c shr 12)).toByte()
                placement[n++] = (0x80 or ((c shr 6) and 63)).toByte()
                placement[n++] = (0x80 or (c and 63)).toByte()
            } else {
                placement[n++] = (0xF0 or (c shr 18)).toByte()
                placement[n++] = (0x80 or ((c shr 12) and 63)).toByte()
                placement[n++] = (0x80 or ((c shr 6) and 63)).toByte()
                placement[n++] = (0x80 or (c and 63)).toByte()
            }
        }
        placement[n] = 0.toByte()
        return placement
    }
}

private fun utf8Length(s: String): Int {
    var n = 0
    var i = 0
    while (i < s.length) {
        val c = s[i].code
        i++
        if (c in 0xD800..0xDBFF && i < s.length && s[i].code in 0xDC00..0xDFFF) {
            i++
            n += 4
        } else if (c < 0x80) n += 1
        else if (c < 0x800) n += 2
        else n += 3
    }
    return n
}

/** The NUL-terminated UTF-8 encoding of the string. */
public val String.cstr: CValues<ByteVar>
    get() = CStringValues(this)

/** Decodes the NUL-terminated UTF-8 string the pointer points to (malformed bytes become U+FFFD). */
public fun CPointer<ByteVar>.toKString(): String {
    val sb = StringBuilder()
    var i = 0
    while (true) {
        val b = this[i].toInt() and 0xFF
        if (b == 0) break
        i++
        var cp: Int
        var extra: Int
        if (b < 0x80) { cp = b; extra = 0 }
        else if (b in 0xC2..0xDF) { cp = b and 0x1F; extra = 1 }
        else if (b in 0xE0..0xEF) { cp = b and 0x0F; extra = 2 }
        else if (b in 0xF0..0xF4) { cp = b and 0x07; extra = 3 }
        else { sb.append('�'); continue }
        var ok = true
        while (extra > 0) {
            val next = this[i].toInt() and 0xFF
            if (next and 0xC0 != 0x80) { ok = false; break }
            cp = (cp shl 6) or (next and 0x3F)
            i++
            extra--
        }
        if (!ok || cp > 0x10FFFF || (cp in 0xD800..0xDFFF)) sb.append('�')
        else if (cp < 0x10000) sb.append(cp.toChar())
        else {
            val v = cp - 0x10000
            sb.append((0xD800 + (v shr 10)).toChar())
            sb.append((0xDC00 + (v and 0x3FF)).toChar())
        }
    }
    return sb.toString()
}

public fun CPointer<ByteVar>.toKStringFromUtf8(): String = toKString()
