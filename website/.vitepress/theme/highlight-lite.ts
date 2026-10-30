/** A tiny Kotlin highlighter for the few snippets that are not rendered by VitePress' markdown pipeline (the hero example). */

const KEYWORDS = new Set(
  'fun val var class object interface data sealed enum override open abstract private public internal return if else when for while do in is as null true false this super import package try catch finally throw by it'.split(' '),
)

const esc = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')

/** Returns HTML with `tok-*` spans (colors come from playground.css). */
export function highlightKotlin(src: string): string {
  const re = /(\/\/[^\n]*)|("(?:[^"\\\n]|\\.)*")|(@[A-Za-z]+(?::[A-Za-z]+)?)|(\b\d[\d_]*(?:\.\d+)?L?\b)|([A-Za-z_][A-Za-z0-9_]*)/g
  let out = ''
  let last = 0
  for (let m = re.exec(src); m; m = re.exec(src)) {
    out += esc(src.slice(last, m.index))
    last = m.index + m[0].length
    const [text, comment, str, meta, num, word] = m
    if (comment) out += `<span class="tok-comment">${esc(text)}</span>`
    else if (str) out += `<span class="tok-string">${esc(text)}</span>`
    else if (meta) out += `<span class="tok-meta">${esc(text)}</span>`
    else if (num) out += `<span class="tok-number">${esc(text)}</span>`
    else if (word && KEYWORDS.has(word)) out += `<span class="tok-keyword">${esc(text)}</span>`
    else if (word && /^[A-Z]/.test(word)) out += `<span class="tok-type">${esc(text)}</span>`
    else out += esc(text)
  }
  return out + esc(src.slice(last))
}
