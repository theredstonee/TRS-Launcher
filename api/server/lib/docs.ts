import { isDocsRoute, type DocsRoute } from '../../shared/docs'

// Metadaten des Docs-Builds (docs-site/.output/docs-meta/{routes,csp}.json, als Server-Asset `docs` gebündelt –
// modules/docs.ts): Seitenliste für die Sitemap der Doku-Domain und Hashes der Inline-Skripte für ihre CSP.
// Fehlt der Docs-Build, gibt es keine Einträge bzw. keine Hashes.

async function readJson(name: string): Promise<Record<string, unknown> | null> {
  try {
    const raw = await useStorage('assets:docs').getItemRaw(name)
    if (raw == null) return null
    const text = typeof raw === 'string' ? raw : Buffer.from(raw as Uint8Array).toString('utf8')
    const data = JSON.parse(text) as unknown
    return data && typeof data === 'object' ? (data as Record<string, unknown>) : null
  } catch {
    return null
  }
}

let cachedRoutes: Promise<DocsRoute[]> | null = null
let cachedHashes: Promise<string[]> | null = null

/** Seiten der Doku – einmal je Prozess gelesen (die Datei ändert sich nur mit einem neuen Build). */
export function docsRoutes(): Promise<DocsRoute[]> {
  cachedRoutes ??= readJson('routes.json').then((d) => (Array.isArray(d?.routes) ? d.routes.filter(isDocsRoute) : []))
  return cachedRoutes
}

/** SHA-256-Hashes der Inline-Skripte des Docs-Builds (`'sha256-…'`). */
export function docsScriptHashes(): Promise<string[]> {
  cachedHashes ??= readJson('csp.json').then((d) =>
    Array.isArray(d?.scriptHashes) ? d.scriptHashes.filter((h): h is string => typeof h === 'string') : [],
  )
  return cachedHashes
}
