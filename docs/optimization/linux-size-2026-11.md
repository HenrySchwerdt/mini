# Linux executable size, November 2026 (`claude/perf-5`)

Three small programs built with `minic build` (C backend, `-O2`) against the same programs written in Rust (`rustc 1.x -O -C strip=symbols`).
Sources: hello (`println`), a collection-heavy one (1000 data-class items: `sortedByDescending`, `groupBy`, `HashMap`, `map`/`filter`/`sum`,
`toSet`), a string-heavy one (`StringBuilder`, `split`, `uppercase`, `joinToString`, `replace`, `substring`, `trim`).

| program | before (bytes) | after (bytes) | Rust, stripped |
| --- | ---: | ---: | ---: |
| hello | 17 416 | 14 576 | 342 520 |
| collections | 166 984 | 133 520 | 397 544 |
| strings | 135 128 | 96 656 | 360 448 |

The generated C and the program output are unchanged; the speed is unchanged (the Unicode tables are decoded once, on the first non-ASCII
character, exactly as before).

* **No symbol table.** Optimized Linux builds are linked with `-Wl,--gc-sections,--strip-all` (`scripts/minic.sh`, and `mini build --release`).
  The symbol table was a fifth of a small executable and only profilers and debuggers read it. `--debug`/`-g` builds keep it;
  `MINIC_KEEP_SYMBOLS=1` (`MINI_KEEP_SYMBOLS=1` for `mini`) keeps it in optimized builds. `mini build --release` now also compiles
  with function and data sections and without unwind tables, like `minic`, so `--gc-sections` has something to drop.
* **Compact Unicode tables.** `stdlib/kotlin/text/UnicodeTables.kt` stored every run as hexadecimal text (8 characters per case-mapping
  run, 5 per category run); a program that calls `trim()`, `uppercase()` or `isWhitespace()` embedded 26 KB of it. The tables now hold
  distances between run starts in 5-bit printable groups (`tools/gen-unicode-tables.py` and `UnicodeData.kt` agree on the encoding; the
  decoded data is identical, checked run by run against the old tables): 14.5 KB to 5.9 KB (categories), 10.3 KB to 2.8 KB (upper case),
  10.1 KB to 2.7 KB (lower case).
* Measured and left alone: `-Os` saves 4 KB of 117 KB of `.text` and costs speed; `-Wl,-z,noseparate-code` and `-z norelro` save 4 KB and
  1 KB but weaken the default hardening (as noted in `bench/hillclimb/LOG.md`, 9); `-no-pie` would save 30 KB of relocations in the
  collection program (about 1100 relocations for the vtables) but gives up ASLR; RELR relocations need glibc 2.36.
