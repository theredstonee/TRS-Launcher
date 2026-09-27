import { z } from 'zod'

// Screenshots als Link teilen (API §23): Schemas für die gesäuberten Antworten
// des Kerns und reine Helfer für Galerie-Knopf und „Meine geteilten Bilder“.

const link = z.string().max(300).regex(/^https?:\/\/[^\s]+$/)

export const sharedImageSchema = z.object({
  id: z.string().regex(/^[A-Za-z0-9_-]{22}$/),
  url: link,
  imageUrl: link,
  thumbUrl: link,
  mime: z.enum(['image/jpeg', 'image/png']),
  width: z.number().int().min(0),
  height: z.number().int().min(0),
  bytes: z.number().int().min(0),
  createdAt: z.string().max(40).nullable(),
  expiresAt: z.string().max(40).nullable(),
})

export const shareLimitsSchema = z.object({
  active: z.number().int().min(0),
  maxActive: z.number().int().min(0),
  uploadsToday: z.number().int().min(0),
  maxPerDay: z.number().int().min(0),
})

export const sharesPageSchema = z.object({ shares: z.array(sharedImageSchema), limits: shareLimitsSchema })

export type SharedImage = z.infer<typeof sharedImageSchema>
export type ShareLimits = z.infer<typeof shareLimitsSchema>
export type SharesPage = z.infer<typeof sharesPageSchema>

/** Links gelten 30 Tage (API §23). */
export const SHARE_DAYS = 30

/** Ob Teilen gerade geht – sonst der passende Hinweis statt einer Anfrage. */
export type ShareGate = 'ok' | 'loading' | 'consent' | 'account' | 'banned'

export function shareGate(s: { statusLoaded: boolean; enabled: boolean; hasAccount: boolean; banned: boolean }): ShareGate {
  if (!s.statusLoaded) return 'loading'
  if (!s.enabled) return 'consent'
  if (!s.hasAccount) return 'account'
  if (s.banned) return 'banned'
  return 'ok'
}

/** Ganze Tage bis zum Ablauf (aufgerundet, nie negativ); `null` ohne Datum. */
export function daysLeft(expiresAt: string | null, now = Date.now()): number | null {
  if (!expiresAt) return null
  const end = Date.parse(expiresAt)
  if (Number.isNaN(end)) return null
  return Math.max(0, Math.ceil((end - now) / 86_400_000))
}

/** Grenzen erreicht? Dann ist der Knopf aus und nennt den Grund. */
export function shareBlockedBy(limits: ShareLimits | null): 'active' | 'daily' | null {
  if (!limits) return null
  if (limits.maxActive > 0 && limits.active >= limits.maxActive) return 'active'
  if (limits.maxPerDay > 0 && limits.uploadsToday >= limits.maxPerDay) return 'daily'
  return null
}

/**
 * Laufende Teilen-Vorgänge je Screenshot (`instanz/datei`): verhindert doppeltes
 * Hochladen desselben Bildes und liefert den Zustand für den Knopf.
 */
export class ShareQueue {
  private readonly running = new Set<string>()
  private readonly done = new Map<string, SharedImage>()

  busy(key: string): boolean {
    return this.running.has(key)
  }

  /** Schon in dieser Sitzung geteilt? Dann nicht erneut hochladen, sondern den Link wiederverwenden. */
  shared(key: string, now = Date.now()): SharedImage | null {
    const hit = this.done.get(key)
    if (!hit) return null
    const left = daysLeft(hit.expiresAt, now)
    if (left === 0) {
      this.done.delete(key)
      return null
    }
    return hit
  }

  /** Führt `upload` aus, sofern nicht schon einer läuft; merkt sich den Link. `null` = läuft bereits. */
  async run(key: string, upload: () => Promise<SharedImage>): Promise<SharedImage | null> {
    const known = this.shared(key)
    if (known) return known
    if (this.running.has(key)) return null
    this.running.add(key)
    try {
      const share = await upload()
      this.done.set(key, share)
      return share
    } finally {
      this.running.delete(key)
    }
  }

  /** Nach dem Löschen eines Links: gemerkte Einträge dafür vergessen. */
  forget(id: string): void {
    for (const [k, v] of this.done) if (v.id === id) this.done.delete(k)
  }
}
