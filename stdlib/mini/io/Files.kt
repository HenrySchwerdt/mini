package mini.io

import mini.internal.fileKind
import mini.internal.fileListDir
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

/** True when [path] exists (a file or a directory). */
public fun fileExists(path: String): Boolean = fileKind(path) != 0

/** True when [path] is a directory. */
public fun isDirectory(path: String): Boolean = fileKind(path) == 2

/** True when [path] is a regular file. */
public fun isRegularFile(path: String): Boolean = fileKind(path) == 1

/** The names (not paths) of the entries of the directory [path], sorted; empty when it is not a directory. */
public fun listDirectory(path: String): List<String> {
    val text = fileListDir(path) ?: return emptyList()
    if (text.isEmpty()) return emptyList()
    return text.split("\n")
}

/**
 * All files below [dir] (recursively) whose name ends with [suffix], as paths that start with [dir], sorted.
 * Directories named `build`, `.git` and `.gradle` are skipped.
 */
public fun walkFiles(dir: String, suffix: String): List<String> {
    val out = ArrayList<String>()
    walkInto(dir, suffix, out)
    return out.sorted()
}

private fun walkInto(dir: String, suffix: String, out: ArrayList<String>) {
    for (name in listDirectory(dir)) {
        val path = if (dir.endsWith("/")) dir + name else dir + "/" + name
        when (fileKind(path)) {
            2 -> if (name != "build" && name != ".git" && name != ".gradle") walkInto(path, suffix, out)
            1 -> if (name.endsWith(suffix)) out.add(path)
        }
    }
}
