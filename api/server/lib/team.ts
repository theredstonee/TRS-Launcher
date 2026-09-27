import { randomBytes } from 'node:crypto'
import { audit } from './audit'
import type { AppContext } from './context'
import { all, one, placeholders, run, tx } from './db'
import { ApiError, badRequest, conflict, forbidden, notFound } from './errors'
import { sanitizeText } from './safety'

/**
 * Team: feste und eigene Rollen mit feingranularen Rechten (API.md §24.2).
 *
 * - **Rechte** sind feste Schlüssel (`PERMISSIONS`). Die Oberfläche blendet danach ein/aus, der Server prüft
 *   JEDE Anfrage selbst (`requireStaff(event, 'reports.view')`).
 * - **Rollen** haben einen Rang (höher = mächtiger, eindeutig). Feste Rollen (`builtin`) lassen sich nicht
 *   löschen; ihre Rechte, Farbe, Rang und Sichtbarkeit sind anpassbar – außer bei `owner`.
 * - **Owner** = `ADMIN_UUIDS`: immer alle Rechte, Rang 1000, nicht aussperrbar, nicht in der Oberfläche vergebbar.
 * - **Mitglieder** können mehrere Rollen haben. Die ranghöchste ist die **Hauptrolle** (Rang, Farbe, Anzeige auf
 *   der Team-Seite); die übrigen ergänzen nur Rechte (Vereinigung). Höchstdauer: die längste der Rollen, die
 *   überhaupt Strafen verhängen dürfen.
 * - **Rang-Regel:** Niemand bearbeitet Rollen oder Mitglieder mit gleichem oder höherem Rang, niemand vergibt
 *   Rechte, die er selbst nicht hat.
 */

import {
  BUILTIN_ROLES,
  MIN_WARN_MINUTES,
  OWNER_RANK,
  ADMIN_RANK,
  PERMISSIONS,
  SANCTION_PERMISSION,
  isPermission,
  type Permission,
} from '../../shared/team'

export * from '../../shared/team'

const SANCTION_PERMS: ReadonlySet<Permission> = new Set(Object.values(SANCTION_PERMISSION))
const BUILTIN_IDS: ReadonlySet<string> = new Set(BUILTIN_ROLES.map((r) => r.id))

// ---------------------------------------------------------------- Akteur

/** Wer im Team handelt (Website-Sitzung, Bearer-Token, `X-Admin-Key` oder das System). */
export interface Staff {
  uuid: string
  /** Altes Raster für ältere Clients und `created_role`: `admin` ab Admin-Rang, sonst `moderator`. */
  role: 'admin' | 'moderator'
  rank: number
  owner: boolean
  perms: ReadonlySet<Permission>
  /** Höchstdauer befristeter Strafen in Minuten; `null` = unbegrenzt (dauerhaft braucht zusätzlich `sanctions.permanent`). */
  maxMinutes: number | null
  /** Rollen-IDs, ranghöchste zuerst. */
  roles: string[]
}

const ALL_PERMS: ReadonlySet<Permission> = new Set(PERMISSIONS)

export function ownerStaff(uuid: string): Staff {
  return { uuid, role: 'admin', rank: OWNER_RANK, owner: true, perms: ALL_PERMS, maxMinutes: null, roles: ['owner'] }
}

export const SYSTEM: Staff = ownerStaff('system')

export function can(staff: Staff, p: Permission): boolean {
  return staff.owner || staff.perms.has(p)
}

export function assertCan(staff: Staff, p: Permission): void {
  if (!can(staff, p)) throw new ApiError(403, 'missing_permission', `Your role is missing the permission ${p}`, { permission: p })
}

export function legacyRole(rank: number): 'admin' | 'moderator' {
  return rank >= ADMIN_RANK ? 'admin' : 'moderator'
}

// ---------------------------------------------------------------- Rollen lesen

export interface RoleRow {
  id: string
  name: string | null
  color: string
  rank: number
  permissions: string
  max_sanction_minutes: number | null
  builtin: number
  public: number
  created_at: number
  updated_at: number
}

function parsePerms(raw: string): Permission[] {
  try {
    const list = JSON.parse(raw) as unknown
    return Array.isArray(list) ? list.filter((p): p is Permission => typeof p === 'string' && isPermission(p)) : []
  } catch {
    return []
  }
}

/** Mitglieds-Rollen einer UUID (ranghöchste zuerst). */
function memberRoles(ctx: AppContext, uuid: string): RoleRow[] {
  return all<RoleRow>(
    ctx.db,
    'SELECT r.* FROM team_members m JOIN team_roles r ON r.id = m.role_id WHERE m.uuid = ? ORDER BY r.rank DESC',
    uuid,
  )
}

/** Effektive Team-Rechte (oder `null`, wenn kein Team-Mitglied). Owner = `ADMIN_UUIDS`. */
export function teamOf(ctx: AppContext, uuid: string): Staff | null {
  if (ctx.config.adminUuids.has(uuid)) return ownerStaff(uuid)
  const roles = memberRoles(ctx, uuid)
  if (roles.length === 0) return null
  const perms = new Set<Permission>()
  let maxMinutes: number | null = 0
  let sanctioning = false
  for (const r of roles) {
    const ps = parsePerms(r.permissions)
    for (const p of ps) perms.add(p)
    if (ps.some((p) => SANCTION_PERMS.has(p))) {
      sanctioning = true
      if (maxMinutes !== null) maxMinutes = r.max_sanction_minutes === null ? null : Math.max(maxMinutes, r.max_sanction_minutes)
    }
  }
  const rank = roles[0]!.rank
  return {
    uuid,
    role: legacyRole(rank),
    rank,
    owner: false,
    perms,
    maxMinutes: sanctioning ? maxMinutes : 0,
    roles: roles.map((r) => r.id),
  }
}

/** Rang einer UUID im Team (0 = kein Mitglied). */
export function rankOf(ctx: AppContext, uuid: string): number {
  if (ctx.config.adminUuids.has(uuid)) return OWNER_RANK
  return one<{ rank: number | null }>(
    ctx.db, 'SELECT MAX(r.rank) AS rank FROM team_members m JOIN team_roles r ON r.id = m.role_id WHERE m.uuid = ?', uuid,
  )?.rank ?? 0
}

export function isTeamMember(ctx: AppContext, uuid: string): boolean {
  return rankOf(ctx, uuid) > 0
}

/** Grenzen für Strafen (Anzeige; geprüft wird in sanctions.ts). */
export interface StaffLimits {
  kinds: string[]
  maxMinutes: number | null
  maxWarnMinutes: number | null
  permanent: boolean
}

export function limitsOf(staff: Staff): StaffLimits {
  const kinds = Object.entries(SANCTION_PERMISSION).filter(([, p]) => can(staff, p)).map(([k]) => k)
  const max = staff.owner ? null : staff.maxMinutes
  return {
    kinds,
    maxMinutes: max,
    maxWarnMinutes: max === null ? null : Math.max(max, MIN_WARN_MINUTES),
    permanent: can(staff, 'sanctions.permanent'),
  }
}

/** Was die Oberflächen über die eigene Team-Zugehörigkeit erfahren (`GET /v1/me`, `GET /v1/web/me`). */
export interface MyTeamView {
  owner: boolean
  rank: number
  roles: RoleRef[]
  permissions: Permission[]
  limits: StaffLimits
}

export interface RoleRef {
  id: string
  name: string | null
  color: string
  builtin: boolean
}

export function myTeamView(ctx: AppContext, staff: Staff): MyTeamView {
  const refs = staff.owner && ctx.config.adminUuids.has(staff.uuid)
    ? [roleRef(getRole(ctx, 'owner')!), ...memberRoles(ctx, staff.uuid).map(roleRef)]
    : memberRoles(ctx, staff.uuid).map(roleRef)
  return {
    owner: staff.owner,
    rank: staff.rank,
    roles: refs,
    permissions: PERMISSIONS.filter((p) => can(staff, p)),
    limits: limitsOf(staff),
  }
}

function roleRef(r: RoleRow): RoleRef {
  return { id: r.id, name: r.name, color: r.color, builtin: r.builtin === 1 }
}

export function getRole(ctx: AppContext, id: string): RoleRow | undefined {
  return one<RoleRow>(ctx.db, 'SELECT * FROM team_roles WHERE id = ?', id)
}

export interface RoleView {
  id: string
  /** `null` bei festen Rollen ohne eigenen Namen → Clients übersetzen `id`. */
  name: string | null
  color: string
  rank: number
  permissions: Permission[]
  maxSanctionMinutes: number | null
  builtin: boolean
  /** Owner: Rechte und Rang fest. */
  locked: boolean
  public: boolean
  members: number
  /** Darf der Betrachter diese Rolle bearbeiten/vergeben (Rang-Regel)? */
  editable: boolean
}

export function roleView(ctx: AppContext, r: RoleRow, viewer: Staff | null, members?: number): RoleView {
  const locked = r.id === 'owner'
  return {
    id: r.id,
    name: r.name,
    color: r.color,
    rank: r.rank,
    permissions: locked ? [...PERMISSIONS] : parsePerms(r.permissions),
    maxSanctionMinutes: r.max_sanction_minutes,
    builtin: r.builtin === 1,
    locked,
    public: r.public === 1,
    members: members ?? (locked ? ctx.config.adminUuids.size : one<{ n: number }>(ctx.db, 'SELECT COUNT(*) AS n FROM team_members WHERE role_id = ?', r.id)!.n),
    editable: !!viewer && !locked && (viewer.owner || r.rank < viewer.rank) && can(viewer, 'roles.manage'),
  }
}

export function listRoleViews(ctx: AppContext, viewer: Staff | null): RoleView[] {
  const counts = new Map(all<{ role_id: string, n: number }>(ctx.db, 'SELECT role_id, COUNT(*) AS n FROM team_members GROUP BY role_id').map((c) => [c.role_id, c.n]))
  return all<RoleRow>(ctx.db, 'SELECT * FROM team_roles ORDER BY rank DESC').map((r) => roleView(ctx, r, viewer, r.id === 'owner' ? ctx.config.adminUuids.size : counts.get(r.id) ?? 0))
}

// ---------------------------------------------------------------- Rollen ändern

export interface RoleInput {
  name?: string | null
  color?: string
  rank?: number
  permissions?: string[]
  maxSanctionMinutes?: number | null
  public?: boolean
}

const COLOR = /^#[0-9a-f]{6}$/
export const MAX_CUSTOM_ROLES = 50

function assertRoleEditable(actor: Staff, r: RoleRow): void {
  assertCan(actor, 'roles.manage')
  if (r.id === 'owner' && !actor.owner) throw forbidden('rank_too_low', 'Only owners can change the owner role')
  if (!actor.owner && r.rank >= actor.rank) throw forbidden('rank_too_low', 'You can only change roles below your own rank')
}

/** Rechte prüfen: gültige Schlüssel, nur solche, die der Handelnde selbst hat. */
function checkedPerms(actor: Staff, list: string[], before: Permission[] = []): Permission[] {
  const out = new Set<Permission>()
  for (const p of list) {
    if (!isPermission(p)) throw badRequest('invalid_permission', `Unknown permission ${p}`)
    out.add(p)
  }
  if (!actor.owner) {
    for (const p of out) {
      // Was schon drin ist, darf bleiben – nur NEUE Rechte muss man selbst haben.
      if (!before.includes(p) && !can(actor, p)) {
        throw new ApiError(403, 'missing_permission', `You cannot grant ${p} because you don't have it`, { permission: p })
      }
    }
  }
  return PERMISSIONS.filter((p) => out.has(p))
}

function checkedRank(ctx: AppContext, actor: Staff, rank: number, exceptId?: string): number {
  if (!Number.isInteger(rank) || rank < 1 || rank >= OWNER_RANK) throw badRequest('invalid_rank', 'Rank must be between 1 and 999')
  if (!actor.owner && rank >= actor.rank) throw forbidden('rank_too_low', 'The rank must be below your own rank')
  const clash = one<{ id: string }>(ctx.db, 'SELECT id FROM team_roles WHERE rank = ? AND id <> ?', rank, exceptId ?? '')
  if (clash) throw conflict('rank_taken', `Another role (${clash.id}) already has this rank`)
  return rank
}

function checkedName(name: string | null | undefined): string | null {
  if (name === undefined || name === null) return null
  const n = sanitizeText(name.normalize('NFKC')).replace(/\s+/g, ' ')
  if (n.length < 2 || n.length > 32) throw badRequest('invalid_name', 'Role names have 2–32 characters')
  return n
}

export function createRole(ctx: AppContext, actor: Staff, input: Required<Pick<RoleInput, 'name' | 'color' | 'rank'>> & RoleInput): RoleView {
  assertCan(actor, 'roles.manage')
  const name = checkedName(input.name)
  if (!name) throw badRequest('invalid_name', 'Custom roles need a name')
  if (!COLOR.test(input.color)) throw badRequest('invalid_color', 'Colour must be #rrggbb')
  const count = one<{ n: number }>(ctx.db, 'SELECT COUNT(*) AS n FROM team_roles WHERE builtin = 0')!.n
  if (count >= MAX_CUSTOM_ROLES) throw conflict('role_limit', `At most ${MAX_CUSTOM_ROLES} custom roles`)
  if (one(ctx.db, 'SELECT 1 AS x FROM team_roles WHERE lower(name) = lower(?)', name)) throw conflict('name_taken', 'A role with this name exists')
  const rank = checkedRank(ctx, actor, input.rank)
  const perms = checkedPerms(actor, input.permissions ?? [])
  const max = checkedMax(actor, input.maxSanctionMinutes ?? null)
  const id = `r${randomBytes(6).toString('hex')}`
  const t = ctx.now()
  tx(ctx.db, () => {
    run(
      ctx.db,
      `INSERT INTO team_roles (id, name, color, rank, permissions, max_sanction_minutes, builtin, public, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, ?)`,
      id, name, input.color, rank, JSON.stringify(perms), max, input.public === false ? 0 : 1, t, t,
    )
    audit(ctx, actor.uuid, 'role.create', null, `${name} (rank ${rank}): ${perms.join(', ') || '–'}`, `role:${id}`)
  })
  return roleView(ctx, getRole(ctx, id)!, actor)
}

function checkedMax(actor: Staff, v: number | null): number | null {
  if (v !== null && (!Number.isInteger(v) || v < 1 || v > 3650 * 1440)) throw badRequest('invalid_duration', 'Invalid maximum duration')
  // Niemand vergibt längere Strafen, als er selbst darf.
  if (!actor.owner && actor.maxMinutes !== null && (v === null || v > actor.maxMinutes)) {
    throw forbidden('duration_not_allowed', 'The maximum duration cannot be longer than your own')
  }
  return v
}

export function updateRole(ctx: AppContext, actor: Staff, id: string, input: RoleInput): RoleView {
  const r = getRole(ctx, id)
  if (!r) throw notFound('role_not_found', 'Role not found')
  assertRoleEditable(actor, r)
  const locked = r.id === 'owner'
  if (locked && (input.permissions !== undefined || input.rank !== undefined || input.maxSanctionMinutes !== undefined)) {
    throw conflict('role_locked', 'The owner role always has every permission and the highest rank')
  }
  const sets: string[] = []
  const params: (string | number | null)[] = []
  const changes: string[] = []
  if (input.name !== undefined) {
    const name = checkedName(input.name)
    if (!name && r.builtin === 0) throw badRequest('invalid_name', 'Custom roles need a name')
    if (name && one(ctx.db, 'SELECT 1 AS x FROM team_roles WHERE lower(name) = lower(?) AND id <> ?', name, id)) throw conflict('name_taken', 'A role with this name exists')
    sets.push('name = ?')
    params.push(name)
    changes.push(`name → ${name ?? '(default)'}`)
  }
  if (input.color !== undefined) {
    if (!COLOR.test(input.color)) throw badRequest('invalid_color', 'Colour must be #rrggbb')
    sets.push('color = ?')
    params.push(input.color)
    changes.push(`colour → ${input.color}`)
  }
  if (input.rank !== undefined && input.rank !== r.rank) {
    sets.push('rank = ?')
    params.push(checkedRank(ctx, actor, input.rank, id))
    changes.push(`rank ${r.rank} → ${input.rank}`)
  }
  if (input.permissions !== undefined) {
    const before = parsePerms(r.permissions)
    const perms = checkedPerms(actor, input.permissions, before)
    sets.push('permissions = ?')
    params.push(JSON.stringify(perms))
    const added = perms.filter((p) => !before.includes(p))
    const removed = before.filter((p) => !perms.includes(p))
    if (added.length || removed.length) changes.push(`+[${added.join(', ')}] -[${removed.join(', ')}]`)
  }
  if (input.maxSanctionMinutes !== undefined) {
    sets.push('max_sanction_minutes = ?')
    params.push(checkedMax(actor, input.maxSanctionMinutes))
    changes.push(`max → ${input.maxSanctionMinutes ?? 'unlimited'}`)
  }
  if (input.public !== undefined) {
    sets.push('public = ?')
    params.push(input.public ? 1 : 0)
    changes.push(`public → ${input.public}`)
  }
  if (sets.length === 0) return roleView(ctx, r, actor)
  tx(ctx.db, () => {
    // Spaltennamen nur aus der festen Liste oben; Werte als Parameter.
    run(ctx.db, `UPDATE team_roles SET ${sets.join(', ')}, updated_at = ? WHERE id = ?`, ...params, ctx.now(), id)
    audit(ctx, actor.uuid, 'role.update', null, `${r.name ?? r.id}: ${changes.join('; ')}`.slice(0, 1000), `role:${id}`)
  })
  return roleView(ctx, getRole(ctx, id)!, actor)
}

export function deleteRole(ctx: AppContext, actor: Staff, id: string): void {
  const r = getRole(ctx, id)
  if (!r) throw notFound('role_not_found', 'Role not found')
  if (r.builtin === 1 || BUILTIN_IDS.has(r.id)) throw conflict('role_builtin', 'Default roles cannot be deleted')
  assertRoleEditable(actor, r)
  const members = one<{ n: number }>(ctx.db, 'SELECT COUNT(*) AS n FROM team_members WHERE role_id = ?', id)!.n
  tx(ctx.db, () => {
    run(ctx.db, 'UPDATE team_jobs SET role_id = NULL WHERE role_id = ?', id)
    run(ctx.db, 'DELETE FROM team_roles WHERE id = ?', id)
    audit(ctx, actor.uuid, 'role.delete', null, `${r.name ?? r.id} (${members} members)`, `role:${id}`)
  })
}

// ---------------------------------------------------------------- Mitglieder

export interface MemberView {
  uuid: string
  name: string | null
  /** `env` = Owner aus ADMIN_UUIDS (fest). */
  source: 'env' | 'db'
  rank: number
  roles: RoleRef[]
  /** Hauptrolle = ranghöchste. */
  primary: RoleRef | null
  grantedAt: string | null
  grantedBy: { uuid: string, name: string | null } | null
  note: string | null
  editable: boolean
}

interface MemberRow {
  uuid: string
  role_id: string
  granted_at: number
  granted_by: string
  note: string | null
}

export function listMembers(ctx: AppContext, viewer: Staff | null): MemberView[] {
  const roles = new Map(all<RoleRow>(ctx.db, 'SELECT * FROM team_roles').map((r) => [r.id, r]))
  const rows = all<MemberRow>(ctx.db, 'SELECT * FROM team_members ORDER BY granted_at')
  const env = [...ctx.config.adminUuids]
  const uuids = [...new Set([...env, ...rows.map((r) => r.uuid)])]
  const names = namesOf(ctx, [...uuids, ...rows.map((r) => r.granted_by)])
  const byUser = new Map<string, MemberRow[]>()
  for (const r of rows) byUser.set(r.uuid, [...(byUser.get(r.uuid) ?? []), r])
  const out: MemberView[] = []
  for (const uuid of uuids) {
    const mine = (byUser.get(uuid) ?? []).map((m) => ({ m, r: roles.get(m.role_id)! })).filter((x) => x.r).sort((a, b) => b.r.rank - a.r.rank)
    const isEnv = ctx.config.adminUuids.has(uuid)
    const refs = [...(isEnv ? [roleRef(roles.get('owner')!)] : []), ...mine.map((x) => roleRef(x.r))]
    const rank = isEnv ? OWNER_RANK : mine[0]?.r.rank ?? 0
    const last = mine.map((x) => x.m).sort((a, b) => b.granted_at - a.granted_at)[0]
    out.push({
      uuid,
      name: names.get(uuid) ?? null,
      source: isEnv ? 'env' : 'db',
      rank,
      roles: refs,
      primary: refs[0] ?? null,
      grantedAt: last ? new Date(last.granted_at).toISOString() : null,
      grantedBy: last ? { uuid: last.granted_by, name: names.get(last.granted_by) ?? null } : null,
      note: last?.note ?? null,
      editable: !!viewer && !isEnv && viewer.uuid !== uuid && can(viewer, 'roles.manage') && (viewer.owner || rank < viewer.rank),
    })
  }
  return out.sort((a, b) => b.rank - a.rank || (a.name ?? '').localeCompare(b.name ?? ''))
}

function namesOf(ctx: AppContext, uuids: string[]): Map<string, string> {
  const list = [...new Set(uuids.filter((u) => u.length === 32))]
  const out = new Map<string, string>()
  for (let i = 0; i < list.length; i += 400) {
    const part = list.slice(i, i + 400)
    for (const r of all<{ uuid: string, name: string }>(ctx.db, `SELECT uuid, name FROM users WHERE uuid IN (${placeholders(part.length)})`, ...part)) out.set(r.uuid, r.name)
  }
  return out
}

/**
 * Rollen eines Mitglieds setzen (vollständige Liste; leer = aus dem Team). Rang-Regel: das Mitglied und jede
 * vergebene bzw. entzogene Rolle muss unter dem eigenen Rang liegen. Niemand ändert sich selbst.
 */
export function setMemberRoles(ctx: AppContext, actor: Staff, uuid: string, roleIds: string[], note?: string | null, opts: { auditAction?: string, ref?: string, skipPermission?: boolean, inTx?: boolean } = {}): MemberView | null {
  if (!opts.skipPermission) assertCan(actor, 'roles.manage')
  if (ctx.config.adminUuids.has(uuid)) throw conflict('role_locked', 'This owner is configured on the server (ADMIN_UUIDS)')
  if (uuid === actor.uuid) throw badRequest('cannot_change_self', 'You cannot change your own roles')
  if (!one(ctx.db, 'SELECT 1 AS x FROM users WHERE uuid = ?', uuid)) throw notFound('user_not_found', 'The player must have signed in to TRS once')
  const current = rankOf(ctx, uuid)
  if (!actor.owner && current >= actor.rank) throw forbidden('rank_too_low', 'You can only change members below your own rank')
  const wanted = [...new Set(roleIds)]
  if (wanted.length > 10) throw badRequest('invalid_request', 'At most 10 roles per member')
  const before = all<{ role_id: string }>(ctx.db, 'SELECT role_id FROM team_members WHERE uuid = ?', uuid).map((r) => r.role_id)
  for (const id of new Set([...wanted, ...before])) {
    if (wanted.includes(id) && before.includes(id)) continue
    const r = getRole(ctx, id)
    if (!r) throw notFound('role_not_found', `Role ${id} not found`)
    if (r.id === 'owner') throw conflict('role_locked', 'Owners are configured on the server (ADMIN_UUIDS)')
    if (!actor.owner && r.rank >= actor.rank) throw forbidden('rank_too_low', 'You can only give or take roles below your own rank')
  }
  const added = wanted.filter((id) => !before.includes(id))
  const removed = before.filter((id) => !wanted.includes(id))
  if (added.length === 0 && removed.length === 0 && note === undefined) return listMembers(ctx, actor).find((m) => m.uuid === uuid) ?? null
  const t = ctx.now()
  const apply = () => {
    for (const id of removed) run(ctx.db, 'DELETE FROM team_members WHERE uuid = ? AND role_id = ?', uuid, id)
    for (const id of added) {
      run(ctx.db, 'INSERT INTO team_members (uuid, role_id, granted_at, granted_by, note) VALUES (?, ?, ?, ?, ?)', uuid, id, t, actor.uuid, note ?? null)
    }
    if (note !== undefined && added.length === 0) run(ctx.db, 'UPDATE team_members SET note = ? WHERE uuid = ?', note, uuid)
    audit(ctx, actor.uuid, opts.auditAction ?? (wanted.length === 0 ? 'role.remove' : 'role.set'), uuid, `+[${added.join(', ')}] -[${removed.join(', ')}]`, opts.ref)
    // Wer (wieder) ins Team kommt, behält angenommene Bewerbungen; wer geht, bekommt die 6-Monats-Frist (applications.ts).
    if (wanted.length > 0) run(ctx.db, "UPDATE team_applications SET retain_until = NULL WHERE uuid = ? AND status = 'accepted'", uuid)
  }
  if (opts.inTx) apply()
  else tx(ctx.db, apply)
  return listMembers(ctx, actor).find((m) => m.uuid === uuid) ?? null
}

/** Für Aufrufer mit eigener Transaktion (Bewerbung annehmen): Rolle ergänzen. */
export function addMemberRole(ctx: AppContext, actor: Staff, uuid: string, roleId: string, ref: string): void {
  const current = all<{ role_id: string }>(ctx.db, 'SELECT role_id FROM team_members WHERE uuid = ?', uuid).map((r) => r.role_id)
  if (current.includes(roleId)) return
  setMemberRoles(ctx, actor, uuid, [...current, roleId], undefined, { auditAction: 'role.grant.application', ref, skipPermission: true, inTx: true })
}

// ---------------------------------------------------------------- Öffentliche Team-Seite

export interface PublicTeam {
  roles: { id: string, name: string | null, color: string, builtin: boolean, members: { uuid: string, name: string }[] }[]
}

/** Team-Mitglieder je öffentlicher Rolle (jede Person nur unter ihrer ranghöchsten öffentlichen Rolle). */
export function publicTeam(ctx: AppContext): PublicTeam {
  const roles = all<RoleRow>(ctx.db, 'SELECT * FROM team_roles WHERE public = 1 ORDER BY rank DESC')
  const seen = new Set<string>()
  const out: PublicTeam['roles'] = []
  const banned = new Set(all<{ uuid: string }>(
    ctx.db, "SELECT uuid FROM sanctions WHERE kind = 'account_ban' AND lifted_at IS NULL AND (expires_at IS NULL OR expires_at > ?)", ctx.now(),
  ).map((r) => r.uuid))
  for (const r of roles) {
    let members: { uuid: string, name: string }[]
    if (r.id === 'owner') {
      const env = [...ctx.config.adminUuids]
      const names = namesOf(ctx, env)
      members = env.filter((u) => names.has(u)).map((u) => ({ uuid: u, name: names.get(u)! }))
    } else {
      members = all<{ uuid: string, name: string }>(
        ctx.db,
        'SELECT u.uuid, u.name FROM team_members m JOIN users u ON u.uuid = m.uuid WHERE m.role_id = ? ORDER BY u.name_lower',
        r.id,
      )
    }
    members = members.filter((m) => !seen.has(m.uuid) && !banned.has(m.uuid))
    for (const m of members) seen.add(m.uuid)
    if (members.length > 0) out.push({ id: r.id, name: r.name, color: r.color, builtin: r.builtin === 1, members })
  }
  return { roles: out }
}
