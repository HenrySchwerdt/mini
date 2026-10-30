---
title: "Libraries"
---

# Libraries

A Mini library is a **source package**: a directory with a `mini.toml` (`kind = "lib"`) whose sources are compiled together with the program that uses it, exactly like the standard library. You add one as a path or git dependency, see [`mini`: the project tool](/reference/mini).

In the browser there is no package registry yet, so the libraries below are **preinstalled** in the playground and the notebook: `import mini.tensor.*` or `import mini.plot.*` in any runnable cell just works.

| Library | Package | What it does |
|---|---|---|
| [mini.tensor](/libraries/tensor) | `mini.tensor` | Tensors, autograd, neural layers and optimizers. [Train a digit classifier →](/libraries/digits) |
| [mini.plot](/libraries/plot) | `mini.plot` | Line, scatter, bar, histogram and heatmap plots as SVG. |
| [Rich output](/libraries/display) | `mini.display` (standard library) | Show SVG, HTML and tables below a cell: the protocol `mini.plot` is built on. |

## A complete experiment

[**The digit lab →**](/libraries/digits) combines the libraries in one editable notebook: fetch a real dataset, train a classifier with autograd, plot its learning curve, and test it with your own handwriting. No installation required.
