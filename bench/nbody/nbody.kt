// n-body: floating point math on 5 bodies. Kotlin/JVM and Mini Managed compile this very file.
import kotlin.math.sqrt

const val STEPS = 25000000
const val PI = 3.141592653589793
const val SOLAR_MASS = 4.0 * PI * PI
const val DAYS_PER_YEAR = 365.24

class Body(var x: Double, var y: Double, var z: Double, var vx: Double, var vy: Double, var vz: Double, val mass: Double)

fun offsetMomentum(bodies: Array<Body>) {
    var px = 0.0
    var py = 0.0
    var pz = 0.0
    for (i in 0 until 5) {
        px += bodies[i].vx * bodies[i].mass
        py += bodies[i].vy * bodies[i].mass
        pz += bodies[i].vz * bodies[i].mass
    }
    bodies[0].vx = -px / SOLAR_MASS
    bodies[0].vy = -py / SOLAR_MASS
    bodies[0].vz = -pz / SOLAR_MASS
}

fun advance(bodies: Array<Body>, dt: Double) {
    for (i in 0 until 5) {
        val a = bodies[i]
        for (j in i + 1 until 5) {
            val b = bodies[j]
            val dx = a.x - b.x
            val dy = a.y - b.y
            val dz = a.z - b.z
            val d2 = dx * dx + dy * dy + dz * dz
            val mag = dt / (d2 * sqrt(d2))
            a.vx -= dx * b.mass * mag
            a.vy -= dy * b.mass * mag
            a.vz -= dz * b.mass * mag
            b.vx += dx * a.mass * mag
            b.vy += dy * a.mass * mag
            b.vz += dz * a.mass * mag
        }
    }
    for (i in 0 until 5) {
        val b = bodies[i]
        b.x += dt * b.vx
        b.y += dt * b.vy
        b.z += dt * b.vz
    }
}

fun energy(bodies: Array<Body>): Double {
    var e = 0.0
    for (i in 0 until 5) {
        val a = bodies[i]
        e += 0.5 * a.mass * (a.vx * a.vx + a.vy * a.vy + a.vz * a.vz)
        for (j in i + 1 until 5) {
            val b = bodies[j]
            val dx = a.x - b.x
            val dy = a.y - b.y
            val dz = a.z - b.z
            e -= (a.mass * b.mass) / sqrt(dx * dx + dy * dy + dz * dz)
        }
    }
    return e
}

fun main() {
    val bodies = arrayOf(
        Body(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, SOLAR_MASS),
        Body(4.84143144246472090e+00, -1.16032004402742839e+00, -1.03622044471123109e-01, 1.66007664274403694e-03 * DAYS_PER_YEAR,
            7.69901118419740425e-03 * DAYS_PER_YEAR, -6.90460016972063023e-05 * DAYS_PER_YEAR, 9.54791938424326609e-04 * SOLAR_MASS),
        Body(8.34336671824457987e+00, 4.12479856412430479e+00, -4.03523417114321381e-01, -2.76742510726862411e-03 * DAYS_PER_YEAR,
            4.99852801234917238e-03 * DAYS_PER_YEAR, 2.30417297573763929e-05 * DAYS_PER_YEAR, 2.85885980666130812e-04 * SOLAR_MASS),
        Body(1.28943695621391310e+01, -1.51111514016986312e+01, -2.23307578892655734e-01, 2.96460137564761618e-03 * DAYS_PER_YEAR,
            2.37847173959480950e-03 * DAYS_PER_YEAR, -2.96589568540237556e-05 * DAYS_PER_YEAR, 4.36624404335156298e-05 * SOLAR_MASS),
        Body(1.53796971148509165e+01, -2.59193146099879641e+01, 1.79258772950371181e-01, 2.68067772490389322e-03 * DAYS_PER_YEAR,
            1.62824170038242295e-03 * DAYS_PER_YEAR, -9.51592254519715870e-05 * DAYS_PER_YEAR, 5.15138902046611451e-05 * SOLAR_MASS),
    )
    offsetMomentum(bodies)
    println((energy(bodies) * 1e9).toLong())
    for (i in 0 until STEPS) advance(bodies, 0.01)
    println((energy(bodies) * 1e9).toLong())
}
