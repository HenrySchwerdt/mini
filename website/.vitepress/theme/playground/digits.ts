/** Safe, versioned data-only output: a 64-input, 10-class affine softmax classifier. */
export const DIGIT_MIME = 'application/vnd.mini.digit-classifier+json'
export interface DigitModel { version: 1; weights: number[]; bias: number[] }
export function parseDigitModel(json: string): DigitModel | null {
  try {
    const m = JSON.parse(json)
    const vector = (v: unknown, n: number) => Array.isArray(v) && v.length === n && v.every(x => typeof x === 'number' && Number.isFinite(x) && Math.abs(x) <= 1e6)
    return m?.version === 1 && vector(m.weights, 640) && vector(m.bias, 10) ? m : null
  } catch { return null }
}
export function digitProbabilities(model: DigitModel, pixels: number[]): number[] {
  if (pixels.length !== 64 || pixels.some(x => !Number.isFinite(x) || x < 0 || x > 1)) throw new Error('Expected 64 pixels in [0, 1].')
  const scores = model.bias.map((b, c) => pixels.reduce((s, x, i) => s + x * model.weights[c * 64 + i], b))
  const max = Math.max(...scores)
  const exps = scores.map(s => Math.exp(s - max))
  const sum = exps.reduce((a, b) => a + b, 0)
  return exps.map(x => x / sum)
}

/** Area-average every 32×32 block, so thin strokes cannot disappear between sample points. */
export function drawingPixels(rgba: Uint8ClampedArray): number[] {
  if (rgba.length !== 256 * 256 * 4) throw new Error('Expected a 256×256 RGBA drawing.')
  const pixels = Array<number>(64).fill(0)
  for (let y = 0; y < 256; y++) for (let x = 0; x < 256; x++) {
    pixels[Math.floor(y / 32) * 8 + Math.floor(x / 32)] += rgba[(y * 256 + x) * 4 + 3] / (32 * 32 * 255)
  }
  return pixels
}
