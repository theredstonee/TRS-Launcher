/**
 * Team-Rechte und feste Rollen (API.md §24.2) – gemeinsam für Server und Website (reine Daten, keine Importe).
 */

export const PERMISSIONS = [
  'dashboard.view',
  'stats.view',
  'audit.view',
  'reports.view',
  'reports.content',
  'reports.handle',
  'sanctions.warn',
  'sanctions.mute',
  'sanctions.social',
  'sanctions.upload',
  'sanctions.hosting',
  'sanctions.ban',
  'sanctions.permanent',
  'sanctions.lift',
  'appeals.handle',
  'players.view',
  'players.notes',
  'uploads.review',
  'uploads.delete',
  'items.grant',
  'worlds.view',
  'worlds.close',
  'codes',
  'wordfilter',
  'roles.manage',
  'applications.view',
  'applications.review',
  'applications.manage',
  'applications.decide',
  'circuits.manage',
  'team.page',
  'issues.manage',
  'issues.moderate',
  'blog.write',
  'blog.publish',
] as const
export type Permission = (typeof PERMISSIONS)[number]
const PERMISSION_SET: ReadonlySet<string> = new Set(PERMISSIONS)
export const isPermission = (p: string): p is Permission => PERMISSION_SET.has(p)

/** Gruppen für die Oberfläche (Reihenfolge = Anzeige). */
export const PERMISSION_GROUPS: { id: string, permissions: Permission[] }[] = [
  { id: 'overview', permissions: ['dashboard.view', 'stats.view', 'audit.view'] },
  { id: 'reports', permissions: ['reports.view', 'reports.content', 'reports.handle'] },
  { id: 'sanctions', permissions: ['sanctions.warn', 'sanctions.mute', 'sanctions.social', 'sanctions.upload', 'sanctions.hosting', 'sanctions.ban', 'sanctions.permanent', 'sanctions.lift', 'appeals.handle'] },
  { id: 'players', permissions: ['players.view', 'players.notes'] },
  { id: 'content', permissions: ['uploads.review', 'uploads.delete', 'items.grant', 'codes', 'wordfilter', 'circuits.manage'] },
  { id: 'worlds', permissions: ['worlds.view', 'worlds.close'] },
  { id: 'applications', permissions: ['applications.view', 'applications.review', 'applications.manage', 'applications.decide'] },
  { id: 'team', permissions: ['roles.manage', 'team.page'] },
  { id: 'issues', permissions: ['issues.manage', 'issues.moderate'] },
  { id: 'blog', permissions: ['blog.write', 'blog.publish'] },
]

/** Recht je Strafart (§22.2). */
export const SANCTION_PERMISSION = {
  warn: 'sanctions.warn',
  chat_mute: 'sanctions.mute',
  social_ban: 'sanctions.social',
  upload_ban: 'sanctions.upload',
  hosting_ban: 'sanctions.hosting',
  account_ban: 'sanctions.ban',
} as const satisfies Record<string, Permission>

export const OWNER_RANK = 1000
export const ADMIN_RANK = 900
export const MODERATOR_RANK = 500
/** Verwarnungen dürfen mindestens so lange gelten (wie bisher bei Moderatoren), auch wenn die Höchstdauer kürzer ist. */
export const MIN_WARN_MINUTES = 30 * 1440

export type BuiltinRoleId = 'owner' | 'admin' | 'senior_moderator' | 'moderator' | 'supporter' | 'content' | 'recruiter'

export interface BuiltinRole {
  id: BuiltinRoleId
  rank: number
  color: string
  permissions: readonly Permission[]
  maxSanctionMinutes: number | null
}

const MOD_BASE: Permission[] = [
  'dashboard.view', 'audit.view', 'reports.view', 'reports.content', 'reports.handle',
  'sanctions.warn', 'sanctions.mute', 'sanctions.social', 'sanctions.upload', 'sanctions.hosting', 'sanctions.lift',
  'appeals.handle', 'players.view', 'players.notes', 'uploads.review', 'worlds.view', 'worlds.close',
]

/** Standardrollen (Rechte = Werkseinstellung; Admins passen sie an). */
export const BUILTIN_ROLES: readonly BuiltinRole[] = [
  { id: 'owner', rank: OWNER_RANK, color: '#facc15', permissions: PERMISSIONS, maxSanctionMinutes: null },
  { id: 'admin', rank: ADMIN_RANK, color: '#e5484d', permissions: PERMISSIONS, maxSanctionMinutes: null },
  {
    id: 'senior_moderator',
    rank: 700,
    color: '#f59e0b',
    permissions: [...MOD_BASE, 'stats.view', 'sanctions.ban', 'applications.view', 'applications.review', 'circuits.manage', 'issues.manage', 'issues.moderate'],
    maxSanctionMinutes: 30 * 1440,
  },
  { id: 'moderator', rank: MODERATOR_RANK, color: '#3b82f6', permissions: [...MOD_BASE, 'issues.moderate'], maxSanctionMinutes: 7 * 1440 },
  {
    id: 'supporter',
    rank: 300,
    color: '#22c55e',
    permissions: ['dashboard.view', 'reports.view', 'players.view', 'sanctions.warn', 'worlds.view', 'issues.manage'],
    maxSanctionMinutes: 1440,
  },
  { id: 'content', rank: 200, color: '#a855f7', permissions: ['dashboard.view', 'uploads.review', 'codes', 'circuits.manage', 'blog.write'], maxSanctionMinutes: null },
  { id: 'recruiter', rank: 150, color: '#14b8a6', permissions: ['dashboard.view', 'applications.view', 'applications.review'], maxSanctionMinutes: null },
]
