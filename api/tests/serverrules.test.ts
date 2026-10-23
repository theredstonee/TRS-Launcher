import { DatabaseSync } from 'node:sqlite'
import { describe, expect, it } from 'vitest'
import { messages } from '../app/utils/messages'
import { SERVER_RULES_CONFIG_EXAMPLE, serversTexts } from '../app/utils/servers-i18n'
import { teamTexts } from '../app/utils/team-i18n'
import { all, migrate } from '../server/lib/db'
import { buildLlmsTxt } from '../server/lib/llms'
import { MIGRATIONS, migrateServerRulesBypass } from '../server/lib/migrations'
import { myTeamView, ownerStaff, setMemberRoles, teamOf } from '../server/lib/team'
import { BUILTIN_ROLES, PERMISSION_GROUPS, PERMISSIONS } from '../shared/team'
import { SITE_PAGES } from '../shared/seo'
import { landingTexts } from '../shared/landing'
import { ADMIN, login, makeEnv } from './helpers'

// TRS Server Rules: Team-Recht `serverrules.bypass` (offen dokumentierter Team-Bypass) und die Seite „Für Server“.

const LANGS = ['en', 'de', 'es'] as const

describe('serverrules.bypass', () => {
  it('is a permission in exactly one group, with labels for every permission and group in EN/DE/ES', () => {
    expect(PERMISSIONS).toContain('serverrules.bypass')
    expect(PERMISSION_GROUPS.filter((g) => g.permissions.includes('serverrules.bypass')).map((g) => g.id)).toEqual(['serverrules'])
    for (const lang of LANGS) {
      const t = teamTexts[lang].adm
      for (const p of PERMISSIONS) expect(t.perms[p], `${lang} ${p}`).toBeTruthy()
      for (const g of PERMISSION_GROUPS) expect(t.permGroups[g.id], `${lang} group ${g.id}`).toBeTruthy()
    }
  })

  it('default roles: owner and admin have it, nobody else', () => {
    const has = Object.fromEntries(BUILTIN_ROLES.map((r) => [r.id, r.permissions.includes('serverrules.bypass')]))
    expect(has).toEqual({ owner: true, admin: true, senior_moderator: false, moderator: false, supporter: false, content: false, recruiter: false })
  })

  it('migration 32 is registered, idempotent and grants it to owner + admin only (custom changes stay)', () => {
    const db = new DatabaseSync(':memory:')
    db.exec('PRAGMA foreign_keys = ON')
    migrate(db)
    expect(MIGRATIONS.find((m) => m.version === 32)?.run).toBe(migrateServerRulesBypass)
    // Wie eine alte Datenbank: Admin hatte das Recht noch nicht, Moderator hat eine eigene Änderung.
    db.prepare("UPDATE team_roles SET permissions = ? WHERE id = 'admin'").run(JSON.stringify(['dashboard.view']))
    migrateServerRulesBypass(db)
    migrateServerRulesBypass(db)
    const roles = all<{ id: string, permissions: string }>(db, 'SELECT id, permissions FROM team_roles ORDER BY id')
    const perms = Object.fromEntries(roles.map((r) => [r.id, JSON.parse(r.permissions) as string[]]))
    expect(perms.admin).toEqual(['dashboard.view', 'serverrules.bypass'])
    expect(perms.owner).toContain('serverrules.bypass')
    for (const id of ['senior_moderator', 'moderator', 'supporter', 'content', 'recruiter']) expect(perms[id], id).not.toContain('serverrules.bypass')
  })

  it('reaches the TRS Client through me.team.permissions (owner yes, moderator no)', async () => {
    const env = makeEnv()
    await login(env, 'Theredstonee', ADMIN)
    const mod = await login(env, 'Moddy')
    const admin = await login(env, 'Admina')
    const owner = ownerStaff(ADMIN)
    setMemberRoles(env.ctx, owner, mod.user.uuid, ['moderator'])
    setMemberRoles(env.ctx, owner, admin.user.uuid, ['admin'])
    expect(myTeamView(env.ctx, teamOf(env.ctx, ADMIN)!).permissions).toContain('serverrules.bypass')
    expect(myTeamView(env.ctx, teamOf(env.ctx, admin.user.uuid)!).permissions).toContain('serverrules.bypass')
    expect(myTeamView(env.ctx, teamOf(env.ctx, mod.user.uuid)!).permissions).not.toContain('serverrules.bypass')
  })
})

describe('website: /servers', () => {
  it('is in the sitemap and in the navigation texts', () => {
    expect(SITE_PAGES.map((p) => p.path)).toContain('/servers')
    for (const lang of LANGS) expect(messages[lang].nav.servers, lang).toBeTruthy()
  })

  it('SEO: title ≤ 65, description 51–160, unique against the other pages', () => {
    for (const lang of LANGS) {
      const { title, description } = serversTexts[lang].seo
      expect(title.length, `${lang} title`).toBeLessThanOrEqual(65)
      expect(description.length, `${lang} description`).toBeGreaterThan(50)
      expect(description.length, `${lang} description`).toBeLessThanOrEqual(160)
      const others = Object.values(messages[lang].seo).filter((v): v is { title: string } => typeof v === 'object' && v !== null && 'title' in v)
      expect(others.map((o) => o.title)).not.toContain(title)
    }
  })

  it('every language has the same structure and names the team bypass openly', () => {
    for (const lang of LANGS) {
      const s = serversTexts[lang]
      expect(s.features.items).toHaveLength(serversTexts.en.features.items.length)
      expect(s.platforms.items.map((p) => p.id)).toEqual(['bukkit', 'velocity', 'bungee', 'sponge', 'mods'])
      expect(s.how.steps).toHaveLength(serversTexts.en.how.steps.length)
      expect(s.team.text).toContain('serverrules.bypass')
      expect(s.team.points.join(' ')).toContain('trsrules.bypass')
    }
  })

  it('the config example matches the documented format', () => {
    expect(SERVER_RULES_CONFIG_EXAMPLE).toContain('serverrules.bypass')
    for (const key of ['modules:', 'settings:', 'worlds:', 'message:', 'missing-client:', 'action: ignore', 'timeout-seconds:', 'bypass-permission: trsrules.bypass']) {
      expect(SERVER_RULES_CONFIG_EXAMPLE).toContain(key)
    }
  })

  it('llms.txt links the page', () => {
    const txt = buildLlmsTxt({
      siteUrl: 'https://site.test',
      m: messages.en,
      landing: landingTexts.en,
      pages: { issues: 'i', roadmap: 'r', circuits: 'c', team: 't', servers: serversTexts.en.seo.description },
      privacy: 'p',
      releases: [],
    })
    expect(txt).toContain('(https://site.test/servers)')
  })
})
