package mini.minicnative

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

private const val REPL_LINE_MAX_BYTES = 65536

sealed class ReplLine {
    data class Input(val text: String) : ReplLine()
    object TooLong : ReplLine()
    object EndOfInput : ReplLine()
}

/** Read one UTF-8 line, up to [REPL_LINE_MAX_BYTES]. Oversize input is drained through its newline. */
fun readReplLine(): ReplLine {
    val buffer = ByteArrayOutputStream()
    while (buffer.size() < REPL_LINE_MAX_BYTES) {
        val byte = System.`in`.read()
        if (byte < 0) {
            if (buffer.size() == 0) return ReplLine.EndOfInput
            return ReplLine.Input(buffer.toString(StandardCharsets.UTF_8).removeSuffix("\r"))
        }
        if (byte == '\n'.code) break
        buffer.write(byte)
    }
    if (buffer.size() == REPL_LINE_MAX_BYTES) {
        var byte = System.`in`.read()
        if (byte != '\n'.code && byte >= 0) {
            do {
                byte = System.`in`.read()
            } while (byte >= 0 && byte != '\n'.code)
            return ReplLine.TooLong
        }
    }
    return ReplLine.Input(buffer.toString(StandardCharsets.UTF_8).removeSuffix("\r"))
}

fun replIsInteractive(): Boolean = System.console() != null

fun flushReplStdout() {
    System.out.flush()
}
