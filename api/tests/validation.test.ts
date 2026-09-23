import { describe, expect, it } from 'vitest'
import { ConfigError, loadConfig } from '../server/lib/config'
import { isApiError } from '../server/lib/errors'
import { parseWith } from '../server/lib/http'
import { newRedeemCode, normalizeRedeemCode, normalizeUuid, safeEqual } from '../server/lib/ids'
import {
  createCodesBody,
  lookupBody,
  presenceBody,
  redeemBody,
  settingsPatch,
  targetBody,
  uploadQuery,
  verifyBody,
} from '../server/lib/schemas'

const ok = <T>(fn: () => T) => fn()
const rejects = (fn: () => unknown) => {
  try {
    fn()
  } catch (e) {
    expect(isApiError(e)).toBe(true)
    return (e as { code: string, details?: { fields?: { path: string }[] } })
  }
  throw new Error('expected validation error')
}

describe('ids', () => {
  it('normalizes dashed and plain UUIDs', () => {
    expect(normalizeUuid('75C1A6F3-1122-40AB-BDB5-7B9D21C64232')).toBe('75c1a6f3112240abbdb57b9d21c64232')
    expect(normalizeUuid('75c1a6f3112240abbdb57b9d21c64232')).toBe('75c1a6f3112240abbdb57b9d21c64232')
    expect(normalizeUuid('75c1a6f3-112240abbdb57b9d21c64232')).toBeNull()
    expect(normalizeUuid('zzzzzzzz112240abbdb57b9d21c64232')).toBeNull()
  })

  it('generates redeem codes that survive human typing', () => {
    const code = newRedeemCode()
    expect(code).toMatch(/^[0-9A-Z]{5}(-[0-9A-Z]{5}){3}$/)
    const n = normalizeRedeemCode(code)!
    expect(normalizeRedeemCode(` ${code.toLowerCase().replaceAll('-', ' ')} `)).toBe(n)
    expect(normalizeRedeemCode('OOOOO-IIIII-LLLLL-00000')).toBe('00000111111111100000')
    expect(normalizeRedeemCode('short')).toBeNull()
    expect(normalizeRedeemCode('UUUUU-UUUUU-UUUUU-UUUUU')).toBeNull() // U gibt es in Crockford nicht
  })

  it('compares secrets in constant time and exactly', () => {
    expect(safeEqual('abc', 'abc')).toBe(true)
    expect(safeEqual('abc', 'abd')).toBe(false)
    expect(safeEqual('abc', 'abcd')).toBe(false)
  })
})

describe('request schemas', () => {
  it('verify: strict names and serverIds, unknown keys rejected', () => {
    const v = ok(() => parseWith(verifyBody, { username: 'Theredstonee', serverId: 'a'.repeat(40) }))
    expect(v.username).toBe('Theredstonee')
    rejects(() => parseWith(verifyBody, { username: 'bad name', serverId: 'a'.repeat(40) }))
    rejects(() => parseWith(verifyBody, { username: 'x'.repeat(17), serverId: 'a'.repeat(40) }))
    rejects(() => parseWith(verifyBody, { username: 'Steve', serverId: 'A'.repeat(40) }))
    const e = rejects(() => parseWith(verifyBody, { username: 'Steve', serverId: 'a'.repeat(40), admin: true }))
    expect(e.code).toBe('invalid_request')
  })

  it('settings patch: only known keys with the right types, at least one', () => {
    expect(parseWith(settingsPatch, { showBadge: false })).toEqual({ showBadge: false })
    rejects(() => parseWith(settingsPatch, {}))
    rejects(() => parseWith(settingsPatch, { showBadge: 'no' }))
    rejects(() => parseWith(settingsPatch, { presenceVisibility: 'everyone' }))
    rejects(() => parseWith(settingsPatch, { isAdmin: true }))
  })

  it('lookup: 1–100 UUIDs, normalized', () => {
    const r = parseWith(lookupBody, { uuids: ['75c1a6f3-1122-40ab-bdb5-7b9d21c64232'] })
    expect(r.uuids).toEqual(['75c1a6f3112240abbdb57b9d21c64232'])
    rejects(() => parseWith(lookupBody, { uuids: [] }))
    rejects(() => parseWith(lookupBody, { uuids: Array.from({ length: 101 }, () => 'a'.repeat(32)) }))
    rejects(() => parseWith(lookupBody, { uuids: ['not-a-uuid'] }))
  })

  it('presence: server must be a plain host[:port]', () => {
    expect(parseWith(presenceBody, { state: 'in-game', game: { version: '1.21.1', loader: 'fabric', server: 'mc.hypixel.net:25565' } }).game?.server).toBe('mc.hypixel.net:25565')
    rejects(() => parseWith(presenceBody, { state: 'in-game', game: { version: '1.21.1', loader: 'fabric', server: 'http://evil/x' } }))
    rejects(() => parseWith(presenceBody, { state: 'in-game', game: { version: '1.21.1', loader: 'fabric', server: 'a b' } }))
    rejects(() => parseWith(presenceBody, { state: 'away' }))
    rejects(() => parseWith(presenceBody, { state: 'online', game: { version: '<script>', loader: 'fabric' } }))
    rejects(() => parseWith(presenceBody, { state: 'online', game: { version: '1.8.9', loader: 'lunar' } }))
  })

  it('friend target: name or UUID', () => {
    expect(parseWith(targetBody, { target: 'Theredstonee' }).target).toEqual({ name: 'Theredstonee' })
    expect(parseWith(targetBody, { target: '75c1a6f3-1122-40ab-bdb5-7b9d21c64232' }).target).toEqual({ uuid: '75c1a6f3112240abbdb57b9d21c64232' })
    rejects(() => parseWith(targetBody, { target: "Robert'); DROP TABLE users;--" }))
  })

  it('redeem code format is checked before hitting the database', () => {
    expect(parseWith(redeemBody, { code: 'abcde-fghjk-mnpqr-stvwx' }).code).toBe('ABCDEFGHJKMNPQRSTVWX')
    rejects(() => parseWith(redeemBody, { code: 'x'.repeat(65) }))
    rejects(() => parseWith(redeemBody, { code: 123 }))
  })

  it('upload name: letters, digits and a few symbols only', () => {
    expect(parseWith(uploadQuery, { name: 'Mein Umhang #1'.replace('#', '') }).name).toBe('Mein Umhang 1')
    rejects(() => parseWith(uploadQuery, { name: '<img src=x onerror=alert(1)>' }))
    rejects(() => parseWith(uploadQuery, { name: 'a'.repeat(33) }))
    rejects(() => parseWith(uploadQuery, { name: ['a', 'b'] }))
  })

  it('admin code creation: bounded counts, ISO dates', () => {
    const r = parseWith(createCodesBody, { capeId: 'team' })
    expect(r).toMatchObject({ capeId: 'team', maxUses: 1, count: 1 })
    rejects(() => parseWith(createCodesBody, { capeId: 'team', count: 101 }))
    rejects(() => parseWith(createCodesBody, { capeId: 'team', maxUses: 0 }))
    rejects(() => parseWith(createCodesBody, { capeId: 'team', expiresAt: 'tomorrow' }))
    rejects(() => parseWith(createCodesBody, { capeId: 'Team!' }))
  })
})

describe('config', () => {
  const base = { SECRET_KEY: 'k'.repeat(32) }
  it('requires a long SECRET_KEY and never echoes values', () => {
    expect(() => loadConfig({})).toThrow(ConfigError)
    try {
      loadConfig({ SECRET_KEY: 'short-secret' })
    } catch (e) {
      expect((e as Error).message).toContain('SECRET_KEY')
      expect((e as Error).message).not.toContain('short-secret')
    }
  })

  it('parses admin UUIDs and rejects garbage', () => {
    const c = loadConfig({ ...base, ADMIN_UUIDS: '75c1a6f3-1122-40ab-bdb5-7b9d21c64232, ' })
    expect([...c.adminUuids]).toEqual(['75c1a6f3112240abbdb57b9d21c64232'])
    expect(() => loadConfig({ ...base, ADMIN_UUIDS: 'Theredstonee' })).toThrow(ConfigError)
  })

  it('CORS: exact https origins only, no wildcard', () => {
    expect([...loadConfig({ ...base, CORS_ORIGINS: 'https://theredstonee.de' }).corsOrigins]).toEqual(['https://theredstonee.de'])
    expect(() => loadConfig({ ...base, CORS_ORIGINS: '*' })).toThrow(ConfigError)
    expect(() => loadConfig({ ...base, CORS_ORIGINS: 'http://example.com' })).toThrow(ConfigError)
    expect(() => loadConfig({ ...base, CORS_ORIGINS: 'https://example.com/path' })).toThrow(ConfigError)
    expect(loadConfig(base).corsOrigins.size).toBe(0)
  })

  it('admin API key must be long if set; Mojang URL must be https unless explicitly allowed', () => {
    expect(() => loadConfig({ ...base, ADMIN_API_KEY: 'short' })).toThrow(ConfigError)
    expect(loadConfig({ ...base, ADMIN_API_KEY: '' }).adminApiKey).toBeNull()
    expect(() => loadConfig({ ...base, MOJANG_SESSIONSERVER_URL: 'http://127.0.0.1:9000' })).toThrow(ConfigError)
    expect(loadConfig({ ...base, MOJANG_SESSIONSERVER_URL: 'http://127.0.0.1:9000', ALLOW_INSECURE_MOJANG_URL: 'true' }).mojangSessionUrl).toBe('http://127.0.0.1:9000')
  })
})
