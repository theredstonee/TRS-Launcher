import { describe, expect, it } from 'vitest'
import { isWebLoginToken, normalizeWebLoginCode, secondsLeft, webLoginRequestSchema } from '../app/utils/webLogin'

describe('Anmeldung auf der Website (API §29)', () => {
  it('Codes wie Kern und Server: 6 Zeichen ohne 0/O, 1/I/L, U', () => {
    expect(normalizeWebLoginCode('k7q-2mx')).toBe('K7Q-2MX')
    expect(normalizeWebLoginCode(' K7Q 2MX ')).toBe('K7Q-2MX')
    for (const bad of ['K0Q2MX', 'K1Q2MX', 'KIQ2MX', 'KLQ2MX', 'KOQ2MX', 'KUQ2MX', 'K7Q2M', 'K7Q2MXX', '', 'x'.repeat(30)]) {
      expect(normalizeWebLoginCode(bad), bad).toBeNull()
    }
  })

  it('Link-Token nur als 43 Zeichen base64url', () => {
    expect(isWebLoginToken('AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde')).toBe(true)
    expect(isWebLoginToken('AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcd')).toBe(false)
    expect(isWebLoginToken('AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abc/e')).toBe(false)
    expect(isWebLoginToken(null)).toBe(false)
  })

  it('Restzeit und Schema der Anfrage', () => {
    expect(secondsLeft('2026-09-28T10:02:00Z', Date.parse('2026-09-28T10:00:00.500Z'))).toBe(120)
    expect(secondsLeft('2026-09-28T09:00:00Z', Date.parse('2026-09-28T10:00:00Z'))).toBe(0)
    expect(secondsLeft(null)).toBe(0)
    const ok = { id: 'U_2cX3IFwaxWR5RiGnDP8A', code: 'MNK-2MQ', site: 'trs-launcher.theredstonee.de', browser: 'Firefox · Windows', createdAt: null, expiresAt: null }
    expect(webLoginRequestSchema.safeParse(ok).success).toBe(true)
    expect(webLoginRequestSchema.safeParse({ ...ok, site: 'evil.example/<x>' }).success).toBe(false)
    expect(webLoginRequestSchema.safeParse({ ...ok, code: 'MNK-2M0' }).success).toBe(false)
  })
})
