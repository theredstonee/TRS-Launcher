// Kopf-Kosmetik der Website (Format v2, `GET /v1/site/cosmetics`, API.md §11.9). Darstellung: utils/cosmetic-v2/.

export interface SiteHat {
  id: string
  name: string
  /** Belohnung für einen Erfolg (Titel je Sprache), sonst `null`. */
  achievement: { en: string, de: string, es: string } | null
  unlock: 'free' | 'code' | 'admin' | 'event'
  /** Nur bei Event-Teilen: das Event (z. B. `halloween`). */
  event?: string | null
  slot?: 'hat' | 'companion'
  format: 2
  /** Alle URLs relativ (gleiche Herkunft). */
  model: string
  texture: string
  glow: string | null
  card: string
  cardNight: string
  frames: number
  frameTimeMs: number | null
  glowFrames: number
  glowFrameTimeMs: number | null
  hash: string
  /** Knochen-Animation vorhanden. */
  animated: boolean
}
