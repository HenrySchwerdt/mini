/**
 * markdown-it plugin: every Kotlin fence that is a runnable program (or marked so, see theme/snippets.ts) is wrapped in a
 * `<MiniCell>` component. The highlighted block VitePress renders stays inside as the component's slot, so the static build
 * (and a browser without JavaScript or WebAssembly) still shows the highlighted code.
 *
 * The cells of a page are also collected in `env.miniCells`, which is how `scripts/extract-snippets.mjs` gets exactly the
 * programs CI has to verify.
 */
import { existsSync, readFileSync } from 'node:fs'
import type { default as MarkdownItClass } from 'markdown-it'
import { decideCell, isKotlin, parseFenceInfo, type CellSpec } from './theme/snippets.ts'

const SAMPLES_MARK = '/samples/'

function sidecar(sampleFile: string, ext: string): string | undefined {
  const f = sampleFile.replace(/\.kt$/, '') + ext
  return existsSync(f) ? readFileSync(f, 'utf8') : undefined
}

export function miniCellsPlugin(md: InstanceType<typeof MarkdownItClass>) {
  // A ```output fence right after a Kotlin fence is that cell's expected output: take it out of the token stream.
  md.core.ruler.push('mini_cell_output', (state) => {
    const toks = state.tokens
    for (let i = 0; i + 1 < toks.length; i++) {
      const t = toks[i]
      const n = toks[i + 1]
      if (t.type !== 'fence' || n.type !== 'fence' || n.info.trim() !== 'output') continue
      if (!isKotlin(parseFenceInfo(t.info).lang)) continue
      t.meta = { ...(t.meta ?? {}), output: n.content }
      toks.splice(i + 1, 1)
    }
    return true
  })

  const inner = md.renderer.rules.fence!
  md.renderer.rules.fence = (tokens, idx, options, env, self) => {
    const html = inner(tokens, idx, options, env, self)
    const token = tokens[idx]
    const { lang } = parseFenceInfo(token.info)
    if (!isKotlin(lang)) return html

    // `<<<` includes carry the sample path in token.src (set by VitePress); the file's content is in token.content by now.
    let sample: Parameters<typeof decideCell>[0]['sample']
    const src: string | undefined = (token as unknown as { src?: string[] }).src?.[0]
    if (src && src.includes(SAMPLES_MARK)) {
      sample = {
        path: src.slice(src.lastIndexOf(SAMPLES_MARK) + SAMPLES_MARK.length),
        out: sidecar(src, '.out'),
        check: sidecar(src, '.check'),
        norun: existsSync(src.replace(/\.kt$/, '.norun')),
      }
    }
    const frontmatter = (env?.frontmatter ?? {}) as { notebook?: boolean }
    const source = token.content.replace(/\n$/, '') + '\n'
    const decided = decideCell({
      info: token.info,
      source,
      notebookPage: frontmatter.notebook === true,
      sample,
      output: (token.meta as { output?: string } | null)?.output,
    })
    if (!decided) return html

    const cells: CellSpec[] = ((env as { miniCells?: CellSpec[] }).miniCells ??= [])
    const spec: CellSpec = { index: cells.length, ...decided }
    cells.push(spec)
    return `<MiniCell :spec="${md.utils.escapeHtml(JSON.stringify(spec))}">${html}</MiniCell>\n`
  }
}
