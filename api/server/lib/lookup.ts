import type { AppContext } from './context'
import { all, placeholders } from './db'
import { capeView, type CapeRow } from './capes'

export interface LookupCape {
  id: string
  url: string
  scale: number
  animated: boolean
  frames: number
  frameTimeMs: number | null
}

export interface LookupEntry {
  uuid: string
  badge: boolean
  cape: LookupCape | null
}

interface Row {
  uuid: string
  show_badge: number
  show_cape: number
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

/**
 * Batch-Abfrage für den In-Game-Mod. Nur TRS-Nutzer, die etwas zeigen wollen,
 * erscheinen in der Antwort. Anderen werden nur freigegebene Umhänge gezeigt;
 * der eigene (auch wartende) Upload nur einem selbst. Gesperrte Nutzer und
 * Nutzer, die den Fragenden blockiert haben, fehlen.
 */
export function lookupPlayers(ctx: AppContext, viewer: string, uuids: string[]): { players: LookupEntry[] } {
  const unique = [...new Set(uuids)]
  if (unique.length === 0) return { players: [] }
  const rows = all<Row>(
    ctx.db,
    `SELECT u.uuid, u.show_badge, u.show_cape,
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
  const players: LookupEntry[] = []
  for (const r of rows) {
    const self = r.uuid === viewer
    let cape: LookupCape | null = null
    if (r.cape_id && (self || r.show_cape === 1)) {
      const visible = r.cape_status === 'approved' || (self && r.cape_status === 'pending')
      if (visible) {
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
    }
    const badge = r.show_badge === 1
    if (!badge && !cape) continue
    players.push({ uuid: r.uuid, badge, cape })
  }
  return { players }
}
