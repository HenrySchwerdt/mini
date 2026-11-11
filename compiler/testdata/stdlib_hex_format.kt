@OptIn(ExperimentalStdlibApi::class)
fun main() {
    println(byteArrayOf(1, 2, 10, -1).toHexString())
    println(byteArrayOf(1, 2, 10, -1).toHexString(HexFormat.UpperCase))
    println(255.toHexString())
    println((-1).toHexString())
    println(255L.toHexString())
    println(0x1234.toShort().toHexString())
    println((-2).toByte().toHexString())
    println("0aff".hexToByteArray().joinToString())
    println("7f".hexToByte())
    println("ffffffff".hexToInt())
    println("0000000000000010".hexToLong())
    println("1234".hexToShort())
    val fmt = HexFormat {
        upperCase = true
        bytes {
            bytesPerGroup = 2
            groupSeparator = " "
            byteSeparator = "-"
        }
        number {
            prefix = "0x"
            removeLeadingZeros = true
        }
    }
    val data = ByteArray(7) { (it * 37).toByte() }
    println(data.toHexString(fmt))
    println(255.toHexString(fmt))
    println(0.toHexString(fmt))
    println("0x00ff".hexToInt(fmt))
    println("0A-0B 0C".hexToByteArray(HexFormat { bytes { bytesPerGroup = 2; groupSeparator = " "; byteSeparator = "-" } }).joinToString())
    println(data.toHexString(1, 4))
    val lined = HexFormat { bytes.bytesPerLine = 4 }
    println(ByteArray(10) { it.toByte() }.toHexString(lined))
    println(HexFormat.Default.upperCase)
    println(HexFormat.UpperCase.upperCase)
    println(HexFormat.Default.number.prefix.length)
    println(HexFormat.Default)
    println(0xABu.toUByte().toHexString())
    println(0xDEADBEEFu.toHexString())
    println("ff".hexToUByte())
    println("deadbeef".hexToUInt())
    try {
        "xyz".hexToInt()
    } catch (e: NumberFormatException) {
        println("NFE " + e.message)
    }
    try {
        "123".hexToByteArray()
    } catch (e: NumberFormatException) {
        println("NFE " + e.message)
    }
    println("0A".hexToByteArray()[0])
    println(255.toHexString(HexFormat { number.minLength = 4 }))
}
