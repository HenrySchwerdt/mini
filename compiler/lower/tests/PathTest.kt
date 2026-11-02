import mini.lower.IrFile
import mini.lower.fileName
import kotlin.test.Test
import kotlin.test.assertEquals

class PathTest {
    @Test
    fun fileFacadeUsesLeafOnBothPlatforms() {
        for (path in listOf("NativeReplIO.kt", "/src/NativeReplIO.kt", "D:\\src\\NativeReplIO.kt", "D:/src\\NativeReplIO.kt", "\\\\server\\share\\NativeReplIO.kt")) {
            assertEquals("NativeReplIO.kt", fileName(path))
            assertEquals("NativeReplIO.kt", IrFile(path, "", "test", false).name)
        }
    }
}
