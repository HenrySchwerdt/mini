/**
 * Decides how SVG from a program is shown. A strict allowlist: when every element and attribute of the document is on it, the
 * markup is inserted into the page (so `currentColor` follows the theme, which is what mini.plot relies on). Anything else
 * (scripts, `<style>`, links, external references, event handlers, ...) is not repaired but not inlined at all: the caller
 * shows the SVG through <img src="data:..."> instead, where it is inert and isolated.
 * Needs a DOM (DOMParser); the tests of the module run in the browser e2e test.
 */

const ELEMENTS = new Set([
  'svg', 'g', 'defs', 'title', 'desc', 'path', 'line', 'rect', 'circle', 'ellipse', 'polyline', 'polygon', 'text', 'tspan',
  'lineargradient', 'radialgradient', 'stop', 'clippath',
])

const ATTRIBUTES = new Set([
  'xmlns', 'viewbox', 'width', 'height', 'x', 'y', 'x1', 'y1', 'x2', 'y2', 'cx', 'cy', 'r', 'rx', 'ry', 'd', 'points', 'transform',
  'fill', 'fill-opacity', 'fill-rule', 'stroke', 'stroke-opacity', 'stroke-width', 'stroke-linecap', 'stroke-linejoin',
  'stroke-dasharray', 'stroke-dashoffset', 'stroke-miterlimit', 'opacity', 'font-family', 'font-size', 'font-weight', 'font-style',
  'text-anchor', 'dominant-baseline', 'letter-spacing', 'text-decoration', 'dx', 'dy', 'overflow', 'role', 'aria-label', 'aria-hidden',
  'class', 'id', 'offset', 'stop-color', 'stop-opacity', 'gradientunits', 'gradienttransform', 'clip-path', 'shape-rendering',
  'preserveaspectratio', 'vector-effect',
])

// values that could load something or run script
const BAD_VALUE = /javascript:|data:|@import|expression\(|<|&#|\\/i
// url(#id) is fine, anything else inside url() is not
const URL_REF = /url\(\s*(?!['"]?#)/i

function clean(el: Element): boolean {
  if (!ELEMENTS.has(el.localName.toLowerCase()) || (el.namespaceURI && el.namespaceURI !== 'http://www.w3.org/2000/svg')) return false
  for (const a of Array.from(el.attributes)) {
    const name = a.name.toLowerCase()
    if (name.startsWith('xmlns')) {
      if (a.value !== 'http://www.w3.org/2000/svg') return false
      continue
    }
    if (!ATTRIBUTES.has(name) || BAD_VALUE.test(a.value) || URL_REF.test(a.value)) return false
  }
  for (const child of Array.from(el.childNodes)) {
    if (child.nodeType === 1) {
      if (!clean(child as Element)) return false
    } else if (child.nodeType !== 3) {
      return false // comments, CDATA, processing instructions
    }
  }
  return true
}

/** Number of elements above which an SVG is not inlined (a huge DOM is slow); it is shown as an image instead. */
const MAX_ELEMENTS = 20000

/** The sanitized markup, or null when the document must not be inlined. `id`s are made unique with `idPrefix`. */
export function inlineSvg(svg: string, idPrefix: string): string | null {
  if (typeof DOMParser === 'undefined') return null
  const doc = new DOMParser().parseFromString(svg, 'image/svg+xml')
  const root = doc.documentElement
  if (!root || root.localName !== 'svg' || doc.getElementsByTagName('parsererror').length) return null
  if (root.getElementsByTagName('*').length > MAX_ELEMENTS || !clean(root)) return null
  // ids are global in the page: give them a per-figure prefix and rewrite the references
  const ids = new Map<string, string>()
  for (const el of Array.from(root.querySelectorAll('[id]'))) {
    const id = el.getAttribute('id')!
    ids.set(id, idPrefix + id)
    el.setAttribute('id', idPrefix + id)
  }
  if (ids.size) {
    for (const el of [root, ...Array.from(root.querySelectorAll('*'))]) {
      for (const a of Array.from(el.attributes)) {
        const v = a.value.replace(/url\(\s*['"]?#([^'")\s]+)['"]?\s*\)/g, (m, id) => (ids.has(id) ? `url(#${ids.get(id)})` : m))
        if (v !== a.value) el.setAttribute(a.name, v)
      }
    }
  }
  root.removeAttribute('width')
  root.removeAttribute('height')
  const w = Number(root.getAttribute('viewBox')?.split(/[\s,]+/)[2])
  const wAttr = svg.match(/<svg[^>]*\swidth="(\d+(?:\.\d+)?)"/)
  const maxWidth = wAttr ? Number(wAttr[1]) : w
  if (Number.isFinite(maxWidth) && maxWidth > 0) root.setAttribute('style', `max-width:${maxWidth}px`)
  return new XMLSerializer().serializeToString(root)
}

/** A `data:` URL for showing an SVG in an <img> (scripts and external loads do not run there). */
export function svgDataUrl(svg: string): string {
  const bytes = new TextEncoder().encode(svg)
  let bin = ''
  for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode(...bytes.subarray(i, i + 0x8000))
  return `data:image/svg+xml;base64,${btoa(bin)}`
}
