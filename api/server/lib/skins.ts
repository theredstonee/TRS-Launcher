import { notFound, tooMany, upstreamFailed } from './errors'
import { MojangUnavailable, type MojangClient, type MojangProfile, type SkinProfile } from './mojang'
import { RULES, type RateLimiter } from './ratelimit'

export interface SkinView {
  uuid: string
  name: string
  model: 'classic' | 'slim'
  /** `https://textures.minecraft.net/texture/<hash>` oder `null` = Standard-Skin (nach UUID wählen wie Vanilla). */
  textureUrl: string | null
  capeUrl: string | null
}

interface Entry<T> {
  value: T
  expires: number
}

const NAME_TTL_MS = 10 * 60_000
const PROFILE_TTL_MS = 2 * 60_000
const MISS_TTL_MS = 2 * 60_000
const MAX_ENTRIES = 5000

/**
 * Skin-Abfrage über Mojang mit Zwischenspeicher, damit Launcher und Mod Mojang
 * nicht einzeln fragen. Gleichzeitige Anfragen für denselben Schlüssel teilen
 * sich einen Mojang-Aufruf; insgesamt gilt ein globales Ausgangs-Limit.
 */
export class SkinService {
  private names = new Map<string, Entry<MojangProfile | null>>()
  private profiles = new Map<string, Entry<SkinProfile | null>>()
  private inflight = new Map<string, Promise<unknown>>()

  constructor(
    private readonly mojang: MojangClient,
    private readonly limiter: RateLimiter,
    private readonly now: () => number,
  ) {}

  private cached<T>(m: Map<string, Entry<T>>, key: string): Entry<T> | undefined {
    const e = m.get(key)
    if (e && e.expires > this.now()) return e
    if (e) m.delete(key)
    return undefined
  }

  private store<T>(m: Map<string, Entry<T>>, key: string, value: T, ttl: number): void {
    m.delete(key)
    if (m.size >= MAX_ENTRIES) m.delete(m.keys().next().value!)
    m.set(key, { value, expires: this.now() + ttl })
  }

  private async fetchOnce<T>(key: string, fn: () => Promise<T>): Promise<T> {
    const running = this.inflight.get(key)
    if (running) return running as Promise<T>
    const r = this.limiter.take('mojang:global', RULES.mojangGlobal)
    if (!r.ok) throw tooMany(r.retryAfter)
    const p = (async () => {
      try {
        return await fn()
      } catch (err) {
        if (err instanceof MojangUnavailable) {
          console.warn('[skins] Mojang lookup failed:', err.message)
          throw upstreamFailed()
        }
        throw err
      } finally {
        this.inflight.delete(key)
      }
    })()
    this.inflight.set(key, p)
    return p
  }

  async byUuid(uuid: string): Promise<SkinView> {
    let hit = this.cached(this.profiles, uuid)
    if (!hit) {
      const value = await this.fetchOnce(`p:${uuid}`, () => this.mojang.skinProfile(uuid))
      this.store(this.profiles, uuid, value, value ? PROFILE_TTL_MS : MISS_TTL_MS)
      hit = { value, expires: 0 }
    }
    const p = hit.value
    if (!p) throw notFound('player_not_found', 'No Minecraft account with this UUID')
    return { uuid: p.uuid, name: p.name, model: p.model, textureUrl: p.skinUrl, capeUrl: p.capeUrl }
  }

  async byName(name: string): Promise<SkinView> {
    const key = name.toLowerCase()
    let hit = this.cached(this.names, key)
    if (!hit) {
      const value = await this.fetchOnce(`n:${key}`, () => this.mojang.profileByName(name))
      this.store(this.names, key, value, value ? NAME_TTL_MS : MISS_TTL_MS)
      hit = { value, expires: 0 }
    }
    if (!hit.value) throw notFound('player_not_found', 'No Minecraft account with this name')
    return this.byUuid(hit.value.uuid)
  }

  /** Nach `POST /v1/me/skin-changed`: nächste Abfrage geht wieder zu Mojang. */
  invalidate(uuid: string): void {
    this.profiles.delete(uuid)
  }
}
