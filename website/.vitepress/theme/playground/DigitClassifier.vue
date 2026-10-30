<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { digitProbabilities, drawingPixels, type DigitModel } from './digits.ts'
const props = defineProps<{ model: DigitModel }>()
const canvas = ref<HTMLCanvasElement>()
const pixels = ref<number[]>(Array(64).fill(0))
const ink = computed(() => pixels.value.some(x => x > 0))
const probabilities = computed(() => digitProbabilities(props.model, pixels.value))
const prediction = computed(() => probabilities.value.indexOf(Math.max(...probabilities.value)))
let pointer: number | null = null
let last: [number, number] | null = null
function clear() {
  canvas.value?.getContext('2d')?.clearRect(0, 0, 256, 256)
  pixels.value = Array(64).fill(0)
  last = null
}
watch(() => props.model, clear)
function sample() {
  const ctx = canvas.value!.getContext('2d')!
  pixels.value = drawingPixels(ctx.getImageData(0, 0, 256, 256).data)
}
function draw(e: PointerEvent) {
  if (pointer !== e.pointerId) return
  const el = canvas.value!
  const rect = el.getBoundingClientRect()
  const x = (e.clientX - rect.left) * 256 / rect.width
  const y = (e.clientY - rect.top) * 256 / rect.height
  const ctx = el.getContext('2d')!
  ctx.strokeStyle = '#ffffff'; ctx.fillStyle = '#ffffff'; ctx.lineWidth = 18; ctx.lineCap = 'round'; ctx.lineJoin = 'round'
  ctx.beginPath()
  if (last) { ctx.moveTo(...last); ctx.lineTo(x, y); ctx.stroke() }
  else { ctx.arc(x, y, 9, 0, Math.PI * 2); ctx.fill() }
  last = [x, y]
  sample()
}
function start(e: PointerEvent) {
  if (pointer !== null) return
  pointer = e.pointerId
  canvas.value!.setPointerCapture(e.pointerId)
  draw(e)
}
function end(e: PointerEvent) {
  if (pointer !== e.pointerId) return
  pointer = null; last = null
}
</script>
<template>
  <div class="digit-lab">
    <div>
      <p class="digit-label">YOUR HANDWRITING → 64 PIXELS</p>
      <canvas ref="canvas" width="256" height="256" aria-label="Draw a digit from 0 to 9 with your mouse or touch" @pointerdown.prevent="start" @pointermove.prevent="draw" @pointerup="end" @pointercancel="end" @lostpointercapture="end" />
      <button class="mp-btn" @click="clear">Clear drawing</button>
    </div>
    <div class="digit-readout">
      <p class="digit-label">THE MODEL YOU JUST TRAINED</p>
      <p class="digit-answer" aria-live="polite">{{ ink ? prediction : '—' }}</p>
      <p>{{ ink ? 'Predicted digit' : 'Draw a large, centered digit to begin.' }}</p>
      <div v-for="(p, digit) in probabilities" :key="digit" class="digit-score">
        <span>{{ digit }}</span><meter :value="ink ? p : 0" min="0" max="1" :aria-label="`Score for ${digit}`" /><span>{{ ink ? (p * 100).toFixed(1) : '0.0' }}%</span>
      </div>
      <p class="digit-footnote">Model scores, not calibrated confidence. Trained on 8×8 scans; mouse drawings may look different.</p>
    </div>
  </div>
</template>
<style scoped>
.digit-lab { display: flex; flex-wrap: wrap; gap: 32px; padding: 16px 0; }
.digit-label { font: 11px var(--vp-font-family-mono); letter-spacing: .08em; color: var(--vp-c-text-2); }
canvas { display: block; width: 256px; max-width: 100%; height: auto; background: #111820; border: 1px solid var(--vp-c-divider); border-radius: 8px; touch-action: none; cursor: crosshair; margin: 12px 0; }
.digit-readout { flex: 1; min-width: 220px; }
.digit-answer { font: 72px/1 var(--vp-font-family-mono); margin: 12px 0; color: var(--vp-c-brand-1); }
.digit-score { display: flex; align-items: center; gap: 12px; font: 12px/1.8 var(--vp-font-family-mono); }
.digit-score meter { flex: 1; height: 10px; accent-color: var(--vp-c-brand-1); }
.digit-score span:last-child { width: 52px; text-align: right; }
.digit-footnote { color: var(--vp-c-text-2); font-size: 12px; max-width: 380px; }
</style>
