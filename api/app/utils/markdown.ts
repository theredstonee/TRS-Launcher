import { Marked, type MarkedExtension, type Tokens } from 'marked'

// Markdown der Update-Beiträge, der News-Beiträge (§30) und der Datenschutzerklärung → HTML. Kein rohes HTML, nur
// http(s)- und eigene Links, Bilder nur aus dem eigenen Blog-Speicher (News) – damit ist das Ergebnis ohne
// HTML-Sanitizer sicher (auch serverseitig).

function escapeHtml(s: string): string {
  return s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!)
}

function safeHref(href: string): string | null {
  const h = href.trim()
  if (/^https?:\/\//i.test(h)) return h
  if (/^\/(?!\/)/.test(h) || /^#[\w-]+$/.test(h)) return h
  return null
}

/** Bilder der News-Beiträge: nur `/v1/site/blog/media/<id>.<jpg|png>` (gleiche Herkunft, neu kodiert). */
export const BLOG_IMAGE_SRC = /^\/v1\/site\/blog\/media\/[A-Za-z0-9_-]{22}(?:\.t)?\.(?:jpg|png)$/

/** Anker wie bei GitHub („TRS services“ → „trs-services“), damit die Links der PRIVACY.md passen. */
export function slug(text: string): string {
  return text
    .toLowerCase()
    .replace(/<[^>]*>/g, '')
    .replace(/[^\p{L}\p{N}\s-]/gu, '')
    .trim()
    .replace(/\s+/g, '-')
}

function extension(images: boolean): MarkedExtension {
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
        const src = token.href.trim()
        if (!images || !BLOG_IMAGE_SRC.test(src)) return escapeHtml(token.text)
        const title = token.title ? ` title="${escapeHtml(token.title)}"` : ''
        return `<img src="${escapeHtml(src)}" alt="${escapeHtml(token.text)}"${title} loading="lazy" decoding="async">`
      },
    },
  }
}

const plain = new Marked(extension(false))
const withBreaks = new Marked(extension(false), { breaks: true })
const blog = new Marked(extension(true))

/** `blogImages` = Bilder aus dem eigenen Blog-Speicher zeigen (News-Beiträge, Vorschau im Editor). */
export function renderMarkdown(md: string, opts: { breaks?: boolean, blogImages?: boolean } = {}): string {
  const m = opts.blogImages ? blog : opts.breaks ? withBreaks : plain
  return m.parse(md, { async: false }) as string
}
