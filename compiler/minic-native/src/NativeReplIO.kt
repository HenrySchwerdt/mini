@file:CInclude("stdio.h")
@file:Suppress("WRONG_MODIFIER_TARGET")

package mini.minicnative

import kotlin.native.CName
import kotlinx.cinterop.*

@CName("getchar") external fun replGetchar(): Int
@CName("isatty") external fun replIsatty(fd: Int): Int
@CName("fflush") external fun replFflush(stream: COpaquePointer?): Int

private const val REPL_LINE_MAX_BYTES = 65536

sealed class ReplLine {
    data class Input(val text: String) : ReplLine()
    object TooLong : ReplLine()
    object EndOfInput : ReplLine()
}

/** Read one UTF-8 line, up to [REPL_LINE_MAX_BYTES]. Oversize input is drained through its newline. */
fun readReplLine(): ReplLine = memScoped {
    val buffer = allocArray<ByteVar>(REPL_LINE_MAX_BYTES + 1)
    var size = 0
    while (size < REPL_LINE_MAX_BYTES) {
        val c = replGetchar()
        if (c < 0) {
            if (size == 0) return ReplLine.EndOfInput
            buffer[size] = 0
            return ReplLine.Input(buffer.toKString().removeSuffix("\r"))
        }
        if (c == 10) break
        buffer[size++] = c.toByte()
    }
    if (size == REPL_LINE_MAX_BYTES) {
        var c = replGetchar()
        if (c != 10 && c >= 0) {
            do {
                c = replGetchar()
            } while (c >= 0 && c != 10)
            return ReplLine.TooLong
        }
    }
    buffer[size] = 0
    ReplLine.Input(buffer.toKString().removeSuffix("\r"))
}

fun replIsInteractive(): Boolean = replIsatty(0) == 1

fun flushReplStdout() {
    replFflush(null)
}
