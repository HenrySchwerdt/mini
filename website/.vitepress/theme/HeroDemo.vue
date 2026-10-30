<script setup lang="ts">
/** The runnable example next to the headline of the landing page. */
import MiniCell from './MiniCell.vue'
import { highlightKotlin } from './highlight-lite.ts'
import type { CellSpec } from './snippets.ts'

const source = `data class Point(val x: Int, val y: Int)

fun main() {
    val pts = listOf(Point(1, 2), Point(3, 4), Point(5, 6))
    val total = pts.sumOf { it.x * it.y }
    println("Hello from native code: $total")
}
`
const spec: CellSpec = { index: -1, kind: 'run', chain: false, exercise: false, source, expected: 'Hello from native code: 44\n' }
const html = highlightKotlin(source)
</script>

<template>
  <div class="hero-demo">
    <div class="hero-demo-title"><span aria-hidden="true"></span><span aria-hidden="true"></span><span aria-hidden="true"></span><em>hello.kt</em></div>
    <MiniCell :spec="spec">
      <div class="language-kotlin vp-adaptive-theme"><pre class="hero-pre"><code v-html="html"></code></pre></div>
    </MiniCell>
  </div>
</template>

<style scoped>
.hero-demo { width: min(100%, 520px); text-align: left; border: 1px solid var(--vp-c-divider); border-radius: 10px; overflow: hidden; background: var(--vp-c-bg-soft); box-shadow: var(--vp-shadow-3); }
.hero-demo-title { display: flex; align-items: center; gap: 6px; padding: 11px 16px; border-bottom: 1px solid var(--vp-c-divider); background: var(--vp-c-bg-alt); color: var(--vp-c-text-3); font-size: 12px; }
.hero-demo-title span { width: 9px; height: 9px; border-radius: 50%; background: var(--vp-c-divider); }
.hero-demo-title em { margin-left: 6px; font-style: normal; font-family: var(--vp-font-family-mono); }
.hero-demo :deep(.mc) { margin: 0; border: 0; border-radius: 0; box-shadow: none; background: var(--vp-c-bg-soft); }
.hero-pre { margin: 0; padding: 14px 16px; overflow-x: auto; font-family: var(--vp-font-family-mono); font-size: 13.5px; line-height: 1.6; background: var(--vp-c-bg-soft); color: var(--vp-c-text-1); }
.hero-pre code { font: inherit; background: none; padding: 0; color: inherit; white-space: pre; }
</style>
