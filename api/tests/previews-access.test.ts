import { DatabaseSync } from 'node:sqlite'
import { describe, expect, it } from 'vitest'
import { teamTexts } from '../app/utils/team-i18n'
import { adminCreateCreator, adminCreatePreview, creatorPreviews } from '../server/lib/creators'
import { all, migrate } from '../server/lib/db'
import { ApiError } from '../server/lib/errors'
import { MIGRATIONS, migratePreviewsAccess } from '../server/lib/migrations'
import { createRole, ownerStaff, setMemberRoles } from '../server/lib/team'
import { PERMISSION_GROUPS, PERMISSIONS } from '../shared/team'
import { ADMIN, login, makeEnv } from './helpers'

const OWNER = ownerStaff(ADMIN)

function code(fn: () => unknown): string {
  try {
    fn()
  } catch (e) {
    if (e instanceof ApiError) return e.code
    throw e
  }
  return 'ok'
}

describe('previews.access (preview builds for the team)', () => {
  it('is a permission with EN/DE/ES texts', () => {
    expect(PERMISSIONS).toContain('previews.access')
    expect(PERMISSION_GROUPS.filter((g) => g.permissions.includes('previews.access')).map((g) => g.id)).toEqual(['previews'])
    for (const lang of ['en', 'de', 'es'] as const) {
      expect(teamTexts[lang].adm.perms['previews.access']).toBeTruthy()
      expect(teamTexts[lang].adm.permGroups.previews).toBeTruthy()
    }
  })

  it('creator tiers: partner ok, starter 403', async () => {
    const env = makeEnv()
    const p = (await login(env, 'Partnerin')).user.uuid
    const s = (await login(env, 'Starterin')).user.uuid
    adminCreateCreator(env.ctx, OWNER, { player: 'Partnerin', tier: 'partner' })
    adminCreateCreator(env.ctx, OWNER, { player: 'Starterin', tier: 'starter' })
    adminCreatePreview(env.ctx, OWNER, { product: 'client', version: '1.0.0-pre', url: 'https://example.test/a.jar', sha256: 'a'.repeat(64) })
    expect(creatorPreviews(env.ctx, p).previews).toHaveLength(1)
    expect(code(() => creatorPreviews(env.ctx, s))).toBe('creator_tier')
  })

  it('team member: with the permission ok (no creator), without it 403', async () => {
    const env = makeEnv()
    const withPerm = (await login(env, 'Teamie')).user.uuid
    const without = (await login(env, 'Moddy')).user.uuid
    adminCreatePreview(env.ctx, OWNER, { product: 'launcher', version: '2.0.0-pre', url: 'https://example.test/b.exe', sha256: 'b'.repeat(64) })
    const role = createRole(env.ctx, OWNER, { name: 'Tester', color: '#aabbcc', rank: 5, permissions: ['previews.access'] })
    const other = createRole(env.ctx, OWNER, { name: 'Other', color: '#aabbcc', rank: 4, permissions: ['dashboard.view'] })
    setMemberRoles(env.ctx, OWNER, withPerm, [role.id])
    setMemberRoles(env.ctx, OWNER, without, [other.id])
    expect(creatorPreviews(env.ctx, withPerm).previews[0]).toMatchObject({ product: 'launcher', version: '2.0.0-pre' })
    expect(code(() => creatorPreviews(env.ctx, without))).toBe('creator_inactive')
    const nobody = (await login(env, 'Niemand')).user.uuid
    expect(code(() => creatorPreviews(env.ctx, nobody))).toBe('creator_inactive')
  })

  it('migration 33 is registered, idempotent, owner + admin only', () => {
    const db = new DatabaseSync(':memory:')
    db.exec('PRAGMA foreign_keys = ON')
    migrate(db)
    expect(MIGRATIONS.find((m) => m.version === 33)?.run).toBe(migratePreviewsAccess)
    db.prepare("UPDATE team_roles SET permissions = ? WHERE id = 'admin'").run(JSON.stringify(['dashboard.view']))
    migratePreviewsAccess(db)
    migratePreviewsAccess(db)
    const perms = Object.fromEntries(all<{ id: string, permissions: string }>(db, 'SELECT id, permissions FROM team_roles').map((r) => [r.id, JSON.parse(r.permissions) as string[]]))
    expect(perms.admin).toEqual(['dashboard.view', 'previews.access'])
    expect(perms.owner).toContain('previews.access')
    expect(perms.moderator).not.toContain('previews.access')
  })
})
