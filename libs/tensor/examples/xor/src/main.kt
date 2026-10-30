import mini.tensor.*
import mini.tensor.nn.*
import mini.tensor.optim.Adam

// A two-layer network learns XOR, written like a PyTorch tutorial.
fun main() {
    manualSeed(7)
    val x = tensor(
        doubleArrayOf(0.0, 0.0),
        doubleArrayOf(0.0, 1.0),
        doubleArrayOf(1.0, 0.0),
        doubleArrayOf(1.0, 1.0)
    )
    val y = tensor(doubleArrayOf(0.0), doubleArrayOf(1.0), doubleArrayOf(1.0), doubleArrayOf(0.0))

    val model = Sequential(Linear(2, 8), Tanh(), Linear(8, 1), Sigmoid())
    val lossFn = MSELoss()
    val opt = Adam(model.parameters(), lr = 0.05)

    for (epoch in 0..300) {
        opt.zeroGrad()
        val loss = lossFn(model(x), y)
        loss.backward()
        opt.step()
        if (epoch % 100 == 0) println("epoch $epoch loss ${loss.item().toFloat()}")
    }

    val pred = noGrad { model(x) }
    println("predictions: $pred")
    println("rounded: ${pred.gt(0.5)}")
    println("accuracy: ${pred.gt(0.5).eq(y).mean().item().toFloat()}")
}
