/*
 * Standard input: readLine / readln / readlnOrNull.
 */
package kotlin.io

import mini.internal.stdinReadLine

/** The next line of standard input without its terminator, or null at end of input. */
public fun readLine(): String? = stdinReadLine()

/** The next line of standard input; throws when the input has ended. */
public fun readln(): String = stdinReadLine() ?: throw ReadAfterEOFException("EOF has already been reached")

public fun readlnOrNull(): String? = stdinReadLine()

public class ReadAfterEOFException(message: String?) : RuntimeException(message)
