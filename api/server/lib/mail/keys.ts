import { randomBytes } from 'node:crypto'
import { audit } from '../audit'
import type { AppContext } from '../context'
import { all, one, run } from '../db'
import { ApiError, badRequest, forbidden, notFound, unauthorized } from '../errors'
import { sha256Hex } from '../ids'
import { ADMIN_RANK, can, type Staff } from '../team'
import type { ActorRefView, MailKeyView } from '../../../shared/newsletter'

/**
 * API-Schlüssel für den Newsletter (§40.5): `Authorization: Bearer trsk_…`. Rechte nur `newsletter.manage` und/oder
 * `newsletter.send` – und NUR auf den Newsletter-Routen (`requireNewsletter`), überall sonst gilt der Schlüssel nicht
 * (dort erwartet die API Sitzungs-Tokens `trs_…`). In der DB liegt nur der SHA-256. Verwalten dürfen Owner und Admins.
 */

export const KEY_PERMISSIONS = ['newsletter.manage', 'newsletter.send'] as const
export type KeyPermission = (typeof KEY_PERMISSIONS)[number]
export const API_KEY = /^trsk_[A-Za-z0-9_-]{43}$/
export const MAX_KEYS = 20

interface KeyRow {
  id: number
  name: string
  key_hash: string
  hint: string
  permissions: string
  test_email: string | null
  created_at: number
  created_by: string
  last_used_at: number | null
  revoked_at: number | null
}

/** Wer im Newsletter handelt: Team-Mitglied (Sitzung/Bearer/X-Admin-Key) oder API-Schlüssel. */
export type NewsletterActor =
  | { kind: 'staff', staff: Staff }
  | { kind: 'key', id: number, name: string, perms: ReadonlySet<KeyPermission>, testEmail: string | null }

export function actorId(a: NewsletterActor): string {
  return a.kind === 'staff' ? a.staff.uuid : `key:${a.id}`
}

export function actorCan(a: NewsletterActor, p: KeyPermission): boolean {
  return a.kind === 'staff' ? can(a.staff, p) : a.perms.has(p)
}

export function assertActorCan(a: NewsletterActor, p: KeyPermission): void {
  if (!actorCan(a, p)) throw new ApiError(403, 'missing_permission', `Missing the permission ${p}`, { permission: p })
}

function parsePerms(raw: string): KeyPermission[] {
  try {
    const v = JSON.parse(raw) as unknown
    return Array.isArray(v) ? KEY_PERMISSIONS.filter((p) => v.includes(p)) : []
  } catch {
    return []
  }
}

/** Schlüssel prüfen (zeitkonstant über den Hash), `last_used_at` grob mitschreiben. */
export function authenticateKey(ctx: AppContext, token: string): NewsletterActor {
  if (!API_KEY.test(token)) throw unauthorized('Invalid API key')
  const row = one<KeyRow>(ctx.db, 'SELECT * FROM mail_api_keys WHERE key_hash = ?', sha256Hex(token))
  if (!row || row.revoked_at !== null) throw unauthorized('Invalid or revoked API key')
  const t = ctx.now()
  if (row.last_used_at === null || t - row.last_used_at > 60_000) run(ctx.db, 'UPDATE mail_api_keys SET last_used_at = ? WHERE id = ?', t, row.id)
  return { kind: 'key', id: row.id, name: row.name, perms: new Set(parsePerms(row.permissions)), testEmail: row.test_email }
}

/** Akteur-Kennung (UUID, `key:<id>`, `system`, `api-key`) → Anzeige. */
export function actorRef(ctx: AppContext, id: string): ActorRefView {
  if (id.startsWith('key:')) {
    const k = one<{ name: string }>(ctx.db, 'SELECT name FROM mail_api_keys WHERE id = ?', Number(id.slice(4)))
    return { kind: 'key', uuid: null, name: k?.name ?? id }
  }
  if (id === 'system' || id === 'api-key') return { kind: 'system', uuid: null, name: id }
  const u = one<{ name: string }>(ctx.db, 'SELECT name FROM users WHERE uuid = ?', id)
  return { kind: 'staff', uuid: id, name: u?.name ?? id.slice(0, 8) }
}

function view(ctx: AppContext, r: KeyRow): MailKeyView {
  return {
    id: r.id,
    name: r.name,
    hint: r.hint,
    permissions: parsePerms(r.permissions),
    testEmail: r.test_email,
    createdAt: new Date(r.created_at).toISOString(),
    createdBy: actorRef(ctx, r.created_by),
    lastUsedAt: r.last_used_at === null ? null : new Date(r.last_used_at).toISOString(),
    revokedAt: r.revoked_at === null ? null : new Date(r.revoked_at).toISOString(),
  }
}

/** Schlüssel verwalten: nur Owner und Admins (Rang ≥ Admin). */
export function assertKeyAdmin(staff: Staff): void {
  if (!staff.owner && staff.rank < ADMIN_RANK) throw forbidden('forbidden', 'Only owners and admins can manage API keys')
}

export function listKeys(ctx: AppContext): MailKeyView[] {
  return all<KeyRow>(ctx.db, 'SELECT * FROM mail_api_keys ORDER BY revoked_at IS NOT NULL, id DESC').map((r) => view(ctx, r))
}

export function createKey(ctx: AppContext, staff: Staff, input: { name: string, permissions: KeyPermission[], testEmail: string | null }): { key: MailKeyView, token: string } {
  assertKeyAdmin(staff)
  const perms = KEY_PERMISSIONS.filter((p) => input.permissions.includes(p))
  if (perms.length === 0) throw badRequest('invalid_request', 'Pick at least one permission')
  const active = one<{ n: number }>(ctx.db, 'SELECT COUNT(*) AS n FROM mail_api_keys WHERE revoked_at IS NULL')!.n
  if (active >= MAX_KEYS) throw new ApiError(409, 'too_many_keys', `At most ${MAX_KEYS} active API keys`)
  const token = `trsk_${randomBytes(32).toString('base64url')}`
  const row = one<KeyRow>(
    ctx.db,
    'INSERT INTO mail_api_keys (name, key_hash, hint, permissions, test_email, created_at, created_by) VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING *',
    input.name, sha256Hex(token), token.slice(-4), JSON.stringify(perms), input.testEmail, ctx.now(), staff.uuid,
  )!
  audit(ctx, staff.uuid, 'newsletter.key.create', null, `${input.name} (${perms.join(', ')})`, `k${row.id}`)
  return { key: view(ctx, row), token }
}

export function revokeKey(ctx: AppContext, staff: Staff, id: number): MailKeyView {
  assertKeyAdmin(staff)
  const row = one<KeyRow>(ctx.db, 'SELECT * FROM mail_api_keys WHERE id = ?', id)
  if (!row) throw notFound('key_not_found', 'API key not found')
  if (row.revoked_at === null) {
    run(ctx.db, 'UPDATE mail_api_keys SET revoked_at = ? WHERE id = ?', ctx.now(), id)
    audit(ctx, staff.uuid, 'newsletter.key.revoke', null, row.name, `k${id}`)
  }
  return view(ctx, one<KeyRow>(ctx.db, 'SELECT * FROM mail_api_keys WHERE id = ?', id)!)
}
