/**
 * Starts loading the in-browser toolchain in the background once a docs page has been shown, so that the first Run
 * on any page does not wait for a download. Cheap by design:
 *  - nothing happens before the page's `load` event and an idle moment (first paint and LCP are not affected);
 *  - the small checker stage (minic.wasm, a few MB) loads first, the compiler stage (clang, wasm-ld, runtime) waits for an actual cache miss;
 *  - Save-Data and slow connections (2g/3g) skip the preload; the toolchain then loads on the first Run, as before;
 *  - the files are versioned by the manifest and cached by the service worker (cache-first), so later visits cost nothing.
 */
import { ref } from 'vue'
import { availability, ensureToolchain, manifest, probe, setSiteBase } from './session.ts'
import { registerServiceWorker } from './toolchain.ts'

export type PreloadState = 'idle' | 'waiting' | 'loading' | 'ready' | 'skipped' | 'unavailable' | 'error'
export const preloadState = ref<PreloadState>('idle')

let started = false

interface NetworkInformation {
  saveData?: boolean
  effectiveType?: string
}

/** Why the preload should not run on this connection, or undefined. */
export function preloadBlocker(nav: { connection?: NetworkInformation }): string | undefined {
  const c = nav.connection
  if (c?.saveData) return 'Save-Data is on'
  if (c?.effectiveType && /^(slow-2g|2g|3g)$/.test(c.effectiveType)) return `slow connection (${c.effectiveType})`
  return undefined
}

/** Waits (at most `ms`) until the service worker that caches the toolchain controls this page, so the downloads land in its cache. */
async function serviceWorkerReady(ms: number): Promise<void> {
  if (!('serviceWorker' in navigator) || location.hostname === 'localhost') return
  await Promise.race([navigator.serviceWorker.ready.then(() => undefined, () => undefined), new Promise<void>((r) => setTimeout(r, ms))])
}

const whenLoaded = () => new Promise<void>((r) => (document.readyState === 'complete' ? r() : window.addEventListener('load', () => r(), { once: true })))
const whenIdle = () =>
  new Promise<void>((r) => {
    const ric = (window as unknown as { requestIdleCallback?: (cb: () => void, o?: { timeout: number }) => void }).requestIdleCallback
    if (ric) ric(() => r(), { timeout: 4000 })
    else setTimeout(r, 1500)
  })

/** Call once from the layout (browser only). Safe to call again. */
export async function startPreload(siteBase: string) {
  if (started || typeof window === 'undefined') return
  started = true
  setSiteBase(siteBase)
  const blocked = preloadBlocker(navigator as unknown as { connection?: NetworkInformation })
  if (blocked) {
    preloadState.value = 'skipped'
    return
  }
  preloadState.value = 'waiting'
  await whenLoaded()
  await whenIdle()
  await probe()
  if (availability.value !== 'available' || !manifest.value) {
    preloadState.value = 'unavailable'
    return
  }
  preloadState.value = 'loading'
  try {
    registerServiceWorker(siteBase, manifest.value.version)
    await serviceWorkerReady(1500)
    await ensureToolchain('check')
    // clang is large and expensive to initialize. Cached/prebuilt runs do not need it.
    preloadState.value = 'ready'
  } catch {
    preloadState.value = 'error'
  }
}
