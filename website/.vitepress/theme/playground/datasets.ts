/** Notebook host directive: download UTF-8 text for the cell’s generated Mini data module. */
export function datasetUrl(source: string): string | null {
  const matches = [...source.matchAll(/^\s*\/\/\s*%dataset\s+(\S+)\s*$/gm)]
  if (!matches.length) return null
  if (matches.length > 1) throw new Error('Use one %dataset directive per cell.')
  const url = new URL(matches[0][1])
  if (url.protocol !== 'https:' || url.username || url.password) throw new Error('Datasets require an HTTPS URL without credentials.')
  return url.href
}

const MAX_BYTES = 2 * 1024 * 1024

async function boundedText(stream: ReadableStream<Uint8Array>, signal: AbortSignal): Promise<string> {
  const reader = stream.getReader()
  const abort = () => { void reader.cancel(signal.reason).catch(() => {}) }
  signal.addEventListener('abort', abort, { once: true })
  const decoder = new TextDecoder('utf-8', { fatal: true })
  let size = 0
  let text = ''
  try {
    while (true) {
      signal.throwIfAborted()
      const { done, value } = await reader.read()
      signal.throwIfAborted()
      if (done) break
      size += value.byteLength
      if (size > MAX_BYTES) throw new Error('Dataset exceeds the 2 MiB limit.')
      text += decoder.decode(value, { stream: true })
    }
    return text + decoder.decode()
  } finally {
    signal.removeEventListener('abort', abort)
    await reader.cancel().catch(() => {})
    reader.releaseLock()
  }
}

export async function fetchDataset(url: string, signal: AbortSignal): Promise<string> {
  const combined = AbortSignal.any([signal, AbortSignal.timeout(30000)])
  const response = await fetch(url, { signal: combined, credentials: 'omit', referrerPolicy: 'no-referrer' })
  if (!response.ok) throw new Error(`Dataset download failed (HTTP ${response.status}).`)
  if (!response.body) throw new Error('Dataset response has no body.')
  // .gz is a file format here, independent of HTTP Content-Encoding (which fetch decodes).
  const stream = new URL(url).pathname.endsWith('.gz')
    ? response.body.pipeThrough(new DecompressionStream('gzip')) : response.body
  return boundedText(stream, combined)
}

/** Pass downloaded text as a generated Mini source, without requiring browser sockets or native IO. */
export function datasetSource(text: string): { name: string; text: string } {
  // JSON strings share Kotlin escapes except for Kotlin string interpolation.
  const literal = JSON.stringify(text).replace(/\$/g, '\\$')
  return { name: 'notebook-dataset.kt', text: `package mini.notebook\nfun datasetText(): String = ${literal}\n` }
}

/** The closest directive at or above a code cell remains its input until replaced. */
export function notebookDatasetUrl(cells: { type: string; source: string }[], target: number): string | null {
  for (let i = target; i >= 0; i--) {
    if (cells[i].type !== 'code') continue
    const url = datasetUrl(cells[i].source)
    if (url !== null) return url
  }
  return null
}
