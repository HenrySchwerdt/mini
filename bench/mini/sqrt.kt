// Mini shim: kotlin.math.sqrt is not in Mini's stdlib yet. The benchmarks import kotlin.math.sqrt (the real one on the JVM);
// the Mini builds add this file, which binds libm's sqrt (a single sqrtsd after inlining by clang).
package kotlin.math

import kotlin.native.CName

@CName("sqrt") external fun sqrt(x: Double): Double
