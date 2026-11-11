/*
 * Mini stdlib: kotlin.io.path (the extensions on java.nio.file.Path that portable programs use).
 */
package kotlin.io.path

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

@RequiresOptIn(level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
@MustBeDocumented
public annotation class ExperimentalPathApi

public fun Path(path: String): Path = Path.of(path)

public fun Path(base: String, vararg subpaths: String): Path = Path.of(base, *subpaths)

public fun String.toPath(): Path = Path.of(this)

public val Path.name: String get() = fileName?.toString() ?: ""

public val Path.extension: String get() = name.substringAfterLast('.', "")

public val Path.nameWithoutExtension: String get() = name.substringBeforeLast(".")

public val Path.pathString: String get() = toString()

public val Path.invariantSeparatorsPathString: String get() = toString()

public operator fun Path.div(other: Path): Path = this.resolve(other)

public operator fun Path.div(other: String): Path = this.resolve(other)

public fun Path.absolute(): Path = this

public fun Path.exists(): Boolean = Files.exists(this)

public fun Path.notExists(): Boolean = Files.notExists(this)

public fun Path.isDirectory(): Boolean = Files.isDirectory(this)

public fun Path.isRegularFile(): Boolean = Files.isRegularFile(this)

public fun Path.fileSize(): Long = Files.size(this)

public fun Path.readText(): String = Files.readString(this)

public fun Path.writeText(text: CharSequence): Unit { Files.writeString(this, text) }

public fun Path.appendText(text: CharSequence): Unit { Files.writeString(this, text, StandardOpenOption.APPEND) }

public fun Path.readLines(): List<String> = Files.readAllLines(this)

public fun Path.readBytes(): ByteArray = Files.readAllBytes(this)

public fun Path.writeBytes(array: ByteArray): Unit { Files.write(this, array) }

public fun Path.writeLines(lines: Iterable<CharSequence>): Unit { Files.write(this, lines) }

public fun Path.forEachLine(action: (String) -> Unit) {
    for (l in Files.readAllLines(this)) action(l)
}

public fun <T> Path.useLines(block: (Sequence<String>) -> T): T = block(Files.lines(this))

public fun Path.createDirectory(): Path = Files.createDirectory(this)

public fun Path.createDirectories(): Path = Files.createDirectories(this)

public fun Path.createFile(): Path = Files.createFile(this)

public fun Path.deleteExisting() { Files.delete(this) }

public fun Path.deleteIfExists(): Boolean = Files.deleteIfExists(this)

public fun Path.copyTo(target: Path, overwrite: Boolean = false): Path =
    if (overwrite) Files.copy(this, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING) else Files.copy(this, target)

public fun Path.moveTo(target: Path, overwrite: Boolean = false): Path =
    if (overwrite) Files.move(this, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING) else Files.move(this, target)

public fun Path.listDirectoryEntries(): List<Path> = Files.list(this).toList().sorted()

@ExperimentalPathApi
public fun Path.walk(): Sequence<Path> = Files.walk(this)


public fun createTempFile(prefix: String? = null, suffix: String? = null): Path = Files.createTempFile(prefix, suffix)

public fun createTempDirectory(prefix: String? = null): Path = Files.createTempDirectory(prefix)

@ExperimentalPathApi
public fun Path.deleteRecursively() {
    val f = toFile()
    for (x in f.walkBottomUp()) x.delete()
}
