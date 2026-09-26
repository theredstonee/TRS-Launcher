import { audit } from './audit'
import type { AppContext } from './context'
import { all, one, run, tx } from './db'
import { badRequest, conflict, notFound } from './errors'
import { MOD_MAX_MINUTES, MOD_MAX_WARN_MINUTES, SANCTION_KINDS, nameMap, type ActorRef, type SanctionKind, type Staff } from './sanctions'
import { getUser, staffRole, type StaffRole } from './users'

/**
 * Team-Rollen (§22.1): `ADMIN_UUIDS` sind immer Admin (nicht entziehbar, nicht strafbar). Weitere Admins
 * und Moderatoren vergeben Admins über `staff_roles`; jede Änderung steht im Audit-Log.
 */

/** Rechte je Rolle – die Oberfläche blendet danach ein/aus; geprüft wird immer serverseitig. */
export const PERMISSIONS = {
  moderator: [
    'dashboard', 'reports', 'reports.bulk', 'appeals', 'players', 'players.notes', 'sanctions', 'sanctions.temporary',
    'uploads.review', 'uploads.bulk', 'worlds', 'worlds.close', 'audit', 'codes.read', 'wordfilter.read',
  ],
  admin: [
    'dashboard', 'reports', 'reports.bulk', 'appeals', 'players', 'players.notes', 'sanctions', 'sanctions.temporary',
    'uploads.review', 'uploads.bulk', 'worlds', 'worlds.close', 'audit', 'codes.read', 'wordfilter.read',
    'sanctions.permanent', 'sanctions.account_ban', 'sanctions.modify_admin', 'roles', 'codes', 'wordfilter',
    'uploads.delete', 'grants',
  ],
} as const satisfies Record<StaffRole, readonly string[]>

export interface StaffLimits {
  /** Arten, die diese Rolle verhängen darf. */
  kinds: SanctionKind[]
  /** Höchstdauer in Minuten (`null` = unbegrenzt, auch dauerhaft). */
  maxMinutes: number | null
  maxWarnMinutes: number | null
  permanent: boolean
}

export function limitsOf(role: StaffRole): StaffLimits {
  if (role === 'admin') return { kinds: [...SANCTION_KINDS], maxMinutes: null, maxWarnMinutes: null, permanent: true }
  return {
    kinds: SANCTION_KINDS.filter((k) => k !== 'account_ban'),
    maxMinutes: MOD_MAX_MINUTES,
    maxWarnMinutes: MOD_MAX_WARN_MINUTES,
    permanent: false,
  }
}

export interface RoleView {
  uuid: string
  name: string | null
  role: StaffRole
  /** `env` = aus ADMIN_UUIDS (fest), `db` = in der Oberfläche vergeben. */
  source: 'env' | 'db'
  grantedAt: string | null
  grantedBy: ActorRef | null
  note: string | null
}

export function listRoles(ctx: AppContext): RoleView[] {
  const rows = all<{ uuid: string, role: StaffRole, granted_at: number, granted_by: string, note: string | null }>(
    ctx.db, 'SELECT * FROM staff_roles ORDER BY role, granted_at',
  )
  const env = [...ctx.config.adminUuids]
  const names = nameMap(ctx, [...env, ...rows.flatMap((r) => [r.uuid, r.granted_by])])
  return [
    ...env.map((u): RoleView => ({ uuid: u, name: names.get(u) ?? null, role: 'admin', source: 'env', grantedAt: null, grantedBy: null, note: null })),
    ...rows.filter((r) => !ctx.config.adminUuids.has(r.uuid)).map((r): RoleView => ({
      uuid: r.uuid,
      name: names.get(r.uuid) ?? null,
      role: r.role,
      source: 'db',
      grantedAt: new Date(r.granted_at).toISOString(),
      grantedBy: { uuid: r.granted_by, name: names.get(r.granted_by) ?? null },
      note: r.note,
    })),
  ]
}

/** Rolle vergeben/ändern (nur Admins – prüft die Route). */
export function setRole(ctx: AppContext, actor: Staff, uuid: string, role: StaffRole, note?: string): RoleView[] {
  if (ctx.config.adminUuids.has(uuid)) throw conflict('role_locked', 'This admin is configured on the server (ADMIN_UUIDS)')
  if (uuid === actor.uuid) throw badRequest('cannot_change_self', 'You cannot change your own role')
  if (!getUser(ctx, uuid)) throw notFound('user_not_found', 'The player must have signed in to TRS once')
  const before = staffRole(ctx, uuid)
  tx(ctx.db, () => {
    run(
      ctx.db,
      `INSERT INTO staff_roles (uuid, role, granted_at, granted_by, note) VALUES (?, ?, ?, ?, ?)
       ON CONFLICT(uuid) DO UPDATE SET role = excluded.role, granted_at = excluded.granted_at, granted_by = excluded.granted_by, note = excluded.note`,
      uuid, role, ctx.now(), actor.uuid, note ?? null,
    )
    audit(ctx, actor.uuid, 'role.set', uuid, `${before ?? 'none'} → ${role}`)
  })
  return listRoles(ctx)
}

export function removeRole(ctx: AppContext, actor: Staff, uuid: string): RoleView[] {
  if (ctx.config.adminUuids.has(uuid)) throw conflict('role_locked', 'This admin is configured on the server (ADMIN_UUIDS)')
  if (uuid === actor.uuid) throw badRequest('cannot_change_self', 'You cannot change your own role')
  const before = one<{ role: StaffRole }>(ctx.db, 'SELECT role FROM staff_roles WHERE uuid = ?', uuid)
  if (!before) throw notFound('role_not_found', 'This player has no team role')
  tx(ctx.db, () => {
    run(ctx.db, 'DELETE FROM staff_roles WHERE uuid = ?', uuid)
    // Website-Sitzungen enden sofort (sie prüfen die Rolle ohnehin bei jeder Anfrage).
    run(ctx.db, 'DELETE FROM web_sessions WHERE uuid = ?', uuid)
    audit(ctx, actor.uuid, 'role.remove', uuid, `${before.role} → none`)
  })
  return listRoles(ctx)
}
