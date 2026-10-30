#!/usr/bin/env node
// Writes every runnable code cell of the docs to disk, so CI can build, run or check exactly what the browser would compile.
//
//   node website/scripts/extract-snippets.mjs <out-dir>
//
// It renders every page with the same markdown pipeline as the site (VitePress + the mini-cells plugin), takes the cells the
// plugin found (env.miniCells) and writes the assembled program of each (chained cells see the declarations above them, a
// fragment is wrapped in `fun main`: the same `programFor` the cell component uses). Next to each NAME.kt:
//   NAME.out    documented output (a ```output fence): the program is built and run and its stdout must match
//   NAME.run    no checked output: the program is built and run and must exit with the status written in the file (default 0)
//   NAME.check  check cell: ok | error CODE | warning CODE (same format as website/samples)
// Cells that are unmodified `<<<` includes of a sample are skipped: check-samples.sh verifies the sample files themselves.
import { mkdirSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createMarkdownRenderer } from 'vitepress'
import { miniCellsPlugin } from '../.vitepress/mini-cells.ts'
import { programFor } from '../.vitepress/theme/snippets.ts'

const here = dirname(fileURLToPath(import.meta.url))
const root = resolve(here, '..')
const out = process.argv[2]
if (!out) {
  console.error('usage: extract-snippets.mjs <out-dir>')
  process.exit(2)
}
rmSync(out, { recursive: true, force: true })
mkdirSync(out, { recursive: true })

function* pages(dir) {
  for (const name of readdirSync(dir)) {
    if (name === 'node_modules' || name === '.vitepress' || name === 'public' || name === 'samples') continue
    const p = join(dir, name)
    if (statSync(p).isDirectory()) yield* pages(p)
    else if (name.endsWith('.md')) yield p
  }
}

const md = await createMarkdownRenderer(root, { config: (m) => m.use(miniCellsPlugin) }, '/mini/', { warn() {} })
let written = 0
let skipped = 0
for (const file of [...pages(root)].sort()) {
  const rel = relative(root, file)
  const env = { path: file, relativePath: rel, realPath: file }
  md.render(readFileSync(file, 'utf8'), env)
  const cells = env.miniCells ?? []
  const sources = cells.map((c) => c.source)
  cells.forEach((c, k) => {
    const prog = programFor(cells, sources, k)
    if (c.from && !c.chain && prog.file.text === c.source) {
      skipped++
      return
    }
    const base = join(out, `${rel.replace(/\.md$/, '').replace(/[\\/]/g, '_')}__${String(k).padStart(2, '0')}`)
    writeFileSync(`${base}.kt`, prog.file.text)
    if (c.kind === 'check') {
      const e = c.expect ?? { kind: 'ok' }
      writeFileSync(`${base}.check`, e.kind === 'ok' ? 'ok\n' : `${e.kind} ${e.code}\n`)
    } else if (c.expected !== undefined && !c.exercise && !c.exit) {
      writeFileSync(`${base}.out`, c.expected)
    } else {
      writeFileSync(`${base}.run`, c.exit ? `${c.exit}\n` : '')
    }
    written++
  })
}
console.log(`extract-snippets: ${written} programs written to ${out} (${skipped} unmodified samples skipped)`)
