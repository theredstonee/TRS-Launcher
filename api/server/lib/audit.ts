import type { AppContext } from './context'
import { all, run } from './db'

/**
 * Audit-Log: jede Aktion von Admins, Moderatoren, API-Schlüssel und Automatik.
 * `ref` = Bezug (Meldungs-ID `r…`, Strafe `s<id>`, Einspruch `a<id>`), damit sich Einträge je Vorgang auflisten lassen.
 */
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

export interface AuditQuery {
  ref?: string
  target?: string
  actor?: string
  /** Präfix der Aktion, z. B. `sanction.` oder `report.`. */
  action?: string
  from?: number
  to?: number
  before?: number
  limit: number
}

/** Audit-Log lesen (neueste zuerst), filterbar; Cursor = `before` (id). */
export function listAudit(ctx: AppContext, opts: AuditQuery): { entries: AuditEntry[], nextBefore: number | null } {
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
  if (opts.actor) {
    where.push('l.actor = ?')
    params.push(opts.actor)
  }
  if (opts.action) {
    // Präfix per Bereich statt LIKE (keine Platzhalter-Zeichen aus der Eingabe).
    where.push('l.action >= ? AND l.action < ?')
    params.push(opts.action, `${opts.action}￿`)
  }
  if (opts.from !== undefined) {
    where.push('l.at >= ?')
    params.push(opts.from)
  }
  if (opts.to !== undefined) {
    where.push('l.at < ?')
    params.push(opts.to)
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
