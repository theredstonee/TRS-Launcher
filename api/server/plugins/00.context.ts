import { mkdirSync } from 'node:fs'
import { join } from 'node:path'
import { grantPendingRewards } from '../lib/achievements'
import { rotateAttachmentKeys, sweepOrphanFiles, sweepPendingAttachments } from '../lib/attachments'
import { sweepExpired } from '../lib/auth'
import { loadBuiltinCosmetics, loadBuiltins } from '../lib/builtin'
import { seedBuiltins, type BuiltinCape } from '../lib/capes'
import { seedBuiltinCosmetics, seedEmotes, type AnyBuiltinCosmetic } from '../lib/cosmetics'
import { loadPrivateCapes, loadPrivateCosmetics, loadPrivateTemplates, mergeBuiltins, privateAssetsAvailable } from '../lib/private-assets'
import { ConfigError, loadConfig, type Config } from '../lib/config'
import { createContext, setContext, setReady } from '../lib/context'
import { rotateMessageKeys, sweepTyping } from '../lib/chat'
import { openDb } from '../lib/db'
import { sweepApplications } from '../lib/applications'
import { seedCircuits, sweepCircuitSubmissions } from '../lib/circuits'
import { sweepHosting } from '../lib/hosting'
import { setWebpWasmLoader } from '../lib/images'
import { sweepIssues, sweepOrphanIssueFiles } from '../lib/issues'
import { rotateReportKeys, sweepModeration } from '../lib/moderation'
import { sweepExpiredPacks, sweepOrphanPackFiles } from '../lib/packs'
import { sweepPush } from '../lib/push'
import { sweepExpiredShares, sweepOrphanShareFiles } from '../lib/shares'
import { createMojangClient } from '../lib/mojang'
import { afterPresenceChange } from '../lib/playerevents'
import { mergeTemplates, parseTemplates } from '../lib/templates'

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
  if (!config.microsoft) console.warn('[trs-api] MS_CLIENT_ID/MS_CLIENT_SECRET not set – website sign-in with Microsoft is disabled')
  if (!config.hosting) console.warn('[trs-api] RELAY_SECRET/RELAY_HOST not set – world hosting is disabled (503 hosting_unavailable)')
  if (!config.vapid) console.warn('[trs-api] VAPID_* not set – UnifiedPush for the apps is disabled (poll devices still work, §33)')
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
  const circuitAssets = assets('circuits')
  // WebP-Dekoder (libwebp als Wasm) aus den Server-Assets – im Bundle, ohne node_modules-Pfade.
  const codecAssets = assets('codecs')
  setWebpWasmLoader(async () => {
    const wasm = await codecAssets.file('webp_dec.wasm')
    if (!wasm) throw new Error('webp_dec.wasm missing in server assets')
    return wasm
  })

  const warn = (message: string) => console.warn(`[trs-api] ${message}`)
  // Fehlender privater Ordner: eine Warnung, öffentliche Teile laufen weiter. Ein Wurf hier darf den Start nicht beenden.
  let privateReady = false
  try {
    privateReady = privateAssetsAvailable(config.privateAssetsDir, warn)
  } catch {
    warn('private assets failed to load – private built-ins skipped')
  }

  async function start(): Promise<void> {
    // Ohne öffentlichen Katalog nichts einspielen – sonst würden die vorhandenen Zeilen ausgemustert,
    // nur weil die privaten Teile da sind. Ein leerer Katalog zählt genauso als „unverändert“.
    const capeRaw = await capeAssets.json('catalog.json')
    let capes: BuiltinCape[] = []
    if (capeRaw == null) console.warn('[trs-api] assets/capes/catalog.json not found – built-in capes unchanged')
    else {
      const publicCapes = await loadBuiltins(async () => capeRaw, capeAssets.file)
      if (publicCapes.length === 0) console.warn('[trs-api] assets/capes/catalog.json not found – built-in capes unchanged')
      else {
        let priv = { loaded: [] as { item: BuiltinCape, at: number | null }[], reserved: [] as number[] }
        if (privateReady) {
          try {
            priv = loadPrivateCapes(config.privateAssetsDir, warn)
          } catch {
            warn('private capes failed to load – private capes skipped')
          }
        }
        capes = mergeBuiltins(publicCapes, priv.loaded, priv.reserved, warn)
        seedBuiltins(ctx, capes)
      }
    }

    seedEmotes(ctx)
    const templates = await cosmeticAssets.json('templates.json')
    let cosmetics = 0
    if (templates === null) {
      console.warn('[trs-api] assets/cosmetics/templates.json not found – cosmetics disabled, built-ins unchanged')
    } else {
      ctx.templates = parseTemplates(templates)
      if (privateReady) {
        try {
          ctx.templates = mergeTemplates(ctx.templates, loadPrivateTemplates(config.privateAssetsDir, warn), warn)
        } catch {
          warn('private templates failed to load – private templates skipped')
        }
      }
      const cosmeticRaw = await cosmeticAssets.json('catalog.json')
      if (cosmeticRaw == null) console.warn('[trs-api] assets/cosmetics/catalog.json not found – built-in cosmetics unchanged')
      else {
        const list = await loadBuiltinCosmetics(async () => cosmeticRaw, cosmeticAssets.file)
        if (list.length === 0) console.warn('[trs-api] assets/cosmetics/catalog.json not found – built-in cosmetics unchanged')
        else {
          let priv = { loaded: [] as { item: AnyBuiltinCosmetic, at: number | null }[], reserved: [] as number[] }
          if (privateReady) {
            try {
              priv = loadPrivateCosmetics(config.privateAssetsDir, ctx, warn)
            } catch {
              warn('private cosmetics failed to load – private cosmetics skipped')
            }
          }
          const merged = mergeBuiltins(list, priv.loaded, priv.reserved, warn)
          seedBuiltinCosmetics(ctx, merged)
          cosmetics = merged.length
        }
      }
    }
    // Schaltungs-Bibliothek (§25): mitgelieferte Schaltungen einspielen (nur fehlende bzw. unveränderte Seed-Einträge).
    const circuitOrder = await circuitAssets.json('index.json').catch(() => null)
    let circuits = 0
    if (!circuitOrder || typeof circuitOrder !== 'object' || !Array.isArray((circuitOrder as { circuits?: unknown }).circuits)) {
      console.warn('[trs-api] assets/circuits/index.json not found – circuit library unchanged')
    } else {
      const order = ((circuitOrder as { circuits: unknown[] }).circuits).filter((x): x is string => typeof x === 'string' && /^[a-z0-9_]{1,48}$/.test(x))
      const files = new Map<string, unknown>()
      for (const id of order) files.set(id, await circuitAssets.json(`${id}.json`).catch(() => undefined))
      const r = seedCircuits(ctx, { order, files })
      if (r.invalid.length) console.warn(`[trs-api] invalid built-in circuits skipped: ${r.invalid.join(', ')}`)
      circuits = r.inserted + r.updated + r.skipped
    }
    // Erfolge (§31): Belohnungen nachreichen, deren Teil es jetzt gibt (z. B. neu im Katalog).
    const rewards = grantPendingRewards(ctx)
    if (rewards > 0) console.info(`[trs-api] granted ${rewards} pending achievement rewards`)
    console.info(
      `[trs-api] ready – ${circuits} built-in circuits, ${capes.length} built-in capes, ${ctx.templates.list.length} templates, ${cosmetics} built-in cosmetics, data in ${config.dataDir}`,
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
    every(10 * 60_000, () => {
      sweepExpired(ctx)
      sweepExpiredShares(ctx)
      sweepExpiredPacks(ctx)
      // Issues (§28): lose Bilder, gelöschte Issues nach 90 Tagen, Tagesprotokoll.
      sweepIssues(ctx)
      // Erfolge (§31): nachgereichte Belohnungen (billig: nur offene Freischaltungen mit vorhandenem Teil).
      grantPendingRewards(ctx)
    }),
    // Chat: Tipp-Status, Wiederaufnahme-Puffer, Spam-Bremse, nicht verwendete Bilder.
    // Welt-Hosting: Räume ohne Herzschlag schließen.
    every(15_000, () => {
      sweepTyping(ctx)
      ctx.events.sweep()
      sweepHosting(ctx)
      // Push (§33): abgelaufene Abruf-Einträge, alte Stream-Vermerke.
      sweepPush(ctx)
    }),
    every(5 * 60_000, () => {
      ctx.spam.sweep()
      sweepPendingAttachments(ctx)
    }),
    // Aufbewahrung der Meldungen + verwaiste Dateien.
    every(6 * 60 * 60_000, () => {
      sweepModeration(ctx)
      sweepOrphanFiles(ctx)
      // Bewerbungen: Löschfristen (§24.3).
      sweepApplications(ctx)
      sweepOrphanShareFiles(ctx)
      sweepOrphanPackFiles(ctx)
      sweepOrphanIssueFiles(ctx)
      // Schaltungs-Einreichungen: Löschfrist nach der Entscheidung (§25.5).
      sweepCircuitSubmissions(ctx)
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
    ctx.push.stop()
    db.close()
  })
})
