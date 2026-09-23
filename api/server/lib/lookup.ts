import type { AppContext } from './context'
import { all, placeholders } from './db'
import { capeView, type CapeRow } from './capes'
import { cosmeticView, renderable, type CosmeticRow } from './cosmetics'
import { WEARABLE_SLOTS, type WearableSlot } from './templates'

export interface LookupCape {
  id: string
  url: string
  scale: number
  animated: boolean
  frames: number
  frameTimeMs: number | null
}

export interface LookupCosmetic {
  id: string
  template: string
  url: string
  scale: number
  animated: boolean
  frames: number
  frameTimeMs: number | null
  emissive: boolean
}

export type LookupCosmetics = Record<WearableSlot, LookupCosmetic | null>

export interface LookupEntry {
  uuid: string
  badge: boolean
  cape: LookupCape | null
  cosmetics: LookupCosmetics
}

interface Row {
  uuid: string
  show_badge: number
  show_cape: number
  show_cosmetics: number
  cape_id: string | null
  cape_status: CapeRow['status'] | null
  cape_sha: string | null
  cape_frames: number | null
  cape_frame_time: number | null
  cape_width: number | null
  cape_height: number | null
  cape_name: string | null
  cape_kind: CapeRow['kind'] | null
  cape_unlock: CapeRow['unlock'] | null
}

/** Sichtbar für andere nur freigegeben; man selbst sieht auch den eigenen wartenden Upload. */
const visibleStatus = (status: string, self: boolean) => status === 'approved' || (self && status === 'pending')

export function emptyCosmetics(): LookupCosmetics {
  return { hat: null, wings: null, back: null, aura: null }
}

/**
 * Sichtbare Abzeichen/Umhänge/Kosmetik für `uuids`. `viewer` ist der Fragende
 * (für „selbst“ und Blockaden); `''` bedeutet „irgendein anderer Spieler“ ohne
 * Blockadeprüfung (die macht dann der Aufrufer). Gesperrte fehlen immer.
 */
function collect(ctx: AppContext, viewer: string, uuids: string[]): LookupEntry[] {
  const unique = [...new Set(uuids)]
  if (unique.length === 0) return []
  const rows = all<Row>(
    ctx.db,
    `SELECT u.uuid, u.show_badge, u.show_cape, u.show_cosmetics,
            c.id AS cape_id, c.status AS cape_status, c.sha256 AS cape_sha, c.frames AS cape_frames,
            c.frame_time_ms AS cape_frame_time, c.width AS cape_width, c.height AS cape_height,
            c.name AS cape_name, c.kind AS cape_kind, c.unlock AS cape_unlock
     FROM users u
     LEFT JOIN capes c ON c.id = u.active_cape_id
     WHERE u.uuid IN (${placeholders(unique.length)})
       AND u.uuid NOT IN (SELECT uuid FROM bans)
       AND NOT EXISTS (SELECT 1 FROM blocks b WHERE b.blocker = u.uuid AND b.blocked = ?)`,
    ...unique, viewer,
  )
  if (rows.length === 0) return []
  const equipped = all<CosmeticRow & { eq_uuid: string, eq_slot: WearableSlot }>(
    ctx.db,
    `SELECT c.*, e.uuid AS eq_uuid, e.slot AS eq_slot FROM equipped_cosmetics e
     JOIN cosmetics c ON c.id = e.cosmetic_id
     WHERE e.uuid IN (${placeholders(rows.length)})`,
    ...rows.map((r) => r.uuid),
  )
  const worn = new Map<string, (CosmeticRow & { eq_slot: WearableSlot })[]>()
  for (const e of equipped) {
    const list = worn.get(e.eq_uuid)
    if (list) list.push(e)
    else worn.set(e.eq_uuid, [e])
  }

  const players: LookupEntry[] = []
  for (const r of rows) {
    const self = r.uuid === viewer
    let cape: LookupCape | null = null
    if (r.cape_id && (self || r.show_cape === 1) && visibleStatus(r.cape_status!, self)) {
      const v = capeView(ctx, {
        id: r.cape_id,
        sha256: r.cape_sha!,
        frames: r.cape_frames!,
        frame_time_ms: r.cape_frame_time,
        width: r.cape_width!,
        height: r.cape_height!,
        name: r.cape_name!,
        kind: r.cape_kind!,
        unlock: r.cape_unlock!,
        status: r.cape_status!,
      } as CapeRow)
      cape = { id: v.id, url: v.url, scale: v.scale, animated: v.animated, frames: v.frames, frameTimeMs: v.frameTimeMs }
    }
    const cosmetics = emptyCosmetics()
    let any = false
    if (self || r.show_cosmetics === 1) {
      for (const c of worn.get(r.uuid) ?? []) {
        if (!visibleStatus(c.status, self) || !renderable(ctx, c) || !WEARABLE_SLOTS.includes(c.eq_slot)) continue
        const tex = cosmeticView(ctx, c).texture
        if (!tex || !c.template) continue
        cosmetics[c.eq_slot] = {
          id: c.id,
          template: c.template,
          url: tex.url,
          scale: tex.scale,
          animated: tex.animated,
          frames: tex.frames,
          frameTimeMs: tex.frameTimeMs,
          emissive: c.emissive === 1,
        }
        any = true
      }
    }
    const badge = r.show_badge === 1
    if (!badge && !cape && !any) continue
    players.push({ uuid: r.uuid, badge, cape, cosmetics })
  }
  return players
}

/**
 * Batch-Abfrage für den In-Game-Mod. Nur TRS-Nutzer, die etwas zeigen,
 * erscheinen in der Antwort. Anderen werden nur freigegebene Umhänge und
 * Kosmetik gezeigt; eigene wartende Uploads nur einem selbst. Gesperrte Nutzer
 * und Nutzer, die den Fragenden blockiert haben, fehlen.
 */
export function lookupPlayers(ctx: AppContext, viewer: string, uuids: string[]): { players: LookupEntry[] } {
  return { players: collect(ctx, viewer, uuids) }
}

/** Sicht auf einen einzelnen Spieler – `self` = der Spieler selbst, sonst „ein anderer“ (ohne Blockadeprüfung). */
export function visualsOf(ctx: AppContext, subject: string, self: boolean): LookupEntry {
  return collect(ctx, self ? subject : '', [subject])[0] ?? { uuid: subject, badge: false, cape: null, cosmetics: emptyCosmetics() }
}
