import type { Config } from './config'
import type { Db } from './db'
import { EventHub } from './events'
import type { MojangClient } from './mojang'
import { PresenceStore } from './presence'
import { RateLimiter } from './ratelimit'

/** Alles, was die Dienste brauchen – im Server einmal erzeugt, in Tests frisch je Test. */
export interface AppContext {
  config: Config
  db: Db
  mojang: MojangClient
  presence: PresenceStore
  events: EventHub
  limiter: RateLimiter
  now: () => number
  /** Ordner für Umhang-PNGs (`<DATA_DIR>/capes`). */
  capeDir: string
}

export function createContext(opts: {
  config: Config
  db: Db
  mojang: MojangClient
  capeDir: string
  now?: () => number
}): AppContext {
  const now = opts.now ?? Date.now
  return {
    config: opts.config,
    db: opts.db,
    mojang: opts.mojang,
    presence: new PresenceStore(opts.config.limits.presenceTtlMs, now),
    events: new EventHub(opts.config.limits.maxSseStreamsPerUser, opts.config.limits.maxSseStreamsTotal),
    limiter: new RateLimiter(now),
    now,
    capeDir: opts.capeDir,
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
