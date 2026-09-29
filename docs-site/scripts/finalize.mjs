// Nach `nuxt generate`: Ausgabe für die Website vorbereiten und prüfen.
//
// 1. Aufräumen: SPA-Fallbacks (200.html, 404.html) und Docus' eigene sitemap.xml (falsche Adressen ohne /docs)
//    entfernen – die Website liefert 404 selbst und führt die Doku in ihrer Sitemap.
// 2. Keine Symlinks/Hardlinks in der Ausgabe (der Deploy-Tar bricht sonst, siehe api/README).
// 3. CSP: SHA-256 aller Inline-Skripte (Nuxt-Konfiguration, Farbschema, Import-Map …) → .output/docs-meta/csp.json.
//    Die Website setzt daraus für /docs/** eine strenge CSP ohne 'unsafe-inline' für Skripte.
// 4. Routenliste für die Website-Sitemap → .output/docs-meta/routes.json (je Seite Sprache, Schlüssel, lastmod).
// 5. Prüfen: jede Seite aus content/ wurde erzeugt, keine Verweise auf fremde Hosts (Iconify-API, Google Fonts),
//    llms.txt vorhanden. Fehlende Bilder sind nur eine Warnung.

import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { existsSync, lstatSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { join, relative, sep } from 'node:path'
import { contentRoutes, ROOT } from '../build/content-routes.mjs'

const OUT = join(ROOT, '.output', 'public')
const META = join(ROOT, '.output', 'docs-meta')
const BASE = '/docs'

const errors = []
const warnings = []

if (!existsSync(OUT)) {
  console.error('[docs] .output/public fehlt – erst `nuxt generate` ausführen.')
  process.exit(1)
}

// 1. Aufräumen
for (const name of ['200.html', '404.html', 'sitemap.xml', 'index.html']) {
  rmSync(join(OUT, name), { force: true })
}

// 2. Dateien einsammeln, Links ablehnen
const files = []
;(function walk(dir) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name)
    const st = lstatSync(p)
    if (st.isSymbolicLink()) errors.push(`Symlink in der Ausgabe: ${relative(OUT, p)}`)
    else if (st.isDirectory()) walk(p)
    else {
      if (st.nlink > 1) errors.push(`Hardlink in der Ausgabe: ${relative(OUT, p)}`)
      files.push(p)
    }
  }
})(OUT)

const rel = (p) => `/${relative(OUT, p).split(sep).join('/')}`
const htmlFiles = files.filter((f) => f.endsWith('.html'))
const pageRoutes = contentRoutes()
const pagePaths = new Set(pageRoutes.map((r) => r.path))

// HTML nacharbeiten (vor dem Hashen – ändert keine Skripte):
// - Nuxt Image setzt beim Vorrendern `onerror="this.setAttribute('data-error', 1)"` an Bilder. Inline-Handler
//   sind unter der CSP verboten (würden als Verstoß gemeldet) und werden nicht gebraucht.
// - Docus verlinkt hreflang-Fassungen in jeder Sprache, auch wenn es die Seite dort (noch) nicht gibt – solche
//   Einträge entfernen, damit Suchmaschinen keine 404-Alternativen sehen.
const NUXT_IMG_ONERROR = / onerror="this\.setAttribute\(&#39;data-error&#39;, ?1\)"/g
const ALTERNATE = /<link rel="alternate" hreflang="([a-zA-Z-]+)" href="https:\/\/[^"/]+\/docs(\/[^"]*)">/g
for (const file of htmlFiles) {
  const before = readFileSync(file, 'utf8')
  const after = before
    .replace(NUXT_IMG_ONERROR, '')
    .replace(ALTERNATE, (tag, _lang, path) => (pagePaths.has(path.replace(/\/+$/, '')) ? tag : ''))
  if (after !== before) writeFileSync(file, after)
}

// llms-full.txt: Platzhalter der Bereichs-Übersichten (Karten entstehen erst auf der Seite) weglassen.
const llmsFull = join(OUT, 'llms-full.txt')
if (existsSync(llmsFull)) {
  writeFileSync(llmsFull, readFileSync(llmsFull, 'utf8').replace(/^:section-pages\s*$/gm, '').replace(/\n{3,}/g, '\n\n'))
}

// 3. Inline-Skripte hashen (alles, was der Browser als Skript ausführt oder als Import-Map liest).
const EXECUTABLE = new Set(['', 'text/javascript', 'application/javascript', 'module', 'importmap'])
const hashes = new Set()
const SCRIPT = /<script\b([^>]*)>([\s\S]*?)<\/script>/gi
for (const file of htmlFiles) {
  const html = readFileSync(file, 'utf8')
  for (const m of html.matchAll(SCRIPT)) {
    const attrs = m[1] ?? ''
    if (/\bsrc\s*=/.test(attrs)) continue
    const type = (/\btype\s*=\s*["']?([^"'\s>]+)/i.exec(attrs)?.[1] ?? '').toLowerCase()
    if (!EXECUTABLE.has(type)) continue
    hashes.add(`'sha256-${createHash('sha256').update(m[2] ?? '', 'utf8').digest('base64')}'`)
  }
  // Inline-Event-Handler würden trotz Hashes blockiert – dürfen nicht vorkommen.
  if (/<[a-z][^>]*\son[a-z]+\s*=/i.test(html.replace(SCRIPT, ''))) errors.push(`Inline-Event-Handler in ${rel(file)} (CSP)`)
}
if (hashes.size > 40) warnings.push(`${hashes.size} verschiedene Inline-Skripte – CSP-Header wird lang, Ursache prüfen`)

// 4. Routen + lastmod (letzter Git-Commit der Datei, sonst Build-Zeit)
const buildTime = new Date().toISOString()
function lastModified(file) {
  try {
    const out = execFileSync('git', ['log', '-1', '--format=%cI', '--', file], { cwd: ROOT, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim()
    return out || buildTime
  } catch {
    return buildTime
  }
}
const routes = pageRoutes.map((r) => ({ path: r.path, lang: r.lang, key: r.key, lastmod: lastModified(r.file) }))

// 5. Prüfungen
const htmlPaths = new Set(htmlFiles.map((f) => rel(f).replace(/\.html$/, '').replace(/\/index$/, '') || '/'))
for (const r of routes) {
  if (!htmlPaths.has(r.path)) errors.push(`Seite fehlt in der Ausgabe: ${r.path} (${relative(ROOT, contentRoutes().find((c) => c.path === r.path)?.file ?? '')})`)
}
const FOREIGN = /https?:\/\/(?:api\.iconify\.design|api\.simplesvg\.com|api\.unisvg\.com|fonts\.googleapis\.com|fonts\.gstatic\.com|fonts\.bunny\.net)/
for (const file of files.filter((f) => /\.(?:html|js|css|json)$/.test(f))) {
  const text = readFileSync(file, 'utf8')
  const hit = FOREIGN.exec(text)
  if (hit) {
    // Nuxt Icon enthält die Iconify-Adressen als Standardwert im Code; zur Laufzeit gesperrt (provider none,
    // fallbackToApi false) – nur in HTML/CSS/JSON wäre ein echter Abruf.
    if (file.endsWith('.js')) warnings.push(`Adresse ${hit[0]} im Code ${rel(file)} (nur Standardwert, wird nicht abgerufen)`)
    else errors.push(`Fremder Host ${hit[0]} in ${rel(file)}`)
  }
}
for (const need of ['/llms.txt', '/llms-full.txt']) {
  if (!existsSync(join(OUT, need))) errors.push(`${need} fehlt`)
}

// Bilder aus Markdown (`/shots/…`), die es nicht gibt: nur warnen.
for (const r of contentRoutes()) {
  const md = readFileSync(r.file, 'utf8')
  for (const m of md.matchAll(/!\[[^\]]*\]\((\/[^)\s]+)\)/g)) {
    const img = m[1]
    if (!existsSync(join(ROOT, 'public', img))) warnings.push(`Bild fehlt: ${img} (in ${relative(ROOT, r.file)})`)
  }
}

mkdirSync(META, { recursive: true })
writeFileSync(join(META, 'csp.json'), `${JSON.stringify({ base: BASE, builtAt: buildTime, scriptHashes: [...hashes].sort() }, null, 2)}\n`)
writeFileSync(join(META, 'routes.json'), `${JSON.stringify({ base: BASE, builtAt: buildTime, routes }, null, 2)}\n`)

const size = files.reduce((n, f) => n + statSync(f).size, 0)
for (const w of warnings) console.warn(`[docs] Warnung: ${w}`)
if (errors.length) {
  for (const e of errors) console.error(`[docs] Fehler: ${e}`)
  process.exit(1)
}
console.log(`[docs] ${routes.length} Seiten, ${files.length} Dateien (${(size / 1024 / 1024).toFixed(1)} MB), ${hashes.size} Skript-Hashes → .output/docs-meta/`)
