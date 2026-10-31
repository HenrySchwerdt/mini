import { newCell, type Notebook } from './notebook.ts'
import training from './digit-training.kt?raw'

const explanations = [
  `## 1. Imports and HTTP data
The host downloads the gzip CSV over HTTPS and exposes its text as \`mini.notebook.datasetText()\`. The dataset directive also applies to later cells. Tensor math, training and plotting run in Mini.`,
  `## 2. Read and normalize
Each row has 64 pixel intensities (0–16) and one class label. Divide pixels by 16 to get inputs in [0, 1]. Labels stay integer class indices.`,
  `## 3. One split for both models
Shuffle once with a fixed seed. Keep 397 images out of training. Both implementations receive exactly the same 1,400 training images and 397 test images.`,
  `## 4. One training loop
Both models use cross entropy, autograd and Adam for 100 epochs. Only their forward calculation and parameter construction differ. This function returns losses, accuracy and the trained weights.`,
  `## 5. Model A · just matrices
Create the weight matrix and bias explicitly, then compute \`x @ weight.T + bias\`. The \`@\` operator calls \`matmul\`. Broadcasting adds the ten biases to every image. We initialize exactly like \`Linear\` so the comparison starts from identical parameters.`,
  `## 6. Model B · Linear
The same model expressed as a layer. \`Linear(64, 10)\` creates and owns the two tensors; \`model(x)\` performs the same matrix multiplication and bias addition. The shared loop is unchanged.`,
  `## 7. Plot style A · pyplot
The stateful, matplotlib-like API: configure the current figure through \`plt\`, then show it. Both learning curves should overlap; the dashed line lets you compare them.`,
  `## 8. Plot style B · Figure / Axes
Keep explicit handles to a figure and its axes. This is convenient for subplots: learning curves on the left, held-out accuracy on the right. Each axis has its own labels and title.`,
  `## 9. Plot style C · block DSL
The same loss curves with a receiver block. Call \`line\` directly and assign \`title\`, \`xlabel\` and \`ylabel\` inside \`plot { … }\`. It uses the same Axes renderer as the other APIs.`,
  `## 10. Turn learned weights into an interactive output
This small helper exports the actual trained matrix and bias as a data-only display bundle. The host supplies the drawing pad and computes softmax scores locally. Each model gets its own pad.`,
  `## 11. Run and compare
Run this final cell (or use **Run experiment** above). It compiles all the declarations above into one program, downloads the dataset, trains both models, renders all three plot APIs and opens two drawing pads. The weight and bias checks should both print \`true\`.

Each run is a fresh program. The earlier cells define helpers; you can edit them without running each one separately.`,
]

export function digitNotebook(): Notebook {
  const sections = training.split(/^\/\/ %% .+\r?\n/gm).slice(1).map(s => s.trim())
  return {
    title: 'The digit lab · matrices, layers and plots',
    cells: [
      newCell('markdown', `# Two models. Three ways to plot.

Train a handwritten-digit classifier twice: first with raw matrices and \`matmul\`, then with \`Linear\`. Compare the same experiment through **pyplot**, **Figure/Axes**, and the **plot DSL**. Short code cells explain each step; **Run experiment** executes the assembled program once.

Data: 1,797 handwritten 8×8 digits from [UCI](https://archive.ics.uci.edu/dataset/80/optical+recognition+of+handwritten+digits), distributed by [scikit-learn](https://github.com/scikit-learn/scikit-learn/blob/1.5.2/sklearn/datasets/data/digits.csv.gz). The first run also loads the browser compiler. Draw large, centered digits; scans and mouse handwriting differ.`),
      ...sections.flatMap((source, i) => [newCell('markdown', explanations[i]), newCell('code', source)]),
      newCell('markdown', `## Try a change
Change the shared learning rate or epoch count, then run the final cell again. Change only the matrix model's seed to see how initialization affects the curves. Both pads use the parameters emitted by that run.

Saving a .minb keeps the code, not outputs or trained weights. Explore [mini.tensor](/libraries/tensor), the [plot gallery](/libraries/plot), or a [new notebook](/notebook).`),
    ],
  }
}
