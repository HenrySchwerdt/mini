package mini.internal

/*
 * Native surface for standard input and clocks (runtime/src/sysio.c, declared in the SYS block of
 * runtime/include/minirt.h). Convention: see CSymbol.
 */

/** One line of standard input without its line terminator, or null at end of input. */
@CSymbol("mk_sys_read_line")
public external fun stdinReadLine(): String?

/** Monotonic clock in nanoseconds. */
@CSymbol("mk_sys_nano_time")
public external fun nanoTime(): Long

/** Milliseconds since the Unix epoch. */
@CSymbol("mk_sys_epoch_millis")
public external fun epochMillis(): Long
