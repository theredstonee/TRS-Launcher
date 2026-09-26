import { mkdirSync } from 'node:fs'
import { join } from 'node:path'
import { rotateAttachmentKeys, sweepOrphanFiles, sweepPendingAttachments } from '../lib/attachments'
import { sweepExpired } from '../lib/auth'
import { loadBuiltinCosmetics, loadBuiltins } from '../lib/builtin'
import { seedBuiltins } from '../lib/capes'
import { seedBuiltinCosmetics, seedEmotes } from '../lib/cosmetics'
import { ConfigError, loadConfig, type Config } from '../lib/config'
import { createContext, setContext, setReady } from '../lib/context'
import { rotateMessageKeys, sweepTyping } from '../lib/chat'
import { openDb } from '../lib/db'
import { sweepHosting } from '../lib/hosting'
import { setWebpWasmLoader } from '../lib/images'
import { rotateReportKeys, sweepModeration } from '../lib/moderation'
import { createMojangClient } from '../lib/mojang'
import { afterPresenceChange } from '../lib/playerevents'
import { parseTemplates } from '../lib/templates'

/** Startet die App: Konfiguration prüfen, DB öffnen + migrieren, Katalog einspielen, Aufräum-Timer. */
export default defineNitroPlugin((nitroApp) => {
  let config: Config
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
  const cosmeticDir = join(config.dataDir, 'cosmetics')
  mkdirSync(capeDir, { recursive: true })
  mkdirSync(cosmeticDir, { recursive: true })
  const db = openDb(join(config.dataDir, 'trs.db'))
  const mojang = createMojangClient(config.mojangSessionUrl, fetch, config.mojangApiUrl)
  const ctx = createContext({ config, db, mojang, capeDir, cosmeticDir })
  mkdirSync(join(ctx.chatDir, 'evidence'), { recursive: true })
  setContext(ctx)
  if (!config.hosting) console.warn('[trs-api] RELAY_SECRET/RELAY_HOST not set – world hosting is disabled (503 hosting_unavailable)')
  if (config.chatKeys.derived) {
    console.warn('[trs-api] CHAT_KEYS is not set – chat encryption key is derived from SECRET_KEY (see API.md §18.9)')
  }

  const toBuffer = (v: unknown): Buffer | null =>
    v == null ? null : Buffer.isBuffer(v) ? v : v instanceof Uint8Array ? Buffer.from(v) : typeof v === 'string' ? Buffer.from(v) : null
  const assets = (base: string) => {
    const storage = useStorage(`assets:${base}`)
    return {
      json: async (name: string) => JSON.parse(toBuffer(await storage.getItemRaw(name))?.toString('utf8') ?? 'null') as unknown,
      file: async (name: string) => toBuffer(await storage.getItemRaw(name)),
    }
  }
  const capeAssets = assets('capes')
  const cosmeticAssets = assets('cosmetics')
  // WebP-Dekoder (libwebp als Wasm) aus den Server-Assets – im Bundle, ohne node_modules-Pfade.
  const codecAssets = assets('codecs')
  setWebpWasmLoader(async () => {
    const wasm = await codecAssets.file('webp_dec.wasm')
    if (!wasm) throw new Error('webp_dec.wasm missing in server assets')
    return wasm
  })

  async function start(): Promise<void> {
    const capes = await loadBuiltins(() => capeAssets.json('catalog.json'), capeAssets.file)
    // Ohne Katalog nichts ausmustern – bestehende Einträge bleiben, wie sie sind.
    if (capes.length === 0) console.warn('[trs-api] assets/capes/catalog.json not found – built-in capes unchanged')
    else seedBuiltins(ctx, capes)

    seedEmotes(ctx)
    const templates = await cosmeticAssets.json('templates.json')
    let cosmetics = 0
    if (templates === null) {
      console.warn('[trs-api] assets/cosmetics/templates.json not found – cosmetics disabled, built-ins unchanged')
    } else {
      ctx.templates = parseTemplates(templates)
      const list = await loadBuiltinCosmetics(() => cosmeticAssets.json('catalog.json'), cosmeticAssets.file)
      if (list.length === 0) console.warn('[trs-api] assets/cosmetics/catalog.json not found – built-in cosmetics unchanged')
      else seedBuiltinCosmetics(ctx, list)
      cosmetics = list.length
    }
    console.info(
      `[trs-api] ready – ${capes.length} built-in capes, ${ctx.templates.list.length} templates, ${cosmetics} built-in cosmetics, data in ${config.dataDir}`,
    )
  }
  setReady(
    start().catch((err) => {
      console.error('[trs-api] failed to load built-in capes/cosmetics', err)
      process.exit(1)
    }),
  )

  let rotating = false
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
    // Abgelaufene Präsenz → Freunde bekommen „offline“, Beobachter ggf. „Abzeichen aus“.
    every(30_000, () => {
      for (const { uuid, wasInGame } of ctx.presence.sweepChanges()) afterPresenceChange(ctx, uuid, true, wasInGame)
    }),
    every(60_000, () => ctx.limiter.sweep()),
    every(10 * 60_000, () => sweepExpired(ctx)),
    // Chat: Tipp-Status, Wiederaufnahme-Puffer, Spam-Bremse, nicht verwendete Bilder.
    // Welt-Hosting: Räume ohne Herzschlag schließen.
    every(15_000, () => {
      sweepTyping(ctx)
      ctx.events.sweep()
      sweepHosting(ctx)
    }),
    every(5 * 60_000, () => {
      ctx.spam.sweep()
      sweepPendingAttachments(ctx)
    }),
    // Aufbewahrung der Meldungen + verwaiste Dateien.
    every(6 * 60 * 60_000, () => {
      sweepModeration(ctx)
      sweepOrphanFiles(ctx)
    }),
    // Schlüsseltausch: alte Chat-Daten nach und nach mit dem aktiven Schlüssel neu verschlüsseln.
    every(60_000, () => {
      const n = rotateMessageKeys(ctx, 500) + rotateReportKeys(ctx, 200) + rotateAttachmentKeys(ctx, 20)
      if (n > 0) console.info(`[trs-api] chat key rotation: re-encrypted ${n} items`)
      else if (rotating) console.info('[trs-api] chat key rotation done')
      rotating = n > 0
    }),
  ]

  nitroApp.hooks.hook('close', () => {
    for (const t of timers) clearInterval(t)
    db.close()
  })
})
