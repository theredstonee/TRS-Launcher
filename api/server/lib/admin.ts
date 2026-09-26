import { statSync } from 'node:fs'
import { join } from 'node:path'
import { audit } from './audit'
import type { AppContext } from './context'
import { all, one, placeholders, run, tx } from './db'
import { badRequest, conflict, notFound } from './errors'
import { capeView, capeWearers, getCape, removeCape, type CapeRow, type CapeView } from './capes'
import {
  cosmeticView,
  getCosmetic,
  removeCosmetic,
  setReviewStatus,
  type CosmeticRow,
  type CosmeticView,
} from './cosmetics'
import { notifyShareRemoved, shareHolders } from './capeshares'
import { emitCape, emitCosmetics } from './playerevents'
import { ACTIVE_BANS, getUser, isAdmin, settingsOf, staffRole, type Settings, type StaffRole } from './users'
import { chatStorageUsed } from './attachments'
import { BULK_MAX, reportStats } from './moderation'
import { activeSanction, createSanction, decodeCursor, encodeCursor, liftActive, staffOf, type Staff } from './sanctions'

export { audit, listAudit, type AuditEntry } from './audit'

export interface OwnerStats {
  /** Alle Uploads dieses Besitzers (inkl. des gezeigten). */
  uploads: number
  approved: number
  pending: number
  rejected: number
}

export interface AdminCapeView extends CapeView {
  owner: { uuid: string, name: string } | null
  createdAt: string
  reviewedAt: string | null
  reviewedBy: string | null
  rejectReason: string | null
  reports: { count: number, reasons: Record<string, number> }
  /** Größe der gespeicherten PNG-Datei in Bytes (0, wenn sie fehlt). */
  bytes: number
  /** Upload-Zahlen des Besitzers über ALLE seine Uploads, `null` ohne Besitzer. */
  ownerStats: OwnerStats | null
}

function fileSize(path: string): number {
  try {
    return statSync(path).size
  } catch {
    return 0
  }
}

export type ReviewListStatus = 'pending' | 'approved' | 'rejected' | 'reported'

/** Filter für die Prüf-Listen (Umhänge und Kosmetik, §22.7). */
export interface ReviewListQuery {
  status: ReviewListStatus
  owner?: string
  /** Teil des Namens (ohne Groß/klein). */
  q?: string
  from?: number
  to?: number
  sort?: 'oldest' | 'newest'
  cursor?: string
  limit?: number
}

/** Gemeinsamer WHERE-Teil für `capes`/`cosmetics` (Alias `c`); Spalten und Tabellen nur aus festen Werten. */
function reviewWhere(table: 'cape' | 'cosmetic', q: ReviewListQuery): { where: string[], params: (string | number)[], asc: boolean } {
  const where = ["c.kind = 'upload'"]
  const params: (string | number)[] = []
  if (q.status === 'reported') {
    where.push(table === 'cape'
      ? 'EXISTS (SELECT 1 FROM cape_reports r WHERE r.cape_id = c.id)'
      : 'EXISTS (SELECT 1 FROM cosmetic_reports r WHERE r.cosmetic_id = c.id)')
  } else {
    where.push('c.status = ?')
    params.push(q.status)
  }
  if (q.owner) {
    where.push('c.owner_uuid = ?')
    params.push(q.owner)
  }
  if (q.q) {
    where.push('instr(lower(c.name), lower(?)) > 0')
    params.push(q.q)
  }
  if (q.from !== undefined) {
    where.push('c.created_at >= ?')
    params.push(q.from)
  }
  if (q.to !== undefined) {
    where.push('c.created_at < ?')
    params.push(q.to)
  }
  // Warteschlangen älteste zuerst, erledigte neueste zuerst.
  const asc = q.sort ? q.sort === 'oldest' : q.status === 'pending' || q.status === 'reported'
  if (q.cursor) {
    // Gleichstand bei der Zeit: Einfüge-Reihenfolge (rowid).
    const [at, rid] = decodeCursor(q.cursor)
    where.push(asc ? '(c.created_at > ? OR (c.created_at = ? AND c.rowid > ?))' : '(c.created_at < ? OR (c.created_at = ? AND c.rowid < ?))')
    params.push(at, at, rid)
  }
  return { where, params, asc }
}

const REVIEW_DEFAULT_LIMIT = 200

export function listCapesForReview(ctx: AppContext, statusOrQuery: ReviewListStatus | ReviewListQuery): AdminCapeView[] {
  return listCapesPage(ctx, typeof statusOrQuery === 'string' ? { status: statusOrQuery } : statusOrQuery).capes
}

export function listCapesPage(ctx: AppContext, q: ReviewListQuery): { capes: AdminCapeView[], nextCursor: string | null } {
  const limit = q.limit ?? REVIEW_DEFAULT_LIMIT
  const { where, params, asc } = reviewWhere('cape', q)
  const found = all<CapeRow & { owner_name: string | null, rid: number }>(
    ctx.db,
    `SELECT c.*, c.rowid AS rid, u.name AS owner_name FROM capes c LEFT JOIN users u ON u.uuid = c.owner_uuid
     WHERE ${where.join(' AND ')} ORDER BY c.created_at ${asc ? 'ASC' : 'DESC'}, c.rowid ${asc ? 'ASC' : 'DESC'} LIMIT ?`,
    ...params, limit + 1,
  )
  const rows = found.slice(0, limit)
  const last = rows[rows.length - 1]
  const nextCursor = found.length > limit && last ? encodeCursor(last.created_at, last.rid) : null
  if (rows.length === 0) return { capes: [], nextCursor: null }

  // Meldungen und Besitzer-Zahlen je eine gruppierte Abfrage für die ganze Liste.
  const ids = rows.map((c) => c.id)
  const reports = new Map<string, { reason: string, n: number }[]>()
  for (const r of all<{ cape_id: string, reason: string, n: number }>(
    ctx.db,
    `SELECT cape_id, reason, COUNT(*) AS n FROM cape_reports WHERE cape_id IN (${placeholders(ids.length)}) GROUP BY cape_id, reason`,
    ...ids,
  )) {
    const list = reports.get(r.cape_id) ?? []
    list.push({ reason: r.reason, n: r.n })
    reports.set(r.cape_id, list)
  }
  const owners = [...new Set(rows.map((c) => c.owner_uuid).filter((u): u is string => u !== null))]
  const ownerStats = new Map<string, OwnerStats>()
  if (owners.length) {
    for (const o of all<{ owner_uuid: string, uploads: number, approved: number, pending: number, rejected: number }>(
      ctx.db,
      `SELECT owner_uuid, COUNT(*) AS uploads,
         COALESCE(SUM(status = 'approved'), 0) AS approved,
         COALESCE(SUM(status = 'pending'), 0) AS pending,
         COALESCE(SUM(status = 'rejected'), 0) AS rejected
       FROM capes WHERE kind = 'upload' AND owner_uuid IN (${placeholders(owners.length)}) GROUP BY owner_uuid`,
      ...owners,
    )) {
      ownerStats.set(o.owner_uuid, { uploads: o.uploads, approved: o.approved, pending: o.pending, rejected: o.rejected })
    }
  }

  const out = rows.map((c) => {
    const r = reports.get(c.id) ?? []
    return {
      ...capeView(ctx, c),
      owner: c.owner_uuid ? { uuid: c.owner_uuid, name: c.owner_name ?? '' } : null,
      createdAt: new Date(c.created_at).toISOString(),
      reviewedAt: c.reviewed_at ? new Date(c.reviewed_at).toISOString() : null,
      reviewedBy: c.reviewed_by,
      rejectReason: c.reject_reason,
      reports: {
        count: r.reduce((s, x) => s + x.n, 0),
        reasons: Object.fromEntries(r.map((x) => [x.reason, x.n])),
      },
      bytes: fileSize(join(ctx.capeDir, `${c.id}.png`)),
      ownerStats: c.owner_uuid ? (ownerStats.get(c.owner_uuid) ?? { uploads: 0, approved: 0, pending: 0, rejected: 0 }) : null,
    }
  })
  return { capes: out, nextCursor }
}

function uploadOr404(ctx: AppContext, id: string): CapeRow {
  const c = getCape(ctx, id)
  if (!c || c.kind !== 'upload') throw notFound('cape_not_found', 'Cape not found')
  return c
}

/** Freigabe/Ablehnung OHNE eigene Transaktion; liefert die Nacharbeit (Ereignisse) für nach dem Commit. */
function reviewCapeInTx(ctx: AppContext, actor: string, c: CapeRow, approve: boolean, reason: string | undefined): () => void {
  const worn = capeWearers(ctx, c.id)
  if (approve) {
    run(
      ctx.db,
      "UPDATE capes SET status = 'approved', reviewed_at = ?, reviewed_by = ?, reject_reason = NULL WHERE id = ?",
      ctx.now(), actor, c.id,
    )
    // Freigabe erledigt offene Meldungen.
    run(ctx.db, 'DELETE FROM cape_reports WHERE cape_id = ?', c.id)
    audit(ctx, actor, 'cape.approve', c.owner_uuid, c.id)
    // Ab jetzt sehen auch andere den Umhang.
    return () => {
      for (const u of worn) emitCape(ctx, u)
    }
  }
  const holders = shareHolders(ctx, c.id)
  run(
    ctx.db,
    "UPDATE capes SET status = 'rejected', reviewed_at = ?, reviewed_by = ?, reject_reason = ? WHERE id = ?",
    ctx.now(), actor, reason ?? null, c.id,
  )
  run(ctx.db, 'UPDATE users SET active_cape_id = NULL WHERE active_cape_id = ?', c.id)
  // Abgelehnt → alle Teilungen (angenommen und offen) sind weg.
  run(ctx.db, 'DELETE FROM cape_shares WHERE cape_id = ?', c.id)
  audit(ctx, actor, 'cape.reject', c.owner_uuid, c.id)
  return () => {
    for (const u of worn) emitCape(ctx, u)
    notifyShareRemoved(ctx, c.id, holders)
  }
}

export function approveCape(ctx: AppContext, actor: string, id: string): CapeView {
  const c = uploadOr404(ctx, id)
  tx(ctx.db, () => reviewCapeInTx(ctx, actor, c, true, undefined))()
  return capeView(ctx, getCape(ctx, c.id)!)
}

export function rejectCape(ctx: AppContext, actor: string, id: string, reason: string | undefined): CapeView {
  const c = uploadOr404(ctx, id)
  tx(ctx.db, () => reviewCapeInTx(ctx, actor, c, false, reason))()
  return capeView(ctx, getCape(ctx, c.id)!)
}

export interface BulkResult {
  updated: string[]
  skipped: string[]
}

/** Sammelaktion für Umhänge: höchstens {@link BULK_MAX} je Anfrage, alles in EINER Transaktion. */
export function bulkReviewCapes(ctx: AppContext, actor: string, ids: string[], action: 'approve' | 'reject', reason?: string): BulkResult {
  const unique = [...new Set(ids)]
  if (unique.length === 0 || unique.length > BULK_MAX) throw badRequest('bulk_too_large', `Between 1 and ${BULK_MAX} items per request`)
  const rows = all<CapeRow>(ctx.db, `SELECT * FROM capes WHERE kind = 'upload' AND id IN (${placeholders(unique.length)})`, ...unique)
  const target = action === 'approve' ? 'approved' : 'rejected'
  const todo = rows.filter((c) => c.status !== target)
  const after = tx(ctx.db, () => todo.map((c) => reviewCapeInTx(ctx, actor, c, action === 'approve', reason)))
  for (const f of after) f()
  const done = new Set(todo.map((c) => c.id))
  return { updated: unique.filter((x) => done.has(x)), skipped: unique.filter((x) => !done.has(x)) }
}

export function deleteCapeAdmin(ctx: AppContext, actor: string, id: string): void {
  const c = getCape(ctx, id)
  if (!c) throw notFound('cape_not_found', 'Cape not found')
  if (c.kind === 'builtin') throw conflict('builtin_cape', 'Built-in capes are managed by the generator, not deletable')
  removeCape(ctx, c.id)
  audit(ctx, actor, 'cape.delete', c.owner_uuid, c.id)
}

// ---------------------------------------------------------------- Kosmetik-Moderation

export interface AdminCosmeticView extends CosmeticView {
  owner: { uuid: string, name: string } | null
  createdAt: string
  reviewedAt: string | null
  reviewedBy: string | null
  rejectReason: string | null
  reports: { count: number, reasons: Record<string, number> }
}

export function listCosmeticsForReview(ctx: AppContext, statusOrQuery: ReviewListStatus | ReviewListQuery): AdminCosmeticView[] {
  return listCosmeticsPage(ctx, typeof statusOrQuery === 'string' ? { status: statusOrQuery } : statusOrQuery).cosmetics
}

export function listCosmeticsPage(ctx: AppContext, q: ReviewListQuery): { cosmetics: AdminCosmeticView[], nextCursor: string | null } {
  const limit = q.limit ?? REVIEW_DEFAULT_LIMIT
  const { where, params, asc } = reviewWhere('cosmetic', q)
  const found = all<CosmeticRow & { owner_name: string | null, rid: number }>(
    ctx.db,
    `SELECT c.*, c.rowid AS rid, u.name AS owner_name FROM cosmetics c LEFT JOIN users u ON u.uuid = c.owner_uuid
     WHERE ${where.join(' AND ')} ORDER BY c.created_at ${asc ? 'ASC' : 'DESC'}, c.rowid ${asc ? 'ASC' : 'DESC'} LIMIT ?`,
    ...params, limit + 1,
  )
  const rows = found.slice(0, limit)
  const last = rows[rows.length - 1]
  const nextCursor = found.length > limit && last ? encodeCursor(last.created_at, last.rid) : null
  const cosmetics = rows.map((c) => {
    const reports = all<{ reason: string, n: number }>(
      ctx.db,
      'SELECT reason, COUNT(*) AS n FROM cosmetic_reports WHERE cosmetic_id = ? GROUP BY reason',
      c.id,
    )
    return {
      ...cosmeticView(ctx, c),
      owner: c.owner_uuid ? { uuid: c.owner_uuid, name: c.owner_name ?? '' } : null,
      createdAt: new Date(c.created_at).toISOString(),
      reviewedAt: c.reviewed_at ? new Date(c.reviewed_at).toISOString() : null,
      reviewedBy: c.reviewed_by,
      rejectReason: c.reject_reason,
      reports: {
        count: reports.reduce((s, r) => s + r.n, 0),
        reasons: Object.fromEntries(reports.map((r) => [r.reason, r.n])),
      },
    }
  })
  return { cosmetics, nextCursor }
}

export function approveCosmetic(ctx: AppContext, actor: string, id: string): CosmeticView {
  const view = setReviewStatus(ctx, actor, id, 'approved', undefined)
  audit(ctx, actor, 'cosmetic.approve', getCosmetic(ctx, id)?.owner_uuid ?? null, id)
  return view
}

export function rejectCosmetic(ctx: AppContext, actor: string, id: string, reason: string | undefined): CosmeticView {
  const view = setReviewStatus(ctx, actor, id, 'rejected', reason)
  audit(ctx, actor, 'cosmetic.reject', getCosmetic(ctx, id)?.owner_uuid ?? null, id)
  return view
}

/** Sammelaktion für Kosmetik-Uploads: höchstens {@link BULK_MAX} je Anfrage, alles in EINER Transaktion. */
export function bulkReviewCosmetics(ctx: AppContext, actor: string, ids: string[], action: 'approve' | 'reject', reason?: string): BulkResult {
  const unique = [...new Set(ids)]
  if (unique.length === 0 || unique.length > BULK_MAX) throw badRequest('bulk_too_large', `Between 1 and ${BULK_MAX} items per request`)
  const rows = all<CosmeticRow>(ctx.db, `SELECT * FROM cosmetics WHERE kind = 'upload' AND id IN (${placeholders(unique.length)})`, ...unique)
  const status = action === 'approve' ? 'approved' : 'rejected'
  const todo = rows.filter((c) => c.status !== status)
  const t = ctx.now()
  const worn = tx(ctx.db, () => todo.flatMap((c) => {
    const w = all<{ uuid: string }>(ctx.db, 'SELECT uuid FROM equipped_cosmetics WHERE cosmetic_id = ?', c.id).map((r) => r.uuid)
    run(
      ctx.db,
      'UPDATE cosmetics SET status = ?, reviewed_at = ?, reviewed_by = ?, reject_reason = ? WHERE id = ?',
      status, t, actor, status === 'rejected' ? (reason ?? null) : null, c.id,
    )
    if (status === 'approved') run(ctx.db, 'DELETE FROM cosmetic_reports WHERE cosmetic_id = ?', c.id)
    else run(ctx.db, 'DELETE FROM equipped_cosmetics WHERE cosmetic_id = ?', c.id)
    audit(ctx, actor, `cosmetic.${action}`, c.owner_uuid, c.id)
    return w
  }))
  for (const u of new Set(worn)) emitCosmetics(ctx, u)
  const done = new Set(todo.map((c) => c.id))
  return { updated: unique.filter((x) => done.has(x)), skipped: unique.filter((x) => !done.has(x)) }
}

export function deleteCosmeticAdmin(ctx: AppContext, actor: string, id: string): void {
  const c = getCosmetic(ctx, id)
  if (!c) throw notFound('cosmetic_not_found', 'Cosmetic not found')
  if (c.kind === 'builtin') throw conflict('builtin_cosmetic', 'Built-in cosmetics are managed by the generator, not deletable')
  removeCosmetic(ctx, c.id)
  audit(ctx, actor, 'cosmetic.delete', c.owner_uuid, c.id)
}

/**
 * Konto dauerhaft sperren (alte Route §8, Admins): eine Strafe `account_ban` (§22). Ist schon ein Bann aktiv,
 * passiert nichts weiter. Admins → `409 cannot_ban_admin`.
 */
export function banUser(ctx: AppContext, actor: string | Staff, uuid: string, reason: string | undefined): void {
  const staff = typeof actor === 'string' ? staffOf(ctx, actor) : actor
  if (staffRole(ctx, uuid) === 'admin') throw conflict('cannot_ban_admin', 'Admins cannot be banned; remove the role first')
  if (activeSanction(ctx, uuid, 'account_ban')) return
  createSanction(ctx, staff, { uuid, kind: 'account_ban', minutes: null, reasonCode: 'other', reason: reason ?? null })
}

export function unbanUser(ctx: AppContext, actor: string | Staff, uuid: string): void {
  const staff = typeof actor === 'string' ? staffOf(ctx, actor) : actor
  const n = liftActive(ctx, staff, uuid, 'account_ban', 'Unbanned', { auditAction: 'user.unban' })
  if (n === 0) throw notFound('not_banned', 'This user is not banned')
}

export interface AdminUserView {
  uuid: string
  name: string | null
  known: boolean
  admin: boolean
  role: StaffRole | null
  banned: { reason: string | null, bannedAt: string, bannedBy: string, until: string | null } | null
  createdAt: string | null
  lastLoginAt: string | null
  settings: Settings | null
  activeCapeId: string | null
  grantedCapes: { capeId: string, source: string, grantedAt: string }[]
  uploads: number
  grantedCosmetics: { cosmeticId: string, source: string, grantedAt: string }[]
  equippedCosmetics: Record<string, string>
  cosmeticUploads: number
  friends: number
  sessions: number
  online: boolean
}

export function userInfo(ctx: AppContext, uuid: string): AdminUserView {
  const u = getUser(ctx, uuid)
  const ban = activeSanction(ctx, uuid, 'account_ban')
  const known = u !== undefined || one(ctx.db, 'SELECT 1 AS x FROM sanctions WHERE uuid = ? LIMIT 1', uuid) !== undefined
  if (!known) throw notFound('user_not_found', 'Unknown user')
  const count = (sql: string, ...p: string[]) => one<{ n: number }>(ctx.db, sql, ...p)!.n
  return {
    uuid,
    name: u?.name ?? null,
    known: !!u,
    admin: isAdmin(ctx, uuid),
    role: staffRole(ctx, uuid),
    banned: ban
      ? { reason: ban.reason, bannedAt: new Date(ban.created_at).toISOString(), bannedBy: ban.created_by, until: ban.expires_at === null ? null : new Date(ban.expires_at).toISOString() }
      : null,
    createdAt: u ? new Date(u.created_at).toISOString() : null,
    lastLoginAt: u ? new Date(u.last_login_at).toISOString() : null,
    settings: u ? settingsOf(u) : null,
    activeCapeId: u?.active_cape_id ?? null,
    grantedCapes: all<{ cape_id: string, source: string, granted_at: number }>(
      ctx.db, 'SELECT cape_id, source, granted_at FROM user_capes WHERE uuid = ? ORDER BY granted_at', uuid,
    ).map((g) => ({ capeId: g.cape_id, source: g.source, grantedAt: new Date(g.granted_at).toISOString() })),
    uploads: count("SELECT COUNT(*) AS n FROM capes WHERE owner_uuid = ? AND kind = 'upload'", uuid),
    grantedCosmetics: all<{ cosmetic_id: string, source: string, granted_at: number }>(
      ctx.db, 'SELECT cosmetic_id, source, granted_at FROM user_cosmetics WHERE uuid = ? ORDER BY granted_at', uuid,
    ).map((g) => ({ cosmeticId: g.cosmetic_id, source: g.source, grantedAt: new Date(g.granted_at).toISOString() })),
    equippedCosmetics: Object.fromEntries(
      all<{ slot: string, cosmetic_id: string }>(ctx.db, 'SELECT slot, cosmetic_id FROM equipped_cosmetics WHERE uuid = ? ORDER BY slot', uuid)
        .map((e) => [e.slot, e.cosmetic_id]),
    ),
    cosmeticUploads: count("SELECT COUNT(*) AS n FROM cosmetics WHERE owner_uuid = ? AND kind = 'upload'", uuid),
    friends: count('SELECT COUNT(*) AS n FROM friendships WHERE a = ? OR b = ?', uuid, uuid),
    sessions: count('SELECT COUNT(*) AS n FROM sessions WHERE uuid = ?', uuid),
    online: ctx.presence.get(uuid) !== null,
  }
}

export function stats(ctx: AppContext) {
  const n = (sql: string, ...p: (string | number)[]) => one<{ n: number }>(ctx.db, sql, ...p)!.n
  const t = ctx.now()
  return {
    users: {
      total: n('SELECT COUNT(*) AS n FROM users'),
      banned: n(`SELECT COUNT(DISTINCT uuid) AS n FROM (${ACTIVE_BANS})`, t),
      activeLast24h: n('SELECT COUNT(*) AS n FROM users WHERE last_login_at > ?', t - 86_400_000),
      online: ctx.presence.count(),
    },
    sessions: n('SELECT COUNT(*) AS n FROM sessions WHERE expires_at > ?', t),
    capes: {
      builtin: n("SELECT COUNT(*) AS n FROM capes WHERE kind = 'builtin' AND retired = 0"),
      approved: n("SELECT COUNT(*) AS n FROM capes WHERE kind = 'upload' AND status = 'approved'"),
      pending: n("SELECT COUNT(*) AS n FROM capes WHERE kind = 'upload' AND status = 'pending'"),
      rejected: n("SELECT COUNT(*) AS n FROM capes WHERE kind = 'upload' AND status = 'rejected'"),
      reported: n('SELECT COUNT(DISTINCT cape_id) AS n FROM cape_reports'),
      activeUsers: n('SELECT COUNT(*) AS n FROM users WHERE active_cape_id IS NOT NULL'),
    },
    cosmetics: {
      builtin: n("SELECT COUNT(*) AS n FROM cosmetics WHERE kind = 'builtin' AND slot <> 'emote' AND retired = 0"),
      emotes: n("SELECT COUNT(*) AS n FROM cosmetics WHERE slot = 'emote' AND retired = 0"),
      approved: n("SELECT COUNT(*) AS n FROM cosmetics WHERE kind = 'upload' AND status = 'approved'"),
      pending: n("SELECT COUNT(*) AS n FROM cosmetics WHERE kind = 'upload' AND status = 'pending'"),
      rejected: n("SELECT COUNT(*) AS n FROM cosmetics WHERE kind = 'upload' AND status = 'rejected'"),
      reported: n('SELECT COUNT(DISTINCT cosmetic_id) AS n FROM cosmetic_reports'),
      equippedUsers: n('SELECT COUNT(DISTINCT uuid) AS n FROM equipped_cosmetics'),
    },
    codes: {
      active: n(
        'SELECT COUNT(*) AS n FROM codes WHERE revoked_at IS NULL AND uses < max_uses AND (expires_at IS NULL OR expires_at > ?)',
        t,
      ),
      redemptions: n('SELECT COUNT(*) AS n FROM code_redemptions'),
    },
    friendships: n('SELECT COUNT(*) AS n FROM friendships'),
    pendingFriendRequests: n('SELECT COUNT(*) AS n FROM friend_requests'),
    eventStreams: ctx.events.size,
    playerStreams: ctx.watch.size,
    chat: {
      conversations: n("SELECT COUNT(*) AS n FROM chat_conversations WHERE kind = 'dm'"),
      groups: n("SELECT COUNT(*) AS n FROM chat_conversations WHERE kind = 'group'"),
      messages: n('SELECT COUNT(*) AS n FROM chat_messages WHERE deleted_at IS NULL'),
      messagesLast24h: n('SELECT COUNT(*) AS n FROM chat_messages WHERE created_at > ?', t - 86_400_000),
      images: n('SELECT COUNT(*) AS n FROM chat_attachments WHERE message_id IS NOT NULL'),
      storageBytes: chatStorageUsed(ctx),
      storageLimitBytes: ctx.config.chatStorageMaxBytes,
    },
    reports: reportStats(ctx),
  }
}

export function assertKnownUser(ctx: AppContext, uuid: string): void {
  if (!getUser(ctx, uuid)) throw notFound('user_not_found', 'User has never signed in to TRS; use a redeem code instead')
}

