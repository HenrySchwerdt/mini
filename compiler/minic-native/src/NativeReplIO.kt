@file:CInclude("stdio.h")
@file:Suppress("WRONG_MODIFIER_TARGET")

package mini.minicnative

import kotlin.native.CName
import kotlinx.cinterop.*

@CName("getchar") external fun replGetchar(): Int
@CName("isatty") external fun replIsatty(fd: Int): Int
@CName("fflush") external fun replFflush(stream: COpaquePointer?): Int

/** Read one UTF-8 line from stdin (up to 8191 bytes); null means EOF. */
fun readReplLine(): String? = memScoped {
    val buffer = allocArray<ByteVar>(8192)
    var size = 0
    while (size < 8191) {
        val c = replGetchar()
        if (c < 0) {
            if (size == 0) return null
            break
        }
        if (c == 10) break
        buffer[size++] = c.toByte()
    }
    buffer[size] = 0
    buffer.toKString().removeSuffix("\r")
}

fun replIsInteractive(): Boolean = replIsatty(0) == 1

fun flushReplStdout() {
    replFflush(null)
}
