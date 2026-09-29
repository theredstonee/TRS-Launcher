// Dokumentation unter /docs (statisch erzeugt in ../docs-site, beim Build als Public-Assets eingebunden, siehe
// modules/docs.ts). Reine Funktionen für Server (Weiterleitung, Header, Sitemap) und Tests.

import type { SeoLang } from './seo'

export const DOCS_BASE = '/docs'
export const DOCS_LANGS: readonly SeoLang[] = ['en', 'de', 'es']

/** Startseite der Doku in einer Sprache – so wie die Doku selbst ihre kanonischen Adressen schreibt (ohne `/` am Ende). */
export function docsHome(lang: SeoLang): string {
  return `${DOCS_BASE}/${lang}`
}

/** Seite der Doku ohne Sprache → Adresse (`/launcher/instances`, `de` → `/docs/de/launcher/instances`). */
export function docsPath(key: string, lang: SeoLang): string {
  const k = key === '/' ? '' : key.startsWith('/') ? key : `/${key}`
  return `${DOCS_BASE}/${lang}${k}`
}

/** `/docs` oder `/docs/` (auch mit Query) – hier entscheidet der Server über die Sprache. */
export function isDocsRoot(pathname: string): boolean {
  return pathname === DOCS_BASE || pathname === `${DOCS_BASE}/`
}

export function isDocsPath(pathname: string): boolean {
  return pathname === DOCS_BASE || pathname.startsWith(`${DOCS_BASE}/`)
}

/**
 * Sprache für `/docs`: zuerst die auf der Website gewählte (Cookie `trs_lang`), dann `Accept-Language` nach Gewicht,
 * sonst Englisch.
 */
export function pickDocsLang(acceptLanguage: string | null | undefined, cookieLang?: string | null): SeoLang {
  if (cookieLang && (DOCS_LANGS as readonly string[]).includes(cookieLang)) return cookieLang as SeoLang
  const wanted = String(acceptLanguage ?? '')
    .split(',')
    .map((part, index) => {
      const [tag, ...params] = part.trim().split(';')
      const q = params.map((p) => /^\s*q=([0-9.]+)\s*$/.exec(p)?.[1]).find(Boolean)
      return { lang: (tag ?? '').trim().toLowerCase().split('-')[0] ?? '', q: q === undefined ? 1 : Number(q), index }
    })
    .filter((w) => w.lang && Number.isFinite(w.q) && w.q > 0)
    .sort((a, b) => b.q - a.q || a.index - b.index)
  for (const w of wanted) if ((DOCS_LANGS as readonly string[]).includes(w.lang)) return w.lang as SeoLang
  return 'en'
}

/**
 * CSP der Doku-Dateien (als Route-Regel beim Build gesetzt, modules/docs.ts). Wie die Website, aber statt Nonce die
 * SHA-256-Hashes der Inline-Skripte aus dem Docs-Build (docs-site/scripts/finalize.mjs) plus `'self'` für die gebauten
 * Skripte unter /docs/_nuxt. `'wasm-unsafe-eval'` braucht die Suche (SQLite als WebAssembly im Browser) – kein
 * `'unsafe-inline'`, kein `'unsafe-eval'`.
 */
export function buildDocsCsp(scriptHashes: readonly string[]): string {
  const hashes = scriptHashes.filter((h) => /^'sha(256|384|512)-[A-Za-z0-9+/=]+'$/.test(h))
  return [
    "default-src 'self'",
    `script-src 'self' 'wasm-unsafe-eval'${hashes.length ? ` ${hashes.join(' ')}` : ''}`,
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' data: blob: https://raw.githubusercontent.com https://textures.minecraft.net",
    "font-src 'self'",
    "connect-src 'self'",
    "worker-src 'self' blob:",
    "manifest-src 'self'",
    "object-src 'none'",
    "base-uri 'none'",
    "form-action 'self'",
    "frame-ancestors 'none'",
    'upgrade-insecure-requests',
  ].join('; ')
}

/** Eine Seite der Doku aus der Routenliste des Docs-Builds (`.output/docs-meta/routes.json`). */
export interface DocsRoute {
  /** Pfad ohne /docs, z. B. `/en/launcher/instances`. */
  path: string
  lang: SeoLang
  /** Pfad ohne Sprache, gleich in allen Sprachen (`/launcher/instances`, Startseite `/`). */
  key: string
  lastmod?: string | null
}

export function isDocsRoute(v: unknown): v is DocsRoute {
  if (!v || typeof v !== 'object') return false
  const r = v as Record<string, unknown>
  return (
    typeof r.path === 'string' &&
    /^\/(?:en|de|es)(?:\/[a-z0-9][a-z0-9-]*)*$/.test(r.path) &&
    typeof r.key === 'string' &&
    (r.lang === 'en' || r.lang === 'de' || r.lang === 'es') &&
    (r.lastmod === undefined || r.lastmod === null || typeof r.lastmod === 'string')
  )
}

/** Seiten nach Schlüssel gruppiert: je Seite die Sprachen, in denen es sie gibt. */
export function groupDocsRoutes(routes: readonly DocsRoute[]): Map<string, DocsRoute[]> {
  const byKey = new Map<string, DocsRoute[]>()
  for (const r of routes) {
    const list = byKey.get(r.key) ?? []
    if (!list.some((x) => x.lang === r.lang)) list.push(r)
    byKey.set(r.key, list)
  }
  for (const list of byKey.values()) list.sort((a, b) => DOCS_LANGS.indexOf(a.lang) - DOCS_LANGS.indexOf(b.lang))
  return byKey
}
