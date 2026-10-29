/*
 * Cache-first service worker for the in-browser Mini toolchain (see .vitepress/theme/playground/README.md).
 * It only handles GET requests for files under <base>/toolchain/ (except manifest.json, which is always
 * fetched fresh so a new toolchain version is noticed). The cache name carries the toolchain version that
 * registered this worker (?v=...); caches of other versions are deleted on activation.
 */
const version = new URL(self.location.href).searchParams.get('v') || 'unversioned'
const PREFIX = 'mini-toolchain-'
const CACHE = PREFIX + version

self.addEventListener('install', () => self.skipWaiting())

self.addEventListener('activate', (event) => {
  event.waitUntil(
    (async () => {
      for (const name of await caches.keys()) {
        if (name.startsWith(PREFIX) && name !== CACHE) await caches.delete(name)
      }
      await self.clients.claim()
    })(),
  )
})

self.addEventListener('fetch', (event) => {
  const req = event.request
  if (req.method !== 'GET' || req.headers.has('range')) return
  const url = new URL(req.url)
  if (url.origin !== self.location.origin) return
  if (!url.pathname.includes('/toolchain/') || url.pathname.endsWith('/manifest.json')) return
  event.respondWith(
    (async () => {
      const cache = await caches.open(CACHE)
      const hit = await cache.match(req)
      if (hit) return hit
      const res = await fetch(req)
      if (res.ok && res.status === 200) await cache.put(req, res.clone())
      return res
    })(),
  )
})
