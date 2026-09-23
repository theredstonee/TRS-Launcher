import { describe, expect, it } from 'vitest'
import { RULES, RateLimiter } from '../server/lib/ratelimit'
import { PresenceStore } from '../server/lib/presence'

describe('rate limiter', () => {
  it('allows `limit` requests, then 429 with Retry-After, then refills', () => {
    let t = 0
    const rl = new RateLimiter(() => t)
    const rule = { limit: 3, windowMs: 60_000 }
    expect([1, 2, 3].map(() => rl.take('k', rule).ok)).toEqual([true, true, true])
    const denied = rl.take('k', rule)
    expect(denied).toEqual({ ok: false, retryAfter: 20 })
    t += 20_000
    expect(rl.take('k', rule).ok).toBe(true)
    expect(rl.take('k', rule).ok).toBe(false)
    expect(rl.take('other', rule).ok).toBe(true)
  })

  it('check() does not consume (used for failed-attempt counters)', () => {
    const rl = new RateLimiter(() => 0)
    const rule = { limit: 1, windowMs: 1000 }
    expect(rl.check('k', rule).ok).toBe(true)
    expect(rl.check('k', rule).ok).toBe(true)
    rl.take('k', rule)
    expect(rl.check('k', rule).ok).toBe(false)
  })

  it('brute-force budget for redeem codes: 5 failures per 15 min', () => {
    let t = 0
    const rl = new RateLimiter(() => t)
    for (let i = 0; i < 5; i++) rl.take('fail', RULES.redeemFailUser)
    expect(rl.check('fail', RULES.redeemFailUser)).toEqual({ ok: false, retryAfter: 180 })
    t += 180_000
    expect(rl.check('fail', RULES.redeemFailUser).ok).toBe(true)
  })

  it('sweep drops full buckets and caps memory', () => {
    let t = 0
    const rl = new RateLimiter(() => t, 10)
    for (let i = 0; i < 10; i++) rl.take(`k${i}`, { limit: 5, windowMs: 1000 })
    rl.take('k10', { limit: 5, windowMs: 1000 })
    expect(rl.size).toBeLessThanOrEqual(10)
    t += 5000
    rl.sweep()
    expect(rl.size).toBe(0)
  })
})

describe('presence store', () => {
  it('reports changes only when state or game changes', () => {
    let t = 0
    const p = new PresenceStore(180_000, () => t)
    expect(p.set('u', 'online', null)).toBe(true)
    expect(p.set('u', 'online', null)).toBe(false)
    expect(p.set('u', 'in-game', { version: '1.21.1', loader: 'fabric' })).toBe(true)
    t += 180_000
    expect(p.sweep()).toEqual(['u'])
    expect(p.get('u')).toBeNull()
  })
})
