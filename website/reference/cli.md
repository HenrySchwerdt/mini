---
title: Command line
---

# Command line

Mini has two command-line programs.

| | `minic` | `mini` |
|---|---|---|
| **Is** | the compiler driver | the project tool, like `cargo` |
| **You use it for** | one or a few `.kt` files: `minic build hello.kt -o hello` | whole projects: `mini new`, `mini run`, `mini test`, dependencies, `mini.toml` |
| **Installed by** | the [release archive](/install) | the [release archive](/install), as a native binary next to `minic` |
| **Reference** | [`minic`](./minic) | [`mini`](./mini) |

```sh
# one file
minic build hello.kt -o hello && ./hello

# a project
mini new hello && cd hello && mini run
```

## Quick reference

| I want to | Command |
|---|---|
| build and run one file | `minic run prog.kt` |
| compile a program | `minic build prog.kt -o prog` |
| optimize | `minic build prog.kt -o prog -O2` |
| see the generated C | `minic build prog.kt -o prog --emit-c prog.c` |
| run only the memory checker | `minic check prog.kt` |
| count allocations and retains | `./minic build prog.kt -o prog --stats` in a checkout |
| start a project | `mini new name` |
| build and run a project | `mini run` |
| understand an error code | `mini explain M0102`, or the [diagnostics reference](/reference/diagnostics/) |

## Exit codes

Both programs use `0` for success, `1` for a compile, build or test failure (the diagnostics were printed) and `2` for a command-line mistake.
