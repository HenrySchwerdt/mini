/*
 * Mini stdlib: java.io.File and the file readers / writers / streams on top of the runtime's whole-file text intrinsics
 * (mini.internal.file*). Paths are used as written (normalized: repeated and trailing separators are dropped); there is no working directory
 * query, so [File.absolutePath] of a relative path stays relative. Contents are UTF-8 text: binary data does not survive a round trip.
 */
package java.io

import mini.internal.fileDelete
import mini.internal.fileKind
import mini.internal.fileListDir
import mini.internal.fileMkdirs
import mini.internal.fileReadText
import mini.internal.fileWriteText

public class File : Comparable<File> {
    private val pathName: String

    public constructor(pathname: String) {
        pathName = normalizePath(pathname)
    }

    public constructor(parent: String?, child: String) {
        pathName = if (parent == null) normalizePath(child) else if (parent.isEmpty()) normalizePath("/" + child) else normalizePath(parent + "/" + child)
    }

    public constructor(parent: File?, child: String) {
        pathName = if (parent == null) normalizePath(child) else if (parent.pathName.isEmpty()) normalizePath("/" + child) else normalizePath(parent.pathName + "/" + child)
    }

    public val path: String get() = pathName

    public val name: String
        get() {
            val i = pathName.lastIndexOf('/')
            return if (i < 0) pathName else pathName.substring(i + 1)
        }

    public val parent: String?
        get() {
            val i = pathName.lastIndexOf('/')
            if (i < 0) return null
            if (i == 0) return if (pathName.length > 1) "/" else null
            return pathName.substring(0, i)
        }

    public val parentFile: File?
        get() {
            val p = parent ?: return null
            return File(p)
        }

    public val isAbsolute: Boolean get() = pathName.startsWith("/")

    public val absolutePath: String get() = pathName

    public val absoluteFile: File get() = this

    public val canonicalPath: String get() = canonical(pathName)

    public val canonicalFile: File get() = File(canonical(pathName))

    public fun exists(): Boolean = fileKind(pathName) != 0

    public val isFile: Boolean get() = fileKind(pathName) == 1

    public val isDirectory: Boolean get() = fileKind(pathName) == 2

    public val isHidden: Boolean get() = name.startsWith(".")

    public fun canRead(): Boolean = exists()

    public fun canWrite(): Boolean = exists()

    /** The size in bytes (of the UTF-8 text), 0 when the file does not exist. */
    public fun length(): Long {
        if (!isFile) return 0L
        val text = fileReadText(pathName) ?: return 0L
        return text.encodeToByteArray().size.toLong()
    }

    public fun list(): Array<String>? {
        if (!isDirectory) return null
        val text = fileListDir(pathName) ?: return null
        if (text.isEmpty()) return emptyArray()
        return text.split("\n").toTypedArray()
    }

    public fun listFiles(): Array<File>? {
        val names = list() ?: return null
        return Array(names.size) { File(this, names[it]) }
    }

    public fun listFiles(filter: (File) -> Boolean): Array<File>? {
        val all = listFiles() ?: return null
        return all.filter(filter).toTypedArray()
    }

    public fun mkdirs(): Boolean {
        if (exists()) return false
        return fileMkdirs(pathName)
    }

    public fun mkdir(): Boolean {
        if (exists()) return false
        val pf = parentFile
        if (pf != null && !pf.isDirectory) return false
        return fileMkdirs(pathName)
    }

    public fun createNewFile(): Boolean {
        if (exists()) return false
        if (!fileWriteText(pathName, "")) throw IOException("No such file or directory")
        return true
    }

    public fun delete(): Boolean {
        if (isDirectory && (list()?.isNotEmpty() == true)) return false
        return fileDelete(pathName)
    }

    public fun deleteOnExit() {}

    public fun renameTo(dest: File): Boolean {
        if (!exists() || dest.exists()) return false
        if (isDirectory) return false
        val text = fileReadText(pathName) ?: return false
        if (!fileWriteText(dest.pathName, text)) return false
        return fileDelete(pathName)
    }

    public fun setLastModified(time: Long): Boolean = exists()

    public fun toPath(): java.nio.file.Path = java.nio.file.Path.of(pathName)

    public override fun compareTo(other: File): Int = pathName.compareTo(other.pathName)

    public override fun equals(other: Any?): Boolean = other is File && other.pathName == pathName

    public override fun hashCode(): Int = pathName.hashCode() xor 1234321

    public override fun toString(): String = pathName

    public companion object {
        public const val separatorChar: Char = '/'
        public const val pathSeparatorChar: Char = ':'
        public val separator: String = "/"
        public val pathSeparator: String = ":"

        private var tempCounter = 0

        public fun createTempFile(prefix: String, suffix: String?): File {
            if (prefix.length < 3) throw IllegalArgumentException("Prefix string too short")
            return createTempFile(prefix, suffix, null)
        }

        public fun createTempFile(prefix: String, suffix: String?, directory: File?): File {
            if (prefix.length < 3) throw IllegalArgumentException("Prefix string too short")
            val dir = directory ?: File("/tmp")
            while (true) {
                tempCounter++
                val f = File(dir, prefix + mini.internal.nanoTime().toString() + tempCounter.toString() + (suffix ?: ".tmp"))
                if (!f.exists()) {
                    if (!fileWriteText(f.pathName, "")) throw IOException("No such file or directory")
                    return f
                }
            }
            @Suppress("UNREACHABLE_CODE")
            throw IllegalStateException()
        }
    }
}

private fun normalizePath(path: String): String {
    val sb = StringBuilder()
    var prevSlash = false
    for (c in path) {
        if (c == '/') {
            if (!prevSlash) sb.append(c)
            prevSlash = true
        } else {
            sb.append(c)
            prevSlash = false
        }
    }
    if (sb.length > 1 && sb[sb.length - 1] == '/') sb.setLength(sb.length - 1)
    return sb.toString()
}

private fun canonical(path: String): String {
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
    return if (abs) "/" + s else s
}

// ---- file readers / writers / streams ------------------------------------------------------------------------------------------------

private fun readFileOrThrow(path: String): String {
    if (fileKind(path) == 2) throw FileNotFoundException(path + " (Is a directory)")
    return fileReadText(path) ?: throw FileNotFoundException(path + " (No such file or directory)")
}

public class FileReader : InputStreamReader {
    private val text: StringReader

    public constructor(fileName: String) : this(File(fileName))

    public constructor(file: File) : super(ByteArrayInputStream(ByteArray(0))) {
        text = StringReader(readFileOrThrow(file.path))
    }

    public override fun read(): Int = text.read()

    public override fun read(cbuf: CharArray, off: Int, len: Int): Int = text.read(cbuf, off, len)

    public override fun ready(): Boolean = text.ready()

    public override fun close() {
        text.close()
    }
}

/** The file is written when the writer is flushed or closed (the whole contents at once). */
public class FileWriter : Writer {
    private val path: String
    private val base: String
    private val pending = StringBuilder()
    private var closed = false

    public constructor(fileName: String) : this(File(fileName), false)

    public constructor(fileName: String, append: Boolean) : this(File(fileName), append)

    public constructor(file: File) : this(file, false)

    public constructor(file: File, append: Boolean) : super() {
        path = file.path
        if (file.isDirectory) throw FileNotFoundException(path + " (Is a directory)")
        base = if (append && file.exists()) (fileReadText(path) ?: "") else ""
        if (!fileWriteText(path, base)) throw FileNotFoundException(path + " (No such file or directory)")
    }

    public override fun write(cbuf: CharArray, off: Int, len: Int) {
        if (closed) throw IOException("Stream closed")
        for (i in off until off + len) pending.append(cbuf[i])
    }

    public override fun write(str: String) {
        if (closed) throw IOException("Stream closed")
        pending.append(str)
    }

    public override fun write(c: Int) {
        if (closed) throw IOException("Stream closed")
        pending.append(c.toChar())
    }

    public override fun flush() {
        if (closed) throw IOException("Stream closed")
        if (!fileWriteText(path, base + pending.toString())) throw IOException("cannot write file: " + path)
    }

    public override fun close() {
        if (closed) return
        flush()
        closed = true
    }
}

public class FileInputStream : ByteArrayInputStream {
    public constructor(name: String) : this(File(name))

    public constructor(file: File) : super(readFileOrThrow(file.path).encodeToByteArray())
}

public class FileOutputStream : OutputStream {
    private val path: String
    private val base: String
    private val data = ByteArrayOutputStream()
    private var closed = false

    public constructor(name: String) : this(File(name), false)

    public constructor(name: String, append: Boolean) : this(File(name), append)

    public constructor(file: File) : this(file, false)

    public constructor(file: File, append: Boolean) : super() {
        path = file.path
        if (file.isDirectory) throw FileNotFoundException(path + " (Is a directory)")
        base = if (append && file.exists()) (fileReadText(path) ?: "") else ""
        if (!fileWriteText(path, base)) throw FileNotFoundException(path + " (No such file or directory)")
    }

    public override fun write(b: Int) {
        if (closed) throw IOException("Stream Closed")
        data.write(b)
    }

    public override fun flush() {
        if (closed) throw IOException("Stream Closed")
        if (!fileWriteText(path, base + data.toString())) throw IOException("cannot write file: " + path)
    }

    public override fun close() {
        if (closed) return
        flush()
        closed = true
    }
}

// ---- helpers for kotlin.io (internal to the stdlib) ----------------------------------------------------------------------------------

public fun fileTextOrThrow(file: File): String = readFileOrThrow(file.path)
