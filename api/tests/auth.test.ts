import { describe, expect, it } from 'vitest'
import { banUser } from '../server/lib/admin'
import { authenticate, authenticateAdmin, createChallenge, logout, sweepExpired, verifyLogin } from '../server/lib/auth'
import { all, one } from '../server/lib/db'
import { createMojangClient } from '../server/lib/mojang'
import { ADMIN, login, makeEnv } from './helpers'

const UUID = '0123456789abcdef0123456789abcdef'

async function code(p: Promise<unknown>): Promise<string> {
  try {
    await p
  } catch (e) {
    return (e as { code?: string }).code ?? String(e)
  }
  return 'ok'
}

describe('Mojang session login', () => {
  it('issues an opaque token after hasJoined and stores only its hash', async () => {
    const env = makeEnv()
    const { serverId, expiresAt } = createChallenge(env.ctx)
    expect(serverId).toMatch(/^[0-9a-f]{40}$/)
    expect(Date.parse(expiresAt) - env.clock.t).toBe(60_000)
    env.mojang.join('Steve', UUID, serverId)
    const r = await verifyLogin(env.ctx, 'steve', serverId)
    expect(r.token).toMatch(/^trs_[A-Za-z0-9_-]{43}$/)
    expect(r.user).toMatchObject({ uuid: UUID, name: 'Steve', admin: false })
    expect(Date.parse(r.expiresAt) - env.clock.t).toBe(30 * 24 * 3600 * 1000)
    const rows = all<{ token_hash: string }>(env.ctx.db, 'SELECT token_hash FROM sessions')
    expect(rows).toHaveLength(1)
    expect(rows[0]!.token_hash).toMatch(/^[0-9a-f]{64}$/)
    expect(rows[0]!.token_hash).not.toContain(r.token.slice(4, 20))
    expect(authenticate(env.ctx, `Bearer ${r.token}`).uuid).toBe(UUID)
  })

  it('challenges are single-use', async () => {
    const env = makeEnv()
    const { serverId } = createChallenge(env.ctx)
    env.mojang.join('Steve', UUID, serverId)
    await verifyLogin(env.ctx, 'Steve', serverId)
    expect(await code(verifyLogin(env.ctx, 'Steve', serverId))).toBe('invalid_challenge')
  })

  it('challenges expire after 60 s', async () => {
    const env = makeEnv()
    const { serverId } = createChallenge(env.ctx)
    env.mojang.join('Steve', UUID, serverId)
    env.clock.advance(60_001)
    expect(await code(verifyLogin(env.ctx, 'Steve', serverId))).toBe('invalid_challenge')
    expect(env.mojang.calls).toBe(0)
  })

  it('rejects when Mojang did not confirm the join, and burns the challenge', async () => {
    const env = makeEnv()
    const { serverId } = createChallenge(env.ctx)
    expect(await code(verifyLogin(env.ctx, 'Steve', serverId))).toBe('not_joined')
    env.mojang.join('Steve', UUID, serverId)
    expect(await code(verifyLogin(env.ctx, 'Steve', serverId))).toBe('invalid_challenge')
  })

  it('rejects a join made under a different name', async () => {
    const env = makeEnv()
    const { serverId } = createChallenge(env.ctx)
    env.mojang.join('Alex', UUID, serverId)
    expect(await code(verifyLogin(env.ctx, 'Steve', serverId))).toBe('not_joined')
  })

  it('maps Mojang outages to 502 upstream_unavailable', async () => {
    const env = makeEnv()
    env.mojang.fail = true
    const { serverId } = createChallenge(env.ctx)
    expect(await code(verifyLogin(env.ctx, 'Steve', serverId))).toBe('upstream_unavailable')
  })

  it('banned accounts cannot log in and lose their sessions', async () => {
    const env = makeEnv()
    const r = await login(env, 'Griefer', UUID)
    banUser(env.ctx, 'api-key', UUID, 'grief')
    expect(() => authenticate(env.ctx, `Bearer ${r.token}`)).toThrow(/banned|Invalid/)
    const { serverId } = createChallenge(env.ctx)
    env.mojang.join('Griefer', UUID, serverId)
    expect(await code(verifyLogin(env.ctx, 'Griefer', serverId))).toBe('banned')
  })

  it('rate-limits logins per UUID', async () => {
    const env = makeEnv()
    let last = 'ok'
    for (let i = 0; i < 21; i++) {
      const { serverId } = createChallenge(env.ctx)
      env.mojang.join('Steve', UUID, serverId)
      last = await code(verifyLogin(env.ctx, 'Steve', serverId))
    }
    expect(last).toBe('rate_limited')
  })

  it('keeps at most 10 sessions per account', async () => {
    const env = makeEnv()
    for (let i = 0; i < 12; i++) {
      await login(env, 'Steve', UUID)
      env.clock.advance(1000)
    }
    expect(one<{ n: number }>(env.ctx.db, 'SELECT COUNT(*) AS n FROM sessions WHERE uuid = ?', UUID)!.n).toBe(10)
  })
})

describe('bearer tokens', () => {
  it('rejects malformed, unknown and expired tokens', async () => {
    const env = makeEnv()
    const r = await login(env, 'Steve', UUID)
    expect(() => authenticate(env.ctx, undefined)).toThrow('Authentication required')
    expect(() => authenticate(env.ctx, r.token)).toThrow()
    expect(() => authenticate(env.ctx, `Bearer trs_${'A'.repeat(43)}`)).toThrow('Invalid or expired token')
    expect(() => authenticate(env.ctx, `Bearer ${r.token}x`)).toThrow('Invalid token')
    env.clock.advance(30 * 24 * 3600 * 1000 + 1)
    expect(() => authenticate(env.ctx, `Bearer ${r.token}`)).toThrow('Invalid or expired token')
    sweepExpired(env.ctx)
    expect(one<{ n: number }>(env.ctx.db, 'SELECT COUNT(*) AS n FROM sessions')!.n).toBe(0)
  })

  it('logout revokes one or all sessions', async () => {
    const env = makeEnv()
    const a = await login(env, 'Steve', UUID)
    const b = await login(env, 'Steve', UUID)
    const c = await login(env, 'Steve', UUID)
    logout(env.ctx, authenticate(env.ctx, `Bearer ${a.token}`), false)
    expect(() => authenticate(env.ctx, `Bearer ${a.token}`)).toThrow()
    expect(authenticate(env.ctx, `Bearer ${b.token}`).uuid).toBe(UUID)
    logout(env.ctx, authenticate(env.ctx, `Bearer ${b.token}`), true)
    expect(() => authenticate(env.ctx, `Bearer ${c.token}`)).toThrow()
  })
})

describe('admin access', () => {
  it('accepts admin sessions from ADMIN_UUIDS and the API key, nothing else', async () => {
    const env = makeEnv()
    const admin = await login(env, 'Theredstonee', ADMIN)
    const user = await login(env, 'Steve', UUID)
    expect(admin.user.admin).toBe(true)
    expect(authenticateAdmin(env.ctx, { authorization: `Bearer ${admin.token}` })).toBe(ADMIN)
    expect(() => authenticateAdmin(env.ctx, { authorization: `Bearer ${user.token}` })).toThrow('Admin only')
    expect(authenticateAdmin(env.ctx, { adminKey: 'admin-key-0123456789abcdef-0123456789abcdef' })).toBe('api-key')
    expect(() => authenticateAdmin(env.ctx, { adminKey: 'admin-key-0123456789abcdef-0123456789abcdeX' })).toThrow('Invalid admin key')
    // Ein falscher Schlüssel fällt nicht auf die Sitzung zurück.
    expect(() => authenticateAdmin(env.ctx, { adminKey: 'x', authorization: `Bearer ${admin.token}` })).toThrow('Invalid admin key')
  })

  it('without ADMIN_API_KEY the header never works', () => {
    const env = makeEnv({ env: { ADMIN_API_KEY: '' } })
    expect(() => authenticateAdmin(env.ctx, { adminKey: '' })).toThrow('Invalid admin key')
  })
})

describe('Mojang client', () => {
  const fake = (status: number, body = '') =>
    (async () => new Response(status === 204 ? null : body, { status })) as unknown as typeof fetch

  it('maps 204 to "not joined" and 200 to a profile', async () => {
    expect(await createMojangClient('https://s.test', fake(204)).hasJoined('Steve', 'a'.repeat(40))).toBeNull()
    const p = await createMojangClient('https://s.test', fake(200, JSON.stringify({ id: UUID, name: 'Steve', properties: [] }))).hasJoined('Steve', 'x')
    expect(p).toEqual({ uuid: UUID, name: 'Steve' })
  })

  it('treats server errors and odd payloads as outages', async () => {
    await expect(createMojangClient('https://s.test', fake(500)).hasJoined('Steve', 'x')).rejects.toThrow('HTTP 500')
    await expect(createMojangClient('https://s.test', fake(200, '{nope')).hasJoined('Steve', 'x')).rejects.toThrow('invalid JSON')
    await expect(createMojangClient('https://s.test', fake(200, JSON.stringify({ id: 'x', name: 'Steve' }))).hasJoined('Steve', 'x')).rejects.toThrow('invalid id')
  })

  it('builds the hasJoined URL with encoded parameters', async () => {
    let seen = ''
    const f = (async (u: URL) => {
      seen = String(u)
      return new Response(null, { status: 204 })
    }) as unknown as typeof fetch
    await createMojangClient('https://sessionserver.mojang.com', f).hasJoined('Steve', 'b'.repeat(40))
    expect(seen).toBe(`https://sessionserver.mojang.com/session/minecraft/hasJoined?username=Steve&serverId=${'b'.repeat(40)}`)
  })
})
