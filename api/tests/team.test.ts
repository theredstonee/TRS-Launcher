import { DatabaseSync } from 'node:sqlite'
import { describe, expect, it } from 'vitest'
import { all, migrate, one } from '../server/lib/db'
import { MIGRATIONS, migrateTeamPage, migrateTeamV3 } from '../server/lib/migrations'
import { adminReportDetail, createReport, redactDetail, reportDetailFor } from '../server/lib/moderation'
import { changeDuration, createSanction, liftSanction, type SanctionInput } from '../server/lib/sanctions'
import { listRoles, setRole } from '../server/lib/staff'
import {
  BUILTIN_ROLES,
  PERMISSIONS,
  can,
  createRole,
  deleteRole,
  limitsOf,
  listMembers,
  listRoleViews,
  ownerStaff,
  roleRefsOf,
  setMemberRoles,
  teamOf,
  updateRole,
  type Staff,
} from '../server/lib/team'
import { rememberSkin, storedSkins } from '../server/lib/skins'
import {
  addTeamPageMember,
  discordNameSchema,
  publicTeamPage,
  removeTeamPageMember,
  reorderTeamPage,
  teamPageAdmin,
  teamPageLinkSchema,
  updateTeamPageMember,
} from '../server/lib/teampage'
import { getUser, staffRole } from '../server/lib/users'
import { code, players } from './chathelpers'
import { ADMIN, login, makeEnv, type TestEnv } from './helpers'

const OWNER = ownerStaff(ADMIN)
const DAY = 1440
const input = (uuid: string, kind: SanctionInput['kind'], minutes: number | null): SanctionInput => ({ uuid, kind, minutes, reasonCode: 'spam' })

/** Owner + je ein Mitglied pro fester Rolle + Spieler. */
async function crew(env: TestEnv) {
  await login(env, 'Owner', ADMIN)
  const [admin, senior, mod, mod2, supporter, content, recruiter, p1, p2] = await players(env, 'Admin', 'Senior', 'Mod', 'Mod2', 'Supporter', 'Content', 'Recruiter', 'Player1', 'Player2')
  const give = (u: { uuid: string }, ...roles: string[]) => setMemberRoles(env.ctx, OWNER, u.uuid, roles)
  give(admin!, 'admin')
  give(senior!, 'senior_moderator')
  give(mod!, 'moderator')
  give(mod2!, 'moderator')
  give(supporter!, 'supporter')
  give(content!, 'content')
  give(recruiter!, 'recruiter')
  const s = (u: { uuid: string }) => teamOf(env.ctx, u.uuid)!
  return {
    admin: s(admin!), senior: s(senior!), mod: s(mod!), mod2: s(mod2!), supporter: s(supporter!), content: s(content!), recruiter: s(recruiter!),
    p1: p1!, p2: p2!, refresh: s,
  }
}

describe('permissions and default roles', () => {
  it('ships seven default roles with sensible rights; owners always have everything', async () => {
    const env = makeEnv()
    const t = await crew(env)
    expect(listRoleViews(env.ctx, OWNER).map((r) => [r.id, r.rank, r.builtin])).toEqual(BUILTIN_ROLES.map((r) => [r.id, r.rank, true]))
    const matrix = (s: Staff) => PERMISSIONS.filter((p) => can(s, p))
    expect(matrix(OWNER)).toEqual([...PERMISSIONS])
    expect(matrix(t.admin)).toEqual([...PERMISSIONS])
    expect(matrix(t.mod)).toEqual([
      'dashboard.view', 'audit.view', 'reports.view', 'reports.content', 'reports.handle',
      'sanctions.warn', 'sanctions.mute', 'sanctions.social', 'sanctions.upload', 'sanctions.hosting', 'sanctions.lift',
      'appeals.handle', 'players.view', 'players.notes', 'uploads.review', 'worlds.view', 'worlds.close',
    ])
    expect(can(t.senior, 'sanctions.ban')).toBe(true)
    expect(can(t.senior, 'sanctions.permanent')).toBe(false)
    expect(matrix(t.supporter)).toEqual(['dashboard.view', 'reports.view', 'sanctions.warn', 'players.view', 'worlds.view'])
    expect(matrix(t.content)).toEqual(['dashboard.view', 'uploads.review', 'codes', 'circuits.manage'])
    expect(can(t.senior, 'circuits.manage')).toBe(true)
    expect(can(t.mod, 'circuits.manage')).toBe(false)
    expect(matrix(t.recruiter)).toEqual(['dashboard.view', 'applications.view', 'applications.review'])
    // Grenzen je Rolle (für die Oberfläche).
    expect(limitsOf(t.mod)).toEqual({ kinds: ['warn', 'chat_mute', 'social_ban', 'upload_ban', 'hosting_ban'], maxMinutes: 7 * DAY, maxWarnMinutes: 30 * DAY, permanent: false })
    expect(limitsOf(t.supporter)).toMatchObject({ kinds: ['warn'], maxMinutes: DAY, maxWarnMinutes: 30 * DAY })
    expect(limitsOf(t.content)).toMatchObject({ kinds: [], maxMinutes: 0 })
    expect(limitsOf(OWNER)).toMatchObject({ maxMinutes: null, permanent: true })
    // Altes Raster für ältere Clients: admin ab Admin-Rang.
    expect([ADMIN, t.admin.uuid, t.senior.uuid, t.recruiter.uuid, t.p1.uuid].map((u) => staffRole(env.ctx, u))).toEqual(['admin', 'admin', 'moderator', 'moderator', null])
    expect((await login(env, 'Recruiter', t.recruiter.uuid)).user.team).toMatchObject({ owner: false, rank: 150, roles: [{ id: 'recruiter' }] })
    expect((await login(env, 'Player1', t.p1.uuid)).user.team).toBeNull()
  })

  it('several roles: the highest is the main role (rank), rights add up, longest duration wins', async () => {
    const env = makeEnv()
    const t = await crew(env)
    setMemberRoles(env.ctx, OWNER, t.supporter.uuid, ['supporter', 'recruiter', 'content'])
    const s = t.refresh(t.supporter)
    expect(s.rank).toBe(300)
    expect(s.roles).toEqual(['supporter', 'content', 'recruiter'])
    expect(can(s, 'applications.review') && can(s, 'codes') && can(s, 'sanctions.warn')).toBe(true)
    expect(s.maxMinutes).toBe(DAY)
    setMemberRoles(env.ctx, OWNER, t.mod.uuid, ['moderator', 'supporter'])
    expect(t.refresh(t.mod).maxMinutes).toBe(7 * DAY)
    const m = listMembers(env.ctx, OWNER).find((x) => x.uuid === t.supporter.uuid)!
    expect(m.primary?.id).toBe('supporter')
    expect(m.roles.map((r) => r.id)).toEqual(['supporter', 'content', 'recruiter'])
  })

  it('reports without reports.content only show metadata', async () => {
    const env = makeEnv()
    const t = await crew(env)
    const [a, b] = await players(env, 'Alex', 'Bob')
    const r = createReport(env.ctx, b!.uuid, { kind: 'player', reason: 'harassment', uuid: a!.uuid })
    const d = adminReportDetail(env.ctx, r.id)
    const red = redactDetail({ ...d, preview: 'secret text', evidence: d.evidence ? { ...d.evidence, messages: [{ ...(d.evidence.messages[0] ?? {} as never), text: 'secret text' }] } : null })
    expect(JSON.stringify(red)).not.toContain('secret text')
    expect(red).toMatchObject({ contentHidden: true, preview: null, reason: 'harassment' })
    expect(reportDetailFor(d, can(t.mod, 'reports.content'))).toBe(d)
    expect(reportDetailFor(d, can(t.supporter, 'reports.content'))).toMatchObject({ contentHidden: true })
  })
})

describe('rank rule', () => {
  it('nobody edits roles or members at or above their own rank, or grants rights they lack', async () => {
    const env = makeEnv()
    const t = await crew(env)
    // Admin (900): eigene Rolle anlegen darunter, nicht darüber/gleich.
    const lead = createRole(env.ctx, t.admin, { name: 'Lead Mod', color: '#ff8800', rank: 800, permissions: ['roles.manage', 'reports.view', 'sanctions.warn', 'sanctions.mute'], maxSanctionMinutes: 14 * DAY })
    expect(lead).toMatchObject({ builtin: false, rank: 800, editable: true })
    expect(code(() => createRole(env.ctx, t.admin, { name: 'Boss', color: '#000000', rank: 950 }))).toBe('rank_too_low')
    expect(code(() => createRole(env.ctx, t.admin, { name: 'Twin', color: '#000000', rank: 700 }))).toBe('rank_taken')
    expect(code(() => createRole(env.ctx, t.admin, { name: 'lead mod', color: '#000000', rank: 10 }))).toBe('name_taken')
    expect(code(() => createRole(env.ctx, t.admin, { name: 'X', color: '#000000', rank: 10 }))).toBe('invalid_name')
    expect(code(() => createRole(env.ctx, t.admin, { name: 'Bad', color: 'red', rank: 10 }))).toBe('invalid_color')
    expect(code(() => createRole(env.ctx, t.admin, { name: 'Bad', color: '#000000', rank: 10, permissions: ['everything'] }))).toBe('invalid_permission')
    expect(code(() => updateRole(env.ctx, t.admin, 'admin', { color: '#123456' }))).toBe('rank_too_low')
    expect(code(() => setMemberRoles(env.ctx, t.admin, t.p1.uuid, ['admin']))).toBe('rank_too_low')
    expect(code(() => setMemberRoles(env.ctx, t.admin, t.admin.uuid, []))).toBe('cannot_change_self')
    expect(code(() => setMemberRoles(env.ctx, t.admin, ADMIN, []))).toBe('role_locked')
    expect(code(() => setMemberRoles(env.ctx, t.mod, t.p1.uuid, ['supporter']))).toBe('missing_permission')

    // Lead (800, roles.manage): verwaltet nur darunter und nur eigene Rechte.
    setMemberRoles(env.ctx, t.admin, t.p1.uuid, [lead.id])
    const leadStaff = teamOf(env.ctx, t.p1.uuid)!
    expect(leadStaff.rank).toBe(800)
    setMemberRoles(env.ctx, leadStaff, t.p2.uuid, ['moderator'])
    expect(teamOf(env.ctx, t.p2.uuid)?.roles).toEqual(['moderator'])
    expect(code(() => setMemberRoles(env.ctx, leadStaff, t.admin.uuid, []))).toBe('rank_too_low')
    expect(code(() => setMemberRoles(env.ctx, leadStaff, t.p2.uuid, ['senior_moderator', 'admin']))).toBe('rank_too_low')
    expect(code(() => updateRole(env.ctx, leadStaff, 'moderator', { permissions: ['reports.view', 'codes'] }))).toBe('missing_permission')
    // Bestehende Rechte darf er stehen lassen, eigene dazugeben, Höchstdauer nur bis zur eigenen.
    updateRole(env.ctx, leadStaff, 'supporter', { permissions: ['dashboard.view', 'reports.view', 'players.view', 'sanctions.warn', 'worlds.view', 'sanctions.mute'] })
    expect(code(() => updateRole(env.ctx, leadStaff, 'supporter', { maxSanctionMinutes: 30 * DAY }))).toBe('duration_not_allowed')
    expect(code(() => updateRole(env.ctx, leadStaff, 'supporter', { rank: 850 }))).toBe('rank_too_low')
    updateRole(env.ctx, leadStaff, 'supporter', { rank: 350, color: '#00aa00', name: 'Helfer' })
    expect(listRoleViews(env.ctx, leadStaff).find((r) => r.id === 'supporter')).toMatchObject({ rank: 350, name: 'Helfer', color: '#00aa00', editable: true })
    expect(listRoleViews(env.ctx, leadStaff).find((r) => r.id === 'admin')?.editable).toBe(false)

    // Feste Rollen lassen sich nicht löschen, Owner nie ändern; eigene schon (Mitglieder verlieren sie).
    expect(code(() => deleteRole(env.ctx, OWNER, 'moderator'))).toBe('role_builtin')
    expect(code(() => updateRole(env.ctx, OWNER, 'owner', { permissions: [] }))).toBe('role_locked')
    updateRole(env.ctx, OWNER, 'owner', { color: '#ffffff' })
    expect(code(() => deleteRole(env.ctx, leadStaff, lead.id))).toBe('rank_too_low')
    deleteRole(env.ctx, t.admin, lead.id)
    expect(teamOf(env.ctx, t.p1.uuid)).toBeNull()
    // Jede Änderung steht im Audit-Log.
    expect(all<{ action: string }>(env.ctx.db, "SELECT action FROM admin_log WHERE action LIKE 'role.%' ORDER BY id").map((r) => r.action)).toEqual(expect.arrayContaining(['role.create', 'role.update', 'role.set', 'role.delete']))
  })

  it('sanctions: only members below your rank; changes of higher-ranked colleagues stay off limits', async () => {
    const env = makeEnv()
    const t = await crew(env)
    expect(createSanction(env.ctx, t.senior, input(t.mod.uuid, 'chat_mute', 60)).id).toBeGreaterThan(0)
    expect(code(() => createSanction(env.ctx, t.senior, input(t.admin.uuid, 'warn', 60)))).toBe('cannot_moderate_staff')
    expect(code(() => createSanction(env.ctx, t.mod, input(t.mod2.uuid, 'warn', 60)))).toBe('cannot_moderate_staff')
    expect(code(() => createSanction(env.ctx, t.mod, input(t.supporter.uuid, 'warn', 60)))).toBe('ok')
    expect(code(() => createSanction(env.ctx, t.admin, input(ADMIN, 'warn', 60)))).toBe('cannot_moderate_admin')
    // Höchstdauer als Rollen-Parameter; dauerhaft nur mit sanctions.permanent; Art nur mit Recht.
    expect(code(() => createSanction(env.ctx, t.senior, input(t.p1.uuid, 'account_ban', 30 * DAY)))).toBe('ok')
    expect(code(() => createSanction(env.ctx, t.senior, input(t.p2.uuid, 'chat_mute', 30 * DAY + 1)))).toBe('duration_not_allowed')
    expect(code(() => createSanction(env.ctx, t.senior, input(t.p2.uuid, 'chat_mute', null)))).toBe('duration_not_allowed')
    expect(code(() => createSanction(env.ctx, t.supporter, input(t.p2.uuid, 'chat_mute', 60)))).toBe('missing_permission')
    expect(code(() => createSanction(env.ctx, t.content, input(t.p2.uuid, 'warn', 60)))).toBe('missing_permission')
    updateRole(env.ctx, OWNER, 'moderator', { maxSanctionMinutes: 2 * DAY })
    expect(code(() => createSanction(env.ctx, t.refresh(t.mod), input(t.p2.uuid, 'hosting_ban', 3 * DAY)))).toBe('duration_not_allowed')

    // Ändern: gleicher Rang darf (Kollegen), höherer Rang nicht; Aufheben braucht sanctions.lift.
    const bySenior = createSanction(env.ctx, t.senior, input(t.p2.uuid, 'upload_ban', DAY))
    const byMod = createSanction(env.ctx, t.mod, input(t.p2.uuid, 'social_ban', DAY))
    expect(code(() => liftSanction(env.ctx, t.mod2, bySenior.id, 'nope'))).toBe('rank_too_low')
    expect(code(() => changeDuration(env.ctx, t.mod2, byMod.id, env.clock.t + 60 * 60_000, 'kürzer'))).toBe('ok')
    expect(code(() => liftSanction(env.ctx, t.supporter, byMod.id, 'x'))).toBe('missing_permission')
    expect(code(() => liftSanction(env.ctx, t.senior, byMod.id, 'erledigt'))).toBe('ok')
    expect(one<{ created_rank: number }>(env.ctx.db, 'SELECT created_rank FROM sanctions WHERE id = ?', bySenior.id)?.created_rank).toBe(700)
  })
})

describe('public team page and the old role API', () => {
  it('shows only manually added members, grouped by public role, without banned accounts', async () => {
    const env = makeEnv()
    const t = await crew(env)
    // Niemand steht automatisch auf der Seite.
    expect((await publicTeamPage(env.ctx)).groups).toEqual([])
    addTeamPageMember(env.ctx, OWNER, ADMIN)
    addTeamPageMember(env.ctx, OWNER, t.mod2.uuid)
    addTeamPageMember(env.ctx, OWNER, t.mod.uuid)
    addTeamPageMember(env.ctx, OWNER, t.content.uuid)
    addTeamPageMember(env.ctx, OWNER, t.supporter.uuid)
    addTeamPageMember(env.ctx, OWNER, t.p1.uuid, 'recruiter')
    expect(code(() => addTeamPageMember(env.ctx, OWNER, t.p2.uuid))).toBe('group_required')
    expect(code(() => addTeamPageMember(env.ctx, OWNER, t.mod.uuid))).toBe('already_on_team_page')
    updateRole(env.ctx, OWNER, 'content', { public: false })
    createSanction(env.ctx, OWNER, input(t.supporter.uuid, 'account_ban', null))
    reorderTeamPage(env.ctx, OWNER, [{ roleId: 'moderator', uuids: [t.mod.uuid, t.mod2.uuid] }])
    updateTeamPageMember(env.ctx, OWNER, t.mod.uuid, {
      titles: { en: 'Event lead', de: 'Event-Leitung' },
      discord: 'mod_one',
      links: [{ label: 'YouTube', url: 'https://youtube.com/@mod' }],
    })
    const page = await publicTeamPage(env.ctx)
    expect(Object.fromEntries(page.groups.map((g) => [g.id, g.members.map((m) => m.name)]))).toEqual({
      owner: ['Owner'],
      moderator: ['Mod', 'Mod2'],
      recruiter: ['Player1'],
    })
    const mod = page.groups[1]!.members[0]!
    expect(mod).toMatchObject({ titles: { en: 'Event lead', de: 'Event-Leitung' }, discord: 'mod_one', links: [{ label: 'YouTube', url: 'https://youtube.com/@mod' }] })
    expect(JSON.stringify(page)).not.toContain('added_by')
    // Drag & Drop in eine andere Gruppe.
    reorderTeamPage(env.ctx, OWNER, [{ roleId: 'admin', uuids: [t.mod2.uuid] }])
    const admin = teamPageAdmin(env.ctx, OWNER)
    expect(admin.groups.find((g) => g.role.id === 'admin')!.members.map((m) => m.name)).toEqual(['Mod2'])
    expect(admin.groups.find((g) => g.role.id === 'admin')!.members[0]!.mainRole).toBe('moderator')
    expect(code(() => reorderTeamPage(env.ctx, OWNER, [{ roleId: 'admin', uuids: [t.mod2.uuid, t.mod2.uuid] }]))).toBe('duplicate_member')
    expect(code(() => reorderTeamPage(env.ctx, OWNER, [{ roleId: 'admin', uuids: [t.p2.uuid] }]))).toBe('not_on_team_page')
    removeTeamPageMember(env.ctx, OWNER, t.p1.uuid)
    expect((await publicTeamPage(env.ctx)).groups.map((g) => g.id)).toEqual(['owner', 'admin', 'moderator'])
    // Rechte: Owner/Admin haben team.page, Moderatoren nicht.
    expect(can(t.admin, 'team.page')).toBe(true)
    expect(can(t.mod, 'team.page')).toBe(false)
  })

  it('remembers skins from Mojang and serves them from the database afterwards', async () => {
    const env = makeEnv()
    const t = await crew(env)
    const tex = 'https://textures.minecraft.net/texture/abc123'
    env.mojang.accounts.set('mod', { uuid: t.mod.uuid, name: 'Mod', model: 'slim', skinUrl: tex, capeUrl: null })
    addTeamPageMember(env.ctx, OWNER, t.mod.uuid)
    const first = await publicTeamPage(env.ctx)
    expect(first.groups[0]!.members[0]!.skin).toEqual({ url: tex, model: 'slim' })
    expect(storedSkins(env.ctx.db, [t.mod.uuid]).get(t.mod.uuid)).toMatchObject({ url: tex, model: 'slim' })
    // Zweiter Aufruf ohne Mojang (auch wenn Mojang ausfällt).
    const calls = env.mojang.profileCalls
    env.mojang.fail = true
    expect((await publicTeamPage(env.ctx)).groups[0]!.members[0]!.skin?.url).toBe(tex)
    expect(env.mojang.profileCalls).toBe(calls)
    // Fremde Adressen werden nie gespeichert.
    rememberSkin(env.ctx.db, { uuid: t.mod.uuid, skinUrl: 'https://evil.example/x.png', model: 'classic' }, 1)
    expect(storedSkins(env.ctx.db, [t.mod.uuid]).get(t.mod.uuid)?.url).toBeNull()
  })

  it('migration 15 is idempotent and adds team.page to owner/admin', () => {
    const db = new DatabaseSync(':memory:')
    migrate(db)
    migrateTeamPage(db)
    migrateTeamPage(db)
    const perms = (id: string) => JSON.parse(one<{ permissions: string }>(db, 'SELECT permissions FROM team_roles WHERE id = ?', id)!.permissions) as string[]
    expect(perms('admin').filter((p) => p === 'team.page')).toHaveLength(1)
    expect(perms('moderator')).not.toContain('team.page')
  })

  it('lists the real team roles of a player (owner first, then by rank)', async () => {
    const env = makeEnv()
    const t = await crew(env)
    setMemberRoles(env.ctx, OWNER, t.mod2.uuid, ['moderator', 'recruiter'])
    expect(roleRefsOf(env.ctx, ADMIN).map((r) => r.id)).toEqual(['owner'])
    expect(roleRefsOf(env.ctx, t.mod2.uuid).map((r) => r.id)).toEqual(['moderator', 'recruiter'])
    expect(roleRefsOf(env.ctx, t.content.uuid).map((r) => r.id)).toEqual(['content'])
    expect(roleRefsOf(env.ctx, t.p1.uuid)).toEqual([])
  })

  it('accepts only safe links and Discord names', () => {
    expect(teamPageLinkSchema.safeParse({ label: 'x', url: 'javascript:alert(1)' }).success).toBe(false)
    expect(teamPageLinkSchema.safeParse({ label: 'x', url: 'http://example.com' }).success).toBe(false)
    expect(teamPageLinkSchema.safeParse({ label: 'x', url: 'https://user:pw@example.com' }).success).toBe(false)
    expect(teamPageLinkSchema.safeParse({ label: 'x', url: 'https://localhost/x' }).success).toBe(false)
    expect(teamPageLinkSchema.safeParse({ label: 'Twitch', url: 'https://twitch.tv/abc' }).success).toBe(true)
    expect(discordNameSchema.safeParse('Some.User_1').data).toBe('some.user_1')
    expect(discordNameSchema.safeParse('a..b').success).toBe(false)
    expect(discordNameSchema.safeParse('<script>').success).toBe(false)
  })

  it('keeps /v1/admin/roles working (admin/moderator map to the default roles)', async () => {
    const env = makeEnv()
    const t = await crew(env)
    setMemberRoles(env.ctx, OWNER, t.p1.uuid, ['recruiter'])
    setRole(env.ctx, OWNER, t.p1.uuid, 'moderator', 'Probe')
    expect(teamOf(env.ctx, t.p1.uuid)?.roles).toEqual(['moderator', 'recruiter'])
    setRole(env.ctx, OWNER, t.p1.uuid, 'admin')
    expect(teamOf(env.ctx, t.p1.uuid)?.roles).toEqual(['admin', 'recruiter'])
    expect(listRoles(env.ctx).find((r) => r.uuid === t.p1.uuid)).toMatchObject({ role: 'admin', source: 'db' })
    expect(listRoles(env.ctx)[0]).toMatchObject({ uuid: ADMIN, role: 'admin', source: 'env' })
    void getUser
  })
})

describe('migration 12', () => {
  it('maps staff_roles to the default roles without loss, backfills sanction ranks, drops code sign-in, is idempotent', () => {
    const db = new DatabaseSync(':memory:')
    db.exec('PRAGMA foreign_keys = ON')
    db.exec('CREATE TABLE schema_migrations (version INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL)')
    for (const m of MIGRATIONS.filter((x) => x.version <= 11)) {
      if (m.sql) db.exec(m.sql)
      m.run?.(db)
      db.prepare('INSERT INTO schema_migrations (version, applied_at) VALUES (?, ?)').run(m.version, 1)
    }
    const a = 'a'.repeat(32)
    const m = 'b'.repeat(32)
    for (const [u, n] of [[a, 'Admin'], [m, 'Mod']] as const) {
      db.prepare('INSERT INTO users (uuid, name, name_lower, created_at, last_login_at) VALUES (?, ?, ?, 1, 1)').run(u, n, n.toLowerCase())
    }
    db.prepare("INSERT INTO staff_roles (uuid, role, granted_at, granted_by, note) VALUES (?, 'admin', 111, ?, 'Co-Admin')").run(a, ADMIN)
    db.prepare("INSERT INTO staff_roles (uuid, role, granted_at, granted_by, note) VALUES (?, 'moderator', 222, ?, NULL)").run(m, a)
    const ins = db.prepare("INSERT INTO sanctions (uuid, kind, reason_code, created_at, created_by, created_role, updated_at) VALUES (?, 'warn', 'spam', 1, ?, ?, 1)")
    ins.run('c'.repeat(32), a, 'admin')
    ins.run('c'.repeat(32), m, 'moderator')
    ins.run('c'.repeat(32), 'system', 'system')
    db.prepare("INSERT INTO web_sessions (token_hash, uuid, csrf, created_at, expires_at) VALUES ('h', ?, 'c', 1, 99999999999999)").run(a)

    expect(migrate(db)).toBe(MIGRATIONS.filter((x) => x.version > 11).length)
    expect(all(db, 'SELECT uuid, role_id, granted_at, granted_by, note FROM team_members ORDER BY granted_at')).toEqual([
      { uuid: a, role_id: 'admin', granted_at: 111, granted_by: ADMIN, note: 'Co-Admin' },
      { uuid: m, role_id: 'moderator', granted_at: 222, granted_by: a, note: null },
    ])
    expect(all<{ created_rank: number }>(db, 'SELECT created_rank FROM sanctions ORDER BY id').map((r) => r.created_rank)).toEqual([900, 500, 0])
    for (const t of ['staff_roles', 'web_logins']) expect(one(db, 'SELECT 1 AS x FROM sqlite_master WHERE name = ?', t)).toBeUndefined()
    expect(all(db, 'SELECT * FROM web_sessions')).toHaveLength(0)
    expect(all<{ id: string }>(db, 'SELECT id FROM team_roles ORDER BY rank DESC').map((r) => r.id)).toEqual(BUILTIN_ROLES.map((r) => r.id))
    // Zweiter Lauf: nichts doppelt, Anpassungen bleiben.
    db.prepare("UPDATE team_roles SET color = '#010203' WHERE id = 'moderator'").run()
    migrateTeamV3(db)
    expect(one<{ n: number }>(db, 'SELECT COUNT(*) AS n FROM team_roles')!.n).toBe(BUILTIN_ROLES.length)
    expect(one<{ color: string }>(db, "SELECT color FROM team_roles WHERE id = 'moderator'")!.color).toBe('#010203')
    expect(one<{ n: number }>(db, 'SELECT COUNT(*) AS n FROM team_members')!.n).toBe(2)
    expect(migrate(db)).toBe(0)
    db.close()
  })
})
