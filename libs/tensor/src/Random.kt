package mini.tensor

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/** A deterministic pseudo random generator (SplitMix64): the same seed gives the same numbers on every platform. */
class Generator(seed: Long) {
    private var state: Long = seed
    private var spare: Double = 0.0
    private var hasSpare: Boolean = false

    fun manualSeed(seed: Long) {
        state = seed
        hasSpare = false
    }

    fun nextLong(): Long {
        state += -7046029254386353131L // 0x9E3779B97F4A7C15
        var z = state
        z = (z xor (z ushr 30)) * -4658895280553007687L // 0xBF58476D1CE4E5B9
        z = (z xor (z ushr 27)) * -7723592293110705685L // 0x94D049BB133111EB
        return z xor (z ushr 31)
    }

    /** Uniform in [0, 1). */
    fun nextDouble(): Double = (nextLong() ushr 11).toDouble() * (1.0 / 9007199254740992.0)

    /** Standard normal (Box-Muller). */
    fun nextGaussian(): Double {
        if (hasSpare) {
            hasSpare = false
            return spare
        }
        var u = nextDouble()
        while (u <= 1e-300) u = nextDouble()
        val v = nextDouble()
        val r = sqrt(-2.0 * ln(u))
        val a = 2.0 * PI * v
        spare = r * kotlin.math.sin(a)
        hasSpare = true
        return r * cos(a)
    }
}

/** The generator used when none is given. */
val defaultGenerator: Generator = Generator(0L)

fun manualSeed(seed: Long) { defaultGenerator.manualSeed(seed) }

/** Uniform [0, 1). */
fun rand(vararg shape: Int, generator: Generator = defaultGenerator, dtype: DType = defaultDtype, requiresGrad: Boolean = false): Tensor {
    val t = build(shape, dtype, requiresGrad)
    for (i in 0 until t.data.size) t.data[i] = generator.nextDouble()
    t.data.roundTo(dtype)
    return t
}

/** Standard normal. */
fun randn(vararg shape: Int, generator: Generator = defaultGenerator, dtype: DType = defaultDtype, requiresGrad: Boolean = false): Tensor {
    val t = build(shape, dtype, requiresGrad)
    for (i in 0 until t.data.size) t.data[i] = generator.nextGaussian()
    t.data.roundTo(dtype)
    return t
}

/** Uniform integers in [low, high) as an Int64 tensor. */
fun randint(low: Int, high: Int, vararg shape: Int, generator: Generator = defaultGenerator): Tensor {
    if (high <= low) fail("randint needs low < high, got $low and $high")
    val t = build(shape, DType.Int64, false)
    val span = (high - low).toDouble()
    for (i in 0 until t.data.size) t.data[i] = (low + (generator.nextDouble() * span).toInt()).toDouble()
    return t
}

/** A random permutation of 0 until n (Int64). */
fun randperm(n: Int, generator: Generator = defaultGenerator): Tensor {
    val t = arange(n)
    var i = n - 1
    while (i > 0) {
        val j = (generator.nextDouble() * (i + 1)).toInt()
        val tmp = t.data[i]
        t.data[i] = t.data[j]
        t.data[j] = tmp
        i--
    }
    return t
}
