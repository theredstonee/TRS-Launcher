import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
// Relativ importiert, damit Tests den Store ohne Nuxt laden können (`useTrsStore` bleibt Auto-Import).
import { backend, errorMessage } from '../utils/backend'
import type { CreatorEvent, CreatorRedeem, CreatorSelf, HelperRight, MyCreator } from '../utils/creator'
import { t, tKey } from '../utils/i18n'
import { useSocialToasts } from './socialToasts'

// Creator-Programm (API §35): Stand der Creator-Seite (unterstützter Creator,
// Fan-Umhänge, Blockierte, Einstellung, Helfer-Einladungen), das Dashboard –
// das eigene oder als Helfer das eines anderen Creators (`?as=`, §35.9) – und
// die Echtzeit-Ereignisse. Die Umhang-Werkstatt fragt hier, ob sie
// „Als Fan-Umhang einreichen“ anbieten darf.
export const useCreatorStore = defineStore('creator', () => {
  const me = ref<MyCreator | null>(null)
  const dashboard = ref<CreatorSelf | null>(null)
  /** Creator, für den man gerade als Helfer arbeitet (`null` = eigenes Dashboard). */
  const actingAs = ref<string | null>(null)
  /** Zählt Haustier-Ereignisse (`pet_*`) – der Reiter „Haustier“ lädt dann neu. */
  const petRevision = ref(0)
  const loading = ref(false)
  const error = ref<string | null>(null)
  const dashboardError = ref<string | null>(null)
  /** Umhang-Werkstatt im Fan-Umhang-Modus (aus dem Dashboard geöffnet). */
  const fanCapeWorkshop = ref(false)

  /** Angezeigte Creator-Ansicht: Dashboard, sonst der eigene Stand aus `me` (auch entzogen/gesperrt). */
  const self = computed<CreatorSelf | null>(() => dashboard.value ?? (actingAs.value ? null : (me.value?.creator ?? null)))
  /** Eigener Creator-Status aktiv? */
  const active = computed(() => me.value?.creator?.state === 'active')
  /** Rechte als Helfer (`null` = man ist selbst der Creator). */
  const helperRights = computed<HelperRight[] | null>(() => (actingAs.value ? (dashboard.value?.viewer?.rights ?? []) : null))
  /** Fan-Umhang einreichen: `standard` (Partner) oder `hd` (Top), sonst `null`. */
  const fanCapeMode = computed(() => {
    const s = self.value
    if (!s || s.state !== 'active') return null
    if (helperRights.value && !helperRights.value.includes('uploads')) return null
    return s.limits.fanCape
  })

  async function load() {
    const trs = useTrsStore()
    if (!trs.enabled || !trs.status?.account) {
      me.value = null
      dashboard.value = null
      actingAs.value = null
      return
    }
    loading.value = true
    error.value = null
    try {
      me.value = await backend.creator.me()
      // Nicht mehr Helfer dieses Creators → zurück zum eigenen Stand.
      if (actingAs.value && !me.value.helping.some((h) => h.creator.uuid === actingAs.value)) actingAs.value = null
      if (actingAs.value || me.value.creator?.state === 'active') await loadDashboard()
      else dashboard.value = null
    } catch (e) {
      error.value = errorMessage(e)
    } finally {
      loading.value = false
    }
  }

  async function loadDashboard() {
    dashboardError.value = null
    try {
      dashboard.value = await backend.creator.dashboard(actingAs.value)
    } catch (e) {
      // `creator_inactive`: Status wurde gerade entzogen – die Seite zeigt den Grund aus `me`.
      dashboard.value = null
      dashboardError.value = errorMessage(e)
    }
  }

  /** Dashboard wechseln: eigenes (`null`) oder das eines Creators, dem man hilft. */
  async function actAs(creator: string | null) {
    if (creator === actingAs.value && dashboard.value) return
    actingAs.value = creator
    dashboard.value = null
    if (creator || active.value) await loadDashboard()
  }

  async function redeem(code: string): Promise<CreatorRedeem> {
    const result = await backend.creator.redeem(code)
    await load()
    return result
  }

  async function endSupport() {
    await backend.creator.endSupport()
    await load()
  }

  async function block(code: string) {
    const blocked = await backend.creator.block(code)
    if (me.value) me.value = { ...me.value, blocked }
  }

  async function unblock(uuid: string) {
    const blocked = await backend.creator.unblock(uuid)
    if (me.value) me.value = { ...me.value, blocked }
  }

  async function setShowSymbols(show: boolean) {
    const before = me.value?.showFanSymbols ?? true
    if (me.value) me.value = { ...me.value, showFanSymbols: show }
    try {
      const saved = await backend.creator.setShowSymbols(show)
      if (me.value) me.value = { ...me.value, showFanSymbols: saved }
    } catch (e) {
      if (me.value) me.value = { ...me.value, showFanSymbols: before }
      throw e
    }
  }

  // --- Helfer (§35.9) ---

  async function answerInvite(creator: string, accept: boolean) {
    await backend.creator.answerHelperInvite(creator, accept)
    await load()
  }

  async function leaveHelping(creator: string) {
    await backend.creator.leaveHelping(creator)
    if (actingAs.value === creator) {
      actingAs.value = null
      dashboard.value = null
    }
    await load()
  }

  async function inviteHelper(uuid: string, rights: HelperRight[]) {
    await backend.creator.inviteHelper(uuid, rights)
    await loadDashboard()
  }

  async function updateHelper(uuid: string, rights: HelperRight[]) {
    await backend.creator.updateHelper(uuid, rights)
    await loadDashboard()
  }

  async function removeHelper(uuid: string) {
    await backend.creator.removeHelper(uuid)
    await loadDashboard()
  }

  function openPage(tab: string | null = null) {
    void navigateTo(tab ? { path: '/creator', query: { tab } } : '/creator')
  }

  /** `creator_updated` (an den Creator), `creator_milestone` (an alle Fans) und die Helfer-Ereignisse. */
  function onLiveEvent(e: CreatorEvent) {
    const toasts = useSocialToasts()
    switch (e.type) {
      case 'creator_milestone': {
        const name = tKey(`creator.milestones.${e.milestone}`)
        void toasts.notify('milestone', {
          key: `creator-milestone:${e.creator.uuid}`,
          title: t('creator.toast.fanMilestoneTitle', { name: e.creator.name, milestone: name }),
          body: t('creator.toast.fanMilestoneBody', { milestone: name }),
          face: e.creator,
          open: () => openPage(),
          actions: [],
        })
        break
      }
      case 'creator_helper_invite': {
        const creator = e.creator
        void toasts.notify('creator', {
          key: `creator-helper-invite:${creator.uuid}`,
          title: t('creator.toast.helperInviteTitle', { name: creator.name }),
          body: e.rights.length ? e.rights.map((r) => tKey(`creator.helpers.rights.${r}`)).join(' · ') : t('creator.toast.helperInviteBody'),
          face: creator,
          open: () => openPage(),
          actions: [
            { label: t('creator.helpers.accept'), primary: true, run: () => answerInvite(creator.uuid, true).catch((err) => useToasts().error(err)) },
            { label: t('creator.helpers.decline'), run: () => answerInvite(creator.uuid, false).catch((err) => useToasts().error(err)) },
          ],
        })
        break
      }
      case 'creator_helper_removed':
        void toasts.notify('creator', {
          key: `creator-helper-removed:${e.creator?.uuid ?? ''}`,
          title: e.creator ? t('creator.toast.helperRemovedTitle', { name: e.creator.name }) : t('creator.toast.helperRemovedGeneric'),
          body: t('creator.toast.helperRemovedBody'),
          face: e.creator,
          actions: [],
        })
        if (e.creator && actingAs.value === e.creator.uuid) {
          actingAs.value = null
          dashboard.value = null
        }
        break
      case 'creator_updated': {
        const tab =
          e.kind.startsWith('symbol') ? 'symbol' : e.kind.startsWith('cape') ? 'cape' : e.kind.startsWith('pet') ? 'pet' : e.kind.startsWith('helper') ? 'helpers' : e.kind === 'tier_changed' || e.kind === 'request_answered' ? 'tier' : null
        const params = {
          reason: e.reason ?? '',
          tier: e.tier ? tKey(`creator.tiers.${e.tier}.name`) : '',
          milestone: e.milestone ? tKey(`creator.milestones.${e.milestone}`) : '',
          name: e.helper?.name ?? '?',
        }
        if (e.kind.startsWith('pet')) petRevision.value++
        // Beim Haustier-Wunsch ist `reason` die Antwort des Teams, kein Ablehnungsgrund.
        const body = e.reason
          ? e.kind === 'pet_request_answered'
            ? t('creator.toast.answer', { reason: e.reason })
            : t('creator.toast.reason', { reason: e.reason })
          : tKey(`creator.toast.updated.${e.kind}.body`, params)
        void toasts.notify('creator', {
          key: `creator:${e.kind}`,
          title: tKey(`creator.toast.updated.${e.kind}.title`, params),
          body,
          face: e.helper,
          open: () => openPage(tab),
          actions: [{ label: t('creator.toast.open'), primary: true, run: () => openPage(tab) }],
        })
        break
      }
    }
    void load()
  }

  return {
    me,
    dashboard,
    actingAs,
    petRevision,
    self,
    active,
    helperRights,
    fanCapeMode,
    fanCapeWorkshop,
    loading,
    error,
    dashboardError,
    load,
    loadDashboard,
    actAs,
    redeem,
    endSupport,
    block,
    unblock,
    setShowSymbols,
    answerInvite,
    leaveHelping,
    inviteHelper,
    updateHelper,
    removeHelper,
    onLiveEvent,
  }
})
