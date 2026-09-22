import DOMPurify from 'dompurify'
import { Marked } from 'marked'

// Markdown von Modrinth (Beschreibungen, Changelogs) ist fremder Inhalt: Er
// wird nur über `renderMarkdown` angezeigt – geparst mit marked und danach mit
// DOMPurify auf eine feste Whitelist reduziert. Skripte, iframes, Styles,
// Event-Handler und Nicht-HTTPS-Links bleiben draußen.

const ALLOWED_TAGS = [
  'a', 'abbr', 'b', 'blockquote', 'br', 'center', 'code', 'del', 'details', 'div', 'em',
  'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'hr', 'i', 'img', 'kbd', 'li', 'mark', 'ol', 'p',
  'pre', 's', 'span', 'strong', 'sub', 'summary', 'sup', 'table', 'tbody', 'td', 'th',
  'thead', 'tr', 'u', 'ul',
]
const ALLOWED_ATTR = ['href', 'src', 'alt', 'title', 'width', 'height', 'align', 'colspan', 'rowspan']

const HTTPS = /^https:\/\//i

/** Nur echte HTTPS-Links dürfen geöffnet werden – kein `javascript:`, `data:`, `file:`. */
export function isSafeLink(href: string | null | undefined): href is string {
  if (!href || href.length > 2048 || !HTTPS.test(href)) return false
  try {
    const url = new URL(href)
    return url.protocol === 'https:' && !url.username && !url.password
  } catch {
    return false
  }
}

type Purifier = ReturnType<typeof DOMPurify>

function configure(purify: Purifier): Purifier {
  purify.addHook('afterSanitizeAttributes', (node) => {
    if (node.tagName === 'A') {
      const href = node.getAttribute('href')
      if (href && !href.startsWith('#') && !isSafeLink(href)) node.removeAttribute('href')
      node.setAttribute('rel', 'noopener noreferrer nofollow')
    }
    if (node.tagName === 'IMG') {
      // Bilder nur per HTTPS (erlaubt die CSP ohnehin nur so) und ohne Tracking-Referrer.
      if (!isSafeLink(node.getAttribute('src'))) node.remove()
      else {
        node.setAttribute('loading', 'lazy')
        node.setAttribute('referrerpolicy', 'no-referrer')
      }
    }
  })
  return purify
}

const marked = new Marked({ gfm: true, breaks: true, async: false })
let render: ((markdown: string) => string) | null = null

/** Für Tests: eigene DOMPurify-Instanz (z. B. mit jsdom-Window). */
export function createRenderer(purify: Purifier) {
  const configured = configure(purify)
  return (markdown: string): string => {
    const html = marked.parse(markdown.slice(0, 200_000)) as string
    return configured.sanitize(html, {
      ALLOWED_TAGS,
      ALLOWED_ATTR,
      ALLOW_DATA_ATTR: false,
      ALLOW_ARIA_ATTR: false,
      FORBID_TAGS: ['style', 'script', 'iframe', 'object', 'embed', 'form', 'input', 'svg', 'math'],
      FORBID_ATTR: ['style', 'class', 'id', 'srcset'],
      KEEP_CONTENT: true,
    }) as string
  }
}

/** Markdown → bereinigtes HTML. Einziger erlaubter Weg zu `v-html`. */
export function renderMarkdown(markdown: string | null | undefined): string {
  if (!markdown) return ''
  render ??= createRenderer(DOMPurify(window))
  return render(markdown)
}
