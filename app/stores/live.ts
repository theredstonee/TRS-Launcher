import { isTauri } from '@tauri-apps/api/core'
import { listen, type UnlistenFn } from '@tauri-apps/api/event'
import { defineStore } from 'pinia'
import { liveEventSchema, liveStatusSchema, messageSummary, type LiveEvent, type LiveStatus } from '~/utils/chat'

/** Rückfall ohne Echtzeit-Kanal (API §19): Zähler, Freunde, offene Unterhaltung. */
const FALLBACK_UNREAD_MS = 30_000
const FALLBACK_FRIENDS_MS = 60_000
const FALLBACK_OPEN_MS = 10_000
/** So lange darf der Kanal „hängen“, bevor der Rückfall anspringt. */
const FALLBACK_GRACE_MS = 5_000

// Echtzeit im ganzen Launcher: Der Kern hält die Verbindung zu `/v1/events/me`
// und schickt jedes Ereignis als Tauri-Event. Dieser Store verteilt sie an
// Chat, Freunde/Umhänge (trs), Einstellungen und Benachrichtigungen. Solange
// der Kanal nicht steht, fragt er in größeren Abständen per REST nach.
export const useLiveStore = defineStore('live', () => {
  const status = ref<LiveStatus>({ state: 'off', account: null, retryInMs: null })
  const connected = computed(() => status.value.state === 'live')
  let started = false
  const unlisten: UnlistenFn[] = []
  let fallbackTimers: ReturnType<typeof setInterval>[] = []
  let graceTimer: ReturnType<typeof setTimeout> | null = null
  let wasLive = false

  function stopFallback() {
    for (const t of fallbackTimers) clearInterval(t)
    fallbackTimers = []
    if (graceTimer) clearTimeout(graceTimer)
    graceTimer = null
  }

  function startFallback() {
    if (fallbackTimers.length || graceTimer) return
    graceTimer = setTimeout(() => {
      graceTimer = null
      const trs = useTrsStore()
      const chat = useChatStore()
      const visible = () => document.visibilityState === 'visible'
      fallbackTimers = [
        setInterval(() => {
          if (trs.enabled && chat.me) void chat.refreshUnread().catch(() => {})
        }, FALLBACK_UNREAD_MS),
        setInterval(() => {
          if (trs.enabled && visible()) void trs.loadFriends()
        }, FALLBACK_FRIENDS_MS),
        setInterval(() => {
          if (chat.activeId && visible()) void chat.catchUp(chat.activeId).catch(() => {})
        }, FALLBACK_OPEN_MS),
      ]
    }, FALLBACK_GRACE_MS)
  }

  function setStatus(next: LiveStatus) {
    status.value = next
    if (next.state === 'live') stopFallback()
    else if (next.state === 'off') stopFallback()
    else startFallback()
  }

  /** Alles per REST neu laden (nach `resync` oder einer Lücke). */
  async function resyncAll() {
    const trs = useTrsStore()
    await Promise.allSettled([trs.loadFriends(), trs.loadCapeOffers(), useChatStore().resync()])
  }

  async function dispatch(e: LiveEvent) {
    const chat = useChatStore()
    const trs = useTrsStore()
    if (e.type === 'hello') {
      // Erste Verbindung: kurz abgleichen (seit dem Laden kann etwas passiert sein).
      // Neue Verbindung ohne Wiederaufnahme: alles neu.
      if (!e.resumed && !e.first) await resyncAll()
      else if (e.first) void Promise.allSettled([chat.refreshUnread(), trs.loadFriends()])
      wasLive = true
      return
    }
    if (e.type === 'resync') {
      await resyncAll()
      return
    }
    await Promise.allSettled([chat.onEvent(e), trs.onLiveEvent(e)])
    notifyFor(e)
  }

  /** Benachrichtigungen zu Ereignissen (Nachrichten, Einladungen, Anfragen …). */
  function notifyFor(e: LiveEvent) {
    const chat = useChatStore()
    const toasts = useSocialToasts()
    const router = useRouter()
    const route = useRoute()
    const onSocial = () => route.path === '/social'
    switch (e.type) {
      case 'chat_message': {
        const m = e.message
        if (m.kind !== 'text' || m.hidden || m.deleted || !m.sender || m.sender.uuid === chat.me) return
        const c = chat.conversations[e.conversationId]
        const group = c?.kind === 'group' ? (c.name ?? null) : null
        const open = () => {
          void backend.social.focusWindow().catch(() => {})
          void router.push({ path: '/social', query: { c: e.conversationId } })
        }
        const looking = chat.activeId === e.conversationId && chat.watching && onSocial()
        const muted = c ? isMuted(c) : false
        if (m.invite) {
          const address = m.invite.address
          void toasts.notify(
            'invite',
            {
              key: `msg:${e.conversationId}`,
              title: t('social.toasts.inviteTitle', { name: m.sender.name }),
              body: m.invite.name ? `${m.invite.name} · ${address}` : address,
              face: m.sender,
              open,
              actions: [
                { label: t('social.invite.join'), primary: true, run: () => useJoinStore().request(address, null) },
                { label: t('social.toasts.openChat'), run: open },
              ],
            },
            { looking, muted },
          )
          return
        }
        const conversationId = e.conversationId
        void toasts.notify(
          'message',
          {
            key: `msg:${conversationId}`,
            title: group ? t('social.toasts.messageInGroup', { name: m.sender.name, group }) : m.sender.name,
            body: messageSummary(m),
            face: m.sender,
            open,
            actions: [],
            reply: c?.canWrite && !chat.chatMuted ? (text: string) => chat.send(conversationId, { text }) : undefined,
          },
          { looking, muted },
        )
        return
      }
      case 'friend_request': {
        const from = e.from
        const trs = useTrsStore()
        void toasts.notify('friendRequest', {
          key: `fr:${from.uuid}`,
          title: from.name,
          body: t('social.toasts.friendRequest'),
          face: from,
          open: () => void router.push({ path: '/social', query: { tab: 'friends' } }),
          actions: [
            { label: t('social.friends.accept'), primary: true, run: () => trs.answerRequest(from, true) },
            { label: t('social.friends.decline'), run: () => trs.answerRequest(from, false) },
          ],
        })
        return
      }
      case 'friend_online': {
        void toasts.notify('online', {
          key: `on:${e.friend.uuid}`,
          title: e.friend.name,
          body: e.presence?.state === 'in-game' ? trsPresenceText(e.presence) : t('social.toasts.online'),
          face: e.friend,
          open: () => void router.push({ path: '/social', query: { tab: 'friends' } }),
          actions: [],
        })
        return
      }
      case 'report_update': {
        const r = e.report
        const body =
          r.status === 'resolved'
            ? r.outcome === 'actioned'
              ? t('social.reports.feedback.actioned')
              : t('social.reports.feedback.dismissed')
            : t('social.reports.feedback.inReview')
        void toasts.notify('report', { key: `rep:${r.id}`, title: t('social.reports.feedback.title'), body, face: null, actions: [] })
        return
      }
      case 'moderation': {
        const until = e.until ? dateTime(e.until) : null
        const body =
          e.action === 'warn'
            ? e.reason
              ? t('social.moderation.warnedReason', { reason: e.reason })
              : t('social.moderation.warned')
            : e.action === 'mute'
              ? until
                ? t('social.moderation.mutedUntil', { date: until })
                : t('social.moderation.mutedReview')
              : t('social.moderation.unmuted')
        void toasts.notify('moderation', { key: 'moderation', title: t('social.moderation.title'), body, face: null, actions: [] })
        return
      }
      default:
        return
    }
  }

  async function start() {
    if (started || !isTauri()) return
    started = true
    unlisten.push(
      await listen('trs-live-status', (event) => {
        const parsed = liveStatusSchema.safeParse(event.payload)
        if (parsed.success) setStatus(parsed.data)
      }),
    )
    unlisten.push(
      await listen('trs-live', (event) => {
        const parsed = liveEventSchema.safeParse(event.payload)
        if (parsed.success) void dispatch(parsed.data)
      }),
    )
    try {
      setStatus(await backend.social.liveStatus())
    } catch {
      // Ältere Kerne ohne Kanal: Rückfall bleibt aktiv.
      startFallback()
    }
    // Nach dem Standby/Aufwachen sofort neu verbinden statt auf den Totmann zu warten.
    window.addEventListener('online', () => void backend.social.liveReconnect().catch(() => {}))
  }

  /** „Jetzt verbinden“ (Knopf im Offline-Hinweis). */
  async function reconnect() {
    await backend.social.liveReconnect().catch(() => {})
  }

  return { status, connected, wasLive: () => wasLive, start, reconnect, resyncAll }
})
