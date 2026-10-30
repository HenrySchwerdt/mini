package mini.internal

/*
 * Native surface of the B1 bootstrap stage (file access for the self-hosted backend driver, see
 * stdlib/mini/io/Files.kt). Kept separate from Intrinsics.kt. Convention: see CSymbol; the symbols
 * are declared in the B1 block of runtime/include/minirt.h, implemented in runtime/src/b1.c.
 * The JVM implementation is stdlib-test/jvm/mini/internal/B1Intrinsics.kt.
 */

/** The whole UTF-8 file as a string, or null when it cannot be read. */
@CSymbol("mk_b1_read_file")
public external fun fileReadText(path: String): String?

/** Writes [text] as UTF-8 (create/truncate); false when that fails. */
@CSymbol("mk_b1_write_file")
public external fun fileWriteText(path: String, text: String): Boolean

/** 0 = [path] does not exist, 1 = regular file, 2 = directory. */
@CSymbol("mk_b1_file_kind")
public external fun fileKind(path: String): Int

/** The sorted entry names of a directory joined with `\n` (empty string for an empty directory), or null when it is not a directory. */
@CSymbol("mk_b1_list_dir")
public external fun fileListDir(path: String): String?
