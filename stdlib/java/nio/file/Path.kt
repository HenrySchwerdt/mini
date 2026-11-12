/*
 * Mini stdlib: java.nio.file.Path / Paths / Files over java.io.File (text files, see java/io/File.kt).
 */
package java.nio.file

import java.io.File
import java.io.IOException

public open class FileSystemException : IOException {
    public val file: String?

    public constructor(file: String?) : super(file) {
        this.file = file
    }

    public constructor(file: String?, other: String?, reason: String?) : super(if (reason != null) (file ?: "") + ": " + reason else file) {
        this.file = file
    }
}

public class NoSuchFileException(file: String?) : FileSystemException(file)

public class FileAlreadyExistsException(file: String?) : FileSystemException(file)

public class DirectoryNotEmptyException(dir: String?) : FileSystemException(dir)

public class NotDirectoryException(file: String?) : FileSystemException(file)

public class Path private constructor(private val file: File) : Comparable<Path> {
    public fun toFile(): File = file

    public val fileName: Path? get() = if (file.path.isEmpty() || file.path == "/") null else Path(File(file.name))

    public val parent: Path? get() = file.parentFile?.let { Path(it) }

    public val root: Path? get() = if (file.path.startsWith("/")) Path(File("/")) else null

    public val nameCount: Int get() = file.path.split("/").count { it.isNotEmpty() }

    public fun isAbsolute(): Boolean = file.path.startsWith("/")

    public fun getName(index: Int): Path {
        val parts = file.path.split("/").filter { it.isNotEmpty() }
        if (index < 0 || index >= parts.size) throw IllegalArgumentException()
        return Path(File(parts[index]))
    }

    public fun resolve(other: String): Path = resolve(Path(File(other)))

    public fun resolve(other: Path): Path {
        if (other.isAbsolute()) return other
        if (other.file.path.isEmpty()) return this
        if (file.path.isEmpty()) return other
        return Path(File(file.path + "/" + other.file.path))
    }

    public fun resolveSibling(other: String): Path {
        val p = parent ?: return Path(File(other))
        return p.resolve(other)
    }

    public fun toAbsolutePath(): Path = this

    public fun normalize(): Path {
        val abs = file.path.startsWith("/")
        val parts = ArrayList<String>()
        for (p in file.path.split("/")) {
            if (p.isEmpty() || p == ".") continue
            if (p == "..") {
                if (parts.isNotEmpty() && parts[parts.size - 1] != "..") parts.removeAt(parts.size - 1) else if (!abs) parts.add(p)
            } else {
                parts.add(p)
            }
        }
        val s = parts.joinToString("/")
        return Path(File(if (abs) "/" + s else s))
    }

    public fun startsWith(other: String): Boolean {
        val a = file.path
        val b = File(other).path
        return a == b || a.startsWith(b + "/") || (b == "/" && a.startsWith("/"))
    }

    public fun endsWith(other: String): Boolean {
        val a = file.path
        val b = File(other).path
        return a == b || a.endsWith("/" + b)
    }

    public fun relativize(other: Path): Path {
        val a = file.path.split("/").filter { it.isNotEmpty() }
        val b = other.file.path.split("/").filter { it.isNotEmpty() }
        var i = 0
        while (i < a.size && i < b.size && a[i] == b[i]) i++
        val parts = ArrayList<String>()
        for (k in i until a.size) parts.add("..")
        for (k in i until b.size) parts.add(b[k])
        return Path(File(parts.joinToString("/")))
    }

    public override fun compareTo(other: Path): Int = file.path.compareTo(other.file.path)

    public override fun equals(other: Any?): Boolean = other is Path && other.file.path == file.path

    public override fun hashCode(): Int = file.path.hashCode()

    public override fun toString(): String = file.path

    public companion object {
        public fun of(first: String, vararg more: String): Path {
            var s = first
            for (m in more) if (m.isNotEmpty()) s = if (s.isEmpty()) m else s + "/" + m
            return Path(File(s))
        }
    }
}

public object Paths {
    public fun get(first: String, vararg more: String): Path = Path.of(first, *more)
}

public object Files {
    public fun exists(path: Path): Boolean = path.toFile().exists()

    public fun notExists(path: Path): Boolean = !path.toFile().exists()

    public fun isDirectory(path: Path): Boolean = path.toFile().isDirectory

    public fun isRegularFile(path: Path): Boolean = path.toFile().isFile

    public fun size(path: Path): Long {
        if (!path.toFile().exists()) throw NoSuchFileException(path.toString())
        return path.toFile().length()
    }

    public fun readString(path: Path): String {
        if (!path.toFile().exists()) throw NoSuchFileException(path.toString())
        return java.io.fileTextOrThrow(path.toFile())
    }

    public fun readAllLines(path: Path): List<String> {
        val text = readString(path)
        if (text.isEmpty()) return emptyList()
        val r = java.io.BufferedReader(java.io.StringReader(text))
        val out = ArrayList<String>()
        while (true) out.add(r.readLine() ?: break)
        return out
    }

    public fun lines(path: Path): Sequence<String> = readAllLines(path).asSequence()

    public fun writeString(path: Path, text: CharSequence, vararg options: Any?): Path {
        val f = path.toFile()
        if (!f.parentFile.let { it == null || it.isDirectory }) throw NoSuchFileException(path.toString())
        if (options.contains(StandardOpenOption.APPEND)) {
            val old = if (f.exists()) java.io.fileTextOrThrow(f) else ""
            mini.internal.fileWriteText(f.path, old + text)
        } else {
            if (options.contains(StandardOpenOption.CREATE_NEW) && f.exists()) throw FileAlreadyExistsException(path.toString())
            mini.internal.fileWriteText(f.path, text.toString())
        }
        return path
    }

    public fun write(path: Path, lines: Iterable<CharSequence>, vararg options: Any?): Path {
        val sb = StringBuilder()
        for (l in lines) sb.append(l).append('\n')
        return writeString(path, sb, *options)
    }

    public fun write(path: Path, bytes: ByteArray, vararg options: Any?): Path {
        val f = path.toFile()
        if (!f.parentFile.let { it == null || it.isDirectory }) throw NoSuchFileException(path.toString())
        val append = options.contains(StandardOpenOption.APPEND)
        if (!append && options.contains(StandardOpenOption.CREATE_NEW) && f.exists()) throw FileAlreadyExistsException(path.toString())
        java.io.fileWriteBytesOrThrow(f, bytes, append)
        return path
    }

    public fun readAllBytes(path: Path): ByteArray = java.io.fileBytesOrThrow(path.toFile())

    public fun createDirectory(path: Path): Path {
        val f = path.toFile()
        if (f.exists()) throw FileAlreadyExistsException(path.toString())
        if (!f.mkdir()) throw NoSuchFileException(path.toString())
        return path
    }

    public fun createDirectories(path: Path): Path {
        val f = path.toFile()
        if (f.isDirectory) return path
        if (f.exists()) throw FileAlreadyExistsException(path.toString())
        f.mkdirs()
        return path
    }

    public fun createFile(path: Path): Path {
        val f = path.toFile()
        if (f.exists()) throw FileAlreadyExistsException(path.toString())
        if (!f.createNewFile()) throw NoSuchFileException(path.toString())
        return path
    }

    public fun createTempFile(prefix: String?, suffix: String?): Path = Path.of(File.createTempFile((prefix ?: "tmp") + "___", suffix).path)

    public fun createTempDirectory(prefix: String?): Path {
        val f = File("/tmp/" + (prefix ?: "") + mini.internal.nanoTime().toString())
        f.mkdirs()
        return Path.of(f.path)
    }

    public fun delete(path: Path) {
        val f = path.toFile()
        if (!f.exists()) throw NoSuchFileException(path.toString())
        if (f.isDirectory && f.list()?.isNotEmpty() == true) throw DirectoryNotEmptyException(path.toString())
        f.delete()
    }

    public fun deleteIfExists(path: Path): Boolean {
        val f = path.toFile()
        if (!f.exists()) return false
        delete(path)
        return true
    }

    public fun copy(source: Path, target: Path, vararg options: Any?): Path {
        if (!source.toFile().exists()) throw NoSuchFileException(source.toString())
        if (target.toFile().exists() && !options.contains(StandardCopyOption.REPLACE_EXISTING)) throw FileAlreadyExistsException(target.toString())
        java.io.fileWriteBytesOrThrow(target.toFile(), java.io.fileBytesOrThrow(source.toFile()), false)
        return target
    }

    public fun move(source: Path, target: Path, vararg options: Any?): Path {
        copy(source, target, *options)
        source.toFile().delete()
        return target
    }

    public fun list(dir: Path): Sequence<Path> {
        val names = dir.toFile().list() ?: throw NotDirectoryException(dir.toString())
        return names.map { dir.resolve(it) }.asSequence()
    }

    public fun walk(start: Path): Sequence<Path> {
        val out = ArrayList<Path>()
        walkInto(start, out)
        return out.asSequence()
    }

    private fun walkInto(p: Path, out: ArrayList<Path>) {
        out.add(p)
        if (p.toFile().isDirectory) for (c in p.toFile().list() ?: emptyArray()) walkInto(p.resolve(c), out)
    }
}

public enum class StandardOpenOption { READ, WRITE, APPEND, TRUNCATE_EXISTING, CREATE, CREATE_NEW }

public enum class StandardCopyOption { REPLACE_EXISTING, COPY_ATTRIBUTES, ATOMIC_MOVE }
