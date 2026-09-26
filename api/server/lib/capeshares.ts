import type { AppContext } from './context'
import { all, one, placeholders, run, tx } from './db'
import { badRequest, conflict, notFound } from './errors'
import { capeView, getCape, type CapeRow, type CapeView } from './capes'
import { areFriends, hasBlocked } from './friends'
import { emitCape } from './playerevents'
import { ACTIVE_BANS, getUser, isBanned, type UserRow } from './users'
import { assertNotSanctioned } from './sanctions'

/**
 * Eigene Umhänge mit Freunden teilen.
 *
 * - Teilen kann man nur **freigegebene eigene Uploads** – und einen geteilten Umhang, den man
 *   angenommen hat (Weiterteilen). Ziel ist immer ein Freund (beidseitig, nicht blockiert).
 * - Ablauf: Angebot (`offered`) → der Freund nimmt an (`accepted`, Umhang in seiner Sammlung,
 *   tragbar wie ein freigeschalteter) oder lehnt ab (Zeile weg, der Anbieter erfährt nichts).
 * - `granted_by` bildet je Umhang einen Baum mit dem Ersteller als Wurzel. Wer entzieht, nimmt den
 *   ganzen Ast mit (alles, was der Betroffene weitergeteilt hat). Der Ersteller darf jeden entziehen,
 *   ein Inhaber nur in seinem Ast, und jeder darf seinen eigenen Umhang zurückgeben.
 * - Höchstens `limits.maxCapeHolders` Inhaber je Umhang außer dem Ersteller (angenommen + offen).
 * - Freundschaft endet / Blockade: offene Angebote zwischen beiden fallen weg; angenommene bleiben,
 *   bis jemand sie entzieht. Umhang gelöscht, abgelehnt oder Konto gelöscht → alles weg.
 */

export type ShareStatus = 'offered' | 'accepted'

export interface ShareRow {
  cape_id: string
  holder_uuid: string
  granted_by: string
  status: ShareStatus
  created_at: number
  accepted_at: number | null
}

export interface PlayerRef {
  uuid: string
  name: string
}

/** Offenes Angebot an mich. */
export interface IncomingOffer {
  cape: CapeView
  from: PlayerRef
  /** Wer den Umhang gemacht hat (bei Weiterteilen ≠ `from`). */
  creator: PlayerRef
  createdAt: string
}

/** Mein offenes Angebot an einen Freund. */
export interface OutgoingOffer {
  cape: CapeView
  to: PlayerRef
  createdAt: string
}

export interface HolderView {
  uuid: string
  name: string
  status: ShareStatus
  grantedBy: PlayerRef
  createdAt: string
  acceptedAt: string | null
}

export interface HoldersList {
  holders: HolderView[]
  /** Alle Inhaber dieses Umhangs außer dem Ersteller (angenommen + offen) – zählt gegen `limit`. */
  count: number
  limit: number
}

const iso = (t: number) => new Date(t).toISOString()

/** Hält `uuid` diesen Umhang (angenommen)? */
export function holdsCape(ctx: AppContext, uuid: string, capeId: string): boolean {
  return one(
    ctx.db,
    "SELECT 1 AS x FROM cape_shares WHERE cape_id = ? AND holder_uuid = ? AND status = 'accepted'",
    capeId, uuid,
  ) !== undefined
}

/** Rolle von `uuid` beim Teilen dieses Umhangs: Ersteller, Inhaber (angenommen) oder keine. */
export function shareRole(ctx: AppContext, uuid: string, c: CapeRow): 'creator' | 'holder' | null {
  if (c.kind !== 'upload' || c.status !== 'approved') return null
  if (c.owner_uuid === uuid) return 'creator'
  return holdsCape(ctx, uuid, c.id) ? 'holder' : null
}

/** `root` und alle, denen `root` den Umhang (direkt oder über andere) weitergegeben hat. */
function subtree(ctx: AppContext, capeId: string, root: string): string[] {
  return all<{ u: string }>(
    ctx.db,
    `WITH RECURSIVE t(u) AS (
       SELECT ?
       UNION
       SELECT s.holder_uuid FROM cape_shares s JOIN t ON s.granted_by = t.u WHERE s.cape_id = ?
     )
     SELECT u FROM t`,
    root, capeId,
  ).map((r) => r.u)
}

function countHolders(ctx: AppContext, capeId: string): number {
  return one<{ n: number }>(ctx.db, 'SELECT COUNT(*) AS n FROM cape_shares WHERE cape_id = ?', capeId)!.n
}

/**
 * Zahl der Inhaber, die `uuid` bei diesem Umhang sieht: als Ersteller alle, als Inhaber die in
 * seinem Ast (ohne ihn selbst). Für den Katalog.
 */
export function visibleHolderCount(ctx: AppContext, uuid: string, c: CapeRow): number {
  const role = shareRole(ctx, uuid, c)
  if (role === 'creator') return countHolders(ctx, c.id)
  if (role === 'holder') return subtree(ctx, c.id, uuid).length - 1
  return 0
}

function ref(ctx: AppContext, uuid: string): PlayerRef {
  return { uuid, name: getUser(ctx, uuid)?.name ?? uuid }
}

// ---------------------------------------------------------------- Anbieten

export function offerCape(ctx: AppContext, me: UserRow, capeId: string, friend: string): OutgoingOffer {
  assertNotSanctioned(ctx, me.uuid, 'social_ban')
  const c = getCape(ctx, capeId)
  if (!c) throw notFound('cape_not_found', 'Cape not found')
  const role = shareRole(ctx, me.uuid, c)
  if (!role) {
    if (c.kind === 'upload' && c.owner_uuid === me.uuid) {
      throw conflict('cape_not_approved', 'Only approved capes can be shared')
    }
    if (c.kind === 'builtin') throw badRequest('cape_not_shareable', 'Only your own uploaded capes can be shared')
    throw notFound('cape_not_found', 'Cape not found')
  }
  if (friend === me.uuid) throw badRequest('cannot_target_self', 'You cannot do this with yourself')
  const other = getUser(ctx, friend)
  if (
    !other
    || isBanned(ctx, friend)
    || hasBlocked(ctx, friend, me.uuid)
    || hasBlocked(ctx, me.uuid, friend)
    || !areFriends(ctx, me.uuid, friend)
  ) {
    throw notFound('friend_not_found', 'This player is not your friend')
  }
  if (friend === c.owner_uuid) throw conflict('already_shared', 'This player already has this cape')
  const lim = ctx.config.limits
  const t = ctx.now()
  tx(ctx.db, () => {
    if (one(ctx.db, 'SELECT 1 AS x FROM cape_shares WHERE cape_id = ? AND holder_uuid = ?', c.id, friend)) {
      throw conflict('already_shared', 'This player already has this cape or an offer for it')
    }
    if (countHolders(ctx, c.id) >= lim.maxCapeHolders) {
      throw conflict('share_limit', 'This cape is already shared with the maximum number of players')
    }
    const inbox = one<{ n: number }>(
      ctx.db,
      "SELECT COUNT(*) AS n FROM cape_shares WHERE holder_uuid = ? AND status = 'offered'",
      friend,
    )!.n
    if (inbox >= lim.maxIncomingCapeOffers) {
      throw conflict('offer_inbox_full', 'This player cannot receive more cape offers right now')
    }
    run(
      ctx.db,
      "INSERT INTO cape_shares (cape_id, holder_uuid, granted_by, status, created_at) VALUES (?, ?, ?, 'offered', ?)",
      c.id, friend, me.uuid, t,
    )
  })
  const view = capeView(ctx, c)
  ctx.events.publish(friend, {
    type: 'cape_offer',
    offer: { cape: view, from: { uuid: me.uuid, name: me.name }, creator: ref(ctx, c.owner_uuid!), createdAt: iso(t) },
  })
  return { cape: view, to: { uuid: other.uuid, name: other.name }, createdAt: iso(t) }
}

// ---------------------------------------------------------------- Angebote lesen / beantworten

export function listOffers(ctx: AppContext, me: string): { incoming: IncomingOffer[], outgoing: OutgoingOffer[] } {
  const incoming = all<CapeRow & { s_from: string, s_created: number, from_name: string, creator_name: string | null }>(
    ctx.db,
    `SELECT c.*, s.granted_by AS s_from, s.created_at AS s_created, f.name AS from_name, o.name AS creator_name
     FROM cape_shares s
     JOIN capes c ON c.id = s.cape_id
     JOIN users f ON f.uuid = s.granted_by
     LEFT JOIN users o ON o.uuid = c.owner_uuid
     WHERE s.holder_uuid = ? AND s.status = 'offered' AND c.status = 'approved'
       AND s.granted_by NOT IN (${ACTIVE_BANS})
     ORDER BY s.created_at DESC`,
    me, ctx.now(),
  )
  const outgoing = all<CapeRow & { s_to: string, s_created: number, to_name: string }>(
    ctx.db,
    `SELECT c.*, s.holder_uuid AS s_to, s.created_at AS s_created, u.name AS to_name
     FROM cape_shares s
     JOIN capes c ON c.id = s.cape_id
     JOIN users u ON u.uuid = s.holder_uuid
     WHERE s.granted_by = ? AND s.status = 'offered'
     ORDER BY s.created_at DESC`,
    me,
  )
  return {
    incoming: incoming.map((r) => ({
      cape: capeView(ctx, r),
      from: { uuid: r.s_from, name: r.from_name },
      creator: { uuid: r.owner_uuid!, name: r.creator_name ?? r.owner_uuid! },
      createdAt: iso(r.s_created),
    })),
    outgoing: outgoing.map((r) => ({
      cape: capeView(ctx, r),
      to: { uuid: r.s_to, name: r.to_name },
      createdAt: iso(r.s_created),
    })),
  }
}

/** Zahl der offenen Angebote an mich (für Zähler/Abzeichen, auch in `GET /v1/friends`). */
export function incomingOfferCount(ctx: AppContext, me: string): number {
  return one<{ n: number }>(
    ctx.db,
    `SELECT COUNT(*) AS n FROM cape_shares s JOIN capes c ON c.id = s.cape_id
     WHERE s.holder_uuid = ? AND s.status = 'offered' AND c.status = 'approved'
       AND s.granted_by NOT IN (${ACTIVE_BANS})`,
    me, ctx.now(),
  )!.n
}

function pendingOffer(ctx: AppContext, me: string, capeId: string): ShareRow {
  const row = one<ShareRow>(
    ctx.db,
    "SELECT * FROM cape_shares WHERE cape_id = ? AND holder_uuid = ? AND status = 'offered'",
    capeId, me,
  )
  if (!row) throw notFound('offer_not_found', 'No pending offer for this cape')
  return row
}

export function acceptOffer(ctx: AppContext, me: UserRow, capeId: string): CapeView {
  const t = ctx.now()
  const done = tx(ctx.db, () => {
    const row = pendingOffer(ctx, me.uuid, capeId)
    const cape = getCape(ctx, capeId)
    // Umhang nicht mehr freigegeben oder Anbieter gesperrt/nicht mehr Freund → Angebot ist hinfällig.
    if (!cape || cape.status !== 'approved' || isBanned(ctx, row.granted_by) || !areFriends(ctx, me.uuid, row.granted_by)) {
      run(ctx.db, 'DELETE FROM cape_shares WHERE cape_id = ? AND holder_uuid = ?', capeId, me.uuid)
      return null
    }
    run(
      ctx.db,
      "UPDATE cape_shares SET status = 'accepted', accepted_at = ? WHERE cape_id = ? AND holder_uuid = ?",
      t, capeId, me.uuid,
    )
    return { c: cape, from: row.granted_by }
  })
  if (!done) throw notFound('offer_not_found', 'No pending offer for this cape')
  ctx.events.publish(done.from, { type: 'cape_offer_accepted', capeId: done.c.id, by: { uuid: me.uuid, name: me.name } })
  return capeView(ctx, done.c)
}

/** Ablehnen – der Anbieter wird nicht benachrichtigt (wie bei Freundschaftsanfragen). */
export function declineOffer(ctx: AppContext, me: string, capeId: string): void {
  const n = run(
    ctx.db,
    "DELETE FROM cape_shares WHERE cape_id = ? AND holder_uuid = ? AND status = 'offered'",
    capeId, me,
  )
  if (n === 0) throw notFound('offer_not_found', 'No pending offer for this cape')
}

// ---------------------------------------------------------------- Inhaber + Entziehen

export function listHolders(ctx: AppContext, me: string, capeId: string): HoldersList {
  const c = getCape(ctx, capeId)
  const role = c ? shareRole(ctx, me, c) : null
  if (!c || !role) throw notFound('cape_not_found', 'Cape not found')
  const rows = all<ShareRow & { holder_name: string, granter_name: string }>(
    ctx.db,
    `SELECT s.*, h.name AS holder_name, g.name AS granter_name FROM cape_shares s
     JOIN users h ON h.uuid = s.holder_uuid
     JOIN users g ON g.uuid = s.granted_by
     WHERE s.cape_id = ?
     ORDER BY s.created_at`,
    c.id,
  )
  // Inhaber sehen nur ihren eigenen Ast (ohne sich selbst), der Ersteller alle.
  const mine = role === 'creator' ? null : new Set(subtree(ctx, c.id, me).filter((u) => u !== me))
  return {
    holders: rows
      .filter((r) => mine === null || mine.has(r.holder_uuid))
      .map((r) => ({
        uuid: r.holder_uuid,
        name: r.holder_name,
        status: r.status,
        grantedBy: { uuid: r.granted_by, name: r.granter_name },
        createdAt: iso(r.created_at),
        acceptedAt: r.accepted_at === null ? null : iso(r.accepted_at),
      })),
    count: rows.length,
    limit: ctx.config.limits.maxCapeHolders,
  }
}

/**
 * Entzieht `holder` den Umhang (bzw. zieht ein offenes Angebot zurück) – samt allem, was `holder`
 * weitergeteilt hat. Erlaubt: dem Ersteller für jeden Inhaber, einem Inhaber in seinem eigenen Ast,
 * und jedem für sich selbst (Umhang zurückgeben / Angebot verwerfen).
 */
export function revokeShare(ctx: AppContext, me: string, capeId: string, holder: string): { removed: number } {
  const c = getCape(ctx, capeId)
  if (!c || c.kind !== 'upload') throw notFound('cape_not_found', 'Cape not found')
  const row = one<ShareRow>(ctx.db, 'SELECT * FROM cape_shares WHERE cape_id = ? AND holder_uuid = ?', c.id, holder)
  const allowed = row !== undefined && (
    me === holder
    || me === c.owner_uuid
    || (holdsCape(ctx, me, c.id) && subtree(ctx, c.id, me).includes(holder))
  )
  if (!allowed) {
    // Wer den Umhang gar nicht kennt, erfährt auch nicht, ob es ihn gibt.
    if (me === c.owner_uuid || holdsCape(ctx, me, c.id)) throw notFound('holder_not_found', 'This player has no share of this cape from you')
    throw notFound('cape_not_found', 'Cape not found')
  }
  const removed = removeBranch(ctx, c.id, holder)
  for (const u of removed) {
    if (u !== me) ctx.events.publish(u, { type: 'cape_share_removed', capeId: c.id })
  }
  return { removed: removed.length }
}

/**
 * Löscht `root` und seinen ganzen Ast bei diesem Umhang; wer ihn trug, trägt danach keinen.
 * Liefert die betroffenen Konten. Eigene Transaktion – nicht in `tx` aufrufen.
 */
function removeBranch(ctx: AppContext, capeId: string, root: string): string[] {
  const { removed, unworn } = tx(ctx.db, () => {
    const branch = subtree(ctx, capeId, root)
    const n = branch.length
    const unwornRows = all<{ uuid: string }>(
      ctx.db,
      `SELECT uuid FROM users WHERE active_cape_id = ? AND uuid IN (${placeholders(n)})`,
      capeId, ...branch,
    )
    run(ctx.db, `DELETE FROM cape_shares WHERE cape_id = ? AND holder_uuid IN (${placeholders(n)})`, capeId, ...branch)
    run(ctx.db, `UPDATE users SET active_cape_id = NULL WHERE active_cape_id = ? AND uuid IN (${placeholders(n)})`, capeId, ...branch)
    return { removed: branch, unworn: unwornRows.map((r) => r.uuid) }
  })
  for (const u of unworn) emitCape(ctx, u)
  return removed
}

/**
 * Freundschaft beendet oder blockiert: offene Angebote zwischen beiden fallen weg (in beide
 * Richtungen). Angenommene Umhänge bleiben, bis jemand sie entzieht. Darf in `tx` laufen –
 * die Benachrichtigung verschickt der Aufrufer über das Ergebnis.
 */
export function dropOffersBetween(ctx: AppContext, x: string, y: string): { holder: string, capeId: string }[] {
  const rows = all<{ holder_uuid: string, cape_id: string }>(
    ctx.db,
    `SELECT holder_uuid, cape_id FROM cape_shares WHERE status = 'offered'
     AND ((holder_uuid = ? AND granted_by = ?) OR (holder_uuid = ? AND granted_by = ?))`,
    x, y, y, x,
  )
  if (rows.length > 0) {
    run(
      ctx.db,
      `DELETE FROM cape_shares WHERE status = 'offered'
       AND ((holder_uuid = ? AND granted_by = ?) OR (holder_uuid = ? AND granted_by = ?))`,
      x, y, y, x,
    )
  }
  return rows.map((r) => ({ holder: r.holder_uuid, capeId: r.cape_id }))
}

/** Konto wird gelöscht: eigene Inhaberschaften samt weitergeteilter Äste entfernen (vor dem Löschen aufrufen). */
export function releaseHoldings(ctx: AppContext, uuid: string): void {
  const held = all<{ cape_id: string }>(ctx.db, 'SELECT cape_id FROM cape_shares WHERE holder_uuid = ?', uuid)
  for (const { cape_id } of held) {
    for (const u of removeBranch(ctx, cape_id, uuid)) {
      if (u !== uuid) ctx.events.publish(u, { type: 'cape_share_removed', capeId: cape_id })
    }
  }
}

/** Alle Inhaber eines Umhangs (für Löschen/Ablehnen: vorher merken, danach benachrichtigen). */
export function shareHolders(ctx: AppContext, capeId: string): string[] {
  return all<{ holder_uuid: string }>(ctx.db, 'SELECT holder_uuid FROM cape_shares WHERE cape_id = ?', capeId)
    .map((r) => r.holder_uuid)
}

export function notifyShareRemoved(ctx: AppContext, capeId: string, holders: string[]): void {
  for (const u of holders) ctx.events.publish(u, { type: 'cape_share_removed', capeId })
}
