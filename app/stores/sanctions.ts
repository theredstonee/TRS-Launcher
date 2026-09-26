import { defineStore } from 'pinia'
import type { LiveEvent } from '~/utils/chat'
import {
  bySeverity,
  endText,
  kindLabel,
  sanctionFromParams,
  sanctionIsActive,
  sanctionReasonText,
  type MySanction,
} from '~/utils/sanctions'

type SanctionEvent = Extract<LiveEvent, { type: 'sanction_added' | 'sanction_updated' | 'appeal_decided' }>

const DISMISSED_KEY = 'trs.sanctions.dismissed'

// Eigene Strafen (API §22): aktive + vergangene, Einspruch, Banner und Live-
// Ereignisse. Gesperrte Konten sehen das auch – der Kern nutzt dann den
// Einspruch-Token. Offline ist kein Fehler: dann bleibt der letzte Stand.
export const useSanctionsStore = defineStore('sanctions', () => {
  const active = ref<MySanction[]>([])
  const past = ref<MySanction[]>([])
  const loaded = ref(false)
  const loading = ref(false)
  /** Dialog „Meine Strafen“ (global, auch aus Banner, Toast oder Einstellungen). */
  const dialogOpen = ref(false)
  /** Diese Strafe im Dialog direkt aufklappen (z. B. „Einspruch einlegen“ aus dem Banner). */
  const focusId = ref<number | null>(null)
  /** Konto gesperrt (aus einem `banned`-Fehler) – auch wenn die Liste noch nicht geladen ist. */
  const banned = ref<MySanction | null>(null)
  /** Uhr für abgelaufene Strafen (Banner verschwindet ohne Ereignis). */
  const now = ref(Date.now())
  let clock: ReturnType<typeof setInterval> | null = null
  const dismissed = ref<Set<number>>(readDismissed())

  function readDismissed(): Set<number> {
    try {
      const raw = JSON.parse(localStorage.getItem(DISMISSED_KEY) ?? '[]') as unknown
      return new Set(Array.isArray(raw) ? raw.filter((n): n is number => typeof n === 'number') : [])
    } catch {
      return new Set()
    }
  }

  function saveDismissed() {
    try {
      localStorage.setItem(DISMISSED_KEY, JSON.stringify([...dismissed.value].slice(-50)))
    } catch {
      // Nur Komfort.
    }
  }

  /** Wirksame Strafen, schwerste zuerst. */
  const current = computed(() => {
    const list = active.value.filter((s) => sanctionIsActive(s, now.value))
    if (banned.value && sanctionIsActive(banned.value, now.value) && !list.some((s) => s.kind === 'account_ban')) list.push(banned.value)
    return list.sort(bySeverity)
  })

  /** Was der Banner zeigt: wirksame Strafen, die nicht weggeklickt wurden (Konto-Bann immer). */
  const banner = computed(() => current.value.filter((s) => s.kind === 'account_ban' || !dismissed.value.has(s.id)))

  const has = (kind: MySanction['kind']) => computed(() => current.value.some((s) => s.kind === kind))
  const chatMuted = has('chat_mute')
  const socialBanned = has('social_ban')
  const uploadBanned = has('upload_ban')
  const hostingBanned = has('hosting_ban')
  const accountBanned = has('account_ban')

  function sanctionOf(kind: MySanction['kind']): MySanction | null {
    return current.value.find((s) => s.kind === kind) ?? null
  }

  function startClock() {
    if (clock || typeof window === 'undefined') return
    clock = setInterval(() => (now.value = Date.now()), 30_000)
  }

  async function load() {
    const trs = useTrsStore()
    if (!trs.enabled || !trs.status?.account) {
      reset()
      return
    }
    loading.value = true
    try {
      const mine = await backend.sanctions.mine()
      active.value = mine.active
      past.value = mine.past
      loaded.value = true
      const ban = mine.active.find((s) => s.kind === 'account_ban') ?? null
      banned.value = ban
      startClock()
    } catch (e) {
      // Offline/nicht angemeldet: still. Gesperrt ohne Einspruch-Token: Hinweis aus dem Fehler.
      noteError(e)
    } finally {
      loading.value = false
    }
  }

  function reset() {
    active.value = []
    past.value = []
    loaded.value = false
    banned.value = null
  }

  /** Aus einem Fehler lernen: `banned` / `chat_muted` / `sanctioned` tragen die Strafe. */
  function noteError(e: unknown) {
    if (!(e instanceof BackendError)) return
    const s = sanctionFromParams(e.apiCode, e.params)
    if (!s) return
    if (s.kind === 'account_ban') banned.value = s
    else if (!active.value.some((a) => a.id === s.id)) void load()
    startClock()
  }

  function upsert(s: MySanction) {
    const rest = (list: MySanction[]) => list.filter((x) => x.id !== s.id)
    if (s.status === 'active') {
      active.value = [s, ...rest(active.value)]
      past.value = rest(past.value)
    } else {
      active.value = rest(active.value)
      past.value = [s, ...rest(past.value)]
    }
    if (s.kind === 'account_ban') banned.value = s.status === 'active' ? s : null
    startClock()
  }

  /** Einspruch einlegen (einmal je Strafe). */
  async function appeal(id: number, text: string): Promise<MySanction> {
    const updated = await backend.sanctions.appeal(id, text)
    upsert(updated)
    return updated
  }

  function open(id: number | null = null) {
    focusId.value = id
    dialogOpen.value = true
  }

  function dismiss(id: number) {
    dismissed.value = new Set([...dismissed.value, id])
    saveDismissed()
  }

  function toastFor(s: MySanction, title: string, body: string) {
    void useSocialToasts().notify('moderation', {
      key: `sanction:${s.id}`,
      title,
      body,
      face: null,
      open: () => open(s.id),
      actions: s.appealable && sanctionIsActive(s) ? [{ label: t('sanctions.appeal.button'), primary: true, run: () => open(s.id) }] : [],
    })
  }

  /** Live-Ereignisse `sanction_added`, `sanction_updated`, `appeal_decided` (§22.9). */
  function onLiveEvent(e: SanctionEvent) {
    if (e.type === 'sanction_added') {
      upsert(e.sanction)
      // Neue Strafe: Banner wieder zeigen, auch wenn eine ältere weggeklickt wurde.
      toastFor(e.sanction, t('sanctions.toasts.added', { kind: kindLabel(e.sanction.kind) }), `${endText(e.sanction)} · ${sanctionReasonText(e.sanction)}`)
      if (e.sanction.kind === 'chat_mute') void useChatStore().loadModeration()
      return
    }
    if (e.type === 'sanction_updated') {
      const before = [...active.value, ...past.value].find((s) => s.id === e.sanction.id)
      upsert(e.sanction)
      // Eigener Einspruch (anderes Gerät) oder Einspruch-Entscheidung folgt gleich: kein eigener Toast.
      const appealChanged = (before?.appeal?.id ?? null) !== (e.sanction.appeal?.id ?? null)
      if (!appealChanged && e.sanction.appeal?.status !== 'open') {
        const body = e.sanction.status === 'lifted' ? t('sanctions.toasts.liftedBody') : endText(e.sanction)
        toastFor(e.sanction, t('sanctions.toasts.updated', { kind: kindLabel(e.sanction.kind) }), body)
      }
      if (e.sanction.kind === 'chat_mute') void useChatStore().loadModeration()
      return
    }
    upsert(e.sanction)
    const body = e.appeal.response ? t('sanctions.toasts.decidedBody', { status: t(`sanctions.appeal.status.${e.appeal.status}`), response: e.appeal.response }) : t(`sanctions.appeal.status.${e.appeal.status}`)
    toastFor(e.sanction, t('sanctions.toasts.decided', { kind: kindLabel(e.sanction.kind) }), body)
    if (e.sanction.kind === 'chat_mute') void useChatStore().loadModeration()
  }

  return {
    active,
    past,
    loaded,
    loading,
    dialogOpen,
    focusId,
    banned,
    current,
    banner,
    chatMuted,
    socialBanned,
    uploadBanned,
    hostingBanned,
    accountBanned,
    sanctionOf,
    load,
    reset,
    noteError,
    upsert,
    appeal,
    open,
    dismiss,
    onLiveEvent,
  }
})
