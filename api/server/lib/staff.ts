import type { AppContext } from './context'
import { all } from './db'
import { notFound } from './errors'
import { legacyRole, listMembers, setMemberRoles, type Staff } from './team'
import type { StaffRole } from './users'

/**
 * Alte Rollen-API (§22.1: `GET/PUT/DELETE /v1/admin/roles`) für ältere Launcher – eine dünne Schicht über den
 * Team-Rollen (team.ts, §24.2). `admin`/`moderator` entsprechen den festen Rollen gleichen Namens; andere Rollen
 * eines Mitglieds bleiben beim Ändern erhalten. Rechte + Rang-Regel wie bei `setMemberRoles`.
 */

export interface RoleView {
  uuid: string
  name: string | null
  role: StaffRole
  /** `env` = aus ADMIN_UUIDS (fest), `db` = in der Oberfläche vergeben. */
  source: 'env' | 'db'
  grantedAt: string | null
  grantedBy: { uuid: string, name: string | null } | null
  note: string | null
}

export function listRoles(ctx: AppContext, viewer: Staff | null = null): RoleView[] {
  return listMembers(ctx, viewer).map((m) => ({
    uuid: m.uuid,
    name: m.name,
    role: legacyRole(m.rank),
    source: m.source,
    grantedAt: m.grantedAt,
    grantedBy: m.grantedBy,
    note: m.note,
  }))
}

/** Rolle vergeben/ändern: ersetzt `admin`/`moderator`, weitere Rollen bleiben. */
export function setRole(ctx: AppContext, actor: Staff, uuid: string, role: StaffRole, note?: string): RoleView[] {
  const others = all<{ role_id: string }>(ctx.db, 'SELECT role_id FROM team_members WHERE uuid = ?', uuid)
    .map((r) => r.role_id)
    .filter((id) => id !== 'admin' && id !== 'moderator')
  setMemberRoles(ctx, actor, uuid, [...others, role], note ?? null)
  return listRoles(ctx, actor)
}

/** Aus dem Team entfernen (alle Rollen). */
export function removeRole(ctx: AppContext, actor: Staff, uuid: string): RoleView[] {
  if (!ctx.config.adminUuids.has(uuid) && all(ctx.db, 'SELECT 1 AS x FROM team_members WHERE uuid = ?', uuid).length === 0) {
    throw notFound('role_not_found', 'This player has no team role')
  }
  setMemberRoles(ctx, actor, uuid, [])
  return listRoles(ctx, actor)
}
