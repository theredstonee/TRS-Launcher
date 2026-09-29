import { spawnSync } from 'node:child_process'
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { defineNuxtModule, useLogger } from 'nuxt/kit'
import { buildDocsCsp } from '../shared/docs'

// Bindet die Dokumentation (../docs-site, Docus, statisch erzeugt) unter /docs in diese App ein:
//
// - `nuxt build`: erzeugt die Doku bei Bedarf selbst (`npm run generate` in docs-site, vorher `npm ci`, wenn
//   node_modules fehlt) – nur wenn sie fehlt oder eine Quelldatei neuer ist als der letzte Docs-Build.
//   TRS_DOCS_BUILD=skip überspringt das (dann wird eine vorhandene Ausgabe eingebunden), =force erzwingt es.
// - docs-site/.output/public → Public-Assets unter /docs (Nitro kopiert echte Dateien nach .output/public/docs,
//   keine Symlinks).
// - docs-site/.output/docs-meta/csp.json (Hashes der Inline-Skripte) → CSP als Route-Regel für /docs/**.
// - docs-site/.output/docs-meta/routes.json → Server-Asset `docs` für die Sitemap (server/lib/docs.ts).
// - Cache-Regeln und noindex für Hilfsdateien.
//
// Fehlt docs-site ganz (z. B. Docker-Build nur mit api/), läuft alles ohne Doku weiter (Warnung).

export default defineNuxtModule({
  meta: { name: 'trs-docs' },
  setup(_options, nuxt) {
    const logger = useLogger('trs-docs')
    const docsDir = resolve(nuxt.options.rootDir, '..', 'docs-site')
    const publicDir = join(docsDir, '.output', 'public')
    const metaDir = join(docsDir, '.output', 'docs-meta')
    const isBuild = !nuxt.options.dev && !nuxt.options._prepare && !process.env.VITEST

    if (!existsSync(join(docsDir, 'package.json'))) {
      if (isBuild) logger.warn('docs-site/ fehlt – /docs wird nicht ausgeliefert.')
      return
    }

    if (isBuild) ensureDocsBuilt(docsDir, metaDir, logger)

    if (!existsSync(join(metaDir, 'routes.json')) || !existsSync(publicDir)) {
      if (isBuild) logger.warn('Docs-Build fehlt (docs-site/.output) – /docs wird nicht ausgeliefert. `npm run generate` in docs-site ausführen.')
      return
    }

    nuxt.hook('nitro:config', (config) => {
      config.publicAssets ||= []
      // maxAge 0: Seiten ändern sich mit jedem Build; die Cache-Header setzen die Route-Regeln unten.
      // fallthrough: fehlende Dateien landen bei Nuxt → 404-Seite der Website (HTML) statt JSON-Fehler.
      config.publicAssets.push({ dir: publicDir, baseURL: '/docs', maxAge: 0, fallthrough: true })
      config.serverAssets ||= []
      config.serverAssets.push({ baseName: 'docs', dir: metaDir, pattern: '*.json' })
    })

    // CSP der Doku: statische Dateien laufen am Seiten-Renderer (und an nuxt-security) vorbei – ihre Header kommen nur
    // aus Route-Regeln. Hashes der Inline-Skripte aus dem Docs-Build, sonst wie die Website (shared/docs.ts).
    const cspFile = JSON.parse(readFileSync(join(metaDir, 'csp.json'), 'utf8')) as { scriptHashes?: unknown }
    const hashes = Array.isArray(cspFile.scriptHashes) ? cspFile.scriptHashes.filter((h): h is string => typeof h === 'string') : []
    const docsCsp = buildDocsCsp(hashes)
    // Fehlt eine Datei, rendert Nuxt die 404-Seite der Website – die braucht wieder die Nonce-CSP der Website.
    // nuxt-security würde sonst die CSP-Route-Regel oben auch auf diese Seite anwenden.
    const siteCsp = (nuxt.options as { security?: { headers?: { contentSecurityPolicy?: unknown } } }).security?.headers?.contentSecurityPolicy

    const noindex = { 'X-Robots-Tag': 'noindex' }
    nuxt.options.routeRules = {
      ...nuxt.options.routeRules,
      // Seiten, Payloads, Bilder: kurz zwischenspeichern, danach neu prüfen (ETag). HSTS, X-Frame-Options, nosniff,
      // Referrer-Policy setzt nuxt-security als Route-Regel für alle Dateien (/**).
      '/docs/**': {
        headers: {
          'Content-Security-Policy': docsCsp,
          'Permissions-Policy': 'camera=(), microphone=(), geolocation=(), payment=(), usb=()',
          'Cross-Origin-Resource-Policy': 'same-origin',
          'Cross-Origin-Opener-Policy': 'same-origin',
          'Cache-Control': 'public, max-age=300, stale-while-revalidate=3600',
        },
        ...(siteCsp ? { security: { headers: { contentSecurityPolicy: siteCsp } } } : {}),
      } as Record<string, unknown>,
      // Gebaute Skripte/Stile haben einen Hash im Namen.
      '/docs/_nuxt/**': { headers: { 'Cache-Control': 'public, max-age=31536000, immutable' } },
      // Markdown-Fassungen (für llms.txt), Such-Datenbank und Sprachdateien: abrufbar, aber nicht in den Suchindex.
      '/docs/raw/**': { headers: { ...noindex, 'Cache-Control': 'public, max-age=300' } },
      '/docs/__nuxt_content/**': { headers: { ...noindex, 'Cache-Control': 'public, max-age=300' } },
      '/docs/_i18n/**': { headers: { ...noindex, 'Cache-Control': 'public, max-age=300' } },
      '/docs/llms.txt': { headers: { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'public, max-age=3600' } },
      '/docs/llms-full.txt': { headers: { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'public, max-age=3600' } },
    }
  },
})

/** Neueste Änderungszeit aller Quelldateien der Doku (ohne node_modules und Build-Ordner). */
function newestSource(dir: string): number {
  let newest = 0
  const skip = new Set(['node_modules', '.nuxt', '.output', '.data', '.nitro', '.cache', 'dist'])
  const walk = (d: string) => {
    for (const name of readdirSync(d)) {
      if (skip.has(name)) continue
      const p = join(d, name)
      const st = statSync(p)
      if (st.isDirectory()) walk(p)
      else newest = Math.max(newest, st.mtimeMs)
    }
  }
  walk(dir)
  return newest
}

function ensureDocsBuilt(docsDir: string, metaDir: string, logger: ReturnType<typeof useLogger>): void {
  const mode = (process.env.TRS_DOCS_BUILD ?? 'auto').toLowerCase()
  if (mode === 'skip') return
  const marker = join(metaDir, 'routes.json')
  const fresh = existsSync(marker) && statSync(marker).mtimeMs >= newestSource(docsDir)
  if (mode !== 'force' && fresh) {
    logger.info('Doku ist aktuell (docs-site/.output) – kein neuer Docs-Build nötig.')
    return
  }
  const run = (args: string[]) => {
    // Feste Befehle ohne Eingaben von außen; als eine Zeichenkette über die Shell (npm ist unter Windows eine .cmd).
    const command = `npm ${args.join(' ')}`
    logger.info(`docs-site: ${command}`)
    const r = spawnSync(command, { cwd: docsDir, stdio: 'inherit', shell: true, env: { ...process.env, NODE_ENV: 'production' } })
    if (r.status !== 0) throw new Error(`docs-site: npm ${args.join(' ')} fehlgeschlagen (Exit ${r.status}). Mit TRS_DOCS_BUILD=skip ohne neue Doku bauen.`)
  }
  if (!existsSync(join(docsDir, 'node_modules'))) run(['ci', '--no-audit', '--no-fund'])
  run(['run', 'generate'])
}
