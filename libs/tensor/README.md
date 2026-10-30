# mini.tensor

Tensors, linear algebra and automatic differentiation for Mini, with an API close to PyTorch's.
A library package (`kind = "lib"`): use it as a path dependency.

```toml
[dependencies]
mini-tensor = { path = "../mini/libs/tensor" }
```

```kotlin
import mini.tensor.*
import mini.tensor.nn.*
import mini.tensor.optim.*

fun main() {
    val a = tensor(doubleArrayOf(1.0, 2.0), doubleArrayOf(3.0, 4.0))
    val b = eye(2) * 2.0
    println(a @ b + 1.0)       // `@` is `a.matmul(b)`
    println(a.sum(0))          // tensor([4., 6.])
}
```

## API

Everything is in `mini.tensor` (top-level functions, like `import torch`); layers are in `mini.tensor.nn`, optimizers in `mini.tensor.optim`.

| Area | API |
|---|---|
| Creation | `tensor(1.0, 2.0)`, `tensor(row1, row2)` (DoubleArrays), `tensorOf(data, 2, 3)`, `fromList`, `fromRows`, `scalarTensor`, `zeros(2, 3)`, `ones`, `full(v, ...)`, `zerosLike`, `onesLike`, `arange(5)` (Int64) / `arange(0.0, 1.0, 0.1)`, `linspace`, `eye(n)`, `rand`, `randn`, `randint`, `randperm`; all take `dtype =` and `requiresGrad =` |
| Random | `manualSeed(s)`, `Generator(seed)` (SplitMix64, identical on every platform), `rand(2, 3, generator = g)` |
| Properties | `shape`, `ndim`, `numel`, `strides`, `dtype`, `size(dim)`, `item()`, `at(i, j)`, `toList()`, `T` |
| Indexing | `t[i]`, `t[i, j]` (sub-tensors, 0-d for a scalar), `t[i, j] = 1.0`, `row(i)`, `col(j)`, `select(dim, i)`, `slice(dim, start, end, step)`, `narrow`, `indexSelect(dim, idx)` |
| Shape | `view(...)`/`reshape(...)` (`-1` allowed, shares storage), `flatten()`, `t()`, `transpose(d0, d1)`, `permute(...)`, `unsqueeze`, `squeeze`, `expand`, `cat(a, b, dim = 1)`, `stack` |
| Arithmetic | `+ - * /` with tensors, `Double` and `Int`, NumPy broadcasting, unary `-`, `eq gt lt ge le`, `allClose`, `map { }` |
| Math | `exp log sqrt tanh sigmoid relu leakyRelu abs sin cos pow(e) square clamp softmax(dim) logSoftmax(dim)` |
| Reductions | `sum() mean() max() min() argmax() norm() std()`, and per dimension `sum(dim, keepdim)`, `mean(dim)`, `max(dim)` / `min(dim)` returning `(values, indices)`, `argmax(dim)` |
| Linear algebra | `a @ b` (`matmul`: matrix x matrix, matrix x vector, vector x vector, batched), `mm`, `mv`, `bmm`, `dot`, `outer`, `matrixPower(n)`, `trace()`, `solve(A, b)` |
| Autograd | `requiresGrad = true`, `loss.backward()`, `.grad`, `noGrad { ... }`, `detach()`, `zeroGrad()` |
| `nn` | `Module`, `Linear`, `ReLU`, `Tanh`, `Sigmoid`, `Softmax`, `Flatten`, `Sequential`, `MSELoss`, `CrossEntropyLoss`, `mseLoss`, `crossEntropy`, `nllLoss`, `l1Loss` |
| `optim` | `SGD(params, lr, momentum, weightDecay)`, `Adam(params, lr, ...)` |

`toString()` prints like PyTorch: aligned columns, four decimals (`1.` for integral floats), `...` for tensors with more than 1000 elements.
Wrong shapes throw `IllegalArgumentException` with the shapes in the message (`matmul: shape mismatch [2, 3] @ [4, 5]: inner dimensions 3 and 4 differ`).

`a @= b` works on a `var` (it is `a = a @ b`); there is no `matmulAssign` because a matrix product cannot be computed in place, and defining both would make `@=` ambiguous on a `var`.

### Autograd

Operations on tensors with `requiresGrad = true` are recorded; `backward()` fills `grad` of the leaves (gradients accumulate until `zeroGrad()`).
Differentiable: arithmetic with broadcasting, `matmul`, reductions, the math functions, `view`/`permute`/`slice`/`cat`/`stack`/`indexSelect`, `softmax`, losses.
`examples/xor` is a complete training loop.

### dtypes

`Float32` (default, like PyTorch), `Float64` and `Int64` (indices, `arange`, `argmax`). `Float32` tensors are stored as a real 32-bit `FloatArray` (`t.data` reads and writes `Double`, a store rounds to single precision); `Float64` and `Int64` use a `DoubleArray`. Element-wise operations compute in double and round the result to float32.
Mixed operations promote like PyTorch (`Int64 < Float32 < Float64`).

## BLAS backend

`matmul` calls `cblas_dgemm` / `dgemv` (Float64) or `cblas_sgemm` / `sgemv` (Float32, on the `FloatArray` directly) when a CBLAS library is available **at run time**; otherwise a pure Kotlin kernel runs (i-k-j loop, k blocked, four rows of B per pass).

* `native/mt_blas.c` (listed in `c-sources`) opens the library with `dlopen`: `libopenblas.so.0`, `libblis`, `libcblas.so.3`, `libblas.so.3` on Linux; the Accelerate framework on macOS.
  There is no build-time dependency and no flag: a machine without BLAS simply uses the Kotlin kernel. (`apt-get install libopenblas0` to get the fast path.)
* `Blas.available`, `Blas.name`, `Blas.enabled = false` (switch off at run time), `Blas.threshold` (smallest product that goes to BLAS).
  `MINI_TENSOR_BLAS=0` in the environment disables BLAS.
* All C calls live in `src/Blas.kt`. For a target without C interop (the browser), replace that file by `alt/Blas.kt` and remove `c-sources` from `mini.toml`: the library then runs on the Kotlin kernels only.

Float32 products with BLAS run in single precision (like PyTorch); without BLAS (or with `MINI_TENSOR_BLAS=0`) the Kotlin kernel computes in double and rounds the result.

### Benchmark (`bench/`, 4 core container, clang -O2, one run, noisy machine)

| n | naive i-j-k Kotlin | Kotlin i-k-j kernel (fallback) | OpenBLAS via mini.tensor | C naive | C i-k-j | C OpenBLAS |
|---|---|---|---|---|---|---|
| 256 | 1.2 GFLOP/s | 4.0 | 25 | 1.4 | 10.5 | 114 |
| 512 | 0.4 | 4.0 | 42 | 0.7 | 8.1 | 78 |
| 1024 | 0.2 | 4.0 | 214 | 0.2 | 5.1 | 89 |

(`cd bench && mini run --release`; C reference: `clang -O2 bench/c/ref.c -lopenblas`. Small sizes are dominated by thread start-up, and the 1024 BLAS figures vary a lot from run to run on a shared machine.)

## Memory

Tensors are ordinary reference counted objects (the default Managed mode): a temporary is freed when its last reference goes away, `view`/`reshape` share the storage array.
The backward closures hold the inputs of an operation but never its output, so there are no reference cycles.
Arena / allocator support: `mini.memory` allocators do not build natively yet (`unsupported construct: value class 'NativePtr'`), so there is no `context(arena)` example; tensors do not take an allocator parameter.

## Examples and tests

`examples/` (each is a package; `examples/run-all.sh` runs them and compares with `expected.txt`):
`linear_regression` (autograd + SGD, checked against the normal equation), `xor` (2-layer network with `Sequential`, `Adam`, `MSELoss`), `markov` (matrix powers, stationary distribution).
`mini test` in this directory runs the unit tests (broadcasting, matmul, reductions, printing, error messages, gradient checks against finite differences).
