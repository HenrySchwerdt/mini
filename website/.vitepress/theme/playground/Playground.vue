<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { useData, withBase } from 'vitepress'
import './playground.css'
import { createEditor, type MiniEditor } from './editor.ts'
import { listExamples, type Example } from './examples.ts'
import { hashFor, readHash } from './share.ts'
import {
  availability, buildAndRun, cancelToolchain, checkOnly, ensureToolchain, loadedLevel, probe, setSiteBase,
} from './session.ts'
import type { Diagnostic, RunResult } from './types.ts'
import RunOutput from './RunOutput.vue'
import { OutputSink, type Segment } from './display.ts'
import ToolchainStatus from './ToolchainStatus.vue'

const FILE = 'main.kt'
const STORAGE_KEY = 'mini-playground-source'
const DEFAULT_SOURCE = `fun main() {
    val names = listOf("Mini", "Kotlin", "native code")
    for (n in names) {
        println("Hello, $n!")
    }
}
`

const { site } = useData()
const host = ref<HTMLElement>()
const editor = shallowRef<MiniEditor>()

const examples = ref<Example[]>([])
const exampleId = ref('')
const exampleNote = ref('')

const diagnostics = ref<Diagnostic[]>([])
const tab = ref<'output' | 'problems' | 'mir' | 'llvm'>('output')
const segments = ref<Segment[]>([])
const runResult = ref<RunResult | null>(null)
const note = ref('')
const mir = ref('')
const llvmIr = ref('')
const timings = ref<Record<string, number>>({})
const stdin = ref('')
const showStdin = ref(false)
const timeoutSec = ref(10)
const busy = ref<'' | 'check' | 'compile' | 'run'>('')
const shareMsg = ref('')
const isMac = ref(false)

let abort: AbortController | undefined
let checkTimer: ReturnType<typeof setTimeout> | undefined
let runSeq = 0
let applyingHash = false

const unavailable = computed(() => availability.value === 'unavailable')
const errors = computed(() => diagnostics.value.filter((d) => d.severity === 'error').length)
const warnings = computed(() => diagnostics.value.filter((d) => d.severity === 'warning').length)
const timingText = computed(() =>
  Object.entries(timings.value)
    .map(([k, v]) => `${k} ${v < 10 ? v.toFixed(1) : Math.round(v)} ms`)
    .join(' · '),
)
const docsUrl = (code: string) => withBase(`/reference/diagnostics/${code}`)

let sink = new OutputSink(segments.value)
function pushOut(type: 'out' | 'err', text: string) {
  sink.push(type, text)
}

function applyDiagnostics(d: Diagnostic[]) {
  diagnostics.value = d
  editor.value?.setDiagnostics(d)
}

function files() {
  return [{ name: FILE, text: editor.value!.getDoc() }]
}

async function check(auto = false) {
  if (unavailable.value || busy.value) return
  const seq = ++runSeq
  if (!auto) busy.value = 'check'
  const r = await checkOnly(files())
  if (seq !== runSeq && auto) return
  if (!auto) busy.value = ''
  if (r.failure) {
    if (!auto) {
      segments.value = [{ type: 'err', text: r.failure + '\n' }]
      tab.value = 'output'
    }
    return
  }
  applyDiagnostics(r.diagnostics)
  if (!auto) {
    note.value = ''
    runResult.value = null
    segments.value = []
    tab.value = r.diagnostics.length ? 'problems' : 'output'
    if (!r.diagnostics.length) note.value = 'Check passed: no diagnostics.\n'
  }
}

function scheduleCheck() {
  clearTimeout(checkTimer)
  if (!loadedLevel.value) return
  checkTimer = setTimeout(() => void check(true), 700)
}

async function run() {
  if (unavailable.value || busy.value === 'run' || busy.value === 'compile') return
  clearTimeout(checkTimer)
  runSeq++
  segments.value = []
  sink = new OutputSink(segments.value)
  runResult.value = null
  note.value = ''
  mir.value = ''
  llvmIr.value = ''
  tab.value = 'output'
  busy.value = 'compile'
  abort = new AbortController()
  const outcome = await buildAndRun(files(), {
    stdin: stdin.value,
    timeoutMs: timeoutSec.value * 1000,
    signal: abort.signal,
    compile: { emitMir: true, emitLlvmIr: true },
    onStdout: (s) => {
      busy.value = 'run'
      pushOut('out', s)
    },
    onStderr: (s) => {
      busy.value = 'run'
      pushOut('err', s)
    },
  })
  busy.value = ''
  abort = undefined
  sink.end()
  mir.value = outcome.mir ?? ''
  llvmIr.value = outcome.llvmIr ?? ''
  timings.value = outcome.timings
  if (outcome.stage !== 'load') applyDiagnostics(outcome.diagnostics)
  if (outcome.failure) {
    pushOut('err', outcome.failure + '\n')
  } else if (outcome.stage === 'compile') {
    const e = outcome.diagnostics.filter((d) => d.severity === 'error').length
    note.value = e > 0 ? `Compilation failed with ${e} error${e === 1 ? '' : 's'}. See Problems.\n` : 'Compilation produced no program.\n'
    if (e > 0) tab.value = 'problems'
  } else if (outcome.run) {
    runResult.value = outcome.run
  }
}

function stop() {
  abort?.abort()
  if (busy.value === 'compile') {
    cancelToolchain()
    busy.value = ''
    note.value = 'Cancelled. The compiler reloads on the next run.\n'
  }
}

function goto(d: Diagnostic) {
  editor.value?.reveal(d.line, d.col)
}

// ---- examples, share, persistence ----

async function chooseExample() {
  const ex = examples.value.find((e) => e.id === exampleId.value)
  if (!ex) return
  const { source, expectation } = await ex.load()
  setSource(source)
  exampleNote.value = expectation
  segments.value = []
  runResult.value = null
  note.value = ''
}

function setSource(text: string) {
  editor.value?.setDoc(text)
  applyDiagnostics([])
}

async function share() {
  const hash = await hashFor(editor.value!.getDoc())
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
    /* clipboard may be blocked; the address bar has the link now */
  }
  const kb = (url.length / 1024).toFixed(1)
  shareMsg.value = copied ? `Link copied (${kb} KB).` : `The address bar now holds the link (${kb} KB).`
  if (url.length > 8000) shareMsg.value += ' It is long; some chat apps may cut it.'
  setTimeout(() => (shareMsg.value = ''), 4000)
}

async function loadFromHash(): Promise<boolean> {
  const text = await readHash(location.hash)
  if (text === undefined) return false
  applyingHash = true
  setSource(text)
  applyingHash = false
  exampleId.value = ''
  exampleNote.value = ''
  return true
}

function onHashChange() {
  void loadFromHash()
}

function saveDraft(text: string) {
  try {
    localStorage.setItem(STORAGE_KEY, text)
  } catch {
    /* storage may be unavailable */
  }
}

function loadDraft(): string | null {
  try {
    return localStorage.getItem(STORAGE_KEY)
  } catch {
    return null
  }
}

function resetSource() {
  setSource(DEFAULT_SOURCE)
  exampleId.value = ''
  exampleNote.value = ''
  try {
    history.replaceState(null, '', location.pathname)
  } catch {
    /* ignore */
  }
}

onMounted(async () => {
  isMac.value = /Mac|iPhone|iPad/.test(navigator.platform)
  setSiteBase(site.value.base)
  examples.value = listExamples()
  const fromHash = await readHash(location.hash)
  const initial = fromHash ?? loadDraft() ?? DEFAULT_SOURCE
  editor.value = createEditor({
    parent: host.value!,
    doc: initial,
    fileName: FILE,
    docsUrl,
    onRun: () => void run(),
    onChange: (doc) => {
      if (!applyingHash) saveDraft(doc)
      scheduleCheck()
    },
  })
  window.addEventListener('hashchange', onHashChange)
  await probe()
  if (availability.value === 'available') {
    // Only the small first stage (parser and checker) is loaded here; the compiler proper waits for the first Run.
    try {
      await ensureToolchain('check')
      void check(true)
    } catch {
      /* ToolchainStatus shows the error */
    }
  }
  await nextTick()
})

onBeforeUnmount(() => {
  window.removeEventListener('hashchange', onHashChange)
  clearTimeout(checkTimer)
  abort?.abort()
  editor.value?.destroy()
})

</script>

<template>
  <div class="mp-root">
    <ToolchainStatus />

    <div class="mp-toolbar" role="toolbar" aria-label="Playground actions">
      <button
        class="mp-btn primary"
        :disabled="unavailable || busy === 'compile' || busy === 'run'"
        :title="unavailable ? 'Run is not available yet: the in-browser compiler is not part of this site build.' : 'Compile and run'"
        @click="run"
      >
        Run <kbd>{{ isMac ? '⌘' : 'Ctrl' }}+Enter</kbd>
      </button>
      <button v-if="busy === 'compile' || busy === 'run'" class="mp-btn" @click="stop">Stop</button>
      <button
        class="mp-btn"
        :disabled="unavailable || !!busy"
        :title="unavailable ? 'Check is not available yet: it needs the in-browser compiler.' : 'Parse and run the memory checker'"
        @click="check(false)"
      >
        Check
      </button>
      <select v-model="exampleId" class="mp-select" aria-label="Examples" @change="chooseExample">
        <option value="" disabled>Examples</option>
        <optgroup v-for="g in [...new Set(examples.map((e) => e.group))]" :key="g" :label="g">
          <option v-for="e in examples.filter((x) => x.group === g)" :key="e.id" :value="e.id">{{ e.title }}</option>
        </optgroup>
      </select>
      <button class="mp-btn" @click="share">Share</button>
      <button class="mp-btn" @click="resetSource">Reset</button>
      <label class="mp-inline">
        Time limit
        <select v-model.number="timeoutSec" class="mp-select">
          <option :value="5">5 s</option>
          <option :value="10">10 s</option>
          <option :value="30">30 s</option>
          <option :value="60">60 s</option>
        </select>
      </label>
      <button class="mp-btn" :aria-pressed="showStdin" @click="showStdin = !showStdin">Input (stdin)</button>
      <span v-if="shareMsg" class="mp-msg" role="status">{{ shareMsg }}</span>
    </div>

    <p v-if="exampleNote" class="mp-example-note">{{ exampleNote }}</p>
    <textarea v-if="showStdin" v-model="stdin" class="mp-stdin" rows="3" placeholder="Text the program reads from standard input" aria-label="Standard input"></textarea>

    <div class="mp-panes">
      <div class="mp-editor-wrap"><div ref="host" class="mp-editor"></div></div>
      <div class="mp-side">
        <div class="mp-tabs" role="tablist">
          <button role="tab" :aria-selected="tab === 'output'" :class="{ on: tab === 'output' }" @click="tab = 'output'">Output</button>
          <button role="tab" :aria-selected="tab === 'problems'" :class="{ on: tab === 'problems' }" @click="tab = 'problems'">
            Problems<span v-if="errors || warnings" class="mp-count" :class="{ bad: errors }">{{ errors + warnings }}</span>
          </button>
          <button v-if="mir" role="tab" :aria-selected="tab === 'mir'" :class="{ on: tab === 'mir' }" @click="tab = 'mir'">MIR</button>
          <button v-if="llvmIr" role="tab" :aria-selected="tab === 'llvm'" :class="{ on: tab === 'llvm' }" @click="tab = 'llvm'">LLVM IR</button>
          <span v-if="busy" class="mp-busy">{{ busy === 'check' ? 'Checking…' : busy === 'compile' ? 'Compiling…' : 'Running…' }}</span>
        </div>
        <div class="mp-panel" role="tabpanel">
          <template v-if="tab === 'output'">
            <RunOutput v-if="segments.length || runResult || note" :segments="segments" :result="runResult" :note="note" />
            <p v-else class="mp-empty">
              {{ unavailable ? 'Run is not available yet, so there is no output.' : 'Output of the program appears here.' }}
            </p>
            <p v-if="timingText" class="mp-timings">Compile: {{ timingText }}</p>
          </template>
          <template v-else-if="tab === 'problems'">
            <ul v-if="diagnostics.length" class="mp-problems">
              <li v-for="(d, i) in diagnostics" :key="i" @click="goto(d)">
                <a v-if="d.code" :href="docsUrl(d.code)" class="mp-diag-code" target="_blank" rel="noopener" @click.stop>{{ d.code }}</a>
                <span :class="'mp-sev-' + d.severity">{{ d.severity }}</span>
                <span>{{ d.message }}</span>
                <span class="loc">{{ d.line }}:{{ d.col }}</span>
                <span v-if="d.help" class="help"><strong>help:</strong> {{ d.help }}</span>
              </li>
            </ul>
            <p v-else class="mp-empty">{{ unavailable ? 'Check is not available yet.' : 'No problems reported.' }}</p>
          </template>
          <pre v-else-if="tab === 'mir'" class="mp-output">{{ mir }}</pre>
          <pre v-else class="mp-output">{{ llvmIr }}</pre>
        </div>
      </div>
    </div>
    <p class="mp-hint">
      The source stays in your browser. Share links keep it in the URL after the <code>#</code>, which is never sent to a server.
      <a :href="withBase('/notebook')">Open the notebook</a> for several cells.
    </p>
  </div>
</template>

<style scoped>
.mp-msg { font-size: 13px; color: var(--vp-c-text-2); }
.mp-example-note { margin: 0; font-size: 13px; color: var(--vp-c-text-2); }
.mp-stdin { width: 100%; box-sizing: border-box; font-family: var(--vp-font-family-mono); font-size: 13px; padding: 8px; border: 1px solid var(--vp-c-divider); border-radius: 6px; background: var(--vp-c-bg); color: var(--vp-c-text-1); }
.mp-panes { display: grid; grid-template-columns: minmax(0, 3fr) minmax(0, 2fr); gap: 12px; height: calc(100vh - 230px); min-height: 420px; }
.mp-editor-wrap, .mp-side { border: 1px solid var(--vp-c-divider); border-radius: 8px; overflow: hidden; min-height: 0; display: flex; flex-direction: column; background: var(--vp-c-bg); }
.mp-editor { flex: 1; min-height: 0; }
.mp-editor :deep(.cm-editor) { height: 100%; }
.mp-tabs { display: flex; align-items: center; border-bottom: 1px solid var(--vp-c-divider); background: var(--vp-c-bg-soft); overflow-x: auto; }
.mp-tabs button { border: none; background: none; padding: 8px 14px; font: inherit; font-size: 13px; color: var(--vp-c-text-2); cursor: pointer; border-bottom: 2px solid transparent; white-space: nowrap; }
.mp-tabs button.on { color: var(--vp-c-text-1); border-bottom-color: var(--vp-c-brand-1); }
.mp-count { margin-left: 6px; padding: 0 6px; border-radius: 8px; font-size: 11px; background: var(--vp-c-warning-soft); color: var(--vp-c-warning-1); }
.mp-count.bad { background: var(--vp-c-danger-soft); color: var(--vp-c-danger-1); }
.mp-busy { margin-left: auto; padding: 0 12px; font-size: 12px; color: var(--vp-c-text-3); white-space: nowrap; }
.mp-panel { flex: 1; min-height: 0; overflow: auto; }
.mp-timings { margin: 0; padding: 6px 14px 10px; font-size: 12px; color: var(--vp-c-text-3); }
.mp-hint { margin: 0; font-size: 12px; color: var(--vp-c-text-3); }
@media (max-width: 860px) {
  .mp-panes { grid-template-columns: 1fr; height: auto; }
  .mp-editor-wrap { height: 55vh; }
  .mp-side { height: 40vh; }
}
</style>
