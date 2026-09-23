import type { Config } from './config'
import type { Db } from './db'
import { EventHub } from './events'
import type { MojangClient } from './mojang'
import { PresenceStore } from './presence'
import { RateLimiter } from './ratelimit'
import { SkinService } from './skins'
import { TemplateSet } from './templates'
import { PlayerWatchHub } from './watch'

/** Alles, was die Dienste brauchen – im Server einmal erzeugt, in Tests frisch je Test. */
export interface AppContext {
  config: Config
  db: Db
  mojang: MojangClient
  presence: PresenceStore
  events: EventHub
  /** Spieler-Stream (`GET /v1/events/players`): Emotes, Skin-, Umhang- und Kosmetik-Änderungen. */
  watch: PlayerWatchHub
  limiter: RateLimiter
  skins: SkinService
  /** Kosmetik-Vorlagen (beim Start aus assets/cosmetics/templates.json). */
  templates: TemplateSet
  now: () => number
  /** Ordner für Umhang-PNGs (`<DATA_DIR>/capes`). */
  capeDir: string
  /** Ordner für Kosmetik-PNGs (`<DATA_DIR>/cosmetics`). */
  cosmeticDir: string
}

export function createContext(opts: {
  config: Config
  db: Db
  mojang: MojangClient
  capeDir: string
  cosmeticDir: string
  templates?: TemplateSet
  now?: () => number
}): AppContext {
  const now = opts.now ?? Date.now
  const limiter = new RateLimiter(now)
  const lim = opts.config.limits
  return {
    config: opts.config,
    db: opts.db,
    mojang: opts.mojang,
    presence: new PresenceStore(lim.presenceTtlMs, now),
    events: new EventHub(lim.maxSseStreamsPerUser, lim.maxSseStreamsTotal),
    watch: new PlayerWatchHub(lim.maxPlayerStreamsPerUser, lim.maxPlayerStreamsTotal),
    limiter,
    skins: new SkinService(opts.mojang, limiter, now),
    templates: opts.templates ?? TemplateSet.empty(),
    now,
    capeDir: opts.capeDir,
    cosmeticDir: opts.cosmeticDir,
  }
}

let current: AppContext | undefined
let ready: Promise<void> = Promise.resolve()

export function setContext(ctx: AppContext | undefined): void {
  current = ctx
}

/** Start-Aufgaben (z. B. Katalog einspielen), auf die jede Anfrage wartet. */
export function setReady(p: Promise<void>): void {
  ready = p
}

export function whenReady(): Promise<void> {
  return ready
}

export function useCtx(): AppContext {
  if (!current) throw new Error('App context not initialised')
  return current
}
