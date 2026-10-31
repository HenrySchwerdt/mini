// %% Imports
// %dataset https://raw.githubusercontent.com/scikit-learn/scikit-learn/1.5.2/sklearn/datasets/data/digits.csv.gz
import mini.notebook.datasetText
import mini.tensor.*
import mini.tensor.nn.*
import mini.tensor.optim.*
import mini.plot.*
import mini.display.*

// %% Read the CSV
fun readDigits(): Pair<Tensor, Tensor> {
    val lines = datasetText().trim().split("\n")
    require(lines.size == 1797) { "Expected 1797 digit images" }
    val pixels = DoubleArray(1797 * 64)
    val labels = DoubleArray(1797)
    for (i in 0 until 1797) {
        val row = lines[i].trim().split(",")
        require(row.size == 65) { "Expected 64 pixels and one label" }
        for (j in 0 until 64) pixels[i * 64 + j] = row[j].toDouble() / 16.0
        labels[i] = row[64].toDouble()
    }
    return tensorOf(pixels, 1797, 64) to tensorOf(labels, 1797, dtype = DType.Int64)
}

// %% Hold out test images
data class DigitData(val trainX: Tensor, val trainY: Tensor, val testX: Tensor, val testY: Tensor)

fun splitDigits(images: Tensor, labels: Tensor): DigitData {
    manualSeed(7)
    val order = randperm(1797)
    val x = images.indexSelect(0, order)
    val y = labels.indexSelect(0, order)
    return DigitData(x.slice(0, 0, 1400), y.slice(0, 0, 1400),
        x.slice(0, 1400, 1797), y.slice(0, 1400, 1797))
}

// %% The shared training loop
data class DigitFit(val weights: Tensor, val bias: Tensor, val losses: List<Double>, val accuracy: Double)

fun fitDigits(data: DigitData, params: List<Tensor>, forward: (Tensor) -> Tensor): DigitFit {
    val optimizer = Adam(params, lr = 0.04)
    val losses = ArrayList<Double>()
    for (epoch in 0 until 100) {
        optimizer.zeroGrad()
        val loss = crossEntropy(forward(data.trainX), data.trainY)
        loss.backward()
        optimizer.step()
        losses.add(loss.item())
    }
    val accuracy = noGrad { forward(data.testX).argmax(1).eq(data.testY).mean().item() }
    return DigitFit(params[0], params[1], losses, accuracy)
}

// %% Model A: matrices and matmul
fun trainMatrices(data: DigitData): DigitFit {
    manualSeed(11)
    // [1400,64] @ [64,10] + [10] → [1400,10] logits.
    // Linear uses this same uniform initialization, with bound = 1/sqrt(64).
    val weight = ((rand(10, 64) * 2.0 - 1.0) * 0.125).requiresGrad()
    val bias = ((rand(10) * 2.0 - 1.0) * 0.125).requiresGrad()
    return fitDigits(data, listOf(weight, bias)) { x ->
        (x @ weight.T) + bias   // @ is Mini's matmul operator
    }
}

// %% Model B: Linear
fun trainLinear(data: DigitData): DigitFit {
    manualSeed(11)
    val model = Linear(64, 10)
    // Linear owns weight and bias and computes x @ weight.T + bias.
    return fitDigits(data, model.parameters()) { x -> model(x) }
}

// %% Plot API 1: pyplot
fun showPyplot(a: DigitFit, b: DigitFit) {
    plt.figure(figsize = 6.0 to 2.8)
    plt.plot(a.losses, label = "matmul")
    plt.plot(b.losses, label = "Linear", linestyle = "--")
    plt.xlabel("Epoch")
    plt.ylabel("Cross entropy")
    plt.title("1 · pyplot: the same learning curve")
    plt.legend()
    plt.show()
}

// %% Plot API 2: Figure and Axes
fun showAxes(a: DigitFit, b: DigitFit) {
    val (fig, axes) = plt.subplots(1, 2, figsize = 8.0 to 3.0)
    axes[0].plot(a.losses, label = "matmul")
    axes[0].plot(b.losses, label = "Linear", linestyle = "--")
    axes[0].set_title("2 · Axes: loss")
    axes[0].set_xlabel("Epoch")
    axes[0].legend()
    axes[1].bar(listOf("matmul", "Linear"), listOf(a.accuracy * 100.0, b.accuracy * 100.0))
    axes[1].set_title("Held-out accuracy")
    axes[1].set_ylabel("Percent")
    fig.show()
}

// %% Plot API 3: the block DSL
fun showDsl(a: DigitFit, b: DigitFit) {
    plot {
        line(a.losses, label = "matmul")
        line(b.losses, label = "Linear", linestyle = "--")
        title = "3 · DSL: the same learning curve"
        xlabel = "Epoch"
        ylabel = "Cross entropy"
        legend()
    }.show()
}

// %% Export a drawing pad
fun showDigitPad(name: String, fit: DigitFit) {
    println("$name · held-out accuracy: ${(fit.accuracy * 100.0).toFloat()}%")
    display("application/vnd.mini.digit-classifier+json",
        "{\"version\":1,\"weights\":[" + fit.weights.toList().joinToString(",") +
        "],\"bias\":[" + fit.bias.toList().joinToString(",") + "]}")
}

// %% Run the experiment
fun main() {
    val (images, labels) = readDigits()
    val data = splitDigits(images, labels)
    println("HTTP dataset: 1797 images · 1400 training / 397 held-out")
    val matrices = trainMatrices(data)
    val linear = trainLinear(data)
    println("Same weights after training: ${matrices.weights.allClose(linear.weights)}")
    println("Same bias after training: ${matrices.bias.allClose(linear.bias)}")
    showPyplot(matrices, linear)
    showAxes(matrices, linear)
    showDsl(matrices, linear)
    showDigitPad("matmul", matrices)
    showDigitPad("Linear", linear)
}
