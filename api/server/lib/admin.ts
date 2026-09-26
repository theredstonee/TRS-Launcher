import { statSync } from 'node:fs'
import { join } from 'node:path'
import type { AppContext } from './context'
import { all, one, run, tx } from './db'
import { conflict, notFound } from './errors'
import { capeView, capeWearers, getCape, removeCape, type CapeRow, type CapeView } from './capes'
import {
  cosmeticView,
  getCosmetic,
  removeCosmetic,
  setReviewStatus,
  type CosmeticRow,
  type CosmeticView,
} from './cosmetics'
import { broadcastPresence } from './friends'
import { endHostingFor } from './hosting'
import { notifyShareRemoved, shareHolders } from './capeshares'
import { emitCape } from './playerevents'
import { getUser, isAdmin, settingsOf, type Settings } from './users'
import { chatStorageUsed } from './attachments'
import { reportStats } from './moderation'

/** Audit-Log. `ref` = Bezug (z. B. Meldungs-ID), damit sich Einträge je Meldung auflisten lassen. */
export function audit(ctx: AppContext, actor: string, action: string, target: string | null, detail?: string, ref?: string): void {
  run(
    ctx.db,
    'INSERT INTO admin_log (at, actor, action, target, detail, ref) VALUES (?, ?, ?, ?, ?, ?)',
    ctx.now(), actor, action, target, detail ?? null, ref ?? null,
  )
}

export interface AuditEntry {
  id: number
  at: string
  actor: string
  actorName: string | null
  action: string
  target: string | null
  targetName: string | null
  detail: string | null
  ref: string | null
}

/** Audit-Log lesen (neueste zuerst), optional nach Bezug oder Ziel gefiltert, Cursor = `before` (id). */
export function listAudit(ctx: AppContext, opts: { ref?: string, target?: string, before?: number, limit: number }): { entries: AuditEntry[], nextBefore: number | null } {
  const where: string[] = []
  const params: (string | number)[] = []
  if (opts.ref) {
    where.push('l.ref = ?')
    params.push(opts.ref)
  }
  if (opts.target) {
    where.push('l.target = ?')
    params.push(opts.target)
  }
  if (opts.before) {
    where.push('l.id < ?')
    params.push(opts.before)
  }
  // Bedingungen stammen nur aus der festen Liste oben, Werte gehen als Parameter.
  const rows = all<{ id: number, at: number, actor: string, action: string, target: string | null, detail: string | null, ref: string | null, actor_name: string | null, target_name: string | null }>(
    ctx.db,
    `SELECT l.*, ua.name AS actor_name, ut.name AS target_name FROM admin_log l
     LEFT JOIN users ua ON ua.uuid = l.actor LEFT JOIN users ut ON ut.uuid = l.target
     ${where.length ? `WHERE ${where.join(' AND ')}` : ''} ORDER BY l.id DESC LIMIT ?`,
    ...params, opts.limit + 1,
  )
  const page = rows.slice(0, opts.limit)
  return {
    entries: page.map((r) => ({
      id: r.id,
      at: new Date(r.at).toISOString(),
      actor: r.actor,
      actorName: r.actor_name,
      action: r.action,
      target: r.target,
      targetName: r.target_name,
      detail: r.detail,
      ref: r.ref,
    })),
    nextBefore: rows.length > opts.limit ? page[page.length - 1]!.id : null,
  }
}

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

/** `?, ?, ?` für eine IN-Liste (Werte bleiben Parameter). */
const placeholders = (n: number) => Array.from({ length: n }, () => '?').join(', ')

export function listCapesForReview(ctx: AppContext, status: 'pending' | 'approved' | 'rejected' | 'reported'): AdminCapeView[] {
  const rows = status === 'reported'
    ? all<CapeRow & { owner_name: string | null }>(
      ctx.db,
      `SELECT c.*, u.name AS owner_name FROM capes c LEFT JOIN users u ON u.uuid = c.owner_uuid
       WHERE c.kind = 'upload' AND EXISTS (SELECT 1 FROM cape_reports r WHERE r.cape_id = c.id)
       ORDER BY c.created_at LIMIT 500`,
    )
    : all<CapeRow & { owner_name: string | null }>(
      ctx.db,
      `SELECT c.*, u.name AS owner_name FROM capes c LEFT JOIN users u ON u.uuid = c.owner_uuid
       WHERE c.kind = 'upload' AND c.status = ? ORDER BY c.created_at LIMIT 500`,
      status,
    )
  if (rows.length === 0) return []

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

  return rows.map((c) => {
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
}

function uploadOr404(ctx: AppContext, id: string): CapeRow {
  const c = getCape(ctx, id)
  if (!c || c.kind !== 'upload') throw notFound('cape_not_found', 'Cape not found')
  return c
}

export function approveCape(ctx: AppContext, actor: string, id: string): CapeView {
  const c = uploadOr404(ctx, id)
  const worn = capeWearers(ctx, c.id)
  tx(ctx.db, () => {
    run(
      ctx.db,
      "UPDATE capes SET status = 'approved', reviewed_at = ?, reviewed_by = ?, reject_reason = NULL WHERE id = ?",
      ctx.now(), actor, c.id,
    )
    // Freigabe erledigt offene Meldungen.
    run(ctx.db, 'DELETE FROM cape_reports WHERE cape_id = ?', c.id)
    audit(ctx, actor, 'cape.approve', c.owner_uuid, c.id)
  })
  // Ab jetzt sehen auch andere den Umhang.
  for (const u of worn) emitCape(ctx, u)
  return capeView(ctx, getCape(ctx, c.id)!)
}

export function rejectCape(ctx: AppContext, actor: string, id: string, reason: string | undefined): CapeView {
  const c = uploadOr404(ctx, id)
  const worn = capeWearers(ctx, c.id)
  const holders = shareHolders(ctx, c.id)
  tx(ctx.db, () => {
    run(
      ctx.db,
      "UPDATE capes SET status = 'rejected', reviewed_at = ?, reviewed_by = ?, reject_reason = ? WHERE id = ?",
      ctx.now(), actor, reason ?? null, c.id,
    )
    run(ctx.db, 'UPDATE users SET active_cape_id = NULL WHERE active_cape_id = ?', c.id)
    // Abgelehnt → alle Teilungen (angenommen und offen) sind weg.
    run(ctx.db, 'DELETE FROM cape_shares WHERE cape_id = ?', c.id)
    audit(ctx, actor, 'cape.reject', c.owner_uuid, c.id)
  })
  for (const u of worn) emitCape(ctx, u)
  notifyShareRemoved(ctx, c.id, holders)
  return capeView(ctx, getCape(ctx, c.id)!)
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

export function listCosmeticsForReview(
  ctx: AppContext,
  status: 'pending' | 'approved' | 'rejected' | 'reported',
): AdminCosmeticView[] {
  const rows = status === 'reported'
    ? all<CosmeticRow & { owner_name: string | null }>(
      ctx.db,
      `SELECT c.*, u.name AS owner_name FROM cosmetics c LEFT JOIN users u ON u.uuid = c.owner_uuid
       WHERE c.kind = 'upload' AND EXISTS (SELECT 1 FROM cosmetic_reports r WHERE r.cosmetic_id = c.id)
       ORDER BY c.created_at LIMIT 500`,
    )
    : all<CosmeticRow & { owner_name: string | null }>(
      ctx.db,
      `SELECT c.*, u.name AS owner_name FROM cosmetics c LEFT JOIN users u ON u.uuid = c.owner_uuid
       WHERE c.kind = 'upload' AND c.status = ? ORDER BY c.created_at LIMIT 500`,
      status,
    )
  return rows.map((c) => {
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

export function deleteCosmeticAdmin(ctx: AppContext, actor: string, id: string): void {
  const c = getCosmetic(ctx, id)
  if (!c) throw notFound('cosmetic_not_found', 'Cosmetic not found')
  if (c.kind === 'builtin') throw conflict('builtin_cosmetic', 'Built-in cosmetics are managed by the generator, not deletable')
  removeCosmetic(ctx, c.id)
  audit(ctx, actor, 'cosmetic.delete', c.owner_uuid, c.id)
}

export function banUser(ctx: AppContext, actor: string, uuid: string, reason: string | undefined): void {
  if (isAdmin(ctx, uuid)) throw conflict('cannot_ban_admin', 'Admins cannot be banned; remove them from ADMIN_UUIDS first')
  tx(ctx.db, () => {
    run(
      ctx.db,
      `INSERT INTO bans (uuid, reason, banned_at, banned_by) VALUES (?, ?, ?, ?)
       ON CONFLICT(uuid) DO UPDATE SET reason = excluded.reason`,
      uuid, reason ?? null, ctx.now(), actor,
    )
    run(ctx.db, 'DELETE FROM sessions WHERE uuid = ?', uuid)
    audit(ctx, actor, 'user.ban', uuid, reason)
  })
  if (ctx.presence.delete(uuid)) broadcastPresence(ctx, uuid)
  endHostingFor(ctx, uuid)
  ctx.events.kick(uuid)
  ctx.watch.kick(uuid)
}

export function unbanUser(ctx: AppContext, actor: string, uuid: string): void {
  const n = run(ctx.db, 'DELETE FROM bans WHERE uuid = ?', uuid)
  if (n === 0) throw notFound('not_banned', 'This user is not banned')
  audit(ctx, actor, 'user.unban', uuid)
}

export interface AdminUserView {
  uuid: string
  name: string | null
  known: boolean
  admin: boolean
  banned: { reason: string | null, bannedAt: string, bannedBy: string } | null
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
  const ban = one<{ reason: string | null, banned_at: number, banned_by: string }>(
    ctx.db, 'SELECT reason, banned_at, banned_by FROM bans WHERE uuid = ?', uuid,
  )
  if (!u && !ban) throw notFound('user_not_found', 'Unknown user')
  const count = (sql: string, ...p: string[]) => one<{ n: number }>(ctx.db, sql, ...p)!.n
  return {
    uuid,
    name: u?.name ?? null,
    known: !!u,
    admin: isAdmin(ctx, uuid),
    banned: ban ? { reason: ban.reason, bannedAt: new Date(ban.banned_at).toISOString(), bannedBy: ban.banned_by } : null,
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
      banned: n('SELECT COUNT(*) AS n FROM bans'),
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

