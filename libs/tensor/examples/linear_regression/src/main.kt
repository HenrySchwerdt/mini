import mini.tensor.*
import mini.tensor.optim.SGD

// Fit y = 3x + 2 (plus a little noise) by gradient descent with autograd, then check against the normal equation.
fun main() {
    manualSeed(42)
    val n = 100
    val x = linspace(-1.0, 1.0, n).view(n, 1)
    val y = x * 3.0 + 2.0 + randn(n, 1) * 0.1

    val w = zeros(1, 1, requiresGrad = true)
    val b = zeros(1, requiresGrad = true)
    val opt = SGD(listOf(w, b), lr = 0.1)

    for (epoch in 0..200) {
        opt.zeroGrad()
        val pred = x @ w + b
        val loss = ((pred - y) * (pred - y)).mean()
        loss.backward()
        opt.step()
        if (epoch % 50 == 0) println("epoch $epoch loss ${loss.item().toFloat()}")
    }
    println("w = ${w.item().toFloat()}, b = ${b.item().toFloat()}")

    // closed form: solve (X^T X) beta = X^T y with X = [x, 1]
    val design = cat(x, ones(n, 1), dim = 1)
    val beta = solve(design.T @ design, design.T @ y)
    println("normal equation: $beta")
}
