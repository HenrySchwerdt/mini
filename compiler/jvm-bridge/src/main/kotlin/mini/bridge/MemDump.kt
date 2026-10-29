package mini.bridge
import org.jetbrains.kotlin.ir.util.dump
import java.io.File
object MemDump { fun run(src: File, extra: List<File>) { FrontendDriver.runFrontend(src, extra) { m, _ -> m.files.filter{ it.fileEntry.name.endsWith(src.name)}.forEach { println(it.dump()) } } } }
