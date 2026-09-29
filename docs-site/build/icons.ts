// Sammelt alle Icon-Namen (`i-lucide-…`, `i-simple-icons-…`, `i-vscode-icons-…`, auch `lucide:…`), die die Doku
// benutzen kann: aus den Inhalten, den eigenen App-Dateien, dem Docus-Layer und Nuxt UI (Standard-Icons der
// Komponenten). Sie landen im Client-Bundle von @nuxt/icon – so lädt die Seite nie etwas von api.iconify.design
// (die CSP erlaubt das auch nicht).

import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const COLLECTIONS = ['lucide', 'simple-icons', 'vscode-icons'] as const
const EXTENSIONS = /\.(?:vue|ts|mjs|js|md|yml|yaml|json)$/
// `i-lucide-arrow-right` bzw. `lucide:arrow-right`
const PATTERN = new RegExp(`(?:\\bi-(${COLLECTIONS.join('|')})-([a-z0-9]+(?:-[a-z0-9]+)*))|(?:\\b(${COLLECTIONS.join('|')}):([a-z0-9]+(?:-[a-z0-9]+)*))`, 'g')

function walk(dir: string, out: string[]): void {
  if (!existsSync(dir)) return
  for (const name of readdirSync(dir)) {
    if (name === 'node_modules' || name.startsWith('.')) continue
    const p = join(dir, name)
    const st = statSync(p)
    if (st.isDirectory()) walk(p, out)
    else if (EXTENSIONS.test(name) && st.size < 2_000_000) out.push(p)
  }
}

/** Gültige Icon-Namen je Sammlung (aus den installierten @iconify-json-Paketen). */
function knownIcons(collection: string): Set<string> | null {
  for (const base of [join(ROOT, 'node_modules'), join(ROOT, 'node_modules', 'docus', 'node_modules')]) {
    const file = join(base, '@iconify-json', collection, 'icons.json')
    if (!existsSync(file)) continue
    const data = JSON.parse(readFileSync(file, 'utf8')) as { icons: Record<string, unknown>, aliases?: Record<string, unknown> }
    return new Set([...Object.keys(data.icons), ...Object.keys(data.aliases ?? {})])
  }
  return null
}

export function iconsToBundle(): string[] {
  const files: string[] = []
  walk(join(ROOT, 'content'), files)
  walk(join(ROOT, 'app'), files)
  walk(join(ROOT, 'node_modules', 'docus', 'app'), files)
  walk(join(ROOT, 'node_modules', 'docus', 'modules'), files)
  walk(join(ROOT, 'node_modules', '@nuxt', 'ui', 'dist'), files)

  const found = new Set<string>()
  for (const file of files) {
    const text = readFileSync(file, 'utf8')
    for (const m of text.matchAll(PATTERN)) {
      const collection = m[1] ?? m[3]
      const name = m[2] ?? m[4]
      if (collection && name) found.add(`${collection}:${name}`)
    }
  }

  // Nur Namen, die es wirklich gibt (Regex findet auch Teilstücke wie `i-lucide-` in Beispielen).
  const known = new Map(COLLECTIONS.map((c) => [c, knownIcons(c)]))
  return [...found]
    .filter((id) => {
      const [collection, name] = id.split(':') as [string, string]
      const set = known.get(collection as (typeof COLLECTIONS)[number])
      return set ? set.has(name) : true
    })
    .sort()
}
