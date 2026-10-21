#!/usr/bin/env node
// Pitch-Präsentation (§38) ins Repo übernehmen: kopiert ein fertig gebautes Bundle (index.html + assets/…, relative
// Pfade, keine Inline-Skripte) nach api/assets/pitch/. Nitro bündelt den Ordner als Server-Asset `pitch` – die Dateien
// liegen NICHT in public/ und sind nur über einen geheimen Link (/pitch/<token>) oder die Team-Vorschau erreichbar.
//
//   node api/scripts/pitch-import.mjs F:/trs-wt/pitch-dist
//
// Danach neu bauen (`npx nuxt build`). Der alte Inhalt von api/assets/pitch/ wird vollständig ersetzt.

import { cpSync, existsSync, lstatSync, mkdirSync, readdirSync, readFileSync, rmSync } from 'node:fs'
import { dirname, join, relative, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const target = resolve(here, '..', 'assets', 'pitch')
const src = process.argv[2] ? resolve(process.argv[2]) : ''

function fail(msg) {
  console.error(`pitch-import: ${msg}`)
  process.exit(1)
}

if (!src) fail('Aufruf: node api/scripts/pitch-import.mjs <bundle-ordner>')
if (!existsSync(src) || !lstatSync(src).isDirectory()) fail(`Ordner fehlt: ${src}`)
if (!existsSync(join(src, 'index.html'))) fail(`${src} enthält keine index.html`)
if (resolve(src) === target) fail('Quelle und Ziel sind derselbe Ordner')

// Nur einfache Namen: der Server schlägt Pfade in einer Tabelle nach (Schlüssel mit „:“ als Trenner) – Sonderzeichen,
// versteckte Dateien und Symlinks gehören nicht in ein Präsentations-Bundle.
const SEGMENT = /^[A-Za-z0-9_-][A-Za-z0-9._-]{0,127}$/
const files = []
let total = 0
const walk = (dir) => {
  for (const ent of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, ent.name)
    const rel = relative(src, p).split(sep).join('/')
    if (ent.name.startsWith('.')) {
      console.warn(`pitch-import: überspringe versteckte Datei ${rel}`)
      continue
    }
    if (ent.isSymbolicLink()) fail(`Symlink nicht erlaubt: ${rel}`)
    if (!SEGMENT.test(ent.name)) fail(`Ungültiger Dateiname (nur A-Z a-z 0-9 . _ -): ${rel}`)
    if (ent.isDirectory()) walk(p)
    else if (ent.isFile()) {
      const size = lstatSync(p).size
      total += size
      files.push({ rel, size })
    }
  }
}
walk(src)

// Inline-Skripte würde die CSP der Pitch-Seiten (script-src 'self') blockieren.
const html = readFileSync(join(src, 'index.html'), 'utf8')
const inline = [...html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/gi)].filter((m) => !/\bsrc\s*=/.test(m[1]) && m[2].trim() && !/type\s*=\s*["']?(application\/(ld\+)?json|importmap)/i.test(m[1]))
if (inline.length) console.warn(`pitch-import: WARNUNG – index.html enthält ${inline.length} Inline-Skript(e); die CSP blockiert sie.`)
if (/\s(src|href)\s*=\s*["']\/(?!\/)/i.test(html)) console.warn('pitch-import: WARNUNG – index.html enthält absolute Pfade („/…“); nur relative Pfade funktionieren unter /pitch/<token>/.')

const MB = 1024 * 1024
if (total > 30 * MB) console.warn(`pitch-import: WARNUNG – Bundle ist ${(total / MB).toFixed(1)} MB groß; Server-Assets landen im Server-Bundle.`)

rmSync(target, { recursive: true, force: true })
mkdirSync(target, { recursive: true })
for (const f of files) {
  const to = join(target, ...f.rel.split('/'))
  mkdirSync(dirname(to), { recursive: true })
  cpSync(join(src, ...f.rel.split('/')), to)
}
console.log(`pitch-import: ${files.length} Dateien (${(total / 1024).toFixed(0)} KB) → ${relative(process.cwd(), target) || target}`)
