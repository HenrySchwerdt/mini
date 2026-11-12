/*
 * Mini stdlib: kotlin.io file and stream extensions (readText, writeText, readLines, useLines, walk, ...), see java/io/File.kt for the model.
 */
package kotlin.io

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.Reader
import java.io.StringReader
import java.io.StringWriter
import java.io.Writer
import java.io.fileBytesOrThrow
import java.io.fileTextOrThrow
import java.io.fileWriteBytesOrThrow
import kotlin.text.Charset
import kotlin.text.Charsets

public const val DEFAULT_BUFFER_SIZE: Int = 8 * 1024

// ---- File: reading and writing ----------------------------------------------------------------------------------------------------

public fun File.readText(charset: Charset = Charsets.UTF_8): String =
    if (charset == Charsets.UTF_8) fileTextOrThrow(this) else fileBytesOrThrow(this).toString(charset)

public fun File.writeText(text: String, charset: Charset = Charsets.UTF_8) {
    fileWriteBytesOrThrow(this, text.toByteArray(charset), false)
}

public fun File.appendText(text: String, charset: Charset = Charsets.UTF_8) {
    fileWriteBytesOrThrow(this, text.toByteArray(charset), true)
}

public fun File.readBytes(): ByteArray = fileBytesOrThrow(this)

public fun File.writeBytes(array: ByteArray) {
    fileWriteBytesOrThrow(this, array, false)
}

public fun File.appendBytes(array: ByteArray) {
    fileWriteBytesOrThrow(this, array, true)
}

/** Reads the file in blocks of [blockSize] bytes (at least 512); the array passed to [action] is reused, only its first `n` bytes are valid. */
public fun File.forEachBlock(blockSize: Int, action: (buffer: ByteArray, bytesRead: Int) -> Unit) {
    val bytes = fileBytesOrThrow(this)
    val buf = ByteArray(if (blockSize < 512) 512 else blockSize)
    var pos = 0
    while (pos < bytes.size) {
        val n = if (bytes.size - pos < buf.size) bytes.size - pos else buf.size
        for (k in 0 until n) buf[k] = bytes[pos + k]
        action(buf, n)
        pos += n
    }
}

public fun File.forEachBlock(action: (buffer: ByteArray, bytesRead: Int) -> Unit): Unit = forEachBlock(4096, action)

public fun File.readLines(charset: Charset = Charsets.UTF_8): List<String> {
    val result = ArrayList<String>()
    forEachLine(charset) { result.add(it) }
    return result
}

public fun File.forEachLine(charset: Charset = Charsets.UTF_8, action: (line: String) -> Unit) {
    val r = bufferedReader(charset)
    try {
        while (true) {
            val line = r.readLine() ?: break
            action(line)
        }
    } finally {
        r.close()
    }
}

public fun <T> File.useLines(charset: Charset = Charsets.UTF_8, block: (Sequence<String>) -> T): T {
    val r = bufferedReader(charset)
    try {
        return block(r.lineSequence())
    } finally {
        r.close()
    }
}

public fun File.reader(charset: Charset = Charsets.UTF_8): InputStreamReader = FileReader(this, charset)

public fun File.bufferedReader(charset: Charset = Charsets.UTF_8, bufferSize: Int = DEFAULT_BUFFER_SIZE): BufferedReader = BufferedReader(FileReader(this, charset), bufferSize)

public fun File.writer(charset: Charset = Charsets.UTF_8): OutputStreamWriter = OutputStreamWriter(FileOutputStream(this), charset)

public fun File.bufferedWriter(charset: Charset = Charsets.UTF_8, bufferSize: Int = DEFAULT_BUFFER_SIZE): BufferedWriter = BufferedWriter(FileWriter(this, charset), bufferSize)

public fun File.printWriter(charset: Charset = Charsets.UTF_8): PrintWriter = PrintWriter(FileWriter(this, charset))

public fun File.inputStream(): InputStream = FileInputStream(this)

public fun File.outputStream(): OutputStream = FileOutputStream(this)

public fun File.copyTo(target: File, overwrite: Boolean = false, bufferSize: Int = DEFAULT_BUFFER_SIZE): File {
    if (!this.exists()) throw FileNotFoundException(this.path + ": The source file doesn't exist.")
    if (target.exists()) {
        if (!overwrite) throw IOException(target.path + ": The destination file already exists.")
        else if (!target.delete()) throw IOException(target.path + ": Failed to overwrite the destination file.")
    }
    if (this.isDirectory) {
        if (!target.mkdirs()) throw IOException(target.path + ": Failed to create target directory.")
    } else {
        target.parentFile?.mkdirs()
        target.writeBytes(this.readBytes())
    }
    return target
}

public fun File.copyRecursively(target: File, overwrite: Boolean = false, onError: (File, IOException) -> OnErrorAction = { _, exception -> throw exception }): Boolean {
    if (!exists()) {
        return onError(this, FileNotFoundException(path + ": The source file doesn't exist.")) != OnErrorAction.TERMINATE
    }
    try {
        for (src in walkTopDown()) {
            if (!src.exists()) {
                if (onError(src, FileNotFoundException(src.path + ": The source file doesn't exist.")) == OnErrorAction.TERMINATE) return false
            } else {
                val relPath = src.relativeTo(this)
                val dstFile = File(target, relPath.path)
                if (dstFile.exists() && !(src.isDirectory && dstFile.isDirectory)) {
                    val stillExists = if (!overwrite) true else {
                        if (dstFile.isDirectory) !dstFile.deleteRecursively() else !dstFile.delete()
                    }
                    if (stillExists) {
                        if (onError(dstFile, java.io.IOException("The destination file already exists.")) == OnErrorAction.TERMINATE) return false
                        continue
                    }
                }
                if (src.isDirectory) {
                    dstFile.mkdirs()
                } else {
                    src.copyTo(dstFile, overwrite)
                }
            }
        }
        return true
    } catch (e: IOException) {
        return false
    }
}

public enum class OnErrorAction { SKIP, TERMINATE }

public fun File.deleteRecursively(): Boolean = walkBottomUp().fold(true) { res, it -> (it.delete() || !it.exists()) && res }

// ---- File: names and paths ----------------------------------------------------------------------------------------------------------

public val File.extension: String
    get() = name.substringAfterLast('.', "")

public val File.nameWithoutExtension: String
    get() = name.substringBeforeLast(".")

public val File.invariantSeparatorsPath: String
    get() = path

public val File.isRooted: Boolean
    get() = path.startsWith("/")

public fun File.startsWith(other: File): Boolean = toComponents().startsWith(other.toComponents())

public fun File.startsWith(other: String): Boolean = startsWith(File(other))

public fun File.endsWith(other: File): Boolean = path.endsWith(other.path) && (path.length == other.path.length || other.path.startsWith("/") || path[path.length - other.path.length - 1] == '/')

public fun File.endsWith(other: String): Boolean = endsWith(File(other))

public fun File.normalize(): File {
    val abs = path.startsWith("/")
    val parts = ArrayList<String>()
    for (p in path.split("/")) {
        if (p.isEmpty() || p == ".") continue
        if (p == "..") {
            if (parts.isNotEmpty() && parts[parts.size - 1] != "..") parts.removeAt(parts.size - 1) else if (!abs) parts.add(p)
        } else {
            parts.add(p)
        }
    }
    val s = parts.joinToString("/")
    return File(if (abs) "/" + s else s)
}

public fun File.resolve(relative: File): File {
    if (relative.isRooted) return relative
    val base = this.path
    return if (base.isEmpty() || base.endsWith("/")) File(base + relative.path) else File(base + "/" + relative.path)
}

public fun File.resolve(relative: String): File = resolve(File(relative))

public fun File.resolveSibling(relative: File): File {
    val p = parentFile
    return if (p != null) p.resolve(relative) else File("").resolve(relative)
}

public fun File.resolveSibling(relative: String): File = resolveSibling(File(relative))

public fun File.toRelativeString(base: File): String =
    toRelativeStringOrNull(base) ?: throw IllegalArgumentException("this and base files have different roots: " + this + " and " + base + ".")

public fun File.relativeTo(base: File): File = File(this.toRelativeString(base))

public fun File.relativeToOrSelf(base: File): File = toRelativeStringOrNull(base)?.let { File(it) } ?: this

public fun File.relativeToOrNull(base: File): File? = toRelativeStringOrNull(base)?.let { File(it) }

private fun File.toRelativeStringOrNull(base: File): String? {
    val thisParts = normalize().toComponents()
    val baseParts = base.normalize().toComponents()
    if (thisParts.root != baseParts.root) return null
    val baseCount = baseParts.segments.size
    val thisCount = thisParts.segments.size
    var i = 0
    val maxSameCount = if (thisCount < baseCount) thisCount else baseCount
    while (i < maxSameCount && thisParts.segments[i] == baseParts.segments[i]) i++
    val sb = StringBuilder()
    val sameCount = i
    for (j in baseCount - 1 downTo sameCount) {
        if (baseParts.segments[j] == "..") return null
        sb.append("..")
        if (j != sameCount) sb.append('/')
    }
    if (sameCount < baseCount && sameCount < thisCount) sb.append('/')
    for (j in sameCount until thisCount) {
        sb.append(thisParts.segments[j])
        if (j != thisCount - 1) sb.append('/')
    }
    return sb.toString()
}

public class FilePathComponents internal constructor(public val root: String, public val segments: List<String>) {
    public val size: Int get() = segments.size

    internal fun startsWith(other: FilePathComponents): Boolean {
        if (root != other.root || other.segments.size > segments.size) return false
        for (i in other.segments.indices) if (segments[i] != other.segments[i]) return false
        return true
    }
}

public fun File.toComponents(): FilePathComponents {
    val p = path
    val root = if (p.startsWith("/")) "/" else ""
    val segs = ArrayList<String>()
    for (s in p.split("/")) if (s.isNotEmpty()) segs.add(s)
    return FilePathComponents(root, segs)
}

// ---- walking ------------------------------------------------------------------------------------------------------------------------

public enum class FileWalkDirection { TOP_DOWN, BOTTOM_UP }

public class FileTreeWalk private constructor(
    private val start: File,
    private val direction: FileWalkDirection,
    private val maxDepth: Int,
    private val onEnter: ((File) -> Boolean)?,
    private val onLeave: ((File) -> Unit)?,
    private val onFail: ((f: File, e: IOException) -> Unit)?,
) : Sequence<File> {
    internal constructor(start: File, direction: FileWalkDirection) : this(start, direction, Int.MAX_VALUE, null, null, null)

    public override fun iterator(): Iterator<File> {
        val out = ArrayList<File>()
        if (start.exists()) collect(start, 0, out)
        return out.iterator()
    }

    private fun collect(dir: File, depth: Int, out: ArrayList<File>) {
        if (dir.isDirectory) {
            if (onEnter != null && !onEnter.invoke(dir)) return
            if (direction == FileWalkDirection.TOP_DOWN) out.add(dir)
            if (depth < maxDepth) {
                val children = dir.listFiles()
                if (children == null) {
                    onFail?.invoke(dir, IOException("Cannot list " + dir.path))
                } else {
                    for (c in children) collect(c, depth + 1, out)
                }
            }
            if (direction == FileWalkDirection.BOTTOM_UP) out.add(dir)
            onLeave?.invoke(dir)
        } else {
            out.add(dir)
        }
    }

    public fun onEnter(function: (File) -> Boolean): FileTreeWalk = FileTreeWalk(start, direction, maxDepth, function, onLeave, onFail)

    public fun onLeave(function: (File) -> Unit): FileTreeWalk = FileTreeWalk(start, direction, maxDepth, onEnter, function, onFail)

    public fun onFail(function: (File, IOException) -> Unit): FileTreeWalk = FileTreeWalk(start, direction, maxDepth, onEnter, onLeave, function)

    public fun maxDepth(depth: Int): FileTreeWalk {
        if (depth <= 0) throw IllegalArgumentException("depth must be positive, but was " + depth + ".")
        return FileTreeWalk(start, direction, depth, onEnter, onLeave, onFail)
    }
}

public fun File.walk(direction: FileWalkDirection = FileWalkDirection.TOP_DOWN): FileTreeWalk = FileTreeWalk(this, direction)

public fun File.walkTopDown(): FileTreeWalk = walk(FileWalkDirection.TOP_DOWN)

public fun File.walkBottomUp(): FileTreeWalk = walk(FileWalkDirection.BOTTOM_UP)

// ---- Reader / Writer / streams --------------------------------------------------------------------------------------------------------

public fun Reader.readText(): String {
    val sb = StringBuilder()
    val buf = CharArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val n = read(buf)
        if (n < 0) break
        for (i in 0 until n) sb.append(buf[i])
    }
    return sb.toString()
}

public fun Reader.readLines(): List<String> {
    val result = ArrayList<String>()
    forEachLine { result.add(it) }
    return result
}

public fun Reader.forEachLine(action: (String) -> Unit): Unit = useLines { it.forEach(action) }

public fun <T> Reader.useLines(block: (Sequence<String>) -> T): T {
    val r = buffered()
    try {
        return block(r.lineSequence())
    } finally {
        r.close()
    }
}

public fun Reader.buffered(bufferSize: Int = DEFAULT_BUFFER_SIZE): BufferedReader = if (this is BufferedReader) this else BufferedReader(this, bufferSize)

public fun BufferedReader.lineSequence(): Sequence<String> = generateSequence { readLine() }

public fun Reader.copyTo(out: Writer, bufferSize: Int = DEFAULT_BUFFER_SIZE): Long {
    var chars = 0L
    val buffer = CharArray(bufferSize)
    var n = read(buffer)
    while (n >= 0) {
        out.write(buffer, 0, n)
        chars += n
        n = read(buffer)
    }
    return chars
}

public fun String.reader(): StringReader = StringReader(this)

public fun Writer.buffered(bufferSize: Int = DEFAULT_BUFFER_SIZE): BufferedWriter = if (this is BufferedWriter) this else BufferedWriter(this, bufferSize)

public fun Writer.buffered(): BufferedWriter = buffered(DEFAULT_BUFFER_SIZE)

public fun Writer.printWriter(): PrintWriter = if (this is PrintWriter) this else PrintWriter(this)

public fun InputStream.reader(charset: Charset = Charsets.UTF_8): InputStreamReader = InputStreamReader(this, charset)

public fun InputStream.bufferedReader(charset: Charset = Charsets.UTF_8): BufferedReader = BufferedReader(InputStreamReader(this, charset))

public fun InputStream.buffered(bufferSize: Int = DEFAULT_BUFFER_SIZE): InputStream = this

public fun InputStream.readBytes(): ByteArray = readAllBytes()

public fun InputStream.copyTo(out: OutputStream, bufferSize: Int = DEFAULT_BUFFER_SIZE): Long {
    var bytes = 0L
    while (true) {
        val b = read()
        if (b < 0) break
        out.write(b)
        bytes++
    }
    return bytes
}

public fun OutputStream.writer(charset: Charset = Charsets.UTF_8): OutputStreamWriter = OutputStreamWriter(this, charset)

public fun OutputStream.bufferedWriter(charset: Charset = Charsets.UTF_8): BufferedWriter = BufferedWriter(OutputStreamWriter(this, charset))

public fun OutputStream.buffered(bufferSize: Int = DEFAULT_BUFFER_SIZE): OutputStream = this

public fun ByteArray.inputStream(): ByteArrayInputStream = ByteArrayInputStream(this)

public fun String.byteInputStream(charset: Charset = Charsets.UTF_8): ByteArrayInputStream = ByteArrayInputStream(toByteArray(charset))

public fun ByteArray.inputStream(offset: Int, length: Int): ByteArrayInputStream = ByteArrayInputStream(this, offset, length)
