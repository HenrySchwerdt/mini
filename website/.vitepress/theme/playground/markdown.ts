import MarkdownIt from 'markdown-it'

/**
 * Markdown for notebook cells. Raw HTML is switched off, so a shared notebook cannot inject markup;
 * markdown-it also refuses `javascript:` and similar link targets by itself.
 */
export function createMarkdownRenderer(withBase: (path: string) => string) {
  const md = new MarkdownIt({ html: false, linkify: true, typographer: false })
  const defaultLinkOpen = md.renderer.rules.link_open ?? ((tokens, idx, options, _env, self) => self.renderToken(tokens, idx, options))
  md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
    const token = tokens[idx]
    const href = String(token.attrGet('href') ?? '')
    if (href.startsWith('/') && !href.startsWith('//')) {
      token.attrSet('href', withBase(href))
    } else if (/^https?:/i.test(href)) {
      token.attrSet('target', '_blank')
      token.attrSet('rel', 'noopener noreferrer')
    }
    return defaultLinkOpen(tokens, idx, options, env, self)
  }
  return (source: string) => md.render(source)
}
