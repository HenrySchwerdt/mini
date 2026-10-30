<script setup lang="ts">
/** The install one-liner under the hero, with a copy button. */
import { ref } from 'vue'
import { withBase } from 'vitepress'

const cmd = 'curl -fsSL https://raw.githubusercontent.com/HenrySchwerdt/mini/main/install.sh | sh'
const copied = ref(false)

async function copy() {
  try {
    await navigator.clipboard.writeText(cmd)
    copied.value = true
    setTimeout(() => (copied.value = false), 1800)
  } catch {
    /* clipboard blocked: the command is selectable */
  }
}
</script>

<template>
  <section class="qi" aria-label="Install">
    <div class="qi-inner">
      <div class="qi-text">
        <strong>Install mini + minic</strong>
        <span>Linux x64 and arm64, macOS on Apple silicon. Needs a C compiler, no JDK.</span>
      </div>
      <div class="qi-cmd">
        <code>{{ cmd }}</code>
        <button type="button" :aria-label="copied ? 'Copied' : 'Copy the install command'" @click="copy">{{ copied ? 'Copied' : 'Copy' }}</button>
      </div>
      <a class="qi-more" :href="withBase('/install')">All install options</a>
    </div>
  </section>
</template>

<style scoped>
.qi { padding: 0 24px; margin: 8px 0 24px; }
.qi-inner {
  display: flex; flex-wrap: wrap; align-items: center; gap: 10px 20px; max-width: 1152px; margin: 0 auto; padding: 14px 18px;
  border: 1px solid var(--vp-c-divider); border-radius: 12px; background: var(--vp-c-bg-soft);
}
.qi-text { display: flex; flex-wrap: wrap; align-items: baseline; gap: 2px 12px; font-size: 13px; color: var(--vp-c-text-2); flex: 1 1 300px; }
.qi-text strong { color: var(--vp-c-text-1); font-size: 14px; }
.qi-cmd { display: flex; align-items: center; gap: 8px; order: 3; flex: 1 1 100%; min-width: 0; padding: 6px 6px 6px 12px; border-radius: 8px; background: var(--vp-code-block-bg); }
.qi-cmd code { flex: 1; min-width: 0; overflow-x: auto; white-space: nowrap; font-size: 13px; color: var(--vp-c-text-1); background: none; padding: 0; }
.qi-cmd button { flex: none; padding: 3px 12px; border: 1px solid var(--vp-c-divider); border-radius: 6px; background: var(--vp-c-bg); color: var(--vp-c-text-1); font: inherit; font-size: 12px; cursor: pointer; }
.qi-cmd button:hover { border-color: var(--vp-c-brand-1); color: var(--vp-c-brand-1); }
.qi-more { font-size: 13px; font-weight: 500; white-space: nowrap; }
@media (max-width: 640px) { .qi { padding: 0 16px; } }
</style>
