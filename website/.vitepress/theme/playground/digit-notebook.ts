import { newCell, type Notebook } from './notebook.ts'
import training from './digit-training.kt?raw'

export function digitNotebook(): Notebook {
  return {
    title: 'The digit lab',
    cells: [
      newCell('markdown', `# Teach Mini your digits

**Download → train → draw.** Run the cell below to fetch 1,797 handwritten digits over HTTPS, train a 64 → 10 classifier with **mini.tensor**, and chart its loss with **mini.plot**. After training, a drawing pad appears right in the output, using the weights you just learned.

This is a small neural classifier, not a language model. The 8×8 dataset keeps training practical in WebAssembly. The first run also downloads the compiler toolchain. Training has a two-minute time limit; you can stop it at any point.

The notebook host interprets \`// %dataset https://…\`, decompresses gzip, and exposes CSV as \`mini.notebook.datasetText()\` in the compiled program. Mini does the parsing, split, training and evaluation. No Python or remote model is involved.

Data: [UCI Optical Recognition of Handwritten Digits](https://archive.ics.uci.edu/dataset/80/optical+recognition+of+handwritten+digits), distributed by [scikit-learn](https://github.com/scikit-learn/scikit-learn/blob/1.5.2/sklearn/datasets/data/digits.csv.gz). Draw a large centered digit; these low-resolution scans differ from mouse handwriting.`),
      newCell('code', training),
      newCell('markdown', `## Make it yours

Change the epoch count, learning rate or random seed and run again. Compare the held-out accuracy and try the same handwritten digit. More epochs can improve the training loss without improving new drawings.

The drawing pad is a snapshot of the parameters emitted by that run. Each cell starts a fresh Mini program; rerunning trains a fresh model. Saving a .minb keeps the code, not its outputs or trained weights.

Explore [mini.tensor](/libraries/tensor), the [plot gallery](/libraries/plot), or start a [blank experiment](/notebook).`),
    ],
  }
}
