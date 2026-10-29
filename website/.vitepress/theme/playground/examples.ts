/**
 * The examples dropdown is fed from website/samples, the same files the documentation pages include. Vite
 * turns every file into its own lazy chunk, so nothing is downloaded until an example is chosen.
 */
const sources = import.meta.glob('../../../samples/**/*.kt', { query: '?raw', import: 'default' }) as Record<string, () => Promise<string>>
const expectations = import.meta.glob('../../../samples/**/*.{check,out}', { query: '?raw', import: 'default' }) as Record<string, () => Promise<string>>

export interface Example {
  /** `memory/leak` */
  id: string
  group: string
  title: string
  load(): Promise<{ source: string; expectation: string }>
}

const GROUPS: [string, string][] = [
  ['guide', 'Guide'],
  ['tour', 'Language tour'],
  ['memory', 'Memory model'],
  ['internals', 'How it works'],
]

function humanize(name: string): string {
  const s = name.replace(/_/g, ' ')
  return s.charAt(0).toUpperCase() + s.slice(1)
}

/** A sentence about what the sample demonstrates, from its `.check`/`.out` expectation file. */
function describeExpectation(kind: 'check' | 'out', text: string): string {
  if (kind === 'out') return 'This sample runs and prints the output shown on its documentation page.'
  const [verdict, code] = text.trim().split(/\s+/)
  if (verdict === 'error') return `Expected: the checker rejects this program with ${code}.`
  if (verdict === 'warning') return `Expected: the program is accepted, with warning ${code}.`
  return 'Expected: the checker accepts this program without diagnostics.'
}

export function listExamples(): Example[] {
  const out: Example[] = []
  for (const [path, loader] of Object.entries(sources)) {
    const rel = path.replace(/^.*\/samples\//, '')
    const [dir, ...rest] = rel.replace(/\.kt$/, '').split('/')
    const group = GROUPS.find(([d]) => d === dir)?.[1] ?? humanize(dir)
    const stem = path.replace(/\.kt$/, '')
    const exp = Object.keys(expectations).find((k) => k === `${stem}.check` || k === `${stem}.out`)
    out.push({
      id: [dir, ...rest].join('/'),
      group,
      title: humanize(rest.join('/')),
      async load() {
        const source = await loader()
        let expectation = ''
        if (exp) expectation = describeExpectation(exp.endsWith('.check') ? 'check' : 'out', await expectations[exp]())
        return { source, expectation }
      },
    })
  }
  const rank = (g: string) => {
    const i = GROUPS.findIndex(([, label]) => label === g)
    return i < 0 ? GROUPS.length : i
  }
  return out.sort((a, b) => rank(a.group) - rank(b.group) || a.title.localeCompare(b.title))
}
