import assert from 'node:assert/strict'
import { createHmac } from 'node:crypto'
import { describe, it } from 'node:test'
import { ConfigError, loadConfig } from '../src/config.ts'
import { TokenBucket } from '../src/limits.ts'
import { signToken, verifyToken, type RelayToken } from '../src/token.ts'
import { GUEST, HOST, OTHER_SECRET, ROOM, SECRET } from './helpers.ts'

const NOW = 1_790_000_000
const base: RelayToken = { v: 1, r: ROOM, u: GUEST, h: HOST, role: 'guest', m: 4, iat: NOW, exp: NOW + 120, n: '0123456789abcdef' }
const make = (over: Partial<RelayToken> = {}, secret = SECRET) => signToken({ ...base, ...over }, secret)

/** Token mit beliebigem Inhalt (auch ungültigem JSON), korrekt signiert. */
function rawToken(payload: string, secret = SECRET): string {
  const p = Buffer.from(payload).toString('base64url')
  return `trsr1.${p}.${createHmac('sha256', secret).update(`trsr1.${p}`).digest('base64url')}`
}

describe('relay token', () => {
  it('accepts a valid token and exposes its fields', () => {
    const r = verifyToken(make(), [SECRET], NOW + 10)
    assert.ok(r.ok)
    assert.equal(r.token.r, ROOM)
    assert.equal(r.token.role, 'guest')
  })

  it('matches the documented signature format', () => {
    const t = make()
    const [prefix, p, s] = t.split('.')
    assert.equal(prefix, 'trsr1')
    assert.equal(s, createHmac('sha256', SECRET).update(`trsr1.${p}`).digest('base64url'))
    assert.equal(s!.length, 43)
  })

  it('rejects wrong secrets, but accepts any configured secret (rotation)', () => {
    assert.deepEqual(verifyToken(make({}, OTHER_SECRET), [SECRET], NOW), { ok: false, code: 'bad_token' })
    assert.ok(verifyToken(make({}, OTHER_SECRET), [SECRET, OTHER_SECRET], NOW).ok)
  })

  it('rejects expired tokens with a 5 s skew allowance', () => {
    assert.ok(verifyToken(make(), [SECRET], NOW + 124).ok)
    assert.deepEqual(verifyToken(make(), [SECRET], NOW + 125), { ok: false, code: 'expired' })
  })

  it('rejects bad times, roles and shapes', () => {
    const bad = (t: string, now = NOW) => assert.deepEqual(verifyToken(t, [SECRET], now), { ok: false, code: 'bad_token' })
    bad(make({ exp: NOW + 131 }))
    bad(make({ exp: NOW }))
    bad(make({ iat: NOW + 10, exp: NOW + 100 }))
    bad(make({ role: 'host' })) // host ⇒ u === h
    bad(make({ u: HOST })) // guest ⇒ u !== h
    bad(make({ m: 11 }))
    bad(make({ m: 1 }))
    bad(make({ r: 'x0123456789abcdef0123' }))
    bad(make({ n: 'short' }))
    bad(rawToken(JSON.stringify({ ...base, extra: 1 })))
    bad(rawToken(JSON.stringify({ ...base, v: 2 })))
    bad(rawToken('not json'))
    bad(rawToken('[]'))
    bad('garbage')
    bad('trsr1..')
    bad(`trsr1.${'a'.repeat(600)}.x`)
    // Nicht-kanonische Signatur (letztes Zeichen mit gesetzten Füllbits).
    const ALPHA = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_'
    const t = make()
    const i = ALPHA.indexOf(t.at(-1)!)
    const nonCanonical = t.slice(0, -1) + ALPHA[i | 1]
    assert.deepEqual(Buffer.from(nonCanonical.split('.')[2]!, 'base64url'), Buffer.from(t.split('.')[2]!, 'base64url'))
    bad(nonCanonical)
  })
})

describe('config', () => {
  it('requires strong secrets and reads ports with SERVER_PORT fallback', () => {
    assert.throws(() => loadConfig({}), ConfigError)
    assert.throws(() => loadConfig({ RELAY_SECRET: 'short' }), ConfigError)
    const c = loadConfig({ RELAY_SECRET: `${SECRET},${OTHER_SECRET}`, SERVER_PORT: '25503' })
    assert.equal(c.secrets.length, 2)
    assert.equal(c.tcpPort, 25503)
    assert.equal(c.udpPort, 25504)
    assert.equal(c.bindHost, '0.0.0.0')
    assert.equal(loadConfig({ RELAY_SECRET: SECRET, SERVER_PORT: '30000', TCP_PORT: '30001' }).tcpPort, 30001)
    assert.throws(() => loadConfig({ RELAY_SECRET: SECRET, UDP_PORT: 'abc' }), ConfigError)
    assert.throws(() => loadConfig({ RELAY_SECRET: SECRET, MAX_ROOM_PLAYERS: '11' }), ConfigError)
  })
})

describe('token bucket', () => {
  it('refills evenly and reports debt as wait time', () => {
    const b = new TokenBucket(10, 0.01, 0) // 10 / s
    for (let i = 0; i < 10; i++) assert.ok(b.take(0))
    assert.equal(b.take(0), false)
    assert.ok(b.take(100))
    assert.equal(b.consume(100, 20), 2000)
  })
})
