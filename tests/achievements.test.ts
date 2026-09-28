import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import {
  achievementIcon,
  achievementItems,
  achievementProgressText,
  achievementRewardAction,
  achievementRewardLabel,
  achievementRewardName,
  achievementTitle,
  achievementToastText,
  applyAchievementUnlock,
  friendAchievements,
  groupAchievements,
  myAchievementsSchema,
  playerAchievementsSchema,
  summarizeAchievements,
  type AchievementUnlockedEvent,
} from '../app/utils/achievements'
import { liveEventSchema } from '../app/utils/chat'
import { setLocale } from '../app/utils/i18n'
import { socialSettingsSchema } from '../app/utils/schemas'
import { decide, defaultSocialPrefs, type Situation } from '../app/utils/socialToasts'

const ME = '75c1a6f3112240abbdb57b9d21c64232'
const FRIEND = 'b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0'

const text = (en: string, de = en) => ({ en, de, es: en })
const entry = (over: Record<string, unknown>) => ({
  secret: false, hidden: false, description: null, icon: 'trophy', points: 5, rarity: 'common', goal: null, unit: null,
  verified: true, reward: null, order: 0, ...over,
})

/** So kommt es aus dem Kern (`trs_achievements`, API §31.3, schon gesäubert). */
const core = () => ({
  achievements: [
    entry({ id: 'play_100h', category: 'playtime', title: text('Veteran'), description: text('Play for 100 hours'), icon: 'clock',
      points: 60, rarity: 'epic', goal: 6000, unit: 'minutes', reward: { kind: 'cape', id: 'veteran', name: null }, order: 7 }),
    entry({ id: 'first_launch', category: 'playtime', title: text('Liftoff', 'Abheben'), description: text('Start a game'), icon: 'rocket',
      verified: false, order: 1 }),
    entry({ id: 'friends_10', category: 'community', title: text('Party'), icon: 'users', points: 30, rarity: 'rare', goal: 10,
      unit: 'count', reward: { kind: 'cosmetic', id: 'emote-party', name: null }, order: 25 }),
    entry({ id: 'secret_01', category: 'secret', secret: true, hidden: true, title: null, icon: 'secret', points: 25, order: 30 }),
    entry({ id: 'all_secrets', category: 'secret', title: text('Keeper of secrets'), icon: 'crown', points: 50, rarity: 'legendary',
      goal: 5, unit: 'count', reward: { kind: 'cosmetic', id: 'secret-crown', name: null }, order: 40 }),
    { id: 'broken', category: 'nope' },
  ],
  unlocked: [{ id: 'first_launch', at: '2026-09-28T10:00:00.000Z' }],
  progress: { play_100h: 1500, friends_10: 3, all_secrets: 0 },
  points: 5,
  totalPoints: 940,
})

const unlockEvent = (over: Record<string, unknown> = {}): AchievementUnlockedEvent =>
  liveEventSchema.parse({
    type: 'achievement_unlocked',
    at: '2026-09-28T21:00:00.000Z',
    achievement: entry({ id: 'secret_01', category: 'secret', secret: true, title: text('Night owl', 'Nachteule'),
      description: text('Play at 3 am'), icon: 'moon', points: 25, rarity: 'epic', order: 30 }),
    reward: null,
    ...over,
  }) as AchievementUnlockedEvent

describe('achievements: data and view', () => {
  beforeAll(() => setLocale('en'))

  it('parses the core answer and drops broken entries', () => {
    const data = myAchievementsSchema.parse(core())
    expect(data.achievements.map((a) => a.id)).toEqual(['play_100h', 'first_launch', 'friends_10', 'secret_01', 'all_secrets'])
    expect(data.totalPoints).toBe(940)
    expect(myAchievementsSchema.safeParse({ achievements: 'x' }).success).toBe(false)
    const friend = playerAchievementsSchema.parse({ uuid: FRIEND, unlocked: [{ id: 'first_launch', at: null }, { id: '../x' }], points: 5 })
    expect(friend.unlocked).toHaveLength(1)
  })

  it('groups by category in order, with progress, filters and sums up', () => {
    const data = myAchievementsSchema.parse(core())
    const groups = groupAchievements(data)
    expect(groups.map((g) => g.category)).toEqual(['playtime', 'community', 'secret'])
    expect(groups[0]!.items.map((i) => i.achievement.id)).toEqual(['first_launch', 'play_100h'], 'nach order')
    const play = groups[0]!.items[1]!
    expect(play.progress).toBe(1500)
    expect(play.ratio).toBe(0.25)
    expect(groups[0]!.items[0]!.unlocked).toBe(true)
    expect(groups[0]!.items[0]!.ratio).toBe(1)
    expect(groupAchievements(data, 'unlocked').map((g) => g.category)).toEqual(['playtime'])
    expect(groupAchievements(data, 'locked').flatMap((g) => g.items).length).toBe(4)
    expect(summarizeAchievements(data)).toEqual({ unlocked: 1, total: 5, points: 5, maxPoints: 940 })
    expect(summarizeAchievements({ ...data, totalPoints: 0 }).maxPoints).toBe(170)
  })

  it('shows playtime in hours and days with their unit', async () => {
    await setLocale('en')
    expect(achievementProgressText(1500, 6000, 'minutes')).toBe('25 / 100 h')
    expect(achievementProgressText(90, 600, 'minutes')).toBe('1.5 / 10 h')
    expect(achievementProgressText(3, 7, 'days')).toBe('3 / 7 days')
    expect(achievementProgressText(1234, 5000, 'count')).toBe('1,234 / 5,000')
    await setLocale('de')
    expect(achievementProgressText(1500, 6000, 'minutes')).toBe('25 / 100 Std.')
    await setLocale('en')
  })

  it('keeps secret achievements secret', async () => {
    const data = myAchievementsSchema.parse(core())
    const secret = achievementItems(data).find((i) => i.achievement.id === 'secret_01')!
    await setLocale('de')
    expect(achievementTitle(secret.achievement)).toBe('Geheimer Erfolg')
    expect(achievementTitle(data.achievements[1])).toBe('Abheben')
    // Auch wenn ein Titel käme: versteckt bleibt versteckt.
    expect(achievementTitle({ ...secret.achievement, title: text('Spoiler') })).toBe('Geheimer Erfolg')
    await setLocale('en')
    expect(achievementTitle(secret.achievement)).toBe('Secret achievement')
    expect(achievementIcon(secret.achievement.icon)).toEqual({ icon: 'lock' })
  })

  it('shows a friend’s latest achievements with my catalog', () => {
    const data = myAchievementsSchema.parse(core())
    const friend = playerAchievementsSchema.parse({
      uuid: FRIEND,
      unlocked: [{ id: 'first_launch', at: '2026-09-01T00:00:00Z' }, { id: 'secret_01', at: '2026-09-20T00:00:00Z' }, { id: 'future_thing', at: null }],
      points: 30,
    })
    const latest = friendAchievements(friend, data, 2)
    expect(latest.map((a) => a.id)).toEqual(['secret_01', 'first_launch'])
    expect(achievementTitle(latest[0]!.achievement)).toBe('Secret achievement')
    expect(friendAchievements(friend, null)[0]!.achievement).toBeNull()
  })

  it('applies an unlock once, reveals the secret and counts it for “all secrets”', () => {
    const data = myAchievementsSchema.parse(core())
    expect(applyAchievementUnlock(data, unlockEvent())).toBe(true)
    expect(data.points).toBe(30)
    expect(data.achievements.find((a) => a.id === 'secret_01')!.title?.en).toBe('Night owl')
    expect(data.progress.all_secrets).toBe(1)
    expect(applyAchievementUnlock(data, unlockEvent())).toBe(false)
    expect(data.points).toBe(30)
    expect(data.progress.all_secrets).toBe(1)
  })

  it('offers “wear now” only for rewards that exist, names come from my lists', () => {
    const cape = { kind: 'cape', id: 'veteran', name: null } as const
    const crown = { kind: 'cosmetic', id: 'secret-crown', name: null } as const
    const emote = { kind: 'cosmetic', id: 'emote-party', name: null } as const
    expect(achievementRewardAction(cape, { capes: ['veteran'], hats: [] })).toBe('cape')
    expect(achievementRewardAction(cape, { capes: [], hats: [] })).toBeNull()
    expect(achievementRewardAction(crown, { capes: [], hats: ['secret-crown'] })).toBe('hat')
    expect(achievementRewardAction(crown, { capes: [], hats: [] })).toBeNull()
    expect(achievementRewardAction(emote, { capes: [], hats: [] })).toBe('emote')
    expect(achievementRewardAction(null, { capes: [], hats: [] })).toBeNull()
    expect(achievementRewardName(crown)).toBe('Secret Crown')
    expect(achievementRewardName(crown, { 'secret-crown': 'Geheime Krone' })).toBe('Geheime Krone')
    expect(achievementRewardLabel(cape)).toBe('Reward: cape “Veteran”')
    expect(achievementRewardLabel(emote)).toBe('Reward: emote “Party”')
  })

  it('maps the API icon keys safely', () => {
    expect(achievementIcon('clock')).toEqual({ icon: 'behavior' })
    expect(achievementIcon('thumbs-up')).toEqual({ icon: 'thumbsUp' })
    expect(achievementIcon('🎉')).toEqual({ emoji: '🎉' })
    expect(achievementIcon('<script>')).toEqual({ icon: 'trophy' })
    expect(achievementIcon('a-very-unknown-icon')).toEqual({ icon: 'trophy' })
  })

  it('writes the toast text and respects the “Achievements” switch', async () => {
    await setLocale('de')
    const crown = { reward: { kind: 'cosmetic', id: 'secret-crown' } }
    expect(achievementToastText(unlockEvent(crown), { 'secret-crown': 'Geheime Krone' })).toEqual({
      title: 'Erfolg freigeschaltet!',
      body: 'Nachteule (+25 Punkte) – Belohnung: Geheime Krone',
    })
    expect(achievementToastText(unlockEvent()).body).toBe('Nachteule (+25 Punkte)')
    await setLocale('en')
    const settings = socialSettingsSchema.parse({})
    expect(settings.achievements).toBe(true)
    const away: Situation = { focused: false, visible: true, fullscreen: false, looking: false, muted: false, clientInGame: false }
    expect(decide('achievement', defaultSocialPrefs, away).toast).toBe(true)
    expect(decide('achievement', { ...defaultSocialPrefs, achievements: false }, away).toast).toBe(false)
  })
})

// --- Store: Benachrichtigung mit „Jetzt tragen“ ---------------------------------------------

const social = { quietHours: vi.fn(async () => false), notifyNative: vi.fn(async () => undefined) }
const achievements = {
  mine: vi.fn(async () => myAchievementsSchema.parse(core())),
  player: vi.fn(async () => playerAchievementsSchema.parse({ uuid: FRIEND, unlocked: [], points: 0 })),
  report: vi.fn(async () => undefined),
  setVisible: vi.fn(async (v: boolean) => v),
}
const trsApi = {
  capes: vi.fn(async () => [{ id: 'veteran', name: 'Veteranen-Umhang', owned: true }, { id: 'ideengeber', name: 'Ideengeber', owned: false }]),
  hats: vi.fn(async (): Promise<{ id: string; name: string }[]> => []),
  setCape: vi.fn(async () => 'veteran'),
  setHat: vi.fn(async () => undefined),
}
vi.mock('../app/utils/backend', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../app/utils/backend')>()),
  backend: { social, achievements, trs: trsApi },
}))
vi.mock('../app/utils/sound', () => ({ playNotificationSound: vi.fn() }))
const { useSocialToasts } = await import('../app/stores/socialToasts')
const { useAchievementsStore } = await import('../app/stores/achievements')

describe('achievement_unlocked in the launcher', () => {
  const trsStore = { enabled: true, status: { account: ME }, capesRevision: 0 }
  const ok = vi.fn()
  const navigate = vi.fn()
  const veteran = { achievement: core().achievements[0], reward: { kind: 'cape', id: 'veteran' } }
  beforeAll(() => {
    vi.stubGlobal('useTrsStore', () => trsStore)
    vi.stubGlobal('useToasts', () => ({ ok, error: vi.fn() }))
    vi.stubGlobal('navigateTo', navigate)
    vi.stubGlobal('document', { hasFocus: () => true, visibilityState: 'visible', documentElement: {} })
  })
  beforeEach(async () => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    await setLocale('de')
  })

  it('updates the list, offers “Jetzt tragen” for an owned cape and wears it', async () => {
    const store = useAchievementsStore()
    const toasts = useSocialToasts()
    await store.load()
    expect(store.summary?.points).toBe(5)
    await store.onLiveEvent(unlockEvent(veteran))
    expect(store.summary?.points).toBe(65)
    const toast = toasts.items[0]!
    expect(toast.kind).toBe('achievement')
    expect(toast.body).toBe('Veteran (+60 Punkte) – Belohnung: Veteranen-Umhang')
    expect(toast.actions.map((a) => a.label)).toEqual(['Jetzt tragen', 'Ansehen'])
    toast.actions[0]!.run()
    await vi.waitFor(() => expect(trsApi.setCape).toHaveBeenCalledWith('veteran'))
    await vi.waitFor(() => expect(ok).toHaveBeenCalledWith(expect.stringContaining('Veteranen-Umhang')))
    expect(trsStore.capesRevision).toBe(1)
    toast.actions[1]!.run()
    expect(navigate).toHaveBeenCalledWith({ path: '/achievements', query: { highlight: 'play_100h' } })

    // Dasselbe Ereignis noch einmal: kein zweiter Hinweis, keine Punkte doppelt.
    toasts.clear()
    await store.onLiveEvent(unlockEvent(veteran))
    expect(toasts.items).toHaveLength(0)
    expect(store.summary?.points).toBe(65)
  })

  it('without the item (reward null or not owned) there is no wear button', async () => {
    const store = useAchievementsStore()
    const toasts = useSocialToasts()
    await store.onLiveEvent(unlockEvent({ reward: { kind: 'cosmetic', id: 'secret-crown' } }))
    const toast = toasts.items[0]!
    expect(toast.body).toContain('Secret Crown')
    expect(toast.actions.map((a) => a.label)).toEqual(['Ansehen'])
    expect(trsApi.hats).toHaveBeenCalled()
  })

  it('an owned head cosmetic can be put on, an emote only gets a hint', async () => {
    trsApi.hats.mockResolvedValueOnce([{ id: 'secret-crown', name: 'Geheime Krone' }])
    const store = useAchievementsStore()
    const toasts = useSocialToasts()
    await store.onLiveEvent(unlockEvent({ reward: { kind: 'cosmetic', id: 'secret-crown' } }))
    expect(toasts.items[0]!.body).toContain('Geheime Krone')
    toasts.items[0]!.actions[0]!.run()
    await vi.waitFor(() => expect(trsApi.setHat).toHaveBeenCalledWith('secret-crown'))

    toasts.clear()
    await store.onLiveEvent(unlockEvent({ achievement: core().achievements[2], reward: { kind: 'cosmetic', id: 'emote-party' } }))
    const toast = toasts.items[0]!
    expect(toast.actions[0]!.label).toBe('Wo finde ich es?')
    toast.actions[0]!.run()
    await vi.waitFor(() => expect(ok).toHaveBeenCalledWith(expect.stringContaining('Emote-Rad')))
  })

  it('stays quiet in game with the TRS Client', async () => {
    const store = useAchievementsStore()
    const toasts = useSocialToasts()
    toasts.setGameClients(['survival'])
    await store.onLiveEvent(unlockEvent())
    expect(toasts.items).toHaveLength(0)
  })

  it('caches a friend, remembers why it failed and reports crash fixes quietly', async () => {
    const store = useAchievementsStore()
    await store.loadFriend(FRIEND)
    await store.loadFriend(FRIEND)
    expect(achievements.player).toHaveBeenCalledTimes(1)
    achievements.player.mockRejectedValueOnce(new Error('player_not_found'))
    expect(await store.loadFriend(ME, true)).toBeNull()
    expect(store.friendErrors[ME]).toBeTruthy()
    store.reportCrashFixed()
    expect(achievements.report).toHaveBeenCalledWith('crash_fixed')
  })

  it('switches “visible to friends” and goes back when saving fails', async () => {
    const store = useAchievementsStore()
    await store.load()
    expect(store.data?.visibleToFriends).toBe(true)
    await store.setVisible(false)
    expect(achievements.setVisible).toHaveBeenCalledWith(false)
    expect(store.data?.visibleToFriends).toBe(false)
    achievements.setVisible.mockRejectedValueOnce(new Error('offline'))
    await store.setVisible(true)
    expect(store.data?.visibleToFriends).toBe(false)
  })

  it('a friend who keeps achievements private', async () => {
    achievements.player.mockResolvedValueOnce(playerAchievementsSchema.parse({ uuid: FRIEND, hidden: true, unlocked: [], points: 0 }))
    const store = useAchievementsStore()
    expect((await store.loadFriend(FRIEND, true))?.hidden).toBe(true)
  })
})
