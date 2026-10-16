import { HALLOWEEN_END_MS, HALLOWEEN_EVENT } from '../../shared/halloween'
import type { AppContext } from './context'
import { all, one, placeholders } from './db'

/**
 * Profil-Abzeichen und Spuk-Effekte für den Spieler-Lookup (§4.1, §36) – getrennt von halloween.ts, damit lookup.ts
 * keine Kette über playerevents.ts zurück in sich selbst bekommt. Nur Lesen, keine Ereignisse.
 */

export type FxKind = 'hits' | 'trail'

export interface BadgeFx {
  badges: string[]
  fx: FxKind[]
}

/** Profil-Abzeichen eines Kontos (älteste zuerst) – ohne Privatsphäre-Regel. */
export function badgesOf(ctx: AppContext, uuid: string): string[] {
  return all<{ badge: string }>(ctx.db, 'SELECT badge FROM user_badges WHERE uuid = ? ORDER BY granted_at, badge', uuid).map((r) => r.badge)
}

/** Laufen die Halloween-Funktionen gerade für `uuid` (Event aktiv UND vor dem Ende)? */
export function halloweenFeaturesActive(ctx: AppContext, uuid: string): boolean {
  if (ctx.now() >= HALLOWEEN_END_MS) return false
  return one(
    ctx.db,
    `SELECT 1 AS x FROM events e WHERE e.id = ? AND (e.enabled = 1 OR EXISTS
       (SELECT 1 FROM event_players p WHERE p.event_id = e.id AND p.player_uuid = ?))`,
    HALLOWEEN_EVENT, uuid,
  ) !== undefined
}

/** Eigene Freigaben (ohne Zeile: beides an). */
export function effectsOf(ctx: AppContext, uuid: string): { hits: boolean, trail: boolean } {
  const r = one<{ hits: number, trail: number }>(ctx.db, 'SELECT hits, trail FROM halloween_effects WHERE uuid = ?', uuid)
  return { hits: (r?.hits ?? 1) === 1, trail: (r?.trail ?? 1) === 1 }
}

/**
 * `badges` und `fx` für mehrere Spieler. `subjects` = Konten mit `show_badge`; `viewer` sieht seine eigenen Abzeichen
 * immer. `fx` nur, solange die Halloween-Funktionen für den Spieler laufen, und nur die Arten, die er anderen erlaubt.
 */
export function badgeFxFor(
  ctx: AppContext,
  viewer: string,
  subjects: readonly { uuid: string, show_badge: number }[],
): Map<string, BadgeFx> {
  const out = new Map<string, BadgeFx>()
  if (subjects.length === 0) return out
  const uuids = subjects.map((s) => s.uuid)
  for (const s of subjects) out.set(s.uuid, { badges: [], fx: [] })
  const show = new Map(subjects.map((s) => [s.uuid, s.uuid === viewer || s.show_badge === 1]))
  for (const r of all<{ uuid: string, badge: string }>(
    ctx.db,
    `SELECT uuid, badge FROM user_badges WHERE uuid IN (${placeholders(uuids.length)}) ORDER BY granted_at, badge`,
    ...uuids,
  )) {
    if (show.get(r.uuid)) out.get(r.uuid)!.badges.push(r.badge)
  }
  if (ctx.now() >= HALLOWEEN_END_MS) return out
  const ev = one<{ enabled: number }>(ctx.db, 'SELECT enabled FROM events WHERE id = ?', HALLOWEEN_EVENT)
  if (!ev) return out
  const allowed = ev.enabled === 1
    ? new Set(uuids)
    : new Set(all<{ player_uuid: string }>(
        ctx.db,
        `SELECT player_uuid FROM event_players WHERE event_id = ? AND player_uuid IN (${placeholders(uuids.length)})`,
        HALLOWEEN_EVENT, ...uuids,
      ).map((r) => r.player_uuid))
  if (allowed.size === 0) return out
  const prefs = new Map(all<{ uuid: string, hits: number, trail: number }>(
    ctx.db,
    `SELECT uuid, hits, trail FROM halloween_effects WHERE uuid IN (${placeholders(uuids.length)})`,
    ...uuids,
  ).map((r) => [r.uuid, r]))
  for (const u of allowed) {
    const p = prefs.get(u)
    const fx = out.get(u)!.fx
    if ((p?.hits ?? 1) === 1) fx.push('hits')
    if ((p?.trail ?? 1) === 1) fx.push('trail')
  }
  return out
}
