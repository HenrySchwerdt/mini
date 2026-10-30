// fannkuch-redux: integer array permutations and flips. Kotlin/JVM and Mini Managed compile this very file.
const val N = 11

fun main() {
    val perm = IntArray(N)
    val perm1 = IntArray(N)
    val count = IntArray(N)
    var maxFlips = 0
    var permCount = 0
    var checksum = 0
    for (i in 0 until N) perm1[i] = i
    var r = N
    while (true) {
        while (r != 1) {
            count[r - 1] = r
            r--
        }
        for (i in 0 until N) perm[i] = perm1[i]
        var flips = 0
        while (true) {
            val k = perm[0]
            if (k == 0) break
            val k2 = (k + 1) shr 1
            for (i in 0 until k2) {
                val t = perm[i]
                perm[i] = perm[k - i]
                perm[k - i] = t
            }
            flips++
        }
        if (flips > maxFlips) maxFlips = flips
        checksum += if (permCount % 2 == 0) flips else -flips
        while (true) {
            if (r == N) {
                println(checksum)
                println("Pfannkuchen($N) = $maxFlips")
                return
            }
            val p0 = perm1[0]
            for (i in 0 until r) perm1[i] = perm1[i + 1]
            perm1[r] = p0
            count[r] = count[r] - 1
            if (count[r] > 0) break
            r++
        }
        permCount++
    }
}
