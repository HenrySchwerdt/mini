// %dataset https://raw.githubusercontent.com/scikit-learn/scikit-learn/1.5.2/sklearn/datasets/data/digits.csv.gz
import mini.notebook.datasetText
import mini.tensor.*
import mini.tensor.nn.*
import mini.tensor.optim.*
import mini.plot.*
import mini.display.*

// Each CSV row contains 64 intensities (0..16), followed by the label.
// A seeded shuffle holds out 397 images; no test images enter the optimizer.
fun readDigits(): Pair<Tensor, Tensor> {
    val pixels = DoubleArray(1797 * 64)
    val labels = DoubleArray(1797)
    val lines = datasetText().trim().split("\n")
    require(lines.size == 1797) { "Expected 1797 digit images" }
    for (i in 0 until 1797) {
        val columns = lines[i].trim().split(",")
        require(columns.size == 65) { "Expected 64 pixels and one label" }
        for (j in 0 until 64) pixels[i * 64 + j] = columns[j].toDouble() / 16.0
        labels[i] = columns[64].toDouble()
    }
    return tensorOf(pixels, 1797, 64) to tensorOf(labels, 1797, dtype = DType.Int64)
}

fun main() {
    val (images, labels) = readDigits()
    manualSeed(7)
    val order = randperm(1797)
    val shuffledImages = images.indexSelect(0, order)
    val shuffledLabels = labels.indexSelect(0, order)
    val trainX = shuffledImages.slice(0, 0, 1400)
    val trainY = shuffledLabels.slice(0, 0, 1400)
    val testX = shuffledImages.slice(0, 1400, 1797)
    val testY = shuffledLabels.slice(0, 1400, 1797)

    println("HTTP dataset: 1797 images · 8 × 8 pixels · 10 classes")
    println("Seeded split: 1400 training / 397 held-out test images")
    plt.figure(figsize = 3.0 to 3.0)
    plt.imshow(images.row(0).toDoubleArray(), rows = 8, cols = 8)
    plt.title("Dataset example: digit 0")
    plt.show()

    // One affine layer + cross entropy = multinomial logistic regression.
    // Small enough to train from scratch in a browser, using real autograd.
    val model = Linear(64, 10)
    val optimizer = Adam(model.parameters(), lr = 0.04)
    val losses = ArrayList<Double>()
    for (epoch in 0 until 100) {
        optimizer.zeroGrad()
        val loss = crossEntropy(model(trainX), trainY)
        loss.backward()
        optimizer.step()
        losses.add(loss.item())
        if (epoch % 20 == 0) println("epoch $epoch · loss ${loss.item().toFloat()}")
    }
    val accuracy = noGrad { model(testX).argmax(1).eq(testY).mean().item() }
    println("Held-out accuracy: ${(accuracy * 100.0).toFloat()}%")
    plt.figure(figsize = 6.0 to 2.8)
    plt.plot(losses, label = "Training cross entropy")
    plt.xlabel("Epoch")
    plt.ylabel("Loss")
    plt.title("Learning to read digits")
    plt.show()

    // A data-only output bundle. The host runs softmax(x @ weight.T + bias)
    // on the exact trained parameters; it never executes code from the bundle.
    display("application/vnd.mini.digit-classifier+json",
        "{\"version\":1,\"weights\":[" + model.weight.toList().joinToString(",") +
        "],\"bias\":[" + model.bias!!.toList().joinToString(",") + "]}")

}
