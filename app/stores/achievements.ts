import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
// Relativ importiert, damit Tests den Store ohne Nuxt laden können (`useTrsStore`, `useToasts`, `navigateTo` bleiben Auto-Imports).
import {
  achievementRewardAction,
  achievementRewardName,
  achievementToastText,
  applyAchievementUnlock,
  summarizeAchievements,
  type AchievementReward,
  type AchievementRewardAction,
  type AchievementUnlockedEvent,
  type MyAchievements,
  type PlayerAchievements,
} from '../utils/achievements'
import { backend, errorMessage } from '../utils/backend'
import { t } from '../utils/i18n'
import type { SocialToastAction } from '../utils/socialToasts'
import { useSocialToasts } from './socialToasts'

/** So lange gelten geladene Erfolge eines Freundes als frisch. */
const FRIEND_TTL_MS = 2 * 60_000

type Owned = { capes: string[]; hats: string[] }

// Launcher-Erfolge (API §31): eigene Liste (Seite /achievements), Erfolge von Freunden
// (Karte in „Sozial“, Freundes-Ansicht) und das Ereignis `achievement_unlocked`
// als Sozial-Benachrichtigung – bei einer Umhang-/Kopf-Belohnung mit „Jetzt tragen“.
export const useAchievementsStore = defineStore('achievements', () => {
  const data = ref<MyAchievements | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)
  /** Für welchen Account `data` gilt. */
  let loadedFor: string | null = null
  const friends = ref<Record<string, { data: PlayerAchievements; at: number }>>({})
  const friendErrors = ref<Record<string, string>>({})
  /** Zuletzt freigeschaltet (zum Hervorheben auf der Seite). */
  const highlight = ref<string | null>(null)
  /** Namen von Umhängen/Kosmetik (die API schickt bei Belohnungen nur die ID). */
  const rewardNames = ref<Record<string, string>>({})
  /** Was ich besitze – nur dann gibt es „Jetzt tragen“. */
  const owned = ref<Owned>({ capes: [], hats: [] })

  const summary = computed(() => (data.value ? summarizeAchievements(data.value) : null))
  const savingVisible = ref(false)

  function reset() {
    data.value = null
    loadedFor = null
    friends.value = {}
    friendErrors.value = {}
    owned.value = { capes: [], hats: [] }
  }

  async function load(force = false) {
    const trs = useTrsStore()
    const account = trs.status?.account ?? null
    if (!trs.enabled || !account) {
      reset()
      return
    }
    if (account !== loadedFor) reset()
    if (!force && data.value) return
    loading.value = true
    error.value = null
    try {
      data.value = await backend.achievements.mine()
      loadedFor = account
    } catch (e) {
      error.value = errorMessage(e)
    } finally {
      loading.value = false
    }
  }

  /** Erfolge eines Freundes (kurz zwischengespeichert). `null` = nicht erlaubt/nicht erreichbar. */
  async function loadFriend(uuid: string, force = false): Promise<PlayerAchievements | null> {
    const cached = friends.value[uuid]
    if (!force && cached && Date.now() - cached.at < FRIEND_TTL_MS) return cached.data
    try {
      const result = await backend.achievements.player(uuid)
      friends.value = { ...friends.value, [uuid]: { data: result, at: Date.now() } }
      const { [uuid]: _, ...rest } = friendErrors.value
      friendErrors.value = rest
      return result
    } catch (e) {
      friendErrors.value = { ...friendErrors.value, [uuid]: errorMessage(e) }
      return null
    }
  }

  /** Umhänge (Katalog mit Namen, eigene) und eigene Kopf-Kosmetik holen – Fehler lassen die Listen leer. */
  async function loadRewards(kinds: { capes: boolean; hats: boolean } = { capes: true, hats: true }): Promise<Owned> {
    const [capes, hats] = await Promise.allSettled([
      kinds.capes ? backend.trs.capes() : Promise.resolve(null),
      kinds.hats ? backend.trs.hats() : Promise.resolve(null),
    ])
    const names = { ...rewardNames.value }
    const next: Owned = { ...owned.value }
    if (capes.status === 'fulfilled' && capes.value) {
      for (const c of capes.value) names[c.id] = c.name
      next.capes = capes.value.filter((c) => c.owned).map((c) => c.id)
    }
    if (hats.status === 'fulfilled' && hats.value) {
      for (const h of hats.value) names[h.id] = h.name
      next.hats = hats.value.map((h) => h.id)
    }
    rewardNames.value = names
    owned.value = next
    return next
  }

  /** Was „Jetzt tragen“ bei dieser Belohnung kann (fragt die eigenen Listen neu ab). */
  async function resolveReward(reward: AchievementReward | null): Promise<AchievementRewardAction> {
    if (!reward) return null
    const now = await loadRewards({ capes: reward.kind === 'cape', hats: reward.kind === 'cosmetic' })
    return achievementRewardAction(reward, now)
  }

  const rewardName = (reward: AchievementReward) => achievementRewardName(reward, rewardNames.value)

  /** Belohnung anlegen (Umhang/Kopf) bzw. beim Emote zeigen, wo es liegt. */
  async function wear(reward: AchievementReward, action: AchievementRewardAction) {
    const toasts = useToasts()
    const name = rewardName(reward)
    try {
      if (action === 'cape') {
        await backend.trs.setCape(reward.id)
        toasts.ok(t('capes.toasts.worn', { name }))
        useTrsStore().capesRevision++
      } else if (action === 'hat') {
        await backend.trs.setHat(reward.id)
        toasts.ok(t('capes.toasts.hatOn', { name }))
        useTrsStore().capesRevision++
      } else if (action === 'emote') {
        toasts.ok(t('achievements.reward.emoteHint', { name }))
      }
    } catch (e) {
      toasts.error(e)
    }
  }

  function open(id: string | null = null) {
    highlight.value = id
    void navigateTo({ path: '/achievements', query: id ? { highlight: id } : {} })
  }

  /** „Erfolge für Freunde sichtbar“ umschalten (sofort sichtbar, bei Fehler zurück). */
  async function setVisible(visible: boolean) {
    const current = data.value
    if (!current || savingVisible.value) return
    const before = current.visibleToFriends
    current.visibleToFriends = visible
    savingVisible.value = true
    try {
      current.visibleToFriends = await backend.achievements.setVisible(visible)
    } catch (e) {
      current.visibleToFriends = before
      useToasts().error(e)
    } finally {
      savingVisible.value = false
    }
  }

  /** Absturz-Helfer hat etwas behoben (Meldung für die Erfolge, still im Hintergrund). */
  function reportCrashFixed() {
    void backend.achievements.report('crash_fixed').catch(() => {})
  }

  /** `achievement_unlocked`: Liste nachziehen und Benachrichtigung (mit „Jetzt tragen“, wenn es geht). */
  async function onLiveEvent(e: AchievementUnlockedEvent) {
    if (data.value && !applyAchievementUnlock(data.value, e)) return
    highlight.value = e.achievement.id
    const reward = e.reward
    const action = await resolveReward(reward)
    const { title, body } = achievementToastText(e, rewardNames.value)
    const actions: SocialToastAction[] = []
    if (reward && action) {
      actions.push({
        label: action === 'emote' ? t('achievements.reward.whereEmote') : t('achievements.reward.wearNow'),
        primary: true,
        run: () => void wear(reward, action),
      })
    }
    actions.push({ label: t('achievements.toast.view'), primary: !actions.length, run: () => open(e.achievement.id) })
    await useSocialToasts().notify('achievement', {
      key: `achievement:${e.achievement.id}`,
      title,
      body,
      face: null,
      open: () => open(e.achievement.id),
      actions,
    })
  }

  return {
    data,
    loading,
    error,
    friends,
    friendErrors,
    highlight,
    rewardNames,
    owned,
    summary,
    savingVisible,
    setVisible,
    load,
    loadFriend,
    loadRewards,
    resolveReward,
    rewardName,
    wear,
    open,
    reset,
    reportCrashFixed,
    onLiveEvent,
  }
})
