<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useData, withBase } from 'vitepress'
import './playground.css'
import CodeCell from './CodeCell.vue'
import RunOutput from './RunOutput.vue'
import { OutputSink, type Segment } from './display.ts'
import ToolchainStatus from './ToolchainStatus.vue'
import { createMarkdownRenderer } from './markdown.ts'
import {
  assembleProgram, deleteCell, insertCell, mapDiagnostics, moveCell, newCell, parseNotebook, serializeNotebook,
  type Notebook, type MappedDiagnostic,
} from './notebook.ts'
import { hashFor, readHash } from './share.ts'
import { starterNotebook } from './starter.ts'
import { availability, buildAndRun, cancelToolchain, probe, setSiteBase } from './session.ts'
import type { Diagnostic, RunResult } from './types.ts'

const STORAGE_KEY = 'mini-notebook'
const { site } = useData()

interface CellOutput {
  status: 'queued' | 'compiling' | 'running' | 'ok' | 'error'
  segments: Segment[]
  result: RunResult | null
  problems: MappedDiagnostic[]
  note: string
}

const nb = ref<Notebook>({ title: '', cells: [] })
const outputs = reactive<Record<string, CellOutput>>({})
const cellDiags = reactive<Record<string, Diagnostic[]>>({})
const editingMd = reactive(new Set<string>())
const message = ref('')
const messageKind = ref<'info' | 'bad'>('info')
const running = ref(false)
const fileInput = ref<HTMLInputElement>()
const cellRefs = new Map<string, InstanceType<typeof CodeCell>>()
const isMac = ref(false)
const dragging = ref(false)

let abort: AbortController | undefined
let saveTimer: ReturnType<typeof setTimeout> | undefined
let renderMd: (s: string) => string = (s) => s
const unavailable = computed(() => availability.value === 'unavailable')
const docsUrl = (code: string) => withBase(`/reference/diagnostics/${code}`)
const mdHtml = (src: string) => renderMd(src) || '<p class="mp-empty">Empty text cell. Double-click to edit.</p>'
const vFocus = { mounted: (el: HTMLElement) => el.focus() }

function say(text: string, kind: 'info' | 'bad' = 'info', ms = 5000) {
  message.value = text
  messageKind.value = kind
  if (ms) setTimeout(() => message.value === text && (message.value = ''), ms)
}

// ---- persistence ----

function persist() {
  clearTimeout(saveTimer)
  saveTimer = setTimeout(() => {
    try {
      localStorage.setItem(STORAGE_KEY, serializeNotebook(nb.value))
    } catch {
      /* storage may be unavailable */
    }
  }, 400)
}

function setNotebook(n: Notebook) {
  for (const k of Object.keys(outputs)) delete outputs[k]
  for (const k of Object.keys(cellDiags)) delete cellDiags[k]
  editingMd.clear()
  nb.value = n
  persist()
}

function loadStored(): Notebook | undefined {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw ? parseNotebook(raw) : undefined
  } catch {
    return undefined
  }
}

async function loadFromHash(): Promise<Notebook | undefined> {
  const text = await readHash(location.hash)
  if (text === undefined) return undefined
  try {
    return parseNotebook(text)
  } catch (e) {
    say(`The share link could not be read: ${(e as Error).message}`, 'bad', 0)
    return undefined
  }
}

function slug(s: string) {
  return s.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || 'notebook'
}

function save() {
  const blob = new Blob([serializeNotebook(nb.value)], { type: 'application/json' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `${slug(nb.value.title)}.minb`
  a.click()
  setTimeout(() => URL.revokeObjectURL(a.href), 1000)
}

async function openFile(file: File | undefined) {
  if (!file) return
  try {
    setNotebook(parseNotebook(await file.text()))
    say(`Opened ${file.name}.`)
  } catch (e) {
    say((e as Error).message, 'bad', 0)
  }
}

function onFileChosen(e: Event) {
  const input = e.target as HTMLInputElement
  void openFile(input.files?.[0])
  input.value = ''
}

function onDrop(e: DragEvent) {
  dragging.value = false
  void openFile(e.dataTransfer?.files?.[0])
}

async function share() {
  const hash = await hashFor(serializeNotebook(nb.value))
  const url = `${location.origin}${location.pathname}${hash}`
  try {
    history.replaceState(null, '', hash)
  } catch {
    /* ignore */
  }
  let copied = false
  try {
    await navigator.clipboard.writeText(url)
    copied = true
  } catch {
    /* clipboard blocked */
  }
  const kb = (url.length / 1024).toFixed(1)
  say((copied ? `Link copied (${kb} KB).` : `The address bar now holds the link (${kb} KB).`) + (url.length > 8000 ? ' It is long; use Save for large notebooks.' : ''))
}

function resetToStarter() {
  if (!confirm('Replace this notebook with the starter notebook? Unsaved changes are lost.')) return
  setNotebook(starterNotebook())
  try {
    history.replaceState(null, '', location.pathname)
  } catch {
    /* ignore */
  }
}

// ---- cell editing ----

function setSource(i: number, text: string) {
  nb.value.cells[i].source = text
  persist()
}

function add(index: number, type: 'code' | 'markdown') {
  const c = newCell(type)
  nb.value = insertCell(nb.value, index, c)
  if (type === 'markdown') editingMd.add(c.id)
  persist()
  void nextTick(() => {
    if (type === 'code') cellRefs.get(c.id)?.focus()
  })
}

function remove(i: number) {
  const c = nb.value.cells[i]
  if (c.source.trim() && !confirm('Delete this cell?')) return
  delete outputs[c.id]
  delete cellDiags[c.id]
  nb.value = deleteCell(nb.value, i)
  persist()
}

function move(i: number, d: -1 | 1) {
  nb.value = moveCell(nb.value, i, d)
  persist()
}

function toggleType(i: number) {
  const c = nb.value.cells[i]
  c.type = c.type === 'code' ? 'markdown' : 'code'
  delete outputs[c.id]
  delete cellDiags[c.id]
  editingMd.delete(c.id)
  persist()
}

function clearOutputs() {
  for (const k of Object.keys(outputs)) delete outputs[k]
  for (const k of Object.keys(cellDiags)) delete cellDiags[k]
}

// ---- running ----

function toEditorDiagnostics(ds: MappedDiagnostic[], cell: number): Diagnostic[] {
  return ds
    .filter((d) => d.cell === cell)
    .map((d) => ({ ...d, secondary: (d.secondary ?? []).filter((s) => s.cell === cell) }))
}

const sinks = new WeakMap<CellOutput, OutputSink>()
function pushOut(o: CellOutput, type: 'out' | 'err', text: string) {
  let s = sinks.get(o)
  if (!s) sinks.set(o, (s = new OutputSink(o.segments)))
  s.push(type, text)
}

/** Runs one cell. Returns true when it finished without errors. */
async function runCell(i: number): Promise<boolean> {
  const cell = nb.value.cells[i]
  if (cell.type !== 'code') return true
  const out: CellOutput = reactive({ status: 'compiling', segments: [], result: null, problems: [], note: '' })
  outputs[cell.id] = out
  const asm = assembleProgram(nb.value.cells, i)
  if (asm.empty) {
    out.status = 'ok'
    out.note = 'Empty cell.'
    return true
  }
  abort = new AbortController()
  const outcome = await buildAndRun([asm.file], {
    timeoutMs: 15000,
    signal: abort.signal,
    onStdout: (s) => ((out.status = 'running'), pushOut(out, 'out', s)),
    onStderr: (s) => ((out.status = 'running'), pushOut(out, 'err', s)),
  })
  abort = undefined
  sinks.get(out)?.end()
  const mapped = mapDiagnostics(outcome.diagnostics, asm, i)
  out.problems = mapped
  // Diagnostics of this run replace the markers of all cells up to this one.
  for (let k = 0; k <= i; k++) cellDiags[nb.value.cells[k].id] = toEditorDiagnostics(mapped, k)
  if (outcome.failure) {
    out.status = 'error'
    pushOut(out, 'err', outcome.failure + '\n')
    return false
  }
  const errs = mapped.filter((d) => d.severity === 'error').length
  if (outcome.stage === 'compile') {
    out.status = errs ? 'error' : 'ok'
    out.note = errs ? `Compilation failed with ${errs} error${errs === 1 ? '' : 's'}.\n` : 'Compilation produced no program.\n'
    return !errs
  }
  out.result = outcome.run ?? null
  out.status = outcome.run && outcome.run.exitCode === 0 ? 'ok' : 'error'
  return out.status === 'ok'
}

async function runOne(i: number) {
  if (running.value || unavailable.value) return
  running.value = true
  try {
    await runCell(i)
  } finally {
    running.value = false
  }
}

async function runAll() {
  if (running.value || unavailable.value) return
  running.value = true
  clearOutputs()
  const codeCells = nb.value.cells.map((c, i) => [c, i] as const).filter(([c]) => c.type === 'code')
  for (const [c] of codeCells) outputs[c.id] = reactive({ status: 'queued', segments: [], result: null, problems: [], note: '' })
  try {
    for (const [, i] of codeCells) {
      if (!(await runCell(i))) {
        if (!abort?.signal.aborted) say(`Stopped at cell ${i + 1}: it failed.`, 'bad')
        break
      }
    }
  } finally {
    running.value = false
  }
}

function stop() {
  abort?.abort()
  cancelToolchain()
  running.value = false
  for (const o of Object.values(outputs)) if (o.status === 'queued' || o.status === 'compiling') o.status = 'error'
}

async function gotoProblem(p: MappedDiagnostic) {
  const c = nb.value.cells[p.cell]
  if (!c) return
  const ref = cellRefs.get(c.id)
  ref?.reveal(p.line, p.col)
}

const setRef = (id: string) => (el: unknown) => {
  if (el) cellRefs.set(id, el as InstanceType<typeof CodeCell>)
  else cellRefs.delete(id)
}

function statusLabel(s: CellOutput['status']) {
  return { queued: 'Queued', compiling: 'Compiling…', running: 'Running…', ok: '', error: '' }[s]
}

function onHashChange() {
  void loadFromHash().then((n) => n && setNotebook(n))
}

onMounted(async () => {
  isMac.value = /Mac|iPhone|iPad/.test(navigator.platform)
  setSiteBase(site.value.base)
  renderMd = createMarkdownRenderer(withBase)
  const fromHash = await loadFromHash()
  setNotebook(fromHash ?? loadStored() ?? starterNotebook())
  window.addEventListener('hashchange', onHashChange)
  void probe()
})

onBeforeUnmount(() => {
  window.removeEventListener('hashchange', onHashChange)
  clearTimeout(saveTimer)
  abort?.abort()
})
</script>

<template>
  <div
    class="mp-root nb"
    @dragover.prevent="dragging = true"
    @dragleave="dragging = false"
    @drop.prevent="onDrop"
  >
    <ToolchainStatus />

    <div class="mp-toolbar" role="toolbar" aria-label="Notebook actions">
      <input v-model="nb.title" class="nb-title" aria-label="Notebook title" placeholder="Untitled notebook" @input="persist" />
      <button
        class="mp-btn primary"
        :disabled="unavailable || running"
        :title="unavailable ? 'Run is not available yet: the in-browser compiler is not part of this site build.' : 'Run all code cells from top to bottom'"
        @click="runAll"
      >
        Run all
      </button>
      <button v-if="running" class="mp-btn" @click="stop">Stop</button>
      <button class="mp-btn" @click="add(nb.cells.length, 'code')">+ Code</button>
      <button class="mp-btn" @click="add(nb.cells.length, 'markdown')">+ Text</button>
      <button class="mp-btn" @click="save">Save .minb</button>
      <button class="mp-btn" @click="fileInput?.click()">Open…</button>
      <button class="mp-btn" @click="share">Share</button>
      <button class="mp-btn" @click="clearOutputs">Clear outputs</button>
      <button class="mp-btn" @click="resetToStarter">Starter notebook</button>
      <input ref="fileInput" type="file" accept=".minb,application/json" hidden @change="onFileChosen" />
    </div>
    <p v-if="message" class="mp-msg" :class="messageKind" role="status">{{ message }}</p>
    <p v-if="dragging" class="mp-drop">Drop a .minb file to open it</p>

    <div class="nb-cells">
      <div v-for="(cell, i) in nb.cells" :key="cell.id" class="nb-cell" :class="[cell.type, outputs[cell.id]?.status]">
        <div class="nb-gutter">
          <button
            v-if="cell.type === 'code'"
            class="nb-run"
            :disabled="unavailable || running"
            :title="unavailable ? 'Run is not available yet' : `Run cell (${isMac ? '⌘' : 'Ctrl'}+Enter)`"
            :aria-label="`Run cell ${i + 1}`"
            @click="runOne(i)"
          >
            ▶
          </button>
          <span class="nb-index">[{{ i + 1 }}]</span>
        </div>
        <div class="nb-body">
          <div class="nb-actions" role="group" :aria-label="`Cell ${i + 1} actions`">
            <button class="mp-btn small" :disabled="i === 0" title="Move up" @click="move(i, -1)">↑</button>
            <button class="mp-btn small" :disabled="i === nb.cells.length - 1" title="Move down" @click="move(i, 1)">↓</button>
            <button class="mp-btn small" title="Insert a code cell below" @click="add(i + 1, 'code')">+ Code</button>
            <button class="mp-btn small" title="Insert a text cell below" @click="add(i + 1, 'markdown')">+ Text</button>
            <button class="mp-btn small" @click="toggleType(i)">{{ cell.type === 'code' ? 'To text' : 'To code' }}</button>
            <button class="mp-btn small" @click="remove(i)">Delete</button>
          </div>

          <template v-if="cell.type === 'code'">
            <div class="nb-editor">
              <CodeCell
                :ref="setRef(cell.id)"
                :source="cell.source"
                :diagnostics="cellDiags[cell.id] ?? []"
                :docs-url="docsUrl"
                @change="setSource(i, $event)"
                @run="runOne(i)"
              />
            </div>
            <div v-if="outputs[cell.id]" class="nb-out">
              <p v-if="statusLabel(outputs[cell.id].status)" class="mp-empty">{{ statusLabel(outputs[cell.id].status) }}</p>
              <RunOutput
                v-if="outputs[cell.id].segments.length || outputs[cell.id].result || outputs[cell.id].note"
                :segments="outputs[cell.id].segments"
                :result="outputs[cell.id].result"
                :note="outputs[cell.id].note"
              />
              <ul v-if="outputs[cell.id].problems.length" class="mp-problems">
                <li v-for="(p, k) in outputs[cell.id].problems" :key="k" @click="gotoProblem(p)">
                  <a v-if="p.code" :href="docsUrl(p.code)" class="mp-diag-code" target="_blank" rel="noopener" @click.stop>{{ p.code }}</a>
                  <span :class="'mp-sev-' + p.severity">{{ p.severity }}</span>
                  <span>{{ p.message }}</span>
                  <span class="loc">cell {{ p.cell + 1 }}, {{ p.line }}:{{ p.col }}</span>
                  <span v-if="p.help" class="help"><strong>help:</strong> {{ p.help }}</span>
                </li>
              </ul>
            </div>
          </template>

          <template v-else>
            <textarea
              v-if="editingMd.has(cell.id)"
              v-focus
              class="nb-md-edit"
              :value="cell.source"
              rows="6"
              aria-label="Markdown source"
              @input="setSource(i, ($event.target as HTMLTextAreaElement).value)"
              @keydown.ctrl.enter.prevent="editingMd.delete(cell.id)"
              @keydown.meta.enter.prevent="editingMd.delete(cell.id)"
              @blur="editingMd.delete(cell.id)"
            ></textarea>
            <div
              v-else
              class="nb-md vp-doc"
              tabindex="0"
              title="Double-click to edit"
              @dblclick="editingMd.add(cell.id)"
              @keydown.enter="editingMd.add(cell.id)"
              v-html="mdHtml(cell.source)"
            ></div>
          </template>
        </div>
      </div>
      <p v-if="!nb.cells.length" class="mp-empty">This notebook has no cells.</p>
    </div>

    <details class="nb-help">
      <summary>How cells run</summary>
      <ul>
        <li>A code cell is compiled together with the <strong>declarations</strong> of the cells above it: imports, <code>@file:</code> annotations, <code>fun</code>, <code>class</code>, <code>interface</code>, <code>object</code>, <code>enum</code>, <code>typealias</code> and top-level <code>val</code>/<code>var</code>.</li>
        <li>The <strong>statements</strong> of the cell you run become the body of its <code>main</code>. Statements of other cells are not run.</li>
        <li>Every run is a separate program: values do not survive between runs, declarations do (top-level properties are initialized again each time).</li>
        <li>A <code>fun main</code> in an earlier cell is ignored in later cells. Declarations cannot be redefined, and an error in an earlier declaration appears in every later run.</li>
        <li>An <code>@file:MemoryMode(Mode.Strict)</code> annotation applies to every cell from that one on.</li>
        <li><strong>Run all</strong> runs the code cells in order and stops at the first one that fails.</li>
        <li>Notebooks are saved as <code>.minb</code> files (JSON: title and cells, no outputs). They also live in your browser's local storage and in share links.</li>
      </ul>
    </details>
  </div>
</template>

<style scoped>
.mp-root.nb { max-width: 1000px; }
.nb-title { flex: 1 1 180px; min-width: 140px; padding: 5px 8px; border: 1px solid transparent; border-radius: 6px; background: transparent; color: var(--vp-c-text-1); font: inherit; font-weight: 600; font-size: 16px; }
.nb-title:hover, .nb-title:focus { border-color: var(--vp-c-divider); background: var(--vp-c-bg-soft); }
.mp-msg { margin: 0; font-size: 13px; color: var(--vp-c-text-2); }
.mp-msg.bad { color: var(--vp-c-danger-1); }
.mp-drop { margin: 0; padding: 14px; text-align: center; border: 2px dashed var(--vp-c-brand-1); border-radius: 8px; color: var(--vp-c-brand-1); }
.nb-cells { display: flex; flex-direction: column; gap: 10px; }
.nb-cell { display: flex; gap: 8px; }
.nb-gutter { width: 44px; flex: none; display: flex; flex-direction: column; align-items: center; gap: 4px; padding-top: 6px; }
.nb-index { font-size: 11px; color: var(--vp-c-text-3); font-family: var(--vp-font-family-mono); }
.nb-run { width: 28px; height: 28px; border-radius: 50%; border: 1px solid var(--vp-c-divider); background: var(--vp-c-bg-soft); color: var(--vp-c-brand-1); cursor: pointer; font-size: 11px; }
.nb-run:hover:not(:disabled) { background: var(--vp-c-brand-1); color: #fff; }
.nb-run:disabled { opacity: 0.5; cursor: not-allowed; }
.nb-body { flex: 1; min-width: 0; position: relative; }
.nb-actions { display: flex; flex-wrap: wrap; gap: 4px; justify-content: flex-end; margin-bottom: 4px; opacity: 0; transition: opacity 0.12s; }
.nb-cell:hover .nb-actions, .nb-cell:focus-within .nb-actions { opacity: 1; }
@media (hover: none) { .nb-actions { opacity: 1; } }
.nb-editor { border: 1px solid var(--vp-c-divider); border-radius: 6px; overflow: hidden; }
.nb-cell:focus-within .nb-editor { border-color: var(--vp-c-brand-1); }
.nb-cell.error .nb-editor { border-color: var(--vp-c-danger-2); }
.nb-out { margin-top: 4px; border-left: 3px solid var(--vp-c-divider); background: var(--vp-c-bg-soft); border-radius: 0 6px 6px 0; }
.nb-cell.ok .nb-out { border-left-color: var(--mp-ok); }
.nb-cell.error .nb-out { border-left-color: var(--vp-c-danger-1); }
.nb-md { padding: 4px 8px; border-radius: 6px; outline-offset: 2px; }
.nb-md:hover { background: var(--vp-c-bg-soft); }
.nb-md :deep(pre) { padding: 10px 14px; background: var(--vp-c-bg-soft); border-radius: 6px; overflow: auto; font-size: 13px; }
.nb-md :deep(p), .nb-md :deep(ul), .nb-md :deep(ol) { margin: 8px 0; }
.nb-md-edit { width: 100%; box-sizing: border-box; font-family: var(--vp-font-family-mono); font-size: 13px; padding: 8px; border: 1px solid var(--vp-c-brand-1); border-radius: 6px; background: var(--vp-c-bg); color: var(--vp-c-text-1); resize: vertical; }
.nb-help { font-size: 13px; color: var(--vp-c-text-2); margin-top: 12px; }
.nb-help summary { cursor: pointer; }
</style>
