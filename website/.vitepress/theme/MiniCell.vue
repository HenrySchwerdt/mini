<script setup lang="ts">
/**
 * A runnable code block. The markdown plugin (`../mini-cells.ts`) wraps the highlighted block of every runnable Kotlin fence
 * in this component, so the page works without JavaScript (just the code). In the browser it adds a bar below the code
 * (Run, Check, Edit, Reset, Open in playground) and an output area. Compiling and running use the shared toolchain worker
 * (`./playground/session.ts`), which the preload starts in the background.
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { useData, useRoute, withBase } from 'vitepress'
import './playground/playground.css'
import RunOutput, { type Segment } from './playground/RunOutput.vue'
import { availability, buildAndRun, checkOnly, loadError, loadedLevel, probe, progress, setSiteBase } from './playground/session.ts'
import { hashFor } from './playground/share.ts'
import type { MiniEditor } from './playground/editor.ts'
import type { MappedDiagnostic } from './playground/notebook.ts'
import type { RunResult } from './playground/types.ts'
import { cellsOf, registerCell, unregisterCell, type CellHandle } from './cell-registry.ts'
import { describeExpectation, meetsExpectation, programFor, sameOutput, type CellSpec } from './snippets.ts'

const props = defineProps<{ spec: CellSpec }>()
const spec = props.spec
const route = useRoute()
const { site } = useData()

const mounted = ref(false)
const source = ref(spec.source)
const editing = ref(false)
const host = ref<HTMLElement>()
const editor = shallowRef<MiniEditor>()

type Phase = '' | 'compiling' | 'running' | 'checking'
const phase = ref<Phase>('')
const segments = ref<Segment[]>([])
const result = ref<RunResult | null>(null)
const diags = ref<MappedDiagnostic[]>([])
const target = ref(0)
const ran = ref<'' | 'run' | 'check'>('')
const failure = ref('')
const verdict = ref<'' | 'match' | 'differs'>('')
let stdout = ''
let abort: AbortController | undefined

const modified = computed(() => source.value !== spec.source)
const isCheck = spec.kind === 'check'
const unavailable = computed(() => availability.value === 'unavailable')
const busy = computed(() => phase.value !== '')
const docsUrl = (code: string) => withBase(`/reference/diagnostics/${code}`)
const pct = computed(() => {
  const p = progress.value
  if (!p) return null
  if (p.fraction !== undefined) return Math.round(p.fraction * 100)
  return p.totalBytes > 0 ? Math.round((p.loadedBytes / p.totalBytes) * 100) : null
})
const status = computed(() => {
  if (phase.value === '') return ''
  if (loadError.value && !loadedLevel.value) return ''
  const need = phase.value === 'checking' ? loadedLevel.value : loadedLevel.value === 'full' ? 'full' : null
  if (!need) return pct.value === null ? 'Waiting for the compiler' : `Loading the compiler ${pct.value}%`
  return phase.value === 'compiling' ? 'Compiling' : phase.value === 'running' ? 'Running' : 'Checking'
})
const showExpected = computed(() => spec.expected !== undefined && (spec.exercise || unavailable.value))
const sourceLines = computed(() => source.value.split('\n'))

onMounted(() => {
  mounted.value = true
  setSiteBase(site.value.base)
  void probe()
  registerCell(route.path, handle)
})
onBeforeUnmount(() => {
  abort?.abort()
  editor.value?.destroy()
  unregisterCell(route.path, handle)
})

function context() {
  const handles = cellsOf(route.path)
  const k = handles.findIndex((h) => h.spec.index === spec.index)
  if (k < 0) return { specs: [spec], sources: [source.value], k: 0 }
  return { specs: handles.map((h) => h.spec), sources: handles.map((h) => (h.spec.index === spec.index ? source.value : h.getSource())), k }
}

function clearOutput() {
  segments.value = []
  result.value = null
  diags.value = []
  failure.value = ''
  verdict.value = ''
  ran.value = ''
  stdout = ''
}

function push(type: 'out' | 'err', text: string) {
  if (type === 'out') stdout += text
  const last = segments.value[segments.value.length - 1]
  if (last && last.type === type) last.text += text
  else segments.value = [...segments.value, { type, text }]
}

/** Resolves to whether the cell ended as documented (no error for a run cell, the expected diagnostics for a check cell). */
async function go(kind: 'run' | 'check'): Promise<boolean> {
  if (busy.value) return false
  clearOutput()
  ran.value = kind
  if (unavailable.value) return false
  const { specs, sources, k } = context()
  const prog = programFor(specs, sources, k)
  target.value = prog.target
  try {
    if (kind === 'check') {
      phase.value = 'checking'
      const r = await checkOnly([prog.file])
      if (r.failure) {
        failure.value = r.failure
        return false
      }
      diags.value = prog.map(r.diagnostics)
      if (spec.exercise) {
        // the goal of a check exercise is a clean program, whatever the broken starter reports
        const clean = meetsExpectation(r.diagnostics, { kind: 'ok' })
        if (clean) verdict.value = 'match'
        return clean
      }
      const met = spec.expect ? meetsExpectation(r.diagnostics, spec.expect) : !r.diagnostics.some((d) => d.severity === 'error')
      if (spec.expect && met) verdict.value = 'match'
      else if (spec.expect && !modified.value) verdict.value = 'differs'
      return met
    }
    phase.value = 'compiling'
    abort = new AbortController()
    const out = await buildAndRun([prog.file], {
      onStdout: (s) => {
        phase.value = 'running'
        push('out', s)
      },
      onStderr: (s) => push('err', s),
      timeoutMs: 10000,
      signal: abort.signal,
    })
    diags.value = prog.map(out.diagnostics)
    if (out.failure) {
      failure.value = out.failure
      return false
    }
    result.value = out.run ?? null
    if (!out.run) return false
    if (spec.expected !== undefined && out.run.exitCode === (spec.exit ?? 0)) {
      if (sameOutput(stdout, spec.expected)) verdict.value = 'match'
      else if (!modified.value) verdict.value = 'differs'
    }
    return out.run.exitCode === (spec.exit ?? 0)
  } finally {
    phase.value = ''
    abort = undefined
  }
}

const run = () => go('run')
const check = () => go('check')
const primary = () => (isCheck ? check() : run())
const stop = () => abort?.abort()

async function startEdit() {
  if (editing.value) return
  editing.value = true
  await nextTick()
  const { createEditor } = await import('./playground/editor.ts')
  if (!host.value || !editing.value) return
  editor.value = createEditor({
    parent: host.value,
    doc: source.value,
    autoHeight: true,
    docsUrl,
    placeholderLabel: 'Editable Mini code',
    onChange: (t) => (source.value = t),
    onRun: () => void primary(),
  })
  editor.value.focus()
}

function reset() {
  abort?.abort()
  editor.value?.destroy()
  editor.value = undefined
  editing.value = false
  source.value = spec.source
  clearOutput()
}

function prefetchEditor() {
  void import('./playground/editor.ts')
}

async function openInPlayground() {
  const { specs, sources, k } = context()
  const text = programFor(specs, sources, k).file.text
  window.location.assign(`${withBase('/playground')}${await hashFor(text)}`)
}

// Diagnostics shown inside the editor while editing, so squiggles and hover work there too.
function showInEditor() {
  editor.value?.setDiagnostics(diags.value.filter((d) => d.cell === target.value))
}

const handle: CellHandle = {
  spec,
  getSource: () => source.value,
  run: async () => {
    const ok = await primary()
    showInEditor()
    return ok
  },
  reset,
  isBusy: () => busy.value,
}

async function onRun(kind: 'run' | 'check') {
  await go(kind)
  showInEditor()
}

const own = (d: MappedDiagnostic) => d.cell === target.value
const lineText = (d: MappedDiagnostic) => sourceLines.value[d.line - 1] ?? ''
const gutter = (n: number) => String(n).padStart(4, ' ')
const checkSummary = computed(() => {
  if (ran.value !== 'check' || failure.value || phase.value) return ''
  const errors = diags.value.filter((d) => d.severity === 'error').length
  const warnings = diags.value.filter((d) => d.severity === 'warning').length
  if (!errors && !warnings) return 'The checker found nothing to report.'
  return `${errors} error${errors === 1 ? '' : 's'}, ${warnings} warning${warnings === 1 ? '' : 's'}`
})
const hasOutput = computed(() => segments.value.length > 0 || result.value !== null)
const showPanel = computed(
  () =>
    mounted.value &&
    (ran.value !== '' || showExpected.value) &&
    (hasOutput.value || diags.value.length > 0 || failure.value || checkSummary.value || unavailable.value || busy.value || showExpected.value),
)
</script>

<template>
  <div class="mc" :class="{ 'mc-check': isCheck, 'mc-exercise': spec.exercise, 'mc-editing': editing }">
    <div class="mc-code">
      <div v-show="!editing" class="mc-static"><slot /></div>
      <div v-if="editing" ref="host" class="mc-editor"></div>
    </div>

    <div v-if="mounted" class="mc-bar" role="toolbar" aria-label="Code cell" @pointerenter.once="prefetchEditor" @focusin.once="prefetchEditor">
      <button v-if="!isCheck && !busy" type="button" class="mc-btn primary" :disabled="unavailable" :title="unavailable ? 'The in-browser compiler is not part of this build of the site' : 'Compile and run (Ctrl+Enter in the editor)'" @click="onRun('run')">
        <svg viewBox="0 0 16 16" width="12" height="12" aria-hidden="true"><path d="M4 2.5v11l9-5.5z" fill="currentColor" /></svg>
        Run
      </button>
      <button v-else-if="!isCheck" type="button" class="mc-btn" :disabled="phase !== 'running'" @click="stop">
        <svg viewBox="0 0 16 16" width="12" height="12" aria-hidden="true"><rect x="3" y="3" width="10" height="10" rx="1.5" fill="currentColor" /></svg>
        Stop
      </button>
      <button v-if="isCheck" type="button" class="mc-btn primary" :disabled="unavailable || busy" title="Run the memory checker on this program" @click="onRun('check')">
        <svg viewBox="0 0 16 16" width="12" height="12" aria-hidden="true"><path d="M3 8.5l3.2 3.2L13 4.8" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" /></svg>
        Check
      </button>
      <button v-else type="button" class="mc-btn" :disabled="unavailable || busy" title="Run only the type and memory checker" @click="onRun('check')">Check</button>
      <button v-if="!editing" type="button" class="mc-btn" title="Edit this code" @click="startEdit">Edit</button>
      <button v-if="editing" type="button" class="mc-btn" title="Back to the original code" @click="reset">Reset</button>
      <span v-if="status" class="mc-status">{{ status }}<template v-if="status.startsWith('Loading')"> (first use)</template></span>
      <span v-else-if="isCheck && spec.exercise" class="mc-hint">Goal: no diagnostics</span>
      <span v-else-if="isCheck && spec.expect" class="mc-hint">Expected: {{ describeExpectation(spec.expect) }}</span>
      <span class="mc-spacer"></span>
      <button type="button" class="mc-btn ghost" title="Open this code in the playground" @click="openInPlayground">Playground</button>
    </div>
    <div v-else class="mc-bar mc-bar-ph" aria-hidden="true"></div>

    <div v-if="showPanel" class="mc-out">
      <p v-if="unavailable" class="mc-note">
        Run is not available in this build of the site: the in-browser compiler is not part of it. The expected result is shown instead.
        <a :href="withBase('/roadmap')">Roadmap</a>
      </p>
      <p v-else-if="failure" class="mc-note bad">{{ failure }}</p>

      <pre v-for="(d, i) in diags" :key="i" class="mc-diag" :class="d.severity"><span class="head"><strong>{{ d.severity }}</strong><template v-if="d.code">[<a :href="docsUrl(d.code)">{{ d.code }}</a>]</template>: {{ d.message }}</span>
<span v-if="own(d)" class="loc">  --> {{ d.line }}:{{ d.col }}</span><span v-else class="loc">  --> in a cell above, line {{ d.line }}</span>
<template v-if="own(d) && lineText(d).trim()"><span class="src">{{ gutter(d.line) }} | {{ lineText(d) }}</span>
<span class="src">     | {{ ' '.repeat(Math.max(0, d.col - 1)) }}<b>{{ '^'.repeat(Math.max(1, d.endLine === d.line ? d.endCol - d.col : 1)) }}</b></span>
</template><template v-for="(s, j) in d.secondary ?? []" :key="j"><span class="note">  note: {{ s.label }} (line {{ s.line }}:{{ s.col }})</span>
</template><span v-if="d.help" class="help">  help: {{ d.help }}</span></pre>

      <p v-if="checkSummary" class="mc-sum">{{ checkSummary }}</p>
      <RunOutput v-if="hasOutput" :segments="segments" :result="result" />
      <p v-if="verdict === 'match'" class="mc-verdict ok">
        {{ isCheck && spec.exercise ? 'Fixed: the checker accepts the program.' : isCheck ? 'Matches the documented result.' : spec.exercise ? 'Matches the goal.' : 'Matches the documented output.' }}
      </p>
      <p v-else-if="verdict === 'differs'" class="mc-verdict bad">
        {{ isCheck ? 'The checker reports something other than the documentation says.' : 'The output differs from the documented output.' }}
      </p>

      <div v-if="showExpected && spec.expected !== undefined" class="mc-expected">
        <span class="mc-label">{{ spec.exercise ? 'Goal' : 'Expected output' }}</span>
        <pre>{{ spec.expected }}</pre>
      </div>
    </div>
  </div>
</template>

<style scoped>
.mc { margin: 16px 0; border: 1px solid var(--vp-c-divider); border-radius: 8px; overflow: hidden; background: var(--vp-c-bg-soft); }
.mc-code :deep(div[class*='language-']) { margin: 0 !important; border-radius: 0 !important; }
.mc-code :deep(.mp-open-pg) { display: none; }
.mc-editor { background: var(--mp-bg); }
.mc-editor :deep(.cm-editor) { min-height: 3em; }
.mc-bar { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 8px; padding: 6px 10px; border-top: 1px solid var(--vp-c-divider); min-height: 38px; box-sizing: border-box; }
.mc-bar-ph { background: transparent; }
.mc-btn {
  display: inline-flex; align-items: center; gap: 6px; padding: 3px 11px; border: 1px solid var(--vp-c-divider); border-radius: 6px;
  background: var(--vp-c-bg); color: var(--vp-c-text-1); font: inherit; font-size: 13px; line-height: 20px; cursor: pointer; white-space: nowrap;
}
.mc-btn:hover:not(:disabled) { border-color: var(--vp-c-brand-1); color: var(--vp-c-brand-1); }
.mc-btn:disabled { opacity: 0.55; cursor: not-allowed; }
.mc-btn.primary { background: var(--vp-c-brand-1); border-color: var(--vp-c-brand-1); color: #fff; font-weight: 600; }
.mc-btn.primary:hover:not(:disabled) { background: var(--vp-c-brand-2); color: #fff; }
.mc-btn.ghost { border-color: transparent; background: transparent; color: var(--vp-c-text-2); font-size: 12px; }
.mc-status, .mc-hint { font-size: 12px; color: var(--vp-c-text-2); }
.mc-status::before { content: ''; display: inline-block; width: 8px; height: 8px; margin-right: 6px; border-radius: 50%; background: var(--vp-c-brand-1); animation: mc-pulse 1.1s ease-in-out infinite; }
.mc-spacer { flex: 1 1 0; }
.mc-out { border-top: 1px solid var(--vp-c-divider); background: var(--vp-c-bg); }
.mc-out :deep(.mp-output) { max-height: 360px; }
.mc-note { margin: 0; padding: 8px 14px; font-size: 13px; color: var(--vp-c-text-2); }
.mc-note.bad { color: var(--vp-c-danger-1); }
.mc-sum { margin: 0; padding: 8px 14px; font-size: 13px; color: var(--vp-c-text-2); }
.mc-verdict { margin: 0; padding: 6px 14px 8px; font-size: 12px; }
.mc-verdict.ok { color: var(--mp-ok); }
.mc-verdict.ok::before { content: '\2713  '; }
.mc-verdict.bad { color: var(--vp-c-warning-1); }
.mc-expected { padding: 8px 14px 10px; border-top: 1px dashed var(--vp-c-divider); }
.mc-expected pre { margin: 4px 0 0; font-family: var(--vp-font-family-mono); font-size: 13px; color: var(--vp-c-text-2); white-space: pre-wrap; }
.mc-label { font-size: 11px; text-transform: uppercase; letter-spacing: 0.05em; color: var(--vp-c-text-3); }
.mc-diag { margin: 0; padding: 10px 14px; font-family: var(--vp-font-family-mono); font-size: 13px; line-height: 1.55; white-space: pre-wrap; overflow-x: auto; border-left: 3px solid var(--vp-c-divider); }
.mc-diag.error { border-left-color: var(--vp-c-danger-1); }
.mc-diag.warning { border-left-color: var(--vp-c-warning-1); }
.mc-diag .head strong { color: var(--vp-c-danger-1); }
.mc-diag.warning .head strong { color: var(--vp-c-warning-1); }
.mc-diag .head a { color: inherit; font-weight: 700; text-decoration: underline; }
.mc-diag .loc, .mc-diag .src { color: var(--vp-c-text-2); }
.mc-diag .src b { color: var(--vp-c-danger-1); }
.mc-diag.warning .src b { color: var(--vp-c-warning-1); }
.mc-diag .help { color: var(--vp-c-green-1); }
.mc-diag .note { color: var(--vp-c-text-2); }
.mc-exercise { border-color: var(--vp-c-brand-soft); box-shadow: 0 0 0 1px var(--vp-c-brand-soft); }
@keyframes mc-pulse { 50% { opacity: 0.3; } }
@media (max-width: 560px) {
  .mc-btn.ghost { margin-left: auto; }
  .mc-spacer { display: none; }
}
@media (prefers-reduced-motion: reduce) { .mc-status::before { animation: none; } }
</style>
