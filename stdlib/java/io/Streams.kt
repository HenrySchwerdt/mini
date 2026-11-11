/*
 * Mini stdlib: java.io streams, readers and writers (the part portable programs use). Text only: bytes are UTF-8, files are read and written
 * as a whole through the runtime's file intrinsics, standard input is read line by line.
 */
package java.io

import mini.internal.stdinReadLine
import mini.internal.stderrWrite

public open class IOException : Exception {
    public constructor() : super()
    public constructor(message: String?) : super(message)
    public constructor(message: String?, cause: Throwable?) : super(message, cause)
    public constructor(cause: Throwable?) : super(cause)
}

public open class FileNotFoundException : IOException {
    public constructor() : super()
    public constructor(message: String?) : super(message)
}

public class EOFException : IOException {
    public constructor() : super()
    public constructor(message: String?) : super(message)
}

public class UncheckedIOException(message: String?, cause: IOException?) : RuntimeException(message, cause) {
    public constructor(cause: IOException) : this(cause.toString(), cause)
}

public interface Closeable : AutoCloseable

public interface Flushable {
    public fun flush()
}

// ---- byte streams ---------------------------------------------------------------------------------------------------------------

public abstract class InputStream : Closeable {
    /** The next byte (0..255) or -1 at the end. */
    public abstract fun read(): Int

    public open fun read(b: ByteArray): Int = read(b, 0, b.size)

    public open fun read(b: ByteArray, off: Int, len: Int): Int {
        if (off < 0 || len < 0 || off + len > b.size) throw IndexOutOfBoundsException("off=" + off + " len=" + len + " size=" + b.size)
        if (len == 0) return 0
        var n = 0
        while (n < len) {
            val c = read()
            if (c < 0) break
            b[off + n] = c.toByte()
            n++
        }
        return if (n == 0) -1 else n
    }

    public open fun readAllBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val c = read()
            if (c < 0) break
            out.write(c)
        }
        return out.toByteArray()
    }

    public open fun skip(n: Long): Long {
        var k = 0L
        while (k < n && read() >= 0) k++
        return k
    }

    public open fun available(): Int = 0

    public override fun close() {}
}

public open class ByteArrayInputStream(protected val buf: ByteArray, offset: Int, length: Int) : InputStream() {
    protected var pos: Int = offset
    protected val count: Int = if (offset + length < buf.size) offset + length else buf.size

    public constructor(buf: ByteArray) : this(buf, 0, buf.size)

    public override fun read(): Int = if (pos < count) buf[pos++].toInt() and 0xFF else -1

    public override fun available(): Int = count - pos
}

public abstract class OutputStream : Closeable, Flushable {
    public abstract fun write(b: Int)

    public open fun write(b: ByteArray) {
        write(b, 0, b.size)
    }

    public open fun write(b: ByteArray, off: Int, len: Int) {
        if (off < 0 || len < 0 || off + len > b.size) throw IndexOutOfBoundsException("off=" + off + " len=" + len + " size=" + b.size)
        for (i in off until off + len) write(b[i].toInt())
    }

    public override fun flush() {}

    public override fun close() {}
}

public open class ByteArrayOutputStream(size: Int) : OutputStream() {
    private var data = ByteArray(if (size < 16) 16 else size)
    private var used = 0

    public constructor() : this(32)

    public override fun write(b: Int) {
        if (used == data.size) data = data.copyOf(data.size * 2)
        data[used++] = b.toByte()
    }

    public fun toByteArray(): ByteArray = data.copyOf(used)

    public fun size(): Int = used

    public fun reset() {
        used = 0
    }

    public override fun toString(): String = data.decodeToString(0, used)

    public fun writeTo(out: OutputStream) {
        out.write(data, 0, used)
    }
}

// ---- standard input -------------------------------------------------------------------------------------------------------------

/** `System.in`: the lines of standard input, one at a time, each with a `\n` terminator. */
internal class StdinStream : InputStream() {
    private var line: ByteArray = ByteArray(0)
    private var pos = 0
    private var eof = false

    private fun fill(): Boolean {
        while (pos >= line.size) {
            if (eof) return false
            val s = stdinReadLine()
            if (s == null) {
                eof = true
                return false
            }
            line = (s + "\n").encodeToByteArray()
            pos = 0
        }
        return true
    }

    public override fun read(): Int = if (fill()) line[pos++].toInt() and 0xFF else -1

    public override fun available(): Int = if (pos < line.size) line.size - pos else 0
}

// ---- print streams --------------------------------------------------------------------------------------------------------------

public open class PrintStream : OutputStream {
    private val target: OutputStream?

    public constructor(out: OutputStream) : super() {
        target = out
    }

    public constructor(out: OutputStream, autoFlush: Boolean) : super() {
        target = out
    }

    internal constructor() : super() {
        target = null
    }

    internal open fun emit(s: String) {
        val t = target ?: return
        t.write(s.encodeToByteArray())
    }

    public override fun write(b: Int) {
        emit((b and 0xFF).toChar().toString())
    }

    public fun print(s: String?) { emit(s.toString()) }
    public fun print(o: Any?) { emit(o.toString()) }
    public fun print(c: Char) { emit(c.toString()) }
    public fun print(i: Int) { emit(i.toString()) }
    public fun print(l: Long) { emit(l.toString()) }
    public fun print(f: Float) { emit(f.toString()) }
    public fun print(d: Double) { emit(d.toString()) }
    public fun print(b: Boolean) { emit(b.toString()) }
    public fun print(a: CharArray) { emit(a.concatToString()) }

    public fun println() { emit("\n") }
    public fun println(s: String?) { emit(s.toString() + "\n") }
    public fun println(o: Any?) { emit(o.toString() + "\n") }
    public fun println(c: Char) { emit(c.toString() + "\n") }
    public fun println(i: Int) { emit(i.toString() + "\n") }
    public fun println(l: Long) { emit(l.toString() + "\n") }
    public fun println(f: Float) { emit(f.toString() + "\n") }
    public fun println(d: Double) { emit(d.toString() + "\n") }
    public fun println(b: Boolean) { emit(b.toString() + "\n") }
    public fun println(a: CharArray) { emit(a.concatToString() + "\n") }

    public fun printf(format: String, vararg args: Any?): PrintStream {
        emit(format.format(*args))
        return this
    }

    public fun format(format: String, vararg args: Any?): PrintStream = printf(format, *args)

    public fun append(c: Char): PrintStream {
        emit(c.toString())
        return this
    }

    public fun append(s: CharSequence?): PrintStream {
        emit(s.toString())
        return this
    }

    public fun checkError(): Boolean = false

    public override fun flush() {
        target?.flush()
    }

    public override fun close() {
        target?.close()
    }
}

internal class StdoutPrintStream : PrintStream() {
    internal override fun emit(s: String) {
        kotlin.io.print(s)
    }
}

internal class StderrPrintStream : PrintStream() {
    internal override fun emit(s: String) {
        stderrWrite(s)
    }
}

// ---- readers --------------------------------------------------------------------------------------------------------------------

public abstract class Reader : Closeable {
    /** Reads up to [len] chars into [cbuf] at [off]; returns the count or -1 at the end. */
    public abstract fun read(cbuf: CharArray, off: Int, len: Int): Int

    public open fun read(): Int {
        val one = CharArray(1)
        return if (read(one, 0, 1) <= 0) -1 else one[0].code
    }

    public open fun read(cbuf: CharArray): Int = read(cbuf, 0, cbuf.size)

    public open fun ready(): Boolean = false

    public open fun skip(n: Long): Long {
        var k = 0L
        while (k < n && read() >= 0) k++
        return k
    }

    public abstract override fun close()
}

public open class StringReader(private val source: String) : Reader() {
    private var pos = 0
    private var closed = false

    public override fun read(): Int {
        if (closed) throw IOException("Stream closed")
        return if (pos < source.length) source[pos++].code else -1
    }

    public override fun read(cbuf: CharArray, off: Int, len: Int): Int {
        if (closed) throw IOException("Stream closed")
        if (off < 0 || len < 0 || off + len > cbuf.size) throw IndexOutOfBoundsException("off=" + off + " len=" + len + " size=" + cbuf.size)
        if (len == 0) return 0
        if (pos >= source.length) return -1
        val n = if (len < source.length - pos) len else source.length - pos
        for (i in 0 until n) cbuf[off + i] = source[pos + i]
        pos += n
        return n
    }

    public override fun ready(): Boolean {
        if (closed) throw IOException("Stream closed")
        return true
    }

    public override fun close() {
        closed = true
    }
}

public open class InputStreamReader(private val input: InputStream) : Reader() {
    public constructor(input: InputStream, charsetName: String) : this(input)
    public constructor(input: InputStream, cs: kotlin.text.Charset) : this(input)

    private var pending = -1

    private fun cont(): Int {
        val b = input.read()
        return if (b < 0) -1 else b and 0x3F
    }

    public override fun read(): Int {
        if (pending >= 0) {
            val p = pending
            pending = -1
            return p
        }
        val b = input.read()
        if (b < 0) return -1
        if (b < 0x80) return b
        var cp: Int
        val extra: Int
        if (b and 0xE0 == 0xC0) { cp = b and 0x1F; extra = 1 }
        else if (b and 0xF0 == 0xE0) { cp = b and 0x0F; extra = 2 }
        else if (b and 0xF8 == 0xF0) { cp = b and 0x07; extra = 3 }
        else return 0xFFFD
        for (i in 0 until extra) {
            val c = cont()
            if (c < 0) return 0xFFFD
            cp = (cp shl 6) or c
        }
        if (cp >= 0x10000) {
            val v = cp - 0x10000
            pending = 0xDC00 + (v and 0x3FF)
            return 0xD800 + (v shr 10)
        }
        return cp
    }

    public override fun read(cbuf: CharArray, off: Int, len: Int): Int {
        if (off < 0 || len < 0 || off + len > cbuf.size) throw IndexOutOfBoundsException("off=" + off + " len=" + len + " size=" + cbuf.size)
        if (len == 0) return 0
        var n = 0
        while (n < len) {
            val c = read()
            if (c < 0) break
            cbuf[off + n] = c.toChar()
            n++
            if (c == '\n'.code && input is StdinStream) break
        }
        return if (n == 0) -1 else n
    }

    public override fun ready(): Boolean = pending >= 0 || input.available() > 0

    public override fun close() {
        input.close()
    }
}

public open class BufferedReader(private val reader: Reader, sz: Int) : Reader() {
    public constructor(reader: Reader) : this(reader, 8192)

    private var peeked = -2
    private var skipLf = false

    private fun nextChar(): Int {
        if (peeked != -2) {
            val p = peeked
            peeked = -2
            return p
        }
        return reader.read()
    }

    private fun peekChar(): Int {
        if (peeked == -2) peeked = reader.read()
        return peeked
    }

    public override fun read(): Int {
        var c = nextChar()
        if (skipLf) {
            skipLf = false
            if (c == '\n'.code) c = nextChar()
        }
        return c
    }

    public override fun read(cbuf: CharArray, off: Int, len: Int): Int {
        if (off < 0 || len < 0 || off + len > cbuf.size) throw IndexOutOfBoundsException("off=" + off + " len=" + len + " size=" + cbuf.size)
        if (len == 0) return 0
        var n = 0
        while (n < len) {
            val c = read()
            if (c < 0) break
            cbuf[off + n] = c.toChar()
            n++
            if (c == '\n'.code && reader is InputStreamReader) break
        }
        return if (n == 0) -1 else n
    }

    /** The next line without its terminator (`\n`, `\r` or `\r\n`), or null at the end. */
    public fun readLine(): String? {
        var c = nextChar()
        if (skipLf) {
            skipLf = false
            if (c == '\n'.code) c = nextChar()
        }
        if (c < 0) return null
        val sb = StringBuilder()
        while (c >= 0) {
            if (c == '\n'.code) return sb.toString()
            if (c == '\r'.code) {
                if (peekChar() == '\n'.code) peeked = -2
                return sb.toString()
            }
            sb.append(c.toChar())
            c = nextChar()
        }
        return sb.toString()
    }

    public override fun ready(): Boolean = peeked != -2 || reader.ready()

    public fun lines(): Sequence<String> = generateSequence { readLine() }

    public override fun close() {
        reader.close()
    }
}

// ---- writers --------------------------------------------------------------------------------------------------------------------

public abstract class Writer : Appendable, Closeable, Flushable {
    public abstract fun write(cbuf: CharArray, off: Int, len: Int)

    public open fun write(c: Int) {
        write(charArrayOf(c.toChar()), 0, 1)
    }

    public open fun write(cbuf: CharArray) {
        write(cbuf, 0, cbuf.size)
    }

    public open fun write(str: String) {
        write(str.toCharArray(), 0, str.length)
    }

    public open fun write(str: String, off: Int, len: Int) {
        write(str.toCharArray(), off, len)
    }

    public override fun append(value: Char): Writer {
        write(value.code)
        return this
    }

    public override fun append(value: CharSequence?): Writer {
        write(value.toString())
        return this
    }

    public override fun append(value: CharSequence?, startIndex: Int, endIndex: Int): Writer {
        write((value ?: "null").subSequence(startIndex, endIndex).toString())
        return this
    }

    public abstract override fun flush()

    public abstract override fun close()
}

public open class StringWriter(initialSize: Int) : Writer() {
    private val sb = StringBuilder()

    public constructor() : this(16)

    public override fun write(cbuf: CharArray, off: Int, len: Int) {
        for (i in off until off + len) sb.append(cbuf[i])
    }

    public override fun write(c: Int) {
        sb.append(c.toChar())
    }

    public override fun write(str: String) {
        sb.append(str)
    }

    public override fun flush() {}

    public override fun close() {}

    public fun getBuffer(): StringBuilder = sb

    public override fun toString(): String = sb.toString()
}

public open class BufferedWriter(private val out: Writer, sz: Int) : Writer() {
    public constructor(out: Writer) : this(out, 8192)

    public override fun write(cbuf: CharArray, off: Int, len: Int) {
        out.write(cbuf, off, len)
    }

    public override fun write(str: String) {
        out.write(str)
    }

    public override fun write(c: Int) {
        out.write(c)
    }

    public fun newLine() {
        out.write("\n")
    }

    public override fun flush() {
        out.flush()
    }

    public override fun close() {
        out.flush()
        out.close()
    }
}

public open class OutputStreamWriter(private val out: OutputStream) : Writer() {
    public constructor(out: OutputStream, charsetName: String) : this(out)
    public constructor(out: OutputStream, cs: kotlin.text.Charset) : this(out)

    public override fun write(cbuf: CharArray, off: Int, len: Int) {
        out.write(cbuf.concatToString(off, off + len).encodeToByteArray())
    }

    public override fun write(str: String) {
        out.write(str.encodeToByteArray())
    }

    public override fun flush() {
        out.flush()
    }

    public override fun close() {
        out.flush()
        out.close()
    }
}

public open class PrintWriter : Writer {
    private val out: Writer
    private val autoFlush: Boolean

    public constructor(out: Writer) : super() {
        this.out = out
        this.autoFlush = false
    }

    public constructor(out: Writer, autoFlush: Boolean) : super() {
        this.out = out
        this.autoFlush = autoFlush
    }

    public constructor(out: OutputStream) : super() {
        this.out = OutputStreamWriter(out)
        this.autoFlush = false
    }

    public constructor(out: OutputStream, autoFlush: Boolean) : super() {
        this.out = OutputStreamWriter(out)
        this.autoFlush = autoFlush
    }

    public constructor(file: File) : super() {
        this.out = FileWriter(file)
        this.autoFlush = false
    }

    public constructor(fileName: String) : super() {
        this.out = FileWriter(fileName)
        this.autoFlush = false
    }

    public override fun write(cbuf: CharArray, off: Int, len: Int) {
        out.write(cbuf, off, len)
    }

    public override fun write(str: String) {
        out.write(str)
    }

    public override fun write(c: Int) {
        out.write(c)
    }

    public fun print(s: String?) { out.write(s.toString()) }
    public fun print(o: Any?) { out.write(o.toString()) }
    public fun print(c: Char) { out.write(c.toString()) }
    public fun print(i: Int) { out.write(i.toString()) }
    public fun print(l: Long) { out.write(l.toString()) }
    public fun print(f: Float) { out.write(f.toString()) }
    public fun print(d: Double) { out.write(d.toString()) }
    public fun print(b: Boolean) { out.write(b.toString()) }
    public fun print(a: CharArray) { out.write(a.concatToString()) }

    public fun println() {
        out.write("\n")
        if (autoFlush) out.flush()
    }

    public fun println(s: String?) { print(s); println() }
    public fun println(o: Any?) { print(o); println() }
    public fun println(c: Char) { print(c); println() }
    public fun println(i: Int) { print(i); println() }
    public fun println(l: Long) { print(l); println() }
    public fun println(f: Float) { print(f); println() }
    public fun println(d: Double) { print(d); println() }
    public fun println(b: Boolean) { print(b); println() }
    public fun println(a: CharArray) { print(a); println() }

    public fun printf(format: String, vararg args: Any?): PrintWriter {
        out.write(format.format(*args))
        if (autoFlush) out.flush()
        return this
    }

    public fun format(format: String, vararg args: Any?): PrintWriter = printf(format, *args)

    public fun checkError(): Boolean {
        out.flush()
        return false
    }

    public override fun flush() {
        out.flush()
    }

    public override fun close() {
        out.flush()
        out.close()
    }
}
