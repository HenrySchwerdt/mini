import mini.tensor.*

// A weather Markov chain: matrix power gives the n-step transition probabilities, and the
// distribution after many days converges to the stationary distribution.
fun main() {
    // states: sunny, cloudy, rainy; rows are "from", columns are "to"
    val p = tensor(
        doubleArrayOf(0.7, 0.2, 0.1),
        doubleArrayOf(0.3, 0.4, 0.3),
        doubleArrayOf(0.2, 0.3, 0.5),
        dtype = DType.Float64
    )
    println("P =\n$p")
    println("P^2 =\n${p.matrixPower(2)}")

    val today = tensor(1.0, 0.0, 0.0, dtype = DType.Float64) // it is sunny
    var dist = today
    for (day in 1..3) {
        dist = dist @ p
        println("day $day: $dist")
    }
    println("after 50 days: ${today @ p.matrixPower(50)}")

    // the stationary distribution solves pi P = pi with sum(pi) = 1: (P^T - I) pi = 0
    val a = p.T - eye(3, dtype = DType.Float64)
    val rows = cat(a.slice(0, 0, 2), ones(1, 3, dtype = DType.Float64), dim = 0)
    val pi = solve(rows, tensor(0.0, 0.0, 1.0, dtype = DType.Float64))
    println("stationary: $pi")
}
