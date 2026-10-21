#!/usr/bin/env node
// Holt das Präsentations-Paket (index.html + assets/*) in den Messe-Build.
// Aufruf: node scripts/messe/import-pitch.mjs [Quellordner]
// Ziel ist `.messe-pitch/` (nicht im Git) – nur Messe-Builds binden es unter /messe-pitch/ ein (nuxt.config.ts),
// normale Builds sehen den Ordner nie. Ohne (vorhandenen) Quellordner kommt der Platzhalter aus placeholder-pitch/.
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const root = path.resolve(here, '..', '..')
const target = path.join(root, '.messe-pitch')
const placeholder = path.join(here, 'placeholder-pitch')

const wanted = process.argv[2] ? path.resolve(process.argv[2]) : null
let source = placeholder
if (wanted && fs.existsSync(path.join(wanted, 'index.html'))) source = wanted
else if (wanted) console.warn(`[messe] ${wanted} hat keine index.html – nehme den Platzhalter.`)

/** Alle Dateien unter `dir` (relativ). */
function files(dir, base = dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) files(full, base, out)
    else out.push(path.relative(base, full))
  }
  return out
}

// Prüfen: die Präsentation muss offline und unter der strengen CSP laufen (keine fremden Server, keine Wurzelpfade,
// kein Inline-Skript in index.html).
const problems = []
for (const rel of files(source)) {
  if (!/\.(html|css|js|mjs|json|svg)$/i.test(rel)) continue
  const text = fs.readFileSync(path.join(source, rel), 'utf8')
  const remote = text.match(/(?:src|href)\s*=\s*["']https?:\/\/[^"']+|url\(\s*["']?https?:\/\/[^)"']+/gi)
  if (remote) problems.push(`${rel}: lädt aus dem Netz (${remote[0]})`)
  if (/\.html$/i.test(rel)) {
    const rootPaths = text.match(/(?:src|href)\s*=\s*["']\/(?!\/)[^"']*/gi)
    if (rootPaths) problems.push(`${rel}: Wurzelpfad statt relativ (${rootPaths[0]})`)
    if (/<script(?![^>]*\bsrc=)[^>]*>\s*\S/i.test(text)) problems.push(`${rel}: Inline-<script> (CSP verbietet es)`)
  }
}
if (problems.length) {
  console.warn(`[messe] Hinweise zum Präsentations-Paket:\n  ${problems.join('\n  ')}`)
  if (process.argv.includes('--strict')) process.exit(1)
}

fs.rmSync(target, { recursive: true, force: true })
fs.cpSync(source, target, { recursive: true })
fs.writeFileSync(
  path.join(target, '.source.json'),
  JSON.stringify({ source: source === placeholder ? 'placeholder' : source, importedAt: new Date().toISOString() }, null, 2),
)
console.log(`[messe] Präsentation aus ${source === placeholder ? 'dem Platzhalter' : source} → ${path.relative(root, target)} (${files(target).length} Dateien)`)
