---
title: mini.tensor
---

# mini.tensor

Tensors, automatic differentiation and neural networks in Mini. Train with familiar `Linear`, `Sequential`, `crossEntropy` and `Adam` APIs, then run the same code natively or in WebAssembly.

**[Open the digit lab →](/libraries/digits)** Download handwritten digits over HTTPS, train a classifier, inspect its loss curve, and draw your own digits in the notebook output. Everything runs in your browser.

## A training loop

```kotlin
import mini.tensor.*
import mini.tensor.nn.*
import mini.tensor.optim.*

fun main() {
    manualSeed(7)
    val x = tensor(doubleArrayOf(0.0), doubleArrayOf(1.0), doubleArrayOf(2.0))
    val y = tensor(doubleArrayOf(1.0), doubleArrayOf(3.0), doubleArrayOf(5.0))
    val model = Linear(1, 1)
    val optimizer = Adam(model.parameters(), lr = 0.1)
    for (epoch in 0 until 100) {
        optimizer.zeroGrad()
        val loss = mseLoss(model(x), y)
        loss.backward()
        optimizer.step()
    }
    println(noGrad { model(x) })
}
```

## What is available

| Area | Examples |
|---|---|
| Creation | `tensor`, `tensorOf`, `zeros`, `ones`, `randn`, `arange` |
| Shape and indexing | `reshape`, `transpose`, `slice`, `indexSelect` |
| Arithmetic | Broadcasting, `@` matrix multiplication, reductions, softmax |
| Autograd | `requiresGrad`, `backward`, `grad`, `noGrad` |
| Layers | `Linear`, `ReLU`, `Tanh`, `Sigmoid`, `Sequential` |
| Training | `MSELoss`, `CrossEntropyLoss`, `SGD`, `Adam` |
| Plots | Pass `toDoubleArray()` to [mini.plot](/libraries/plot) |

The browser includes the library automatically when you import it. Native projects add a source dependency:

```toml
[dependencies]
mini-tensor = { path = "../mini/libs/tensor" }
```

Native matrix multiplication can use BLAS when available. The browser uses the pure Mini kernels. See the [library reference and examples](https://github.com/HenrySchwerdt/mini/tree/main/libs/tensor) for the complete API.
