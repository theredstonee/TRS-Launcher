import { mkdirSync } from 'node:fs'
import { join } from 'node:path'
import { sweepExpired } from '../lib/auth'
import { loadBuiltins } from '../lib/builtin'
import { seedBuiltins } from '../lib/capes'
import { ConfigError, loadConfig } from '../lib/config'
import { createContext, setContext, setReady } from '../lib/context'
import { openDb } from '../lib/db'
import { broadcastPresence } from '../lib/friends'
import { createMojangClient } from '../lib/mojang'

/** Startet die App: Konfiguration prüfen, DB öffnen + migrieren, Katalog einspielen, Aufräum-Timer. */
export default defineNitroPlugin((nitroApp) => {
  let config
  try {
    config = loadConfig(process.env)
  } catch (err) {
    if (err instanceof ConfigError) {
      console.error(`[trs-api] ${err.message}`)
      process.exit(1)
    }
    throw err
  }
  const capeDir = join(config.dataDir, 'capes')
  mkdirSync(capeDir, { recursive: true })
  const db = openDb(join(config.dataDir, 'trs.db'))
  const ctx = createContext({ config, db, mojang: createMojangClient(config.mojangSessionUrl), capeDir })
  setContext(ctx)

  const storage = useStorage('assets:capes')
  const toBuffer = (v: unknown): Buffer | null =>
    v == null ? null : Buffer.isBuffer(v) ? v : v instanceof Uint8Array ? Buffer.from(v) : typeof v === 'string' ? Buffer.from(v) : null
  setReady(
    loadBuiltins(
      async () => JSON.parse(toBuffer(await storage.getItemRaw('catalog.json'))?.toString('utf8') ?? 'null'),
      async (name) => toBuffer(await storage.getItemRaw(name)),
    ).then((capes) => {
      // Ohne Katalog nichts ausmustern – bestehende Einträge bleiben, wie sie sind.
      if (capes.length === 0) console.warn('[trs-api] assets/capes/catalog.json not found – built-in capes unchanged')
      else seedBuiltins(ctx, capes)
      console.info(`[trs-api] ready – ${capes.length} built-in capes, data in ${config.dataDir}`)
    }).catch((err) => {
      console.error('[trs-api] failed to load built-in capes', err)
      process.exit(1)
    }),
  )

  const every = (ms: number, fn: () => void) => {
    const t = setInterval(() => {
      try {
        fn()
      } catch (err) {
        console.error('[trs-api] background task failed', err)
      }
    }, ms)
    t.unref()
    return t
  }
  const timers = [
    // Abgelaufene Präsenz → Freunde bekommen „offline“.
    every(30_000, () => {
      for (const uuid of ctx.presence.sweep()) broadcastPresence(ctx, uuid)
    }),
    every(60_000, () => ctx.limiter.sweep()),
    every(10 * 60_000, () => sweepExpired(ctx)),
  ]

  nitroApp.hooks.hook('close', () => {
    for (const t of timers) clearInterval(t)
    db.close()
  })
})
