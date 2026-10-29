package mini.io

import mini.internal.fileReadText
import mini.internal.fileWriteText

/*
 * Minimal text file access (bootstrap B1). Files are UTF-8. The command line arrives through
 * `fun main(args: Array<String>)`.
 */

/** The whole file as a string; throws [IllegalStateException] when it cannot be read. */
public fun readTextFile(path: String): String =
    fileReadText(path) ?: throw IllegalStateException("cannot read file: " + path)

/** Replaces the contents of [path] with [text]; throws [IllegalStateException] when it cannot be written. */
public fun writeTextFile(path: String, text: String) {
    if (!fileWriteText(path, text)) throw IllegalStateException("cannot write file: " + path)
}
