import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import {
  cornerClasses,
  decide,
  defaultSocialPrefs,
  pushSocialToast,
  toastDuration,
  type Situation,
  type SocialToast,
} from '../app/utils/socialToasts'
import { actionBody, adminReportEnvelopeSchema } from '../app/utils/moderation'

const here: Situation = { focused: true, visible: true, fullscreen: false, looking: false, muted: false }

describe('Wann erscheint eine Benachrichtigung?', () => {
  it('normal: Hinweis im Launcher, Ton, keine Windows-Benachrichtigung', () => {
    expect(decide('message', defaultSocialPrefs, here)).toEqual({ toast: true, native: false, sound: true })
  })

  it('Fenster im Hintergrund: zusätzlich Windows-Benachrichtigung (abschaltbar)', () => {
    expect(decide('message', defaultSocialPrefs, { ...here, focused: false })).toEqual({ toast: true, native: true, sound: true })
    expect(decide('message', { ...defaultSocialPrefs, native: false }, { ...here, visible: false }).native).toBe(false)
  })

  it('still bei offener Unterhaltung, Stummschaltung, Nicht stören und Vollbild', () => {
    expect(decide('message', defaultSocialPrefs, { ...here, looking: true }).toast).toBe(false)
    // Schaut man hin, aber das Fenster ist im Hintergrund → trotzdem melden.
    expect(decide('message', defaultSocialPrefs, { ...here, looking: true, focused: false }).toast).toBe(true)
    expect(decide('message', defaultSocialPrefs, { ...here, muted: true }).toast).toBe(false)
    expect(decide('friendRequest', { ...defaultSocialPrefs, doNotDisturb: true }, here).toast).toBe(false)
    expect(decide('invite', defaultSocialPrefs, { ...here, fullscreen: true }).toast).toBe(false)
    expect(decide('invite', { ...defaultSocialPrefs, quietInFullscreen: false }, { ...here, fullscreen: true }).toast).toBe(true)
  })

  it('Arten einzeln abschaltbar, Rückmeldungen über dich kommen immer (ohne Ton bei Nicht stören)', () => {
    expect(decide('online', { ...defaultSocialPrefs, friendOnline: false }, here).toast).toBe(false)
    expect(decide('capeOffer', { ...defaultSocialPrefs, toasts: false }, here).toast).toBe(false)
    expect(decide('moderation', { ...defaultSocialPrefs, toasts: false, doNotDisturb: true }, here)).toEqual({ toast: true, native: false, sound: false })
    expect(decide('report', { ...defaultSocialPrefs, sound: false }, here).sound).toBe(false)
  })
})

describe('Liste der Benachrichtigungen', () => {
  const toast = (id: number, key: string): Omit<SocialToast, 'count'> => ({ id, key, kind: 'message', title: 't', body: 'b', face: null, actions: [] })

  it('fasst dieselbe Unterhaltung zusammen und begrenzt die Anzahl', () => {
    let list: SocialToast[] = []
    list = pushSocialToast(list, toast(1, 'msg:a')).list
    const again = pushSocialToast(list, toast(2, 'msg:a'))
    expect(again.replaced).toBe(1)
    expect(again.list).toEqual([expect.objectContaining({ id: 2, count: 2 })])
    list = again.list
    const dropped: number[] = []
    for (let i = 3; i <= 7; i++) {
      const r = pushSocialToast(list, toast(i, `k${i}`))
      list = r.list
      dropped.push(...r.dropped)
    }
    expect(list).toHaveLength(4)
    expect(dropped).toEqual([2, 3])
  })

  it('Dauer 3–10 s, mit Aktionen etwas länger; Ecken', () => {
    expect(toastDuration({ durationSecs: 5 }, false)).toBe(5000)
    expect(toastDuration({ durationSecs: 1 }, false)).toBe(3000)
    expect(toastDuration({ durationSecs: 60 }, true)).toBe(13_000)
    expect(cornerClasses('bottom-left')).toContain('bottom-4')
    expect(cornerClasses('top-right')).toContain('right-4')
  })
})

const social = {
  quietHours: vi.fn(async () => false),
  notifyNative: vi.fn(async () => undefined),
}
vi.mock('../app/utils/backend', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../app/utils/backend')>()),
  backend: { social },
}))
vi.mock('../app/utils/sound', () => ({ playNotificationSound: vi.fn() }))
const { useSocialToasts } = await import('../app/stores/socialToasts')
const { playNotificationSound } = await import('../app/utils/sound')

describe('Benachrichtigungs-Store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.useFakeTimers()
    vi.clearAllMocks()
  })
  afterEach(() => vi.useRealTimers())

  it('zeigt, spielt den Ton und verschwindet nach der eingestellten Dauer', async () => {
    const store = useSocialToasts()
    store.setPrefs({ durationSecs: 4 })
    expect(await store.notify('online', { key: 'on:x', title: 'Bob', body: 'ist online', face: null, actions: [] })).toBe(true)
    expect(store.items).toHaveLength(1)
    expect(playNotificationSound).toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(3900)
    expect(store.items).toHaveLength(1)
    await vi.advanceTimersByTimeAsync(200)
    expect(store.items).toHaveLength(0)
  })

  it('bleibt stehen, solange die Maus darüber ist', async () => {
    const store = useSocialToasts()
    await store.notify('online', { key: 'on:y', title: 'Bob', body: '', face: null, actions: [] })
    const id = store.items[0]!.id
    store.hold(id, true)
    await vi.advanceTimersByTimeAsync(20_000)
    expect(store.items).toHaveLength(1)
    store.hold(id, false)
    await vi.advanceTimersByTimeAsync(6000)
    expect(store.items).toHaveLength(0)
  })

  it('Vollbild (Windows) hält Benachrichtigungen zurück', async () => {
    social.quietHours.mockResolvedValueOnce(true)
    const store = useSocialToasts()
    expect(await store.notify('message', { key: 'msg:z', title: 'Bob', body: 'hi', face: null, actions: [] })).toBe(false)
    expect(store.items).toHaveLength(0)
  })
})

describe('Moderation (Admin)', () => {
  it('schickt nur passende Felder zur Entscheidung', () => {
    const form = { reason: ' Beleidigung ', minutes: 60, keepOpen: true, includeRelated: true }
    expect(actionBody('mute', form)).toEqual({ action: 'mute', reason: 'Beleidigung', minutes: 60, keepOpen: true, includeRelated: true })
    expect(actionBody('dismiss', form)).toEqual({ action: 'dismiss', includeRelated: true })
    expect(actionBody('mute', { ...form, minutes: null, keepOpen: false, includeRelated: false })).toEqual({ action: 'mute', reason: 'Beleidigung' })
  })

  it('verträgt fehlende Felder in der Meldung', () => {
    const parsed = adminReportEnvelopeSchema.safeParse({
      report: { id: 'r0123456789abcdef', kind: 'message', reason: 'spam', status: 'open', createdAt: '2026-09-26T10:00:00Z' },
    })
    expect(parsed.success).toBe(true)
    expect(parsed.data?.report.evidence).toBeNull()
    expect(parsed.data?.report.notes).toEqual([])
  })
})
