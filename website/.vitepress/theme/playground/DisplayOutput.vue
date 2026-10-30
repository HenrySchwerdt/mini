<script setup lang="ts">
/**
 * One rich output bundle of a program (protocol: docs/display.md, parser: display.ts).
 *  - image/svg+xml: inlined when it passes the strict allowlist of sanitize.ts (so `currentColor` follows the theme),
 *    otherwise shown through <img src="data:..."> where it is inert.
 *  - text/html: in a sandboxed iframe (no scripts, no same-origin access, no network: a CSP in the document).
 *  - application/vnd.mini.table+json: a table.   text/markdown, text/plain: preformatted text.
 */
import { computed, onMounted, ref } from 'vue'
import { parseTable, type DisplayBundle } from './display.ts'
import { inlineSvg, svgDataUrl } from './sanitize.ts'

const props = defineProps<{ bundle: DisplayBundle }>()

let counter = 0
const uid = `mpd${++counter}-${Math.random().toString(36).slice(2, 7)}-`
const mounted = ref(false)
onMounted(() => (mounted.value = true))

const svg = computed(() => {
  if (props.bundle.mime !== 'image/svg+xml' || !mounted.value) return { inline: null as string | null, url: '' }
  return { inline: inlineSvg(props.bundle.data, uid), url: svgDataUrl(props.bundle.data) }
})
const table = computed(() => (props.bundle.mime === 'application/vnd.mini.table+json' ? parseTable(props.bundle.data) : null))

const CSP = "default-src 'none'; style-src 'unsafe-inline'; img-src data:; font-src data:"
const srcdoc = computed(() => {
  if (props.bundle.mime !== 'text/html') return ''
  return `<!doctype html><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="${CSP}"><meta name="color-scheme" content="light dark"><base target="_blank"><style>html{color-scheme:light dark}body{margin:8px;font:14px/1.5 system-ui,sans-serif}</style>${props.bundle.data}`
})
const known = computed(() => ['image/svg+xml', 'text/html', 'text/markdown', 'text/plain'].includes(props.bundle.mime) || table.value !== null)
</script>

<template>
  <figure class="mp-display" :data-mime="bundle.mime">
    <template v-if="bundle.mime === 'image/svg+xml'">
      <div v-if="svg.inline" class="mp-svg" v-html="svg.inline" />
      <img v-else-if="svg.url" class="mp-svg-img" :src="svg.url" alt="Image output of the program" />
    </template>
    <iframe
      v-else-if="bundle.mime === 'text/html'"
      class="mp-html"
      sandbox=""
      referrerpolicy="no-referrer"
      title="HTML output of the program"
      :srcdoc="srcdoc"
    />
    <div v-else-if="table" class="mp-table-wrap">
      <table class="mp-table">
        <thead><tr><th v-for="(c, i) in table.columns" :key="i">{{ c }}</th></tr></thead>
        <tbody><tr v-for="(r, i) in table.rows" :key="i"><td v-for="(c, j) in r" :key="j">{{ c }}</td></tr></tbody>
      </table>
    </div>
    <pre v-else-if="bundle.mime === 'text/markdown' || bundle.mime === 'text/plain'" class="mp-text">{{ bundle.data }}</pre>
    <pre v-if="!known" class="mp-text mp-unknown">Output of type {{ bundle.mime }} (not shown here)</pre>
  </figure>
</template>
