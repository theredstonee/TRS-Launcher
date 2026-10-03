import { describe, expect, it } from 'vitest'
import { categoryOn, pushDeviceSchema, pushStatusSchema, pushTargetAction, withCategory, type PushSettings } from '../app/utils/push'

const settings: PushSettings = { enabled: true, categories: {}, preview: false, pushWhilePlaying: false, pollFallback: false }
const defaults = { chat: true, friends: true, friend_online: false, achievements: true }

describe('Push-Ziele (§33.5)', () => {
  it('öffnet die passende Seite', () => {
    expect(pushTargetAction('/chat/c0123456789abcdef0123')).toEqual({ kind: 'route', path: '/social', query: { c: 'c0123456789abcdef0123' } })
    expect(pushTargetAction('/friends/requests')).toEqual({ kind: 'route', path: '/social', query: { tab: 'friends' } })
    expect(pushTargetAction('/friends')).toEqual({ kind: 'route', path: '/social', query: { tab: 'friends' } })
    expect(pushTargetAction('/worlds/r1/requests')).toEqual({ kind: 'route', path: '/social', query: { tab: 'worlds' } })
    expect(pushTargetAction('/achievements/first_launch')).toEqual({ kind: 'route', path: '/achievements', query: { highlight: 'first_launch' } })
    expect(pushTargetAction('/capes/offers')).toEqual({ kind: 'route', path: '/skins' })
    expect(pushTargetAction('/packs/p1')).toEqual({ kind: 'route', path: '/instances' })
  })

  it('öffnet Dialoge und die Website', () => {
    expect(pushTargetAction('/moderation/sanctions/42')).toEqual({ kind: 'sanction', id: 42 })
    expect(pushTargetAction('/moderation/sanctions/x')).toEqual({ kind: 'sanction', id: null })
    expect(pushTargetAction('/team/applications/a1')).toEqual({ kind: 'application', id: 'a1' })
    expect(pushTargetAction('/issues/17')).toEqual({ kind: 'external', url: 'https://trs-launcher.theredstonee.de/issues/17' })
    expect(pushTargetAction('/circuits/submissions/s1')).toEqual({ kind: 'external', url: 'https://trs-launcher.theredstonee.de/circuits' })
  })

  it('führt bei Unsinn zur Startseite', () => {
    for (const bad of [null, 42, '', 'chat/c1', '/Chat', '/chat/../../x', '/a/b/c/d/e', 'javascript:alert(1)', '/unbekannt', `/chat/${'x'.repeat(400)}`]) {
      expect(pushTargetAction(bad)).toEqual({ kind: 'route', path: '/' })
    }
  })
})

describe('Push-Schalter', () => {
  it('nimmt eigene Wahl vor dem Standard des Servers', () => {
    expect(categoryOn(settings, defaults, 'chat')).toBe(true)
    expect(categoryOn(settings, defaults, 'friend_online')).toBe(false)
    expect(categoryOn(settings, {}, 'friend_online')).toBe(false)
    expect(categoryOn({ ...settings, categories: { chat: false } }, defaults, 'chat')).toBe(false)
  })

  it('speichert nur Abweichungen vom Standard', () => {
    const off = withCategory(settings, defaults, 'chat', false)
    expect(off.categories).toEqual({ chat: false })
    expect(withCategory(off, defaults, 'chat', true).categories).toEqual({})
    expect(withCategory(settings, defaults, 'friend_online', true).categories).toEqual({ friend_online: true })
  })

  it('prüft Antworten des Kerns', () => {
    const status = {
      supported: true,
      settings,
      state: 'registered',
      distributors: [{ id: 'io.heckel.ntfy', name: 'ntfy' }],
      distributor: 'io.heckel.ntfy',
      permission: 'granted',
      deviceId: 'd0123456789abcdef0123',
      error: null,
      categories: ['chat'],
      defaults: { chat: true },
    }
    expect(pushStatusSchema.parse(status).state).toBe('registered')
    expect(() => pushStatusSchema.parse({ ...status, state: 'kaputt' })).toThrow()
    expect(() => pushStatusSchema.parse({ ...status, deviceId: '../x' })).toThrow()
    expect(() =>
      pushDeviceSchema.parse({ id: 'd0123456789abcdef0123', platform: 'web', kind: 'poll', endpointHost: null, deviceName: 'x', appVersion: '1',
        categories: {}, preview: false, pushWhilePlaying: false, current: true, thisDevice: true, createdAt: null, lastSeenAt: null, lastSuccessAt: null, failing: false }),
    ).toThrow()
  })
})
