---
title: "Libraries"
---

# Libraries

A Mini library is a **source package**: a directory with a `mini.toml` (`kind = "lib"`) whose sources are compiled together with the program that uses it, exactly like the standard library. You add one as a path or git dependency, see [`mini`: the project tool](/reference/mini).

In the browser there is no package registry yet, so the libraries below are **preinstalled** in the playground and the notebook: `import mini.plot.*` in any runnable cell just works.

| Library | Package | What it does |
|---|---|---|
| [mini.plot](/libraries/plot) | `mini.plot` | Line, scatter, bar, histogram and heatmap plots as SVG. |
| [Rich output](/libraries/display) | `mini.display` (standard library) | Show SVG, HTML and tables below a cell: the protocol `mini.plot` is built on. |

More libraries (tensors, for example) will be listed here as they arrive.
