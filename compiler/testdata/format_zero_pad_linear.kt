// Zero padding of String.format is linear in the width (found by the libFuzzer harness tools/fuzz/libfuzzer, format): the padding was built by
// prepending one "0" at a time, so `%080282d` copied 6 GB and a width of 300000 did not finish in reasonable time under a sanitizer.
fun main() {
    val d = String.format("%0300000d", 42)
    println(d.length)
    println(d.substring(299990))
    val x = String.format("%#0300000x", 255)
    println(x.length)
    println(x.substring(0, 4) + " " + x.substring(299996))
    val f = String.format("%+0300000.2f", 3.5)
    println(f.length)
    println(f.substring(0, 3) + " " + f.substring(299995))
    println(String.format("%05d|%08.3f|%#06x|%(07d", -42, -1.5, 255, -9))
}
