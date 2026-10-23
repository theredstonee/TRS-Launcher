import { audit } from './audit'
import type { AppContext } from './context'
import { all, one, run, tx } from './db'
import { badRequest, conflict, notFound } from './errors'
import { normalizeUuid } from './ids'

/**
 * Events (API.md §32, erstes: `halloween`). Das Team schaltet ein Event global an/aus und kann einzelne Spieler
 * zusätzlich freigeben (auch wenn es global aus ist). Für einen Spieler ist ein Event aktiv, wenn es global an ist
 * ODER er in `event_players` steht. Event-Kosmetik ist dann gratis abholbar (`POST …/claim`) und bleibt danach.
 * Zusätzlich kann das Team für einzelne Spieler nur den LOOK abschalten (`event_look_blocks`, §32.6) – Features bleiben.
 */

export const EVENT_ID = /^[a-z][a-z0-9_]{0,31}$/

export interface AdminEventView {
  id: string
  enabled: boolean
  updatedAt: string | null
  players: { uuid: string, name: string | null, addedAt: string }[]
  /** Spieler, für die das Team nur den LOOK dieses Events abgeschaltet hat (Features bleiben, §32.6). */
  lookBlocked: { uuid: string, name: string | null, addedAt: string }[]
}

/** IDs der global aktiven Events (für `GET /v1/events` und anonyme Sichten). */
export function globalEvents(ctx: AppContext): string[] {
  return all<{ id: string }>(ctx.db, 'SELECT id FROM events WHERE enabled = 1 ORDER BY id').map((r) => r.id)
}

/** Events, die für `uuid` aktiv sind (global an ODER freigegeben), sortiert. */
export function activeEventsFor(ctx: AppContext, uuid: string): string[] {
  return all<{ id: string }>(
    ctx.db,
    `SELECT e.id FROM events e
     WHERE e.enabled = 1 OR EXISTS (SELECT 1 FROM event_players p WHERE p.event_id = e.id AND p.player_uuid = ?)
     ORDER BY e.id`,
    uuid,
  ).map((r) => r.id)
}

/**
 * Events, deren LOOK (Theme, Szenen, Effekte, Spuk) `uuid` sehen soll (§32.6): `activeEventsFor` minus Team-Sperrliste
 * `event_look_blocks`. Features (Kürbis-Suche, Süßes oder Saures, Abholen …) hängen weiter nur an `activeEventsFor`.
 */
export function lookEventsFor(ctx: AppContext, uuid: string): string[] {
  const blocked = new Set(
    all<{ event_id: string }>(ctx.db, 'SELECT event_id FROM event_look_blocks WHERE player_uuid = ?', uuid).map((r) => r.event_id),
  )
  const active = activeEventsFor(ctx, uuid)
  return blocked.size === 0 ? active : active.filter((id) => !blocked.has(id))
}

export function eventActiveFor(ctx: AppContext, uuid: string | null, eventId: string): boolean {
  if (uuid === null) return one(ctx.db, 'SELECT 1 AS x FROM events WHERE id = ? AND enabled = 1', eventId) !== undefined
  return activeEventsFor(ctx, uuid).includes(eventId)
}

export function adminEvents(ctx: AppContext): AdminEventView[] {
  const events = all<{ id: string, enabled: number, updated_at: number | null }>(ctx.db, 'SELECT id, enabled, updated_at FROM events ORDER BY id')
  type Row = { event_id: string, player_uuid: string, player_name: string | null, added_at: number }
  const players = all<Row>(
    ctx.db, 'SELECT event_id, player_uuid, player_name, added_at FROM event_players ORDER BY added_at, player_uuid',
  )
  const blocks = all<Row>(
    ctx.db, 'SELECT event_id, player_uuid, player_name, added_at FROM event_look_blocks ORDER BY added_at, player_uuid',
  )
  const of = (rows: Row[], id: string) => rows
    .filter((p) => p.event_id === id)
    .map((p) => ({ uuid: p.player_uuid, name: p.player_name, addedAt: new Date(p.added_at).toISOString() }))
  return events.map((e) => ({
    id: e.id,
    enabled: e.enabled === 1,
    updatedAt: e.updated_at === null ? null : new Date(e.updated_at).toISOString(),
    players: of(players, e.id),
    lookBlocked: of(blocks, e.id),
  }))
}

function requireEvent(ctx: AppContext, id: string): void {
  if (one(ctx.db, 'SELECT 1 AS x FROM events WHERE id = ?', id) === undefined) throw notFound('event_not_found', 'Event not found')
}

/** Neuen Stand an Spieler schicken (`uuids`; ohne Angabe an alle mit offenem Stream). */
function pushEvents(ctx: AppContext, uuids?: string[]): void {
  for (const uuid of uuids ?? ctx.events.listeningUsers()) {
    if (!ctx.events.wants(uuid)) continue
    ctx.events.publish(uuid, { type: 'events_changed', events: activeEventsFor(ctx, uuid), look: lookEventsFor(ctx, uuid) }, { meOnly: true })
  }
}

export function setEventEnabled(ctx: AppContext, actor: string, id: string, enabled: boolean): void {
  requireEvent(ctx, id)
  const changed = run(
    ctx.db,
    'UPDATE events SET enabled = ?, updated_at = ?, updated_by = ? WHERE id = ? AND enabled <> ?',
    enabled ? 1 : 0, ctx.now(), actor, id, enabled ? 1 : 0,
  )
  if (changed === 0) return
  audit(ctx, actor, enabled ? 'event.enable' : 'event.disable', null, id, `event:${id}`)
  pushEvents(ctx)
}

export function addEventPlayer(ctx: AppContext, actor: string, id: string, uuidRaw: string, name: string | null): void {
  requireEvent(ctx, id)
  const uuid = normalizeUuid(uuidRaw)
  if (!uuid) throw badRequest('invalid_uuid', 'Invalid player UUID')
  const added = tx(ctx.db, () => {
    const n = run(
      ctx.db,
      `INSERT INTO event_players (event_id, player_uuid, player_name, added_at, added_by) VALUES (?, ?, ?, ?, ?)
       ON CONFLICT(event_id, player_uuid) DO NOTHING`,
      id, uuid, name, ctx.now(), actor,
    )
    if (n > 0) run(ctx.db, 'UPDATE events SET updated_at = ?, updated_by = ? WHERE id = ?', ctx.now(), actor, id)
    return n > 0
  })
  if (!added) throw conflict('already_added', 'This player is already on the list')
  audit(ctx, actor, 'event.player_add', uuid, name ? `${id}: ${name}` : id, `event:${id}`)
  pushEvents(ctx, [uuid])
}

export function removeEventPlayer(ctx: AppContext, actor: string, id: string, uuidRaw: string): void {
  requireEvent(ctx, id)
  const uuid = normalizeUuid(uuidRaw)
  if (!uuid) throw notFound('player_not_found', 'Player is not on the list')
  const n = tx(ctx.db, () => {
    const c = run(ctx.db, 'DELETE FROM event_players WHERE event_id = ? AND player_uuid = ?', id, uuid)
    if (c > 0) run(ctx.db, 'UPDATE events SET updated_at = ?, updated_by = ? WHERE id = ?', ctx.now(), actor, id)
    return c
  })
  if (n === 0) throw notFound('player_not_found', 'Player is not on the list')
  audit(ctx, actor, 'event.player_remove', uuid, id, `event:${id}`)
  pushEvents(ctx, [uuid])
}

/** Look des Events für einen Spieler abschalten (§32.6). Features bleiben. `409 already_added`, wenn schon gesperrt. */
export function addEventLookBlock(ctx: AppContext, actor: string, id: string, uuidRaw: string, name: string | null): void {
  requireEvent(ctx, id)
  const uuid = normalizeUuid(uuidRaw)
  if (!uuid) throw badRequest('invalid_uuid', 'Invalid player UUID')
  const added = tx(ctx.db, () => {
    const n = run(
      ctx.db,
      `INSERT INTO event_look_blocks (event_id, player_uuid, player_name, added_at, added_by) VALUES (?, ?, ?, ?, ?)
       ON CONFLICT(event_id, player_uuid) DO NOTHING`,
      id, uuid, name, ctx.now(), actor,
    )
    if (n > 0) run(ctx.db, 'UPDATE events SET updated_at = ?, updated_by = ? WHERE id = ?', ctx.now(), actor, id)
    return n > 0
  })
  if (!added) throw conflict('already_added', 'This player is already on the list')
  audit(ctx, actor, 'event.look_block_add', uuid, name ? `${id}: ${name}` : id, `event:${id}`)
  pushEvents(ctx, [uuid])
}

/** Look-Sperre aufheben (§32.6). `404 player_not_found`, wenn der Spieler nicht gesperrt war. */
export function removeEventLookBlock(ctx: AppContext, actor: string, id: string, uuidRaw: string): void {
  requireEvent(ctx, id)
  const uuid = normalizeUuid(uuidRaw)
  if (!uuid) throw notFound('player_not_found', 'Player is not on the list')
  const n = tx(ctx.db, () => {
    const c = run(ctx.db, 'DELETE FROM event_look_blocks WHERE event_id = ? AND player_uuid = ?', id, uuid)
    if (c > 0) run(ctx.db, 'UPDATE events SET updated_at = ?, updated_by = ? WHERE id = ?', ctx.now(), actor, id)
    return c
  })
  if (n === 0) throw notFound('player_not_found', 'Player is not on the list')
  audit(ctx, actor, 'event.look_block_remove', uuid, id, `event:${id}`)
  pushEvents(ctx, [uuid])
}
