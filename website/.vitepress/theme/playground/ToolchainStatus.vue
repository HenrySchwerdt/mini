<script setup lang="ts">
import { computed } from 'vue'
import { withBase } from 'vitepress'
import { availability, loadError, loadedLevel, progress } from './session.ts'

const pct = computed(() => {
  const p = progress.value
  if (!p) return null
  if (p.fraction !== undefined) return Math.round(p.fraction * 100)
  if (p.totalBytes > 0) return Math.round((p.loadedBytes / p.totalBytes) * 100)
  return null
})
const mb = (n: number) => (n / 1048576).toFixed(1)
</script>

<template>
  <div v-if="availability === 'unavailable'" class="mp-status warn" role="status">
    <span>
      <strong>Run is not available yet.</strong>
      The in-browser Mini compiler is still being built, so programs cannot be compiled or checked here.
      Editing, highlighting, examples, sharing and saving work.
    </span>
    <a :href="withBase('/roadmap')">See the roadmap</a>
  </div>
  <div v-else-if="progress" class="mp-status" role="status" aria-live="polite">
    <span>
      <strong>Loading {{ progress.label }}</strong>
      <template v-if="progress.totalBytes > 0"> ({{ mb(progress.loadedBytes) }} of {{ mb(progress.totalBytes) }} MB)</template>
      <template v-else-if="progress.loadedBytes > 0"> ({{ mb(progress.loadedBytes) }} MB)</template>
    </span>
    <div class="mp-bar" :class="{ indeterminate: pct === null }" role="progressbar" :aria-valuenow="pct ?? undefined" aria-valuemin="0" aria-valuemax="100">
      <div :style="{ width: (pct ?? 35) + '%' }"></div>
    </div>
    <span v-if="pct !== null">{{ pct }}%</span>
  </div>
  <div v-else-if="loadError" class="mp-status bad" role="alert">
    <span><strong>The toolchain could not be loaded.</strong> {{ loadError }}</span>
  </div>
  <div v-else-if="availability === 'available' && !loadedLevel" class="mp-status" role="status">
    <span>The compiler loads when you first check or run a program and is cached by your browser afterwards.</span>
  </div>
</template>
