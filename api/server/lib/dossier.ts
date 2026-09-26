import { audit } from './audit'
import { capeView, type CapeRow, type CapeView } from './capes'
import type { AppContext } from './context'
import { cosmeticView, type CosmeticRow, type CosmeticView } from './cosmetics'
import { all, one, placeholders, run } from './db'
import { badRequest, forbidden, notFound } from './errors'
import { adminRooms, type AdminRoomView } from './hosting'
import { reporterTrust, summaries, type AdminReportSummary, type ReportRow } from './moderation'
import {
  activeSanction,
  adminSanctionViews,
  encodeCursor,
  nameMap,
  sanctionsOf,
  type ActorRef,
  type AdminSanctionView,
  type SanctionKind,
  type Staff,
} from './sanctions'
import { limitsOf, type StaffLimits } from './staff'
import { ACTIVE_BANS, getUser, staffRole, type StaffRole } from './users'

/**
 * Spieler-Akte (§22.4): alles, was das Team zu einem Konto braucht – Profil, Namen, Strafen, Meldungen,
 * Uploads, Welten, interne Notizen – plus was der Betrachter damit tun darf. Spieler-Liste mit Filtern.
 */

const iso = (t: number) => new Date(t).toISOString()

export interface PlayerNoteView {
  id: number
  at: string
  actor: ActorRef
  text: string
  /** Darf der Betrachter sie löschen (eigene oder Admin)? */
  deletable: boolean
}

type Counts = { total: number, open: number, actioned: number, dismissed: number }

export interface PlayerFile {
  player: {
    uuid: string
    name: string | null
    known: boolean
    role: StaffRole | null
    online: boolean
    firstLoginAt: string | null
    lastLoginAt: string | null
    friends: number
    sessions: number
    banned: boolean
  }
  names: { name: string, firstSeen: string, lastSeen: string }[]
  sanctions: AdminSanctionView[]
  warnings: { total: number, active: number }
  reports: {
    against: { counts: Counts, recent: AdminReportSummary[] }
    filed: { counts: Counts, recent: AdminReportSummary[] }
    /** Melder-Score: Anteil bestätigter Meldungen (0–100, `null` ohne erledigte), `low` = wenig Vertrauen. */
    reporterScore: { actioned: number, dismissed: number, low: boolean, score: number | null }
  }
  capes: (CapeView & { createdAt: string, reports: number, source: 'upload' | 'code' | 'admin' })[]
  cosmetics: (CosmeticView & { createdAt: string | null, reports: number, source: 'upload' | 'code' | 'admin' })[]
  worlds: AdminRoomView[]
  notes: PlayerNoteView[]
  /** Was der Betrachter hier darf (Schnellaktionen). */
  can: { sanction: boolean, reason: string | null, limits: StaffLimits }
}

function reportCounts(ctx: AppContext, col: 'target_uuid' | 'reporter_uuid', uuid: string): Counts {
  // Spaltenname nur aus dem festen Typ oben.
  return one<Counts>(
    ctx.db,
    `SELECT COUNT(*) AS total, COALESCE(SUM(status <> 'resolved'), 0) AS open,
       COALESCE(SUM(outcome = 'actioned'), 0) AS actioned, COALESCE(SUM(outcome = 'dismissed'), 0) AS dismissed
     FROM chat_reports WHERE ${col} = ?`,
    uuid,
  )!
}

export function playerNotes(ctx: AppContext, viewer: Staff, uuid: string): PlayerNoteView[] {
  const rows = all<{ id: number, at: number, actor: string, text: string }>(
    ctx.db, 'SELECT * FROM player_notes WHERE uuid = ? ORDER BY at DESC, id DESC LIMIT 200', uuid,
  )
  const names = nameMap(ctx, rows.map((r) => r.actor))
  return rows.map((r) => ({
    id: r.id,
    at: iso(r.at),
    actor: { uuid: r.actor, name: names.get(r.actor) ?? null },
    text: r.text,
    deletable: viewer.role === 'admin' || r.actor === viewer.uuid,
  }))
}

/** Wer ist das? Bekannt = TRS-Konto, Strafe oder Meldung vorhanden. */
function knownUuid(ctx: AppContext, uuid: string): boolean {
  return getUser(ctx, uuid) !== undefined
    || one(ctx.db, 'SELECT 1 AS x FROM sanctions WHERE uuid = ? LIMIT 1', uuid) !== undefined
    || one(ctx.db, 'SELECT 1 AS x FROM chat_reports WHERE target_uuid = ? LIMIT 1', uuid) !== undefined
}

export function playerFile(ctx: AppContext, viewer: Staff, uuid: string): PlayerFile {
  if (!knownUuid(ctx, uuid)) throw notFound('user_not_found', 'Unknown player')
  const u = getUser(ctx, uuid)
  const role = staffRole(ctx, uuid)
  const count = (sql: string, ...p: (string | number)[]) => one<{ n: number }>(ctx.db, sql, ...p)!.n
  const names = all<{ name: string, first_seen: number, last_seen: number }>(
    ctx.db, 'SELECT name, first_seen, last_seen FROM name_history WHERE uuid = ? ORDER BY last_seen DESC', uuid,
  )
  const sanctions = adminSanctionViews(ctx, sanctionsOf(ctx, uuid, 200))
  const trust = reporterTrust(ctx, uuid)
  const decided = trust.actioned + trust.dismissed

  const capes = all<CapeRow & { src: 'upload' | 'code' | 'admin', since: number, reports: number }>(
    ctx.db,
    `SELECT c.*, 'upload' AS src, c.created_at AS since, (SELECT COUNT(*) FROM cape_reports r WHERE r.cape_id = c.id) AS reports
       FROM capes c WHERE c.owner_uuid = ? AND c.kind = 'upload'
     UNION ALL
     SELECT c.*, g.source AS src, g.granted_at AS since, 0 AS reports
       FROM user_capes g JOIN capes c ON c.id = g.cape_id WHERE g.uuid = ?
     ORDER BY since DESC LIMIT 100`,
    uuid, uuid,
  )
  const cosmetics = all<CosmeticRow & { src: 'upload' | 'code' | 'admin', since: number, reports: number }>(
    ctx.db,
    `SELECT c.*, 'upload' AS src, c.created_at AS since, (SELECT COUNT(*) FROM cosmetic_reports r WHERE r.cosmetic_id = c.id) AS reports
       FROM cosmetics c WHERE c.owner_uuid = ? AND c.kind = 'upload'
     UNION ALL
     SELECT c.*, g.source AS src, g.granted_at AS since, 0 AS reports
       FROM user_cosmetics g JOIN cosmetics c ON c.id = g.cosmetic_id WHERE g.uuid = ?
     ORDER BY since DESC LIMIT 100`,
    uuid, uuid,
  )

  let reason: string | null = null
  if (viewer.uuid === uuid) reason = 'self'
  else if (role === 'admin') reason = 'admin'
  else if (role === 'moderator' && viewer.role !== 'admin') reason = 'staff'

  return {
    player: {
      uuid,
      name: u?.name ?? names[0]?.name ?? null,
      known: !!u,
      role,
      online: ctx.presence.get(uuid) !== null,
      firstLoginAt: u ? iso(u.created_at) : null,
      lastLoginAt: u ? iso(u.last_login_at) : null,
      friends: count('SELECT COUNT(*) AS n FROM friendships WHERE a = ? OR b = ?', uuid, uuid),
      sessions: count("SELECT COUNT(*) AS n FROM sessions WHERE uuid = ? AND scope = 'full' AND expires_at > ?", uuid, ctx.now()),
      banned: activeSanction(ctx, uuid, 'account_ban') !== undefined,
    },
    names: names.map((n) => ({ name: n.name, firstSeen: iso(n.first_seen), lastSeen: iso(n.last_seen) })),
    sanctions,
    warnings: {
      total: sanctions.filter((s) => s.kind === 'warn').length,
      active: sanctions.filter((s) => s.kind === 'warn' && s.status === 'active').length,
    },
    reports: {
      against: {
        counts: reportCounts(ctx, 'target_uuid', uuid),
        recent: summaries(ctx, all<ReportRow>(ctx.db, 'SELECT * FROM chat_reports WHERE target_uuid = ? ORDER BY created_at DESC LIMIT 20', uuid)),
      },
      filed: {
        counts: reportCounts(ctx, 'reporter_uuid', uuid),
        recent: summaries(ctx, all<ReportRow>(ctx.db, 'SELECT * FROM chat_reports WHERE reporter_uuid = ? ORDER BY created_at DESC LIMIT 20', uuid)),
      },
      reporterScore: { ...trust, score: decided === 0 ? null : Math.round((trust.actioned / decided) * 100) },
    },
    capes: capes.map((c) => ({ ...capeView(ctx, c), createdAt: iso(c.since), reports: c.reports, source: c.src })),
    cosmetics: cosmetics.map((c) => ({ ...cosmeticView(ctx, c), createdAt: iso(c.since), reports: c.reports, source: c.src })),
    worlds: adminRooms(ctx, { uuid, limit: 20 }),
    notes: playerNotes(ctx, viewer, uuid),
    can: { sanction: reason === null, reason, limits: limitsOf(viewer.role) },
  }
}

// ---------------------------------------------------------------- Notizen

export function addPlayerNote(ctx: AppContext, actor: Staff, uuid: string, text: string): PlayerNoteView[] {
  if (!knownUuid(ctx, uuid)) throw notFound('user_not_found', 'Unknown player')
  const id = one<{ id: number }>(
    ctx.db, 'INSERT INTO player_notes (uuid, at, actor, text) VALUES (?, ?, ?, ?) RETURNING id', uuid, ctx.now(), actor.uuid, text,
  )!.id
  // Der Text bleibt in der Akte; das Audit-Log nennt nur die Notiz.
  audit(ctx, actor.uuid, 'player.note', uuid, `#${id}`)
  return playerNotes(ctx, actor, uuid)
}

export function deletePlayerNote(ctx: AppContext, actor: Staff, uuid: string, id: number): PlayerNoteView[] {
  const n = one<{ actor: string }>(ctx.db, 'SELECT actor FROM player_notes WHERE id = ? AND uuid = ?', id, uuid)
  if (!n) throw notFound('note_not_found', 'Note not found')
  if (actor.role !== 'admin' && n.actor !== actor.uuid) throw forbidden('admin_only', 'Only admins can delete notes of others')
  run(ctx.db, 'DELETE FROM player_notes WHERE id = ?', id)
  audit(ctx, actor.uuid, 'player.note.delete', uuid, `#${id}`)
  return playerNotes(ctx, actor, uuid)
}

// ---------------------------------------------------------------- Spieler-Liste

export interface PlayerListItem {
  uuid: string
  name: string
  role: StaffRole | null
  online: boolean
  createdAt: string
  lastLoginAt: string
  activeSanctions: SanctionKind[]
  openReports: number
}

export interface PlayerListQuery {
  q?: string
  status: 'all' | 'sanctioned' | 'banned' | 'staff' | 'reported'
  sort: 'last_login' | 'created'
  cursor?: string
  limit: number
}

export function listPlayers(ctx: AppContext, q: PlayerListQuery): { players: PlayerListItem[], nextCursor: string | null } {
  const where: string[] = []
  const params: (string | number)[] = []
  const t = ctx.now()
  if (q.q) {
    // Präfix des (aktuellen oder früheren) Namens, ohne Groß/klein – per Bereich, keine LIKE-Platzhalter.
    const p = q.q.toLowerCase()
    where.push(`(u.name_lower >= ? AND u.name_lower < ?
      OR u.uuid IN (SELECT h.uuid FROM name_history h WHERE lower(h.name) >= ? AND lower(h.name) < ?))`)
    params.push(p, `${p}￿`, p, `${p}￿`)
  }
  if (q.status === 'sanctioned') {
    where.push('u.uuid IN (SELECT uuid FROM sanctions WHERE lifted_at IS NULL AND (expires_at IS NULL OR expires_at > ?))')
    params.push(t)
  } else if (q.status === 'banned') {
    where.push(`u.uuid IN (${ACTIVE_BANS})`)
    params.push(t)
  } else if (q.status === 'staff') {
    const env = [...ctx.config.adminUuids]
    where.push(`(u.uuid IN (SELECT uuid FROM staff_roles)${env.length ? ` OR u.uuid IN (${placeholders(env.length)})` : ''})`)
    params.push(...env)
  } else if (q.status === 'reported') {
    where.push("u.uuid IN (SELECT target_uuid FROM chat_reports WHERE status <> 'resolved')")
  }
  const col = q.sort === 'created' ? 'u.created_at' : 'u.last_login_at'
  if (q.cursor) {
    const m = /^(\d{1,15}):([0-9a-f]{32})$/.exec(Buffer.from(q.cursor, 'base64url').toString('utf8'))
    if (!m) throw badRequest('invalid_cursor', 'Invalid cursor')
    where.push(`(${col} < ? OR (${col} = ? AND u.uuid < ?))`)
    params.push(Number(m[1]), Number(m[1]), m[2]!)
  }
  // Spalte nur aus dem festen Wert oben, Bedingungen aus festen Bausteinen, Werte als Parameter.
  const rows = all<{ uuid: string, name: string, created_at: number, last_login_at: number }>(
    ctx.db,
    `SELECT u.uuid, u.name, u.created_at, u.last_login_at FROM users u
     ${where.length ? `WHERE ${where.join(' AND ')}` : ''} ORDER BY ${col} DESC, u.uuid DESC LIMIT ?`,
    ...params, q.limit + 1,
  )
  const page = rows.slice(0, q.limit)
  const last = page[page.length - 1]
  const ids = page.map((r) => r.uuid)
  const active = new Map<string, SanctionKind[]>()
  const reports = new Map<string, number>()
  if (ids.length) {
    for (const r of all<{ uuid: string, kind: SanctionKind }>(
      ctx.db,
      `SELECT DISTINCT uuid, kind FROM sanctions WHERE uuid IN (${placeholders(ids.length)}) AND lifted_at IS NULL AND (expires_at IS NULL OR expires_at > ?)`,
      ...ids, t,
    )) active.set(r.uuid, [...(active.get(r.uuid) ?? []), r.kind])
    for (const r of all<{ target_uuid: string, n: number }>(
      ctx.db,
      `SELECT target_uuid, COUNT(*) AS n FROM chat_reports WHERE status <> 'resolved' AND target_uuid IN (${placeholders(ids.length)}) GROUP BY target_uuid`,
      ...ids,
    )) reports.set(r.target_uuid, r.n)
  }
  return {
    players: page.map((r) => ({
      uuid: r.uuid,
      name: r.name,
      role: staffRole(ctx, r.uuid),
      online: ctx.presence.get(r.uuid) !== null,
      createdAt: iso(r.created_at),
      lastLoginAt: iso(r.last_login_at),
      activeSanctions: active.get(r.uuid) ?? [],
      openReports: reports.get(r.uuid) ?? 0,
    })),
    nextCursor: rows.length > q.limit && last ? encodeCursor(q.sort === 'created' ? last.created_at : last.last_login_at, last.uuid) : null,
  }
}
