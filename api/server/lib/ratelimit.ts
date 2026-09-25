/** Regel: höchstens `limit` Anfragen je `windowMs` (Token-Bucket, füllt gleichmäßig nach). */
export interface Rule {
  limit: number
  windowMs: number
}

interface Bucket {
  tokens: number
  updated: number
  rule: Rule
}

export interface TakeResult {
  ok: boolean
  /** Sekunden bis wieder ein Token frei ist (nur bei ok=false sinnvoll). */
  retryAfter: number
}

/**
 * Speicher-Rate-Limiter (ein Prozess, ein Container). Schlüssel enthalten nie
 * Klartext-Tokens; IPs liegen nur flüchtig im RAM.
 */
export class RateLimiter {
  private buckets = new Map<string, Bucket>()
  constructor(
    private readonly now: () => number = Date.now,
    private readonly maxKeys = 200_000,
  ) {}

  private refill(key: string, rule: Rule): Bucket {
    const t = this.now()
    let b = this.buckets.get(key)
    if (!b) {
      if (this.buckets.size >= this.maxKeys) this.sweep(true)
      b = { tokens: rule.limit, updated: t, rule }
      this.buckets.set(key, b)
      return b
    }
    const elapsed = Math.max(0, t - b.updated)
    b.tokens = Math.min(rule.limit, b.tokens + (elapsed * rule.limit) / rule.windowMs)
    b.updated = t
    b.rule = rule
    return b
  }

  private retry(b: Bucket, need: number): number {
    const missing = need - b.tokens
    return Math.max(1, Math.ceil((missing * b.rule.windowMs) / b.rule.limit / 1000))
  }

  /** Verbraucht einen Token. */
  take(key: string, rule: Rule): TakeResult {
    const b = this.refill(key, rule)
    if (b.tokens >= 1) {
      b.tokens -= 1
      return { ok: true, retryAfter: 0 }
    }
    return { ok: false, retryAfter: this.retry(b, 1) }
  }

  /** Prüft, ob noch ein Token da ist, ohne ihn zu verbrauchen (z. B. Fehlversuchs-Zähler). */
  check(key: string, rule: Rule): TakeResult {
    const b = this.refill(key, rule)
    return b.tokens >= 1 ? { ok: true, retryAfter: 0 } : { ok: false, retryAfter: this.retry(b, 1) }
  }

  reset(key: string): void {
    this.buckets.delete(key)
  }

  /** Entfernt volle (also wirkungslose) Buckets. `force` leert bei Überlauf zusätzlich die älteste Hälfte. */
  sweep(force = false): void {
    const t = this.now()
    for (const [k, b] of this.buckets) {
      const tokens = b.tokens + ((t - b.updated) * b.rule.limit) / b.rule.windowMs
      if (tokens >= b.rule.limit) this.buckets.delete(k)
    }
    if (force && this.buckets.size >= this.maxKeys) {
      let drop = Math.ceil(this.buckets.size / 2)
      for (const k of this.buckets.keys()) {
        if (drop-- <= 0) break
        this.buckets.delete(k)
      }
    }
  }

  get size(): number {
    return this.buckets.size
  }
}

const MIN = 60_000
const HOUR = 60 * MIN

/** Alle Limits an einer Stelle (je IP bzw. je Konto). */
export const RULES = {
  globalIp: { limit: 300, windowMs: MIN },
  challengeIp: { limit: 20, windowMs: MIN },
  verifyIp: { limit: 10, windowMs: MIN },
  verifyName: { limit: 10, windowMs: 10 * MIN },
  loginUuid: { limit: 20, windowMs: HOUR },
  readUser: { limit: 120, windowMs: MIN },
  writeUser: { limit: 30, windowMs: MIN },
  lookupUser: { limit: 120, windowMs: MIN },
  presenceUser: { limit: 6, windowMs: MIN },
  friendsWriteUser: { limit: 30, windowMs: MIN },
  redeemUser: { limit: 10, windowMs: MIN },
  redeemFailUser: { limit: 5, windowMs: 15 * MIN },
  redeemFailIp: { limit: 20, windowMs: 15 * MIN },
  uploadUser: { limit: 5, windowMs: 24 * HOUR },
  reportUser: { limit: 10, windowMs: HOUR },
  eventsUser: { limit: 10, windowMs: MIN },
  adminActor: { limit: 240, windowMs: MIN },
  deleteUser: { limit: 3, windowMs: HOUR },
  cosmeticUploadUser: { limit: 5, windowMs: 24 * HOUR },
  emoteUser: { limit: 1, windowMs: 2000 },
  skinChangedUser: { limit: 6, windowMs: MIN },
  skinLookupUser: { limit: 30, windowMs: MIN },
  /** Ausgehende Mojang-Profilabfragen insgesamt (nur Cache-Fehlschläge). */
  mojangGlobal: { limit: 100, windowMs: MIN },
  playerEventsUser: { limit: 20, windowMs: MIN },
  /** Website-Login: Codes anfordern (je IP), bestätigen (je Konto), abfragen (je IP). */
  webLoginStartIp: { limit: 10, windowMs: 10 * MIN },
  webLoginApproveUser: { limit: 10, windowMs: 10 * MIN },
  webLoginPollIp: { limit: 90, windowMs: MIN },
  /** TRS-Sync (`/v1/me/sync*`): alle Anfragen je Konto, dazu Skin-Uploads extra. */
  syncUser: { limit: 120, windowMs: MIN },
  syncUploadUser: { limit: 30, windowMs: MIN },
  /** Umhänge teilen: anbieten, annehmen, ablehnen, entziehen (je Konto). */
  capeShareUser: { limit: 30, windowMs: MIN },
} satisfies Record<string, Rule>
