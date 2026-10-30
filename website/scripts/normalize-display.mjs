#!/usr/bin/env node
// Reads the stdout of a program from stdin and writes it with every display bundle (docs/display.md) replaced by a line
// `[display: <mime>]`, the way the docs show them (display.ts, OutputSink). Each bundle is validated on the way:
// the data must decode, an SVG must be an <svg> document, a table must parse. Exit status 1 when one does not.
//
//   ./program | node website/scripts/normalize-display.mjs > normalized.txt
import { readFileSync } from 'node:fs'
import { splitOutput, parseTable } from '../.vitepress/theme/playground/display.ts'

const raw = readFileSync(0, 'utf8')
const { text, bundles } = splitOutput(raw)
let bad = 0
for (const b of bundles) {
  let problem = ''
  if (b.mime === 'image/svg+xml') {
    if (!/^\s*(<\?xml[^>]*>\s*)?<svg[\s>]/.test(b.data) || !/<\/svg>\s*$/.test(b.data)) problem = 'not an <svg> document'
    else if ((b.data.match(/<(\w+)[\s>]/g) ?? []).length < 2) problem = 'empty drawing'
  } else if (b.mime === 'application/vnd.mini.table+json' && !parseTable(b.data)) problem = 'invalid table'
  if (problem) {
    console.error(`display bundle ${b.mime}: ${problem}`)
    bad++
  }
}
if (text.includes('[display: invalid]')) {
  console.error('display bundle with undecodable data')
  bad++
}
process.stdout.write(text)
process.exit(bad ? 1 : 0)
