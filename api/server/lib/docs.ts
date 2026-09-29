import { isDocsRoute, type DocsRoute } from '../../shared/docs'

// Seitenliste des Docs-Builds (docs-site/.output/docs-meta/routes.json, als Server-Asset `docs` gebündelt –
// modules/docs.ts) für die Sitemap. Fehlt der Docs-Build, gibt es keine Doku-Einträge.

let cached: Promise<DocsRoute[]> | null = null

async function load(): Promise<DocsRoute[]> {
  try {
    const raw = await useStorage('assets:docs').getItemRaw('routes.json')
    if (raw == null) return []
    const text = typeof raw === 'string' ? raw : Buffer.from(raw as Uint8Array).toString('utf8')
    const data = JSON.parse(text) as { routes?: unknown }
    return Array.isArray(data.routes) ? data.routes.filter(isDocsRoute) : []
  } catch {
    return []
  }
}

/** Einmal je Prozess gelesen (die Datei ändert sich nur mit einem neuen Build). */
export function docsRoutes(): Promise<DocsRoute[]> {
  cached ??= load()
  return cached
}
