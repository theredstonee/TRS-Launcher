import { statSync, statfsSync } from 'node:fs'
import { join } from 'node:path'
import { listAudit, type AuditEntry } from './audit'
import type { AppContext } from './context'
import { all, one } from './db'
import { normalizeUuid } from './ids'
import { REPORT_ID, reportStats, summaries, type AdminReportSummary, type ReportRow } from './moderation'
import { activeCounts, adminSanctionViews, getSanction, type AdminSanctionView, type SanctionKind } from './sanctions'
import { getUser, staffRole, type StaffRole } from './users'
import pkg from '../../package.json'

/** Übersicht (§22.5) und globale Suche (§22.7) für das Team. Nur Zahlen – keine Chat-Inhalte. */

const DAY = 86_400_000
const STARTED_AT = Date.now()

export interface Dashboard {
  reports: { open: number, inReview: number, highPriority: number, oldestOpenAt: string | null }
  appeals: { open: number, oldestOpenAt: string | null }
  sanctions: Record<SanctionKind, number>
  uploads: { capesPending: number, capesReported: number, cosmeticsPending: number, cosmeticsReported: number }
  users: { total: number, new24h: number, new7d: number, active24h: number, active7d: number, online: number }
  hosting: { openRooms: number, players: number }
  chat: { messages24h: number }
  /** Tageswerte (UTC) der letzten 30 Tage, ältester zuerst; die Website zeigt 7 oder 30. */
  series: { days: string[], newUsers: number[], messages: number[], reports: number[], sanctions: number[] }
  server: {
    version: string
    node: string
    uptimeSec: number
    startedAt: string
    dbBytes: number
    disk: { freeBytes: number, totalBytes: number } | null
  }
  recentAudit: AuditEntry[]
}

function fileSize(path: string): number {
  try {
    return statSync(path).size
  } catch {
    return 0
  }
}

/** Zählt Zeilen je UTC-Tag für die letzten `days` Tage. Tabelle/Spalte nur aus festen Werten unten. */
function perDay(ctx: AppContext, sql: string, from: number, days: number): number[] {
  const out = Array.from({ length: days }, () => 0)
  for (const r of all<{ d: number, n: number }>(ctx.db, sql, from)) {
    const i = r.d - Math.floor(from / DAY)
    if (i >= 0 && i < days) out[i] = r.n
  }
  return out
}

export function dashboard(ctx: AppContext): Dashboard {
  const t = ctx.now()
  const n = (sql: string, ...p: (string | number)[]) => one<{ n: number }>(ctx.db, sql, ...p)!.n
  const rs = reportStats(ctx)
  const highPriority = n(
    `SELECT COUNT(*) AS n FROM chat_reports c WHERE c.status <> 'resolved' AND (
       (c.reason IN ('insult_hate', 'harassment', 'scam_phishing') AND c.low_trust = 0)
       OR (c.target_uuid IS NOT NULL AND (SELECT COUNT(*) FROM chat_reports r2 WHERE r2.target_uuid = c.target_uuid AND r2.status <> 'resolved') >= 3))`,
  )
  const oldestReport = one<{ at: number | null }>(ctx.db, "SELECT MIN(created_at) AS at FROM chat_reports WHERE status <> 'resolved'")!.at
  const oldestAppeal = one<{ at: number | null }>(ctx.db, "SELECT MIN(created_at) AS at FROM sanction_appeals WHERE status = 'open'")!.at

  const days = 30
  const from = (Math.floor(t / DAY) - (days - 1)) * DAY
  const dayList = Array.from({ length: days }, (_, i) => new Date(from + i * DAY).toISOString().slice(0, 10))

  let disk: Dashboard['server']['disk']
  try {
    const s = statfsSync(ctx.config.dataDir)
    disk = { freeBytes: Number(s.bavail) * Number(s.bsize), totalBytes: Number(s.blocks) * Number(s.bsize) }
  } catch {
    disk = null
  }
  const db = join(ctx.config.dataDir, 'trs.db')

  return {
    reports: { open: rs.open, inReview: rs.inReview, highPriority, oldestOpenAt: oldestReport === null ? null : new Date(oldestReport).toISOString() },
    appeals: {
      open: n("SELECT COUNT(*) AS n FROM sanction_appeals WHERE status = 'open'"),
      oldestOpenAt: oldestAppeal === null ? null : new Date(oldestAppeal).toISOString(),
    },
    sanctions: activeCounts(ctx),
    uploads: {
      capesPending: n("SELECT COUNT(*) AS n FROM capes WHERE kind = 'upload' AND status = 'pending'"),
      capesReported: n('SELECT COUNT(DISTINCT cape_id) AS n FROM cape_reports'),
      cosmeticsPending: n("SELECT COUNT(*) AS n FROM cosmetics WHERE kind = 'upload' AND status = 'pending'"),
      cosmeticsReported: n('SELECT COUNT(DISTINCT cosmetic_id) AS n FROM cosmetic_reports'),
    },
    users: {
      total: n('SELECT COUNT(*) AS n FROM users'),
      new24h: n('SELECT COUNT(*) AS n FROM users WHERE created_at > ?', t - DAY),
      new7d: n('SELECT COUNT(*) AS n FROM users WHERE created_at > ?', t - 7 * DAY),
      active24h: n('SELECT COUNT(*) AS n FROM users WHERE last_login_at > ?', t - DAY),
      active7d: n('SELECT COUNT(*) AS n FROM users WHERE last_login_at > ?', t - 7 * DAY),
      online: ctx.presence.count(),
    },
    hosting: {
      openRooms: n('SELECT COUNT(*) AS n FROM hosting_rooms'),
      players: n('SELECT COALESCE(SUM(players), 0) AS n FROM hosting_rooms'),
    },
    chat: { messages24h: n('SELECT COUNT(*) AS n FROM chat_messages WHERE created_at > ?', t - DAY) },
    series: {
      days: dayList,
      newUsers: perDay(ctx, 'SELECT created_at / 86400000 AS d, COUNT(*) AS n FROM users WHERE created_at >= ? GROUP BY d', from, days),
      messages: perDay(ctx, 'SELECT created_at / 86400000 AS d, COUNT(*) AS n FROM chat_messages WHERE created_at >= ? GROUP BY d', from, days),
      reports: perDay(ctx, 'SELECT created_at / 86400000 AS d, COUNT(*) AS n FROM chat_reports WHERE created_at >= ? GROUP BY d', from, days),
      sanctions: perDay(ctx, 'SELECT created_at / 86400000 AS d, COUNT(*) AS n FROM sanctions WHERE created_at >= ? GROUP BY d', from, days),
    },
    server: {
      version: (pkg as { version: string }).version,
      node: process.version,
      uptimeSec: Math.round(process.uptime()),
      startedAt: new Date(STARTED_AT).toISOString(),
      dbBytes: fileSize(db) + fileSize(`${db}-wal`) + fileSize(`${db}-shm`),
      disk,
    },
    recentAudit: listAudit(ctx, { limit: 12 }).entries,
  }
}

// ---------------------------------------------------------------- Suche

export interface SearchResult {
  players: { uuid: string, name: string | null, role: StaffRole | null, matched: string | null }[]
  reports: AdminReportSummary[]
  capes: { id: string, name: string, kind: string, status: string, owner: { uuid: string, name: string | null } | null }[]
  cosmetics: { id: string, name: string, kind: string, status: string, slot: string, owner: { uuid: string, name: string | null } | null }[]
  sanctions: AdminSanctionView[]
}

/**
 * Globale Suche: Spielername (auch frühere Namen, Präfix), UUID, Melde-ID (`r…`), Strafe (`#12`/`s12`),
 * Umhang-/Kosmetik-Name oder -ID. Höchstens 8 Treffer je Gruppe.
 */
export function search(ctx: AppContext, raw: string): SearchResult {
  const q = raw.trim()
  const out: SearchResult = { players: [], reports: [], capes: [], cosmetics: [], sanctions: [] }
  if (!q) return out

  const reportId = q.toLowerCase()
  if (REPORT_ID.test(reportId)) {
    const r = one<ReportRow>(ctx.db, 'SELECT * FROM chat_reports WHERE id = ?', reportId)
    if (r) out.reports = summaries(ctx, [r])
  }
  const sid = /^(?:#|s)(\d{1,12})$/i.exec(q)
  if (sid) {
    const s = getSanction(ctx, Number(sid[1]))
    if (s) out.sanctions = adminSanctionViews(ctx, [s])
  }
  const uuid = normalizeUuid(q)
  if (uuid) {
    const u = getUser(ctx, uuid)
    const known = u || one(ctx.db, 'SELECT 1 AS x FROM sanctions WHERE uuid = ? LIMIT 1', uuid) || one(ctx.db, 'SELECT 1 AS x FROM chat_reports WHERE target_uuid = ? LIMIT 1', uuid)
    if (known) out.players.push({ uuid, name: u?.name ?? null, role: staffRole(ctx, uuid), matched: null })
  }
  if (q.length >= 2 && /^[A-Za-z0-9_]{2,16}$/.test(q)) {
    const p = q.toLowerCase()
    const seen = new Set(out.players.map((x) => x.uuid))
    for (const r of all<{ uuid: string, name: string, matched: string }>(
      ctx.db,
      `SELECT u.uuid, u.name, h.name AS matched FROM name_history h JOIN users u ON u.uuid = h.uuid
       WHERE lower(h.name) >= ? AND lower(h.name) < ?
       ORDER BY (u.name_lower = ?) DESC, (h.name = u.name) DESC, u.last_login_at DESC LIMIT 16`,
      p, `${p}￿`, p,
    )) {
      if (seen.has(r.uuid) || out.players.length >= 8) continue
      seen.add(r.uuid)
      out.players.push({ uuid: r.uuid, name: r.name, role: staffRole(ctx, r.uuid), matched: r.matched.toLowerCase() === r.name.toLowerCase() ? null : r.matched })
    }
  }
  if (q.length >= 2 && q.length <= 64) {
    out.capes = all<{ id: string, name: string, kind: string, status: string, owner_uuid: string | null, owner_name: string | null }>(
      ctx.db,
      `SELECT c.id, c.name, c.kind, c.status, c.owner_uuid, u.name AS owner_name FROM capes c LEFT JOIN users u ON u.uuid = c.owner_uuid
       WHERE c.id = ? OR instr(lower(c.name), lower(?)) > 0 ORDER BY c.kind = 'upload' DESC, c.created_at DESC LIMIT 8`,
      q, q,
    ).map((c) => ({ id: c.id, name: c.name, kind: c.kind, status: c.status, owner: c.owner_uuid ? { uuid: c.owner_uuid, name: c.owner_name } : null }))
    out.cosmetics = all<{ id: string, name: string, kind: string, status: string, slot: string, owner_uuid: string | null, owner_name: string | null }>(
      ctx.db,
      `SELECT c.id, c.name, c.kind, c.status, c.slot, c.owner_uuid, u.name AS owner_name FROM cosmetics c LEFT JOIN users u ON u.uuid = c.owner_uuid
       WHERE c.slot <> 'emote' AND (c.id = ? OR instr(lower(c.name), lower(?)) > 0) ORDER BY c.kind = 'upload' DESC, c.created_at DESC LIMIT 8`,
      q, q,
    ).map((c) => ({ id: c.id, name: c.name, kind: c.kind, status: c.status, slot: c.slot, owner: c.owner_uuid ? { uuid: c.owner_uuid, name: c.owner_name } : null }))
  }
  return out
}
