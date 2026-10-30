import './open-in-playground.css'
import { withBase } from 'vitepress'
import { hashFor } from './playground/share.ts'

const CLASS = 'mp-open-pg'

/** The text of a rendered code block, without diff-removed lines (same rule as VitePress' copy button). */
function sourceOf(block: Element): string {
  const code = block.querySelector('pre code')
  if (!code) return ''
  const clone = code.cloneNode(true) as HTMLElement
  clone.querySelectorAll('.vp-copy-ignore, .diff.remove').forEach((n) => n.remove())
  return (clone.textContent ?? '').replace(/\n$/, '') + '\n'
}

/** Adds an "Open in playground" button to every Kotlin code block on the page. Safe to call repeatedly. */
export function decorateCodeBlocks() {
  const blocks = document.querySelectorAll('.vp-doc div[class*="language-kotlin"], .vp-doc div[class*="language-kt"]')
  blocks.forEach((block) => {
    if (block.closest('.mc')) return // runnable cells have their own toolbar
    if (block.querySelector(`.${CLASS}`)) return
    const button = document.createElement('button')
    button.type = 'button'
    button.className = CLASS
    button.textContent = 'Open in playground'
    button.title = 'Edit and run this code in the playground'
    button.addEventListener('click', async () => {
      const hash = await hashFor(sourceOf(block))
      window.location.assign(`${withBase('/playground')}${hash}`)
    })
    block.append(button)
  })
}
