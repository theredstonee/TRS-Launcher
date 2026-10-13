import { existsSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import type { H3Event } from 'h3'
import { useCtx, whenReady } from '../lib/context'
import { docsRoutes, docsScriptHashes } from '../lib/docs'
import { clientIp } from '../lib/http'
import { RULES } from '../lib/ratelimit'
import { createWikiHandler, indexWikiDir } from '../lib/wiki'

/**
 * Doku-Domain (https://trs-launcher.wiki): Host-Handler als ERSTE Schicht im h3-Stack – vor Nitros Route-Regeln, dem
 * Static-Handler der Website (Public-Assets) und server/middleware. Nur so kommt auf der Doku-Domain nie eine Datei
 * der Website heraus. Andere Hosts laufen unverändert weiter. Regeln: server/lib/wiki.ts.
 */
export default defineNitroPlugin((nitroApp) => {
  const handler = createWikiHandler(async () => {
    await whenReady()
    const ctx = useCtx()
    const dir = findDocsDir(ctx.config.docsDir)
    if (!dir) console.warn('[trs-wiki] Kein Docs-Build gefunden (.output/docs-wiki) – die Doku-Domain hat keine Seiten.')
    const [routes, scriptHashes] = await Promise.all([docsRoutes(), docsScriptHashes()])
    return {
      hosts: ctx.config.wikiHosts,
      wikiUrl: ctx.config.wikiUrl,
      siteUrl: ctx.config.siteUrl,
      files: dir ? indexWikiDir(dir) : new Map(),
      scriptHashes,
      routes,
      limit: (event: H3Event) => {
        const r = ctx.limiter.take(`ip:${clientIp(event)}`, RULES.globalIp)
        return r.ok ? null : r.retryAfter
      },
    }
  })
  nitroApp.h3App.stack.unshift({ route: '', handler })
})

/** DOCS_DIR, sonst `.output/docs-wiki` neben dem Server (Build), sonst `../docs-site/.output/public` (Entwicklung). */
function findDocsDir(configured: string): string | null {
  if (configured) return existsSync(configured) ? resolve(configured) : null
  const entry = (globalThis as { _importMeta_?: { url?: string } })._importMeta_?.url
  const candidates = [
    entry?.startsWith('file:') ? join(dirname(fileURLToPath(entry)), '..', 'docs-wiki') : null,
    process.argv[1] ? join(dirname(resolve(process.argv[1])), '..', 'docs-wiki') : null,
    resolve(process.cwd(), '.output', 'docs-wiki'),
    resolve(process.cwd(), '..', 'docs-site', '.output', 'public'),
  ]
  return candidates.find((c): c is string => !!c && existsSync(join(c, '404.html'))) ?? null
}
