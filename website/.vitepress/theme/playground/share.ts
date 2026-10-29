/**
 * Share links: the text goes into the URL hash (never sent to a server). The payload is `z` + base64url of
 * the deflate-raw compressed UTF-8 text, or `p` + base64url of the plain UTF-8 where CompressionStream is
 * missing. Both are accepted when reading.
 */

function toBase64Url(bytes: Uint8Array): string {
  let s = ''
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000))
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

function fromBase64Url(text: string): Uint8Array {
  const b = atob(text.replace(/-/g, '+').replace(/_/g, '/'))
  const out = new Uint8Array(b.length)
  for (let i = 0; i < b.length; i++) out[i] = b.charCodeAt(i)
  return out
}

async function pipe(bytes: Uint8Array, stream: CompressionStream | DecompressionStream): Promise<Uint8Array> {
  const writer = stream.writable.getWriter()
  void writer.write(bytes as BufferSource).then(() => writer.close()).catch(() => {}) // errors surface from the reader
  const chunks: Uint8Array[] = []
  const reader = stream.readable.getReader()
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    chunks.push(value)
  }
  const out = new Uint8Array(chunks.reduce((n, c) => n + c.length, 0))
  let off = 0
  for (const c of chunks) (out.set(c, off), (off += c.length))
  return out
}

export async function packText(text: string): Promise<string> {
  const bytes = new TextEncoder().encode(text)
  if (typeof CompressionStream === 'function') {
    return 'z' + toBase64Url(await pipe(bytes, new CompressionStream('deflate-raw')))
  }
  return 'p' + toBase64Url(bytes)
}

export async function unpackText(payload: string): Promise<string> {
  const kind = payload[0]
  const bytes = fromBase64Url(payload.slice(1))
  if (kind === 'p') return new TextDecoder().decode(bytes)
  if (kind === 'z') {
    if (typeof DecompressionStream !== 'function') throw new Error('This browser cannot read compressed share links.')
    return new TextDecoder().decode(await pipe(bytes, new DecompressionStream('deflate-raw')))
  }
  throw new Error('Unknown share link format.')
}

/** Reads `#c=<payload>` from a location hash. Returns undefined when there is none or it is broken. */
export async function readHash(hash: string): Promise<string | undefined> {
  const m = /^#?c=([A-Za-z0-9_-]+)$/.exec(hash)
  if (!m) return undefined
  try {
    return await unpackText(m[1])
  } catch {
    return undefined
  }
}

export async function hashFor(text: string): Promise<string> {
  return `#c=${await packText(text)}`
}
