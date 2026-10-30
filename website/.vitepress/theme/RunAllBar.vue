<script setup lang="ts">
/** Shown on notebook pages (`notebook: true`): runs every cell of the page from top to bottom. */
import { computed, ref } from 'vue'
import { useRoute } from 'vitepress'
import { availability } from './playground/session.ts'
import { cellsOf } from './cell-registry.ts'

const route = useRoute()
const running = ref(false)
const done = ref(0)
const failedAt = ref(-1)
const cells = computed(() => cellsOf(route.path))
const total = computed(() => cells.value.length)

async function runAll() {
  if (running.value) return
  running.value = true
  done.value = 0
  failedAt.value = -1
  const list = cells.value.filter((h) => !h.spec.exercise)
  for (let i = 0; i < list.length; i++) {
    const ok = await list[i].run()
    done.value = i + 1
    if (!ok) {
      failedAt.value = list[i].spec.index
      break
    }
  }
  running.value = false
}

function resetAll() {
  for (const h of cells.value) h.reset()
  done.value = 0
  failedAt.value = -1
}
</script>

<template>
  <div v-if="total > 0" class="ra" role="group" aria-label="Notebook controls">
    <span class="ra-info">
      <strong>Notebook page.</strong> Cells share declarations from top to bottom; edit any cell and run it.
    </span>
    <span class="ra-actions">
      <button type="button" class="ra-btn primary" :disabled="running || availability === 'unavailable'" @click="runAll">
        {{ running ? `Running ${done + 1} of ${total}` : 'Run all' }}
      </button>
      <button type="button" class="ra-btn" :disabled="running" @click="resetAll">Reset all</button>
    </span>
    <span v-if="failedAt >= 0" class="ra-fail">Stopped at cell {{ failedAt + 1 }}.</span>
  </div>
</template>

<style scoped>
.ra { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 14px; margin: 0 0 20px; padding: 8px 12px; border: 1px solid var(--vp-c-divider); border-radius: 8px; background: var(--vp-c-bg-soft); font-size: 13px; color: var(--vp-c-text-2); }
.ra-info { flex: 1 1 260px; }
.ra-info strong { color: var(--vp-c-text-1); }
.ra-actions { display: inline-flex; gap: 6px; }
.ra-btn { padding: 3px 12px; border: 1px solid var(--vp-c-divider); border-radius: 6px; background: var(--vp-c-bg); color: var(--vp-c-text-1); font: inherit; font-size: 13px; cursor: pointer; }
.ra-btn:hover:not(:disabled) { border-color: var(--vp-c-brand-1); }
.ra-btn:disabled { opacity: 0.55; cursor: not-allowed; }
.ra-btn.primary { background: var(--vp-c-brand-1); border-color: var(--vp-c-brand-1); color: #fff; font-weight: 600; }
.ra-fail { color: var(--vp-c-danger-1); }
</style>
