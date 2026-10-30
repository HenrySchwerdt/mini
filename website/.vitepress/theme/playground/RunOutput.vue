<script setup lang="ts">
import { computed } from 'vue'
import type { RunResult } from './types.ts'
import type { Segment } from './display.ts'
import DisplayOutput from './DisplayOutput.vue'

export type { Segment }

const props = defineProps<{ segments: Segment[]; result?: RunResult | null; note?: string }>()

const summary = computed(() => {
  const r = props.result
  if (!r) return null
  const ms = r.durationMs < 10 ? r.durationMs.toFixed(1) : Math.round(r.durationMs)
  if (r.timedOut) return { text: `Stopped: time limit reached after ${ms} ms`, bad: true }
  if (r.outputLimitExceeded) return { text: `Stopped: output limit exceeded after ${ms} ms`, bad: true }
  if (r.error) return { text: `${r.error} (exit code ${r.exitCode}, ${ms} ms)`, bad: true }
  return { text: `exit code ${r.exitCode} in ${ms} ms`, bad: r.exitCode !== 0 }
})

type TextSegment = { type: 'out' | 'err'; text: string }
type Group = { kind: 'text'; segs: TextSegment[]; last: boolean } | { kind: 'display'; seg: Extract<Segment, { type: 'display' }> }

/** Text runs and rich bundles in the order the program produced them; the note and the exit summary close the last text run. */
const groups = computed<Group[]>(() => {
  const out: Group[] = []
  for (const s of props.segments) {
    if (s.type === 'display') out.push({ kind: 'display', seg: s })
    else {
      const g = out[out.length - 1]
      if (g && g.kind === 'text') g.segs.push(s)
      else out.push({ kind: 'text', segs: [s], last: false })
    }
  }
  if (props.note || summary.value) {
    const g = out[out.length - 1]
    if (g && g.kind === 'text') g.last = true
    else out.push({ kind: 'text', segs: [], last: true })
  }
  return out
})

const endsWithNewline = (g: Extract<Group, { kind: 'text' }>) => {
  const l = g.segs[g.segs.length - 1]
  return !l || l.text.endsWith('\n')
}
</script>

<template>
  <div class="mp-outputs" aria-live="polite">
    <template v-for="(g, i) in groups" :key="i">
      <DisplayOutput v-if="g.kind === 'display'" :bundle="{ mime: g.seg.mime, data: g.seg.data }" />
      <pre v-else class="mp-output"><span v-for="(s, k) in g.segs" :key="k" :class="s.type">{{ s.text }}</span><template v-if="g.last"><span v-if="note" class="meta">{{ note }}</span><span v-if="summary" class="meta" :class="summary.bad ? 'bad' : 'good'">{{ endsWithNewline(g) ? '' : '\n' }}{{ summary.text }}</span></template></pre>
    </template>
  </div>
</template>
