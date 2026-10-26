/**
 * Einfaches Markdown für Mail-Texte: **fett**, *kursiv* / _kursiv_, [Link](https://…), Listen (`- ` / `1. `),
 * Absätze (Leerzeile) und Zeilenumbrüche. Kein rohes HTML – alles wird escaped, Links nur http(s)/mailto.
 * Ergebnis als HTML mit Inline-Stilen (Mail-Clients kennen kein CSS aus <style> zuverlässig) und als Klartext.
 */

export function esc(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;')
}

/** Nur sichere Link-Ziele; relative Pfade (`/…`) werden an `base` gehängt. */
export function safeUrl(raw: string, base: string): string | null {
  const s = raw.trim()
  if (s.startsWith('/') && !s.startsWith('//')) return `${base}${s}`
  try {
    const u = new URL(s)
    if (u.protocol === 'https:' || u.protocol === 'http:' || u.protocol === 'mailto:') return u.href
  } catch {
    // ungültig
  }
  return null
}

export interface InlineStyle {
  link: string
  strong: string
}

function inline(src: string, base: string, st: InlineStyle, mode: 'html' | 'text'): string {
  let out = ''
  let i = 0
  while (i < src.length) {
    const rest = src.slice(i)
    const link = /^\[([^\]\n]{1,200})\]\(([^)\s]{1,800})\)/.exec(rest)
    if (link) {
      const url = safeUrl(link[2]!, base)
      const label = inline(link[1]!, base, st, mode)
      if (mode === 'text') out += url ? `${label} (${url})` : label
      else out += url ? `<a href="${esc(url)}" style="${st.link}">${label}</a>` : label
      i += link[0].length
      continue
    }
    const bold = /^\*\*([^*\n](?:[^\n]*?[^*\n])?)\*\*/.exec(rest)
    if (bold) {
      const inner = inline(bold[1]!, base, st, mode)
      out += mode === 'text' ? inner : `<b style="${st.strong}">${inner}</b>`
      i += bold[0].length
      continue
    }
    const em = /^(\*|_)([^*_\n](?:[^\n]*?[^*_\n])?)\1/.exec(rest)
    if (em && (i === 0 || /[\s([]/.test(src[i - 1]!))) {
      const inner = inline(em[2]!, base, st, mode)
      out += mode === 'text' ? inner : `<i>${inner}</i>`
      i += em[0].length
      continue
    }
    out += mode === 'text' ? src[i] : esc(src[i]!)
    i++
  }
  return out
}

export interface BlockStyle extends InlineStyle {
  p: string
  li: string
  bullet: string
}

/** Markdown → HTML-Fragment (Absätze/Listen als `<div>`/Tabellen, mailtauglich). */
export function mdToHtml(md: string, base: string, st: BlockStyle): string {
  const paras = md.replace(/\r\n?/g, '\n').trim().split(/\n{2,}/)
  const out: string[] = []
  for (const para of paras) {
    const lines = para.split('\n')
    const isList = lines.every((l) => /^\s*(?:[-*•]|\d{1,2}[.)])\s+/.test(l))
    if (isList) {
      const rows = lines.map((l, n) => {
        const ordered = /^\s*\d/.test(l)
        const text = l.replace(/^\s*(?:[-*•]|\d{1,2}[.)])\s+/, '')
        const marker = ordered ? `<span style="${st.li}">${n + 1}.</span>` : `<div style="${st.bullet}"></div>`
        return `<tr><td width="18" style="vertical-align:top;padding:4px 0;">${marker}</td><td style="${st.li}padding:2px 0 4px 6px;">${inline(text, base, st, 'html')}</td></tr>`
      })
      out.push(`<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="margin:0 0 12px 0;">${rows.join('')}</table>`)
    } else {
      out.push(`<div style="${st.p}">${lines.map((l) => inline(l, base, st, 'html')).join('<br>')}</div>`)
    }
  }
  return out.join('')
}

/** Markdown → Klartext (Listen als „- “, Links mit Adresse in Klammern). */
export function mdToText(md: string, base: string): string {
  const st: InlineStyle = { link: '', strong: '' }
  return md
    .replace(/\r\n?/g, '\n')
    .trim()
    .split('\n')
    .map((l) => {
      const m = /^\s*([-*•]|\d{1,2}[.)])\s+(.*)$/.exec(l)
      return m ? `${/\d/.test(m[1]!) ? m[1] : '-'} ${inline(m[2]!, base, st, 'text')}` : inline(l, base, st, 'text')
    })
    .join('\n')
}
