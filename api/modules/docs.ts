import { spawnSync } from 'node:child_process'
import { cpSync, existsSync, readdirSync, rmSync, statSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { defineNuxtModule, useLogger } from 'nuxt/kit'

// Bindet die Dokumentation (../docs-site, Docus, statisch erzeugt) für die Doku-Domain https://trs-launcher.wiki ein:
//
// - `nuxt build`: erzeugt die Doku bei Bedarf selbst (`npm run generate` in docs-site, vorher `npm ci`, wenn
//   node_modules fehlt) – nur wenn sie fehlt oder eine Quelldatei neuer ist als der letzte Docs-Build.
//   TRS_DOCS_BUILD=skip überspringt das (dann wird eine vorhandene Ausgabe eingebunden), =force erzwingt es.
// - docs-site/.output/public → `.output/docs-wiki` (echte Dateien, keine Links). Bewusst KEINE Public-Assets: die
//   liefert Nitro auf jedem Host aus, und Doku und Website haben gleiche Pfade (/icon.png, /shots/, /llms.txt).
//   Ausgeliefert wird nur über den Host-Handler server/lib/wiki.ts (Plugin server/plugins/02.wiki.ts).
// - docs-site/.output/docs-meta/{csp,routes}.json → Server-Asset `docs` (CSP-Hashes, Sitemap der Doku-Domain).
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
      if (isBuild) logger.warn('docs-site/ fehlt – die Doku-Domain liefert nichts aus.')
      return
    }

    if (isBuild) ensureDocsBuilt(docsDir, metaDir, logger)

    if (!existsSync(join(metaDir, 'routes.json')) || !existsSync(publicDir)) {
      if (isBuild) logger.warn('Docs-Build fehlt (docs-site/.output) – die Doku-Domain liefert nichts aus. `npm run generate` in docs-site ausführen.')
      return
    }

    nuxt.hook('nitro:config', (config) => {
      config.serverAssets ||= []
      config.serverAssets.push({ baseName: 'docs', dir: metaDir, pattern: '*.json' })
    })

    // Nach dem Kopieren der Public-Assets: Doku neben den Server legen (.output/docs-wiki), ohne Symlinks.
    nuxt.hook('nitro:build:public-assets', (nitro) => {
      const target = join(nitro.options.output.dir, 'docs-wiki')
      rmSync(target, { recursive: true, force: true })
      cpSync(publicDir, target, { recursive: true, dereference: true })
      logger.info(`Doku → ${target}`)
    })
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
  if (!existsSync(join(docsDir, 'node_modules'))) run(['install', '--no-audit', '--no-fund'])
  run(['run', 'generate'])
}
