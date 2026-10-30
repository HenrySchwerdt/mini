<script setup lang="ts">
/** A small pill in the corner of every page: the in-browser compiler is loading (with percent) or ready. */
import { computed, onMounted, ref, watch } from 'vue'
import { useData } from 'vitepress'
import { loadError, loadedLevel, progress } from './playground/session.ts'
import { preloadState, startPreload } from './playground/preload.ts'

const { site } = useData()
const settled = ref(false) // the "ready" label folds into a dot after a few seconds

onMounted(() => void startPreload(site.value.base))

watch(preloadState, (s) => {
  if (s === 'ready') setTimeout(() => (settled.value = true), 4000)
})

const pct = computed(() => {
  const p = progress.value
  if (!p) return null
  if (p.fraction !== undefined) return Math.round(p.fraction * 100)
  if (p.totalBytes > 0) return Math.round((p.loadedBytes / p.totalBytes) * 100)
  return null
})

const state = computed<'hidden' | 'loading' | 'ready' | 'error'>(() => {
  if (loadError.value) return 'error'
  if (loadedLevel.value === 'full') return 'ready'
  if (preloadState.value === 'loading' || progress.value) return 'loading'
  return 'hidden'
})
const label = computed(() => {
  if (state.value === 'loading') return pct.value === null ? 'Loading compiler' : `Loading compiler ${pct.value}%`
  if (state.value === 'ready') return 'Compiler ready'
  return 'Compiler failed to load'
})
</script>

<template>
  <div v-if="state !== 'hidden'" class="tc-badge" :class="[state, { settled: state === 'ready' && settled }]" role="status" aria-live="polite" :title="state === 'ready' ? 'The Mini compiler runs in your browser. Code cells can run now.' : label">
    <span class="dot" aria-hidden="true"></span>
    <span class="text">{{ label }}</span>
    <span v-if="state === 'loading'" class="bar" aria-hidden="true"><i :style="{ width: (pct ?? 20) + '%' }" :class="{ indeterminate: pct === null }"></i></span>
  </div>
</template>

<style scoped>
.tc-badge {
  position: fixed;
  right: 14px;
  bottom: 14px;
  z-index: 40;
  display: flex;
  align-items: center;
  gap: 8px;
  max-width: calc(100vw - 28px);
  padding: 5px 12px 5px 10px;
  border: 1px solid var(--vp-c-divider);
  border-radius: 999px;
  background: var(--vp-c-bg-elv);
  color: var(--vp-c-text-2);
  font-size: 12px;
  line-height: 18px;
  box-shadow: var(--vp-shadow-2);
  transition: padding 0.3s, gap 0.3s, opacity 0.3s;
  pointer-events: auto;
}
.dot { width: 8px; height: 8px; border-radius: 50%; background: var(--vp-c-brand-1); flex: none; }
.loading .dot { animation: pulse 1.2s ease-in-out infinite; }
.ready .dot { background: var(--vp-c-green-1); }
.error .dot { background: var(--vp-c-danger-1); }
.bar { width: 44px; height: 4px; border-radius: 2px; background: var(--vp-c-default-soft); overflow: hidden; flex: none; }
.bar i { display: block; height: 100%; background: var(--vp-c-brand-1); transition: width 0.3s; }
.bar i.indeterminate { animation: slide 1.2s ease-in-out infinite; }
.settled { padding: 5px; gap: 0; opacity: 0.7; }
.settled .text { display: none; }
.settled:hover { opacity: 1; }
@keyframes pulse { 50% { opacity: 0.35; } }
@keyframes slide { 0% { margin-left: -30%; } 100% { margin-left: 100%; } }
@media (max-width: 640px) {
  .tc-badge { right: 8px; bottom: 8px; font-size: 11px; }
  .bar { display: none; }
}
@media (prefers-reduced-motion: reduce) {
  .dot, .bar i { animation: none !important; }
}
</style>
