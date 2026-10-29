<script setup lang="ts">
import { computed } from 'vue'
import type { RunResult } from './types.ts'

export interface Segment {
  type: 'out' | 'err'
  text: string
}

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
</script>

<template>
  <pre class="mp-output" aria-live="polite"><span v-for="(s, i) in segments" :key="i" :class="s.type">{{ s.text }}</span><span v-if="note" class="meta">{{ note }}</span><span v-if="summary" class="meta" :class="summary.bad ? 'bad' : 'good'">{{ segments.length && !segments[segments.length - 1].text.endsWith('\n') ? '\n' : '' }}{{ summary.text }}</span></pre>
</template>
