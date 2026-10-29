import java.io.File

/** Programs that use the C test library (`@file:CInclude("cinterop_lib.h")`) need `cinterop_lib.c` compiled and linked in. */
object CInteropTestData {
    fun cArgs(src: File): Array<String> =
        if (src.readText().contains("cinterop_lib.h")) arrayOf("--c-src", File(src.parentFile, "cinterop_lib.c").path) else emptyArray()
}
