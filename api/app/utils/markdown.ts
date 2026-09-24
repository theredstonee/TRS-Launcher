import { Marked, type MarkedExtension, type Tokens } from 'marked'

// Markdown der Update-Beiträge und der Datenschutzerklärung → HTML. Kein rohes HTML, nur http(s)- und
// eigene Links, keine fremden Bilder – damit ist das Ergebnis ohne HTML-Sanitizer sicher (auch serverseitig).

function escapeHtml(s: string): string {
  return s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!)
}

function safeHref(href: string): string | null {
  const h = href.trim()
  if (/^https?:\/\//i.test(h)) return h
  if (/^\/(?!\/)/.test(h) || /^#[\w-]+$/.test(h)) return h
  return null
}

/** Anker wie bei GitHub („TRS services“ → „trs-services“), damit die Links der PRIVACY.md passen. */
export function slug(text: string): string {
  return text
    .toLowerCase()
    .replace(/<[^>]*>/g, '')
    .replace(/[^\p{L}\p{N}\s-]/gu, '')
    .trim()
    .replace(/\s+/g, '-')
}

function extension(): MarkedExtension {
  return {
    gfm: true,
    renderer: {
      html(token: Tokens.HTML | Tokens.Tag) {
        return escapeHtml(token.text)
      },
      heading(token: Tokens.Heading) {
        const text = this.parser.parseInline(token.tokens)
        return `<h${token.depth} id="${escapeHtml(slug(token.text))}">${text}</h${token.depth}>\n`
      },
      link(token: Tokens.Link) {
        const text = this.parser.parseInline(token.tokens)
        const href = safeHref(token.href)
        if (!href) return text
        const external = /^https?:/i.test(href)
        return `<a href="${escapeHtml(href)}"${external ? ' rel="noopener nofollow" target="_blank"' : ''}>${text}</a>`
      },
      image(token: Tokens.Image) {
        return escapeHtml(token.text)
      },
    },
  }
}

const plain = new Marked(extension())
const withBreaks = new Marked(extension(), { breaks: true })

export function renderMarkdown(md: string, opts: { breaks?: boolean } = {}): string {
  return (opts.breaks ? withBreaks : plain).parse(md, { async: false }) as string
}
