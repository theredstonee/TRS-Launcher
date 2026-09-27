import { z } from 'zod'
import { audit } from './audit'
import type { AppContext } from './context'
import { all, one, run, tx } from './db'
import { badRequest, conflict, notFound } from './errors'
import { plainText } from './schemas'
import { storedSkins } from './skins'
import { can, getRole, teamOf, type RoleRow, type Staff } from './team'

/**
 * Öffentliche Team-Seite (§26): nur Mitglieder, die ein Team-Mitglied mit `team.page` hier einträgt.
 * Gruppe = Rolle (Reihenfolge nach Rang, Owner oben), Reihenfolge innerhalb der Gruppe per Drag & Drop.
 */

export const TEAM_PAGE_LANGS = ['en', 'de', 'es'] as const
export type TeamPageTitles = Partial<Record<(typeof TEAM_PAGE_LANGS)[number], string>>

export interface TeamPageLink {
  label: string
  url: string
}

export const MAX_TEAM_PAGE_LINKS = 3
export const MAX_TEAM_PAGE_MEMBERS = 200

// ---------------------------------------------------------------- Eingaben

/** Nur https, ohne Zugangsdaten, mit echtem Hostnamen – nichts, was im Browser Code ausführen könnte. */
export const teamLinkUrl = z
  .string()
  .trim()
  .max(200)
  .refine((s) => {
    try {
      const u = new URL(s)
      return u.protocol === 'https:' && !u.username && !u.password && /^[a-z0-9.-]+\.[a-z]{2,}$/i.test(u.hostname)
    } catch {
      return false
    }
  }, 'must be an https:// address')
  .transform((s) => new URL(s).toString())

export const teamPageLinkSchema = z.strictObject({ label: plainText(30), url: teamLinkUrl })

/** Discord-Benutzername (neues System): 2–32 Zeichen, a–z, 0–9, _ und . (keine zwei Punkte hintereinander). */
export const discordNameSchema = z
  .string()
  .trim()
  .toLowerCase()
  .regex(/^(?!.*\.\.)[a-z0-9_.]{2,32}$/, 'must be a Discord username (2-32 characters: a-z, 0-9, _ and .)')

export const teamPageTitlesSchema = z.strictObject({
  en: plainText(60).optional(),
  de: plainText(60).optional(),
  es: plainText(60).optional(),
})

export const roleIdSchema = z.string().regex(/^[a-z][a-z0-9_]{1,39}$/)

export interface TeamPagePatch {
  roleId?: string
  titles?: TeamPageTitles
  discord?: string | null
  links?: TeamPageLink[]
}

// ---------------------------------------------------------------- Lesen

interface Row {
  uuid: string
  name: string
  role_id: string | null
  sort: number
  titles: string
  discord: string | null
  links: string
}

function parseTitles(raw: string): TeamPageTitles {
  try {
    const v = JSON.parse(raw) as Record<string, unknown>
    const out: TeamPageTitles = {}
    for (const l of TEAM_PAGE_LANGS) if (typeof v?.[l] === 'string' && v[l]) out[l] = v[l] as string
    return out
  } catch {
    return {}
  }
}

function parseLinks(raw: string): TeamPageLink[] {
  try {
    const v = JSON.parse(raw) as unknown
    if (!Array.isArray(v)) return []
    return v.flatMap((l) => {
      const r = teamPageLinkSchema.safeParse(l)
      return r.success ? [r.data] : []
    }).slice(0, MAX_TEAM_PAGE_LINKS)
  } catch {
    return []
  }
}

function rows(ctx: AppContext): Row[] {
  return all<Row>(
    ctx.db,
    `SELECT p.uuid, u.name, p.role_id, p.sort, p.titles, p.discord, p.links
     FROM team_page_members p JOIN users u ON u.uuid = p.uuid ORDER BY p.sort, u.name_lower`,
  )
}

function bannedSet(ctx: AppContext): Set<string> {
  return new Set(all<{ uuid: string }>(
    ctx.db, "SELECT uuid FROM sanctions WHERE kind = 'account_ban' AND lifted_at IS NULL AND (expires_at IS NULL OR expires_at > ?)", ctx.now(),
  ).map((r) => r.uuid))
}

/** Hauptrolle einer Person (Owner aus `ADMIN_UUIDS`, sonst die ranghöchste Rolle) oder `null`. */
function mainRole(ctx: AppContext, uuid: string): string | null {
  return teamOf(ctx, uuid)?.roles[0] ?? (ctx.config.adminUuids.has(uuid) ? 'owner' : null)
}

export interface TeamPageGroupRef {
  id: string
  name: string | null
  color: string
  rank: number
  builtin: boolean
  /** Nicht öffentliche Rollen erscheinen nicht auf der Seite (Hinweis im Admin). */
  public: boolean
}

export interface TeamPageMemberView {
  uuid: string
  name: string
  roleId: string | null
  titles: TeamPageTitles
  discord: string | null
  links: TeamPageLink[]
  /** Aktuelle Hauptrolle der Person (Hinweis, wenn sie nicht zur Gruppe passt); `null` = nicht (mehr) im Team. */
  mainRole: string | null
  banned: boolean
}

export interface TeamPageAdminView {
  /** Alle Rollen nach Rang (auch leere – als Ablagefläche für Drag & Drop). */
  groups: { role: TeamPageGroupRef, members: TeamPageMemberView[] }[]
  /** Mitglieder, deren Rolle gelöscht wurde. */
  ungrouped: TeamPageMemberView[]
  editable: boolean
}

function groupRef(r: RoleRow): TeamPageGroupRef {
  return { id: r.id, name: r.name, color: r.color, rank: r.rank, builtin: r.builtin === 1, public: r.public === 1 }
}

export function teamPageAdmin(ctx: AppContext, viewer: Staff): TeamPageAdminView {
  const roles = all<RoleRow>(ctx.db, 'SELECT * FROM team_roles ORDER BY rank DESC')
  const banned = bannedSet(ctx)
  const members = rows(ctx).map((r): TeamPageMemberView => ({
    uuid: r.uuid,
    name: r.name,
    roleId: r.role_id,
    titles: parseTitles(r.titles),
    discord: r.discord,
    links: parseLinks(r.links),
    mainRole: mainRole(ctx, r.uuid),
    banned: banned.has(r.uuid),
  }))
  return {
    groups: roles.map((r) => ({ role: groupRef(r), members: members.filter((m) => m.roleId === r.id) })),
    ungrouped: members.filter((m) => m.roleId === null),
    editable: can(viewer, 'team.page'),
  }
}

export interface PublicTeamMember {
  uuid: string
  name: string
  skin: { url: string | null, model: 'classic' | 'slim' } | null
  cape: { id: string, url: string, frames: number, frameTimeMs: number | null } | null
  titles: TeamPageTitles
  discord: string | null
  links: TeamPageLink[]
}

export interface PublicTeamPage {
  groups: { id: string, name: string | null, color: string, builtin: boolean, members: PublicTeamMember[] }[]
}

/** Neue Mojang-Abfragen je Aufruf der öffentlichen Seite (der Rest kommt beim nächsten Aufruf). */
const PUBLIC_FRESH = 8
const PUBLIC_STALE_MS = 6 * 60 * 60_000

/**
 * Öffentliche Sicht: nur öffentliche Rollen, ohne gesperrte Konten, nur freigegebene Felder. Skins aus der Datenbank;
 * fehlende oder ältere werden in kleinen Portionen bei Mojang nachgeschlagen (und dabei gespeichert).
 */
export async function publicTeamPage(ctx: AppContext): Promise<PublicTeamPage> {
  const roles = all<RoleRow>(ctx.db, 'SELECT * FROM team_roles WHERE public = 1 ORDER BY rank DESC')
  const banned = bannedSet(ctx)
  const list = rows(ctx).filter((r) => r.role_id !== null && !banned.has(r.uuid))
  const uuids = list.map((r) => r.uuid)
  let skins = storedSkins(ctx.db, uuids)
  const stale = uuids.filter((u) => {
    const s = skins.get(u)
    return !s || s.at === null || ctx.now() - s.at > PUBLIC_STALE_MS
  }).slice(0, PUBLIC_FRESH)
  if (stale.length) {
    await Promise.allSettled(stale.map((u) => ctx.skins.byUuid(u)))
    skins = storedSkins(ctx.db, uuids)
  }
  const capes = capesOf(ctx, uuids)
  const groups: PublicTeamPage['groups'] = []
  for (const r of roles) {
    const members = list.filter((m) => m.role_id === r.id).map((m): PublicTeamMember => {
      const s = skins.get(m.uuid)
      return {
        uuid: m.uuid,
        name: m.name,
        skin: s && s.at !== null ? { url: s.url, model: s.model } : null,
        cape: capes.get(m.uuid) ?? null,
        titles: parseTitles(m.titles),
        discord: m.discord,
        links: parseLinks(m.links),
      }
    })
    if (members.length) groups.push({ id: r.id, name: r.name, color: r.color, builtin: r.builtin === 1, members })
  }
  return { groups }
}

/** Aktiver, freigegebener TRS-Umhang (nur wenn die Person ihn anderen zeigt). Adresse relativ zur Website. */
function capesOf(ctx: AppContext, uuids: string[]): Map<string, PublicTeamMember['cape']> {
  const out = new Map<string, PublicTeamMember['cape']>()
  if (!uuids.length) return out
  const rows = all<{ uuid: string, id: string, sha256: string, frames: number, frame_time_ms: number | null }>(
    ctx.db,
    `SELECT u.uuid, c.id, c.sha256, c.frames, c.frame_time_ms FROM users u JOIN capes c ON c.id = u.active_cape_id
     WHERE u.show_cape = 1 AND c.status = 'approved' AND u.uuid IN (${uuids.map(() => '?').join(', ')})`,
    ...uuids,
  )
  for (const r of rows) {
    out.set(r.uuid, {
      id: r.id,
      url: `/v1/capes/${r.id}.png?v=${r.sha256.slice(0, 12)}`,
      frames: r.frames,
      frameTimeMs: r.frames > 1 ? r.frame_time_ms : null,
    })
  }
  return out
}

// ---------------------------------------------------------------- Ändern

function needRole(ctx: AppContext, id: string): RoleRow {
  const r = getRole(ctx, id)
  if (!r) throw notFound('role_not_found', `Role ${id} not found`)
  return r
}

function nextSort(ctx: AppContext, roleId: string): number {
  return (one<{ s: number | null }>(ctx.db, 'SELECT MAX(sort) AS s FROM team_page_members WHERE role_id = ?', roleId)?.s ?? -1) + 1
}

function cleanTitles(t: TeamPageTitles): string {
  const out: TeamPageTitles = {}
  for (const l of TEAM_PAGE_LANGS) if (t[l]) out[l] = t[l]
  return JSON.stringify(out)
}

/** Person zur Team-Seite hinzufügen; ohne `roleId` in die Gruppe ihrer Hauptrolle. */
export function addTeamPageMember(ctx: AppContext, actor: Staff, uuid: string, roleId?: string): void {
  const user = one<{ uuid: string }>(ctx.db, 'SELECT uuid FROM users WHERE uuid = ?', uuid)
  if (!user) throw notFound('user_not_found', 'This player has never signed in to TRS')
  if (one(ctx.db, 'SELECT 1 AS x FROM team_page_members WHERE uuid = ?', uuid)) throw conflict('already_on_team_page', 'This player is already on the team page')
  const count = one<{ n: number }>(ctx.db, 'SELECT COUNT(*) AS n FROM team_page_members')!.n
  if (count >= MAX_TEAM_PAGE_MEMBERS) throw conflict('team_page_full', `At most ${MAX_TEAM_PAGE_MEMBERS} people fit on the team page`)
  const group = roleId ?? mainRole(ctx, uuid)
  if (!group) throw badRequest('group_required', 'This player has no team role – choose a group')
  needRole(ctx, group)
  const t = ctx.now()
  tx(ctx.db, () => {
    run(
      ctx.db,
      'INSERT INTO team_page_members (uuid, role_id, sort, titles, discord, links, added_at, added_by, updated_at) VALUES (?, ?, ?, ?, NULL, ?, ?, ?, ?)',
      uuid, group, nextSort(ctx, group), '{}', '[]', t, actor.uuid, t,
    )
    audit(ctx, actor.uuid, 'teampage.add', uuid, group)
  })
}

export function updateTeamPageMember(ctx: AppContext, actor: Staff, uuid: string, patch: TeamPagePatch): void {
  const row = one<{ role_id: string | null }>(ctx.db, 'SELECT role_id FROM team_page_members WHERE uuid = ?', uuid)
  if (!row) throw notFound('not_on_team_page', 'This player is not on the team page')
  if (patch.links && patch.links.length > MAX_TEAM_PAGE_LINKS) throw badRequest('too_many_links', `At most ${MAX_TEAM_PAGE_LINKS} links`)
  const sets: string[] = []
  const params: (string | number | null)[] = []
  const changed: string[] = []
  if (patch.roleId !== undefined && patch.roleId !== row.role_id) {
    needRole(ctx, patch.roleId)
    sets.push('role_id = ?', 'sort = ?')
    params.push(patch.roleId, nextSort(ctx, patch.roleId))
    changed.push(`group=${patch.roleId}`)
  }
  if (patch.titles !== undefined) {
    sets.push('titles = ?')
    params.push(cleanTitles(patch.titles))
    changed.push('titles')
  }
  if (patch.discord !== undefined) {
    sets.push('discord = ?')
    params.push(patch.discord)
    changed.push('discord')
  }
  if (patch.links !== undefined) {
    sets.push('links = ?')
    params.push(JSON.stringify(patch.links))
    changed.push('links')
  }
  if (!sets.length) return
  sets.push('updated_at = ?')
  params.push(ctx.now())
  tx(ctx.db, () => {
    run(ctx.db, `UPDATE team_page_members SET ${sets.join(', ')} WHERE uuid = ?`, ...params, uuid)
    audit(ctx, actor.uuid, 'teampage.update', uuid, changed.join(', '))
  })
}

export function removeTeamPageMember(ctx: AppContext, actor: Staff, uuid: string): void {
  tx(ctx.db, () => {
    if (run(ctx.db, 'DELETE FROM team_page_members WHERE uuid = ?', uuid) === 0) throw notFound('not_on_team_page', 'This player is not on the team page')
    audit(ctx, actor.uuid, 'teampage.remove', uuid)
  })
}

/**
 * Neue Anordnung nach Drag & Drop: je Gruppe die vollständige Reihenfolge. Jede Person höchstens einmal;
 * Personen, die nicht vorkommen, bleiben wo sie sind.
 */
export function reorderTeamPage(ctx: AppContext, actor: Staff, layout: { roleId: string, uuids: string[] }[]): void {
  const seen = new Set<string>()
  const known = new Set(all<{ uuid: string }>(ctx.db, 'SELECT uuid FROM team_page_members').map((r) => r.uuid))
  for (const g of layout) {
    needRole(ctx, g.roleId)
    for (const u of g.uuids) {
      if (seen.has(u)) throw badRequest('duplicate_member', 'A player appears twice in the new order')
      if (!known.has(u)) throw notFound('not_on_team_page', 'A player in the new order is not on the team page')
      seen.add(u)
    }
  }
  const t = ctx.now()
  tx(ctx.db, () => {
    for (const g of layout) {
      g.uuids.forEach((u, i) => run(ctx.db, 'UPDATE team_page_members SET role_id = ?, sort = ?, updated_at = ? WHERE uuid = ?', g.roleId, i, t, u))
    }
    audit(ctx, actor.uuid, 'teampage.reorder', null, layout.map((g) => `${g.roleId}:${g.uuids.length}`).join(', '))
  })
}
