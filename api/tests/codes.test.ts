import { describe, expect, it } from 'vitest'
import { canUse, getCape } from '../server/lib/capes'
import { createCodes, listCodes, redeemCode, revokeCode } from '../server/lib/codes'
import { all } from '../server/lib/db'
import { normalizeRedeemCode } from '../server/lib/ids'
import { ADMIN, login, makeEnv, seedFixtures } from './helpers'

function err(fn: () => unknown): string {
  try {
    fn()
  } catch (e) {
    return (e as { code: string }).code
  }
  return 'ok'
}

describe('redeem codes', () => {
  it('unlocks a code cape once and stores codes only hashed', async () => {
    const env = makeEnv()
    seedFixtures(env)
    const u = await login(env, 'Steve')
    const { codes } = createCodes(env.ctx, ADMIN, { capeId: 'emerald', maxUses: 1, count: 1 })
    const plain = codes[0]!.code
    const n = normalizeRedeemCode(plain)!
    const stored = all<Record<string, unknown>>(env.ctx.db, 'SELECT * FROM codes')
    expect(JSON.stringify(stored)).not.toContain(n)
    expect(JSON.stringify(listCodes(env.ctx))).not.toContain(n)
    expect(listCodes(env.ctx)[0]!.hint).toBe(n.slice(-4))

    expect(canUse(env.ctx, u.user.uuid, getCape(env.ctx, 'emerald')!)).toBe(false)
    const r = redeemCode(env.ctx, u.user.uuid, n)
    expect(r).toMatchObject({ alreadyOwned: false, cape: { id: 'emerald', scale: 1, animated: false } })
    expect(canUse(env.ctx, u.user.uuid, getCape(env.ctx, 'emerald')!)).toBe(true)
  })

  it('single-use codes cannot be used by a second player; re-redeeming does not consume', async () => {
    const env = makeEnv()
    seedFixtures(env)
    const a = await login(env, 'Alex')
    const b = await login(env, 'Bob')
    const n = normalizeRedeemCode(createCodes(env.ctx, ADMIN, { capeId: 'team', maxUses: 1, count: 1 }).codes[0]!.code)!
    redeemCode(env.ctx, a.user.uuid, n)
    expect(redeemCode(env.ctx, a.user.uuid, n).alreadyOwned).toBe(true)
    expect(err(() => redeemCode(env.ctx, b.user.uuid, n))).toBe('code_used_up')
    expect(listCodes(env.ctx)[0]!.uses).toBe(1)
  })

  it('multi-use codes count uses per player', async () => {
    const env = makeEnv()
    seedFixtures(env)
    const n = normalizeRedeemCode(createCodes(env.ctx, ADMIN, { capeId: 'emerald', maxUses: 2, count: 1 }).codes[0]!.code)!
    const players = [await login(env, 'P1'), await login(env, 'P2'), await login(env, 'P3')]
    expect(redeemCode(env.ctx, players[0]!.user.uuid, n).alreadyOwned).toBe(false)
    expect(redeemCode(env.ctx, players[1]!.user.uuid, n).alreadyOwned).toBe(false)
    expect(err(() => redeemCode(env.ctx, players[2]!.user.uuid, n))).toBe('code_used_up')
  })

  it('expired, revoked and unknown codes fail', async () => {
    const env = makeEnv()
    seedFixtures(env)
    const u = await login(env, 'Steve')
    const exp = createCodes(env.ctx, ADMIN, {
      capeId: 'emerald',
      maxUses: 1,
      count: 1,
      expiresAt: new Date(env.clock.t + 60_000).toISOString(),
    }).codes[0]!
    env.clock.advance(61_000)
    expect(err(() => redeemCode(env.ctx, u.user.uuid, normalizeRedeemCode(exp.code)!))).toBe('code_expired')

    const rev = createCodes(env.ctx, ADMIN, { capeId: 'emerald', maxUses: 5, count: 1 }).codes[0]!
    revokeCode(env.ctx, rev.id)
    expect(err(() => redeemCode(env.ctx, u.user.uuid, normalizeRedeemCode(rev.code)!))).toBe('invalid_code')
    expect(err(() => redeemCode(env.ctx, u.user.uuid, 'ABCDEFGHJKMNPQRSTVWX'))).toBe('invalid_code')
    expect(err(() => revokeCode(env.ctx, 9999))).toBe('code_not_found')
  })

  it('cannot create codes for free or unknown capes or past dates', () => {
    const env = makeEnv()
    seedFixtures(env)
    expect(err(() => createCodes(env.ctx, ADMIN, { capeId: 'redstone', maxUses: 1, count: 1 }))).toBe('cape_is_free')
    expect(err(() => createCodes(env.ctx, ADMIN, { capeId: 'nope', maxUses: 1, count: 1 }))).toBe('cape_not_found')
    expect(
      err(() => createCodes(env.ctx, ADMIN, { capeId: 'team', maxUses: 1, count: 1, expiresAt: new Date(env.clock.t - 1).toISOString() })),
    ).toBe('invalid_request')
  })

  it('creates batches of unique codes', () => {
    const env = makeEnv()
    seedFixtures(env)
    const { codes } = createCodes(env.ctx, ADMIN, { capeId: 'team', maxUses: 1, count: 50 })
    expect(new Set(codes.map((c) => c.code)).size).toBe(50)
  })
})
