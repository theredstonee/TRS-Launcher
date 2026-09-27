import { z } from 'zod'
// Relativ importiert, damit Tests die Datei ohne Nuxt laden können.
import type { IconName } from './icons'
import { hasKey, t, tKey, type MessageKey } from './i18n'
import { sanctionKinds, type SanctionKind } from './sanctions'

// Team-Rechte (API §24.2): die eigene Team-Ansicht aus `GET /v1/me` (`team`),
// was der Team-Bereich danach zeigt und wann eine Strafe änderbar ist.
// Die Oberfläche blendet nur aus – der Server prüft jede Anfrage selbst.

/** Alle Rechte der API. Unbekannte Rechte einer neueren API fallen weg. */
export const permissions = [
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
] as const
export type Permission = (typeof permissions)[number]
const permissionSet: ReadonlySet<string> = new Set(permissions)
export const isPermission = (p: string): p is Permission => permissionSet.has(p)

/** Recht je Strafart (§22.2). */
export const sanctionPermission: Record<SanctionKind, Permission> = {
  warn: 'sanctions.warn',
  chat_mute: 'sanctions.mute',
  social_ban: 'sanctions.social',
  upload_ban: 'sanctions.upload',
  hosting_ban: 'sanctions.hosting',
  account_ban: 'sanctions.ban',
}

/** Standardrollen; ihr Name kommt aus der Übersetzung (`team.roles.<id>`). */
export const builtinRoleIds = ['owner', 'admin', 'senior_moderator', 'moderator', 'supporter', 'content', 'recruiter'] as const

export interface StaffLimits {
  kinds: SanctionKind[]
  /** Höchstdauer in Minuten (`null` = unbegrenzt). */
  maxMinutes: number | null
  maxWarnMinutes: number | null
  permanent: boolean
}

/** Ohne Team keine Strafen. */
export const noLimits: StaffLimits = { kinds: [], maxMinutes: 0, maxWarnMinutes: 0, permanent: false }

const minutes = z.number().int().min(0).max(5_256_000).nullable().catch(0).default(null)

export const teamRoleRefSchema = z.object({
  id: z.string().regex(/^[a-z0-9_-]{1,32}$/),
  name: z.string().max(32).nullable().catch(null).default(null),
  color: z.string().regex(/^#[0-9a-f]{6}$/i).catch('#9ca3af').default('#9ca3af'),
  builtin: z.boolean().catch(false).default(false),
})

export const myTeamSchema = z.object({
  owner: z.boolean().catch(false).default(false),
  rank: z.number().int().min(0).max(1000).catch(0).default(0),
  roles: z.array(teamRoleRefSchema.nullable().catch(null)).max(32).catch([]).default([])
    .transform((list) => list.filter((r): r is TeamRoleRef => r !== null)),
  permissions: z.array(z.string().max(64)).max(64).catch([]).default([]).transform((list) => [...new Set(list.filter(isPermission))]),
  limits: z
    .object({
      kinds: z.array(z.string()).catch([]).default([])
        .transform((list) => sanctionKinds.filter((k) => list.includes(k))),
      maxMinutes: minutes,
      maxWarnMinutes: minutes,
      permanent: z.boolean().catch(false).default(false),
    })
    .catch({ ...noLimits })
    .default({ ...noLimits }),
})

export type TeamRoleRef = z.infer<typeof teamRoleRefSchema>
export type MyTeam = z.infer<typeof myTeamSchema>

export function teamCan(team: MyTeam | null | undefined, permission: Permission): boolean {
  return !!team && (team.owner || team.permissions.includes(permission))
}

export function teamCanAny(team: MyTeam | null | undefined, list: readonly Permission[]): boolean {
  return list.some((p) => teamCan(team, p))
}

/** Hauptrolle = ranghöchste (die API liefert sie zuerst). */
export function mainRole(team: MyTeam | null | undefined): TeamRoleRef | null {
  return team?.roles[0] ?? null
}

// --- Seitenleiste und Seiten ---------------------------------------------------------------

/** Die Website – dort liegen Rollen, Bewerbungen und Stellen mit voller Oberfläche. */
export const TEAM_WEBSITE = 'https://trs-launcher.theredstonee.de'
export const WEBSITE_LOGIN_URL = `${TEAM_WEBSITE}/login`

export interface TeamSection {
  /** Pfad im Launcher oder – bei `external` – auf der Website. */
  to: string
  label: MessageKey
  icon: IconName
  /** Eines davon reicht. */
  perms: readonly Permission[]
  exact?: boolean
  /** Öffnet die Website im Browser statt einer Launcher-Seite. */
  external?: boolean
  /** Zähler aus der Übersicht. */
  count?: 'reports' | 'appeals' | 'uploads' | 'applications'
}

/** Wie die Website (gleiche Rechte je Bereich). */
export const teamSections: readonly TeamSection[] = [
  { to: '/admin', label: 'team.nav.overview', icon: 'home', perms: ['dashboard.view'], exact: true },
  { to: '/admin/reports', label: 'team.nav.reports', icon: 'flag', perms: ['reports.view'], count: 'reports' },
  { to: '/admin/appeals', label: 'team.nav.appeals', icon: 'appeal', perms: ['appeals.handle'], count: 'appeals' },
  { to: '/admin/players', label: 'team.nav.players', icon: 'friends', perms: ['players.view'] },
  { to: '/admin/sanctions', label: 'team.nav.sanctions', icon: 'gavel', perms: ['players.view', 'appeals.handle', 'sanctions.lift'] },
  { to: '/admin/uploads', label: 'team.nav.uploads', icon: 'skins', perms: ['uploads.review'], count: 'uploads' },
  { to: '/admin/worlds', label: 'team.nav.worlds', icon: 'world', perms: ['worlds.view'] },
  { to: '/admin/codes', label: 'team.nav.codes', icon: 'ticket', perms: ['codes'] },
  { to: '/admin/word-filter', label: 'team.nav.wordFilter', icon: 'filter', perms: ['wordfilter'] },
  { to: '/admin/audit', label: 'team.nav.audit', icon: 'list', perms: ['audit.view'] },
]

/** Bereiche, die nur auf der Website eine Oberfläche haben. */
export const websiteSections: readonly TeamSection[] = [
  { to: `${TEAM_WEBSITE}/admin/applications`, label: 'team.nav.applications', icon: 'mailUnread', perms: ['applications.view'], external: true, count: 'applications' },
  { to: `${TEAM_WEBSITE}/admin/jobs`, label: 'team.nav.jobs', icon: 'list', perms: ['applications.view', 'applications.manage'], external: true },
  { to: `${TEAM_WEBSITE}/admin/roles`, label: 'team.nav.roles', icon: 'key', perms: ['roles.manage'], external: true },
]

export function visibleSections(team: MyTeam | null | undefined, list: readonly TeamSection[] = teamSections): TeamSection[] {
  return list.filter((s) => teamCanAny(team, s.perms))
}

/** Bereich zu einem Launcher-Pfad (längster Treffer), `null` = unbekannt. */
export function sectionFor(path: string): TeamSection | null {
  const clean = path.split(/[?#]/)[0]!.replace(/\/+$/, '') || '/'
  let best: TeamSection | null = null
  for (const s of teamSections) {
    const hit = s.exact ? clean === s.to : clean === s.to || clean.startsWith(`${s.to}/`)
    if (hit && (!best || s.to.length > best.to.length)) best = s
  }
  return best
}

/** Darf man diese Launcher-Seite sehen? Unbekannte Pfade unter /admin nicht. */
export function mayOpen(team: MyTeam | null | undefined, path: string): boolean {
  const s = sectionFor(path)
  return !!s && teamCanAny(team, s.perms)
}

/** Wohin /admin führt, wenn die Übersicht fehlt: erster erlaubter Bereich (`null` = keiner). */
export function landingPath(team: MyTeam | null | undefined): string | null {
  return visibleSections(team)[0]?.to ?? null
}

/** Suche gibt es, wenn mindestens eine Gruppe erlaubt ist (§24.2). */
export function canSearch(team: MyTeam | null | undefined): boolean {
  return teamCanAny(team, ['players.view', 'reports.view', 'uploads.review'])
}

// --- Strafen ändern (Rang-Regel) ------------------------------------------------------------

interface SanctionLike {
  kind: SanctionKind
  createdBy: { uuid: string }
  createdRole: 'admin' | 'moderator' | 'system'
  createdRank?: number | null
}

/** Rang des Erstellers; ältere API ohne `createdRank` → aus der alten Rolle. */
export function createdRankOf(s: SanctionLike): number {
  if (typeof s.createdRank === 'number') return s.createdRank
  return s.createdRole === 'admin' ? 900 : s.createdRole === 'moderator' ? 500 : 0
}

export interface SanctionRights {
  lift: boolean
  /** Ende ändern (verkürzen braucht `lift` oder das Recht der Art, verlängern das Recht der Art). */
  change: boolean
  /** Warum nichts geht: höherer Rang oder fehlendes Recht. */
  blocked: 'rank' | 'permission' | null
}

/**
 * Wie `assertCanModify` der API: Owner dürfen alles; Strafen von Mitgliedern mit
 * HÖHEREM Rang bleiben tabu (gleicher Rang und eigene Strafen gehen).
 */
export function sanctionRights(s: SanctionLike, team: MyTeam | null | undefined, myUuid: string | null | undefined): SanctionRights {
  if (!team) return { lift: false, change: false, blocked: 'permission' }
  if (team.owner) return { lift: true, change: true, blocked: null }
  const own = !!myUuid && s.createdBy.uuid === myUuid
  if (!own && createdRankOf(s) > team.rank) return { lift: false, change: false, blocked: 'rank' }
  const banOk = s.kind !== 'account_ban' || teamCan(team, 'sanctions.ban')
  const kindOk = teamCan(team, sanctionPermission[s.kind])
  const lift = banOk && teamCan(team, 'sanctions.lift')
  const change = banOk && (kindOk || teamCan(team, 'sanctions.lift'))
  return { lift, change, blocked: lift || change ? null : 'permission' }
}

// --- Texte ----------------------------------------------------------------------------------

/** Anzeigename eines Rechts (`team.permissions.reports_view`). */
export function permissionLabel(p: Permission): string {
  return tKey(`team.permissions.${p.replaceAll('.', '_')}`)
}

/** Anzeigename einer Rolle: eigener Name, sonst die Übersetzung der Standardrolle, sonst die ID. */
export function roleLabel(r: Pick<TeamRoleRef, 'id' | 'name'>): string {
  if (r.name) return r.name
  return hasKey(`team.roles.${r.id}`) ? tKey(`team.roles.${r.id}`) : r.id
}

/** `missing_permission` mit dem Recht aus dem Fehler („Deiner Rolle fehlt das Recht …“). */
export function teamErrorText(apiCode: string | null | undefined, params: Record<string, string> | null | undefined): string | null {
  if (apiCode !== 'missing_permission') return null
  const p = params?.permission
  if (!p || !isPermission(p)) return null
  return t('team.errors.missingPermission', { permission: permissionLabel(p) })
}
