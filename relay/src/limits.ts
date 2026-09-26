/** Token-Bucket: `capacity` Tokens, füllt mit `perMs` Tokens je Millisekunde nach. */
export class TokenBucket {
  tokens: number
  updated: number
  readonly capacity: number
  readonly perMs: number

  constructor(capacity: number, perMs: number, now: number) {
    this.capacity = capacity
    this.perMs = perMs
    this.tokens = capacity
    this.updated = now
  }

  refill(now: number): void {
    const elapsed = Math.max(0, now - this.updated)
    this.tokens = Math.min(this.capacity, this.tokens + elapsed * this.perMs)
    this.updated = now
  }

  /** Nimmt `n` Tokens, wenn vorhanden. */
  take(now: number, n = 1): boolean {
    this.refill(now)
    if (this.tokens < n) return false
    this.tokens -= n
    return true
  }

  /** Ist noch ein Token da (ohne zu verbrauchen)? */
  has(now: number, n = 1): boolean {
    this.refill(now)
    return this.tokens >= n
  }

  /**
   * Verbraucht `n` auch auf Pump (Schulden) und liefert die Wartezeit in ms, bis der
   * Kontostand wieder ≥ 0 ist (0 = sofort weiter). Für Datenströme, die man nur bremsen kann.
   */
  consume(now: number, n: number): number {
    this.refill(now)
    this.tokens -= n
    return this.tokens >= 0 ? 0 : Math.ceil(-this.tokens / this.perMs)
  }

  full(now: number): boolean {
    this.refill(now)
    return this.tokens >= this.capacity
  }
}

/** Buckets je Schlüssel (z. B. IP), volle werden beim Aufräumen verworfen. */
export class KeyedLimiter {
  private buckets = new Map<string, TokenBucket>()
  readonly limit: number
  readonly windowMs: number
  private readonly now: () => number

  constructor(limit: number, windowMs: number, now: () => number) {
    this.limit = limit
    this.windowMs = windowMs
    this.now = now
  }

  private get(key: string): TokenBucket {
    let b = this.buckets.get(key)
    if (!b) {
      if (this.buckets.size > 100_000) this.sweep()
      b = new TokenBucket(this.limit, this.limit / this.windowMs, this.now())
      this.buckets.set(key, b)
    }
    return b
  }

  take(key: string): boolean {
    return this.get(key).take(this.now())
  }

  has(key: string): boolean {
    return this.get(key).has(this.now())
  }

  sweep(): void {
    const t = this.now()
    for (const [k, b] of this.buckets) if (b.full(t)) this.buckets.delete(k)
  }

  get size(): number {
    return this.buckets.size
  }
}

/** Bandbreite je Raum: Mbit/s → Bytes/ms, Puffer ¼ s (mind. 16 KiB). */
export function bandwidthBucket(mbit: number, now: number): TokenBucket {
  const bytesPerMs = (mbit * 1_000_000) / 8 / 1000
  return new TokenBucket(Math.max(16 * 1024, bytesPerMs * 250), bytesPerMs, now)
}

/** IPv4-gemappte IPv6-Adressen auf IPv4 kürzen (für Limits je IP). */
export function normalizeIp(addr: string | undefined): string {
  if (!addr) return 'unknown'
  return addr.startsWith('::ffff:') && addr.includes('.') ? addr.slice(7) : addr
}
