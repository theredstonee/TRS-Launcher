// Seiten der Doku aus den Markdown-Dateien: `content/<lang>/<N.bereich>/<N.slug>.md` → `/<lang>/<bereich>/<slug>`.
// Genutzt von nuxt.config.ts (alle Seiten vorrendern, auch wenn kein Link hinführt) und scripts/finalize.mjs
// (Routenliste für die Sitemap der Website).

import { existsSync, readdirSync, statSync } from 'node:fs'
import { dirname, join, relative, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

export const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
export const CONTENT_DIR = join(ROOT, 'content')
export const LANGS = ['en', 'de', 'es']

/** `1.getting-started` → `getting-started` (Nummer nur für die Reihenfolge). */
export function stripNumber(segment) {
  return segment.replace(/^\d+\./, '')
}

/**
 * @typedef {{ path: string, lang: string, key: string, file: string }} ContentRoute
 * `path` = Route ohne /docs (z. B. `/en/launcher/instances`), `key` = derselbe Pfad ohne Sprache (`/launcher/instances`,
 * Startseite `/`) – gleich in allen Sprachen, darüber finden sich die Sprachfassungen einer Seite.
 */

/** @returns {ContentRoute[]} */
export function contentRoutes() {
  /** @type {ContentRoute[]} */
  const out = []
  for (const lang of LANGS) {
    const dir = join(CONTENT_DIR, lang)
    if (!existsSync(dir)) continue
    walk(dir, (file) => {
      if (!file.endsWith('.md')) return
      const rel = relative(dir, file).split(sep)
      const parts = rel.map((p, i) => (i === rel.length - 1 ? stripNumber(p.replace(/\.md$/, '')) : stripNumber(p)))
      if (parts.at(-1) === 'index') parts.pop()
      const key = `/${parts.join('/')}`
      out.push({ path: key === '/' ? `/${lang}` : `/${lang}${key}`, lang, key, file })
    })
  }
  return out.sort((a, b) => a.path.localeCompare(b.path))
}

function walk(dir, fn) {
  for (const name of readdirSync(dir)) {
    if (name.startsWith('.')) continue
    const p = join(dir, name)
    if (statSync(p).isDirectory()) walk(p, fn)
    else fn(p)
  }
}
