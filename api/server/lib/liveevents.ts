import { audit } from './audit'
import type { AppContext } from './context'
import { all, one, run, tx } from './db'
import { badRequest, conflict, notFound } from './errors'
import { normalizeUuid } from './ids'

/**
 * Events (API.md §32, erstes: `halloween`). Das Team schaltet ein Event global an/aus und kann einzelne Spieler
 * zusätzlich freigeben (auch wenn es global aus ist). Für einen Spieler ist ein Event aktiv, wenn es global an ist
 * ODER er in `event_players` steht. Event-Kosmetik ist dann gratis abholbar (`POST …/claim`) und bleibt danach.
 */

export const EVENT_ID = /^[a-z][a-z0-9_]{0,31}$/

export interface AdminEventView {
  id: string
  enabled: boolean
  updatedAt: string | null
  players: { uuid: string, name: string | null, addedAt: string }[]
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

export function eventActiveFor(ctx: AppContext, uuid: string | null, eventId: string): boolean {
  if (uuid === null) return one(ctx.db, 'SELECT 1 AS x FROM events WHERE id = ? AND enabled = 1', eventId) !== undefined
  return activeEventsFor(ctx, uuid).includes(eventId)
}

export function adminEvents(ctx: AppContext): AdminEventView[] {
  const events = all<{ id: string, enabled: number, updated_at: number | null }>(ctx.db, 'SELECT id, enabled, updated_at FROM events ORDER BY id')
  const players = all<{ event_id: string, player_uuid: string, player_name: string | null, added_at: number }>(
    ctx.db, 'SELECT event_id, player_uuid, player_name, added_at FROM event_players ORDER BY added_at, player_uuid',
  )
  return events.map((e) => ({
    id: e.id,
    enabled: e.enabled === 1,
    updatedAt: e.updated_at === null ? null : new Date(e.updated_at).toISOString(),
    players: players
      .filter((p) => p.event_id === e.id)
      .map((p) => ({ uuid: p.player_uuid, name: p.player_name, addedAt: new Date(p.added_at).toISOString() })),
  }))
}

function requireEvent(ctx: AppContext, id: string): void {
  if (one(ctx.db, 'SELECT 1 AS x FROM events WHERE id = ?', id) === undefined) throw notFound('event_not_found', 'Event not found')
}

/** Neuen Stand an Spieler schicken (`uuids`; ohne Angabe an alle mit offenem Stream). */
function pushEvents(ctx: AppContext, uuids?: string[]): void {
  for (const uuid of uuids ?? ctx.events.listeningUsers()) {
    if (!ctx.events.wants(uuid)) continue
    ctx.events.publish(uuid, { type: 'events_changed', events: activeEventsFor(ctx, uuid) }, { meOnly: true })
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
