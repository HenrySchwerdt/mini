// Char arithmetic and conversions
fun main() {
    val c = 'a'
    println(c + 1); println(c - 1); println('z' - 'a'); println(c.code); println(c.code + 1); println((c + 2).code)
    println('a'.compareTo('b')); println('5' - '0'); println('5'.digitToInt()); println(7.digitToChar()); println(11.digitToChar(16)); println('f'.digitToInt(16))
    println('a'.uppercaseChar()); println('Z'.lowercaseChar()); println('1'.isDigit()); println('x'.isLetter()); println(' '.isWhitespace()); println('_'.isLetterOrDigit())
    println(Char(65)); println(65.toChar()); println('A'.code.toChar()); println(('a'.code + 25).toChar()); println(Char.MAX_VALUE.code); println(Char.MIN_VALUE.code)
    var ch = 'a'; ch++; println(ch); ch += 2; println(ch); ch--; println(ch)
    println("abc".map { it + 1 }.joinToString("")); println("abc".map { (it.code - 'a'.code + 1) }); println("hello".map { if (it in 'a'..'m') it.uppercaseChar() else it }.joinToString(""))
    println("Hello".sumOf { it.code }); println("az".map { ((it - 'a' + 13) % 26 + 'a'.code).toChar() }.joinToString(""))
    println('a' < 'b' && 'b' <= 'b'); println('a'.equals('a')); println('a'.equals('A', ignoreCase = true)); println(listOf('c', 'a', 'b').sorted()); println(listOf('c', 'a').max())
    println('\n'.code); println('\t'.code); println('\\'.code); println('\''.code); println('\u0041'); println("\u00e9".single().code); println('€'.code)
    println('a'.toString() + 'b'); println('a' + "b"); println("" + 'a' + 'b'); println('a'.plus(1)); println('9'.code - 48)
    println(('a'..'c').map { it.toString().repeat(2) }); println('x'.isUpperCase()); println('X'.isUpperCase()); println('ß'.uppercaseChar()); println("ß".uppercase())
    println(Char('a'.code + 1)); println('a'.category); println('1'.isLetter()); println('٣'.isDigit()); println('٣'.digitToInt())
}
