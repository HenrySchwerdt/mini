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

/** The IEEE 754 bits of a Double / Float and back. */
@CSymbol("mk_sys_double_bits")
public external fun doubleToBits(v: Double): Long

@CSymbol("mk_sys_float_bits")
public external fun floatToBits(v: Float): Int

@CSymbol("mk_sys_bits_double")
public external fun bitsToDouble(bits: Long): Double

@CSymbol("mk_sys_bits_float")
public external fun bitsToFloat(bits: Int): Float
