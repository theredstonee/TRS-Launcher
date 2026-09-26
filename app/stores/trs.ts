import { isTauri } from '@tauri-apps/api/core'
import { listen } from '@tauri-apps/api/event'
import { defineStore } from 'pinia'
import {
  trsSyncEventSchema,
  type TrsBlocked,
  type TrsCapeOffers,
  type TrsFriends,
  type TrsIncomingOffer,
  type TrsMe,
  type TrsPresence,
  type TrsStatus,
  type TrsSyncStatus,
  type TrsUserRef,
} from '~/utils/trs'
import type { LiveEvent } from '~/utils/chat'

// Zustand der TRS-Dienste: Einwilligung, eigenes Profil (Admin?), Freunde.
// Offline/abgeschaltet ist kein Fehler – die Seiten zeigen dann einen Zustand
// statt Meldungen.
export const useTrsStore = defineStore('trs', () => {
  const status = ref<TrsStatus | null>(null)
  const me = ref<TrsMe | null>(null)
  const friends = ref<TrsFriends | null>(null)
  /** Letzter „stiller“ Zustand: offline, gesperrt usw. */
  const problem = ref<'offline' | 'banned' | 'auth' | null>(null)
  const consentOpen = ref(false)
  /** Dialog „Website-Anmeldung bestätigen“ (global, auch aus der Befehlspalette). */
  const webLoginOpen = ref(false)
  let knownIncoming: Set<string> | null = null
  /** Blockierte Spieler (Reiter „Freunde“ → „Blockiert“). */
  const blocked = ref<TrsBlocked[] | null>(null)
  /**
   * Wann ein Freund zuletzt online gesehen wurde (nur lokal, die API kennt das
   * nicht): UUID → ISO-Zeit. Für „Zuletzt online vor …“ in der Freundesliste.
   */
  const lastSeen = ref<Record<string, string>>({})
  const lastSeenKey = () => 'trs.lastSeen.' + (status.value?.account ?? '')

  function loadLastSeen() {
    try {
      const raw = localStorage.getItem(lastSeenKey())
      const parsed = raw ? (JSON.parse(raw) as unknown) : null
      lastSeen.value = parsed && typeof parsed === 'object' ? (parsed as Record<string, string>) : {}
    } catch {
      lastSeen.value = {}
    }
  }

  function markSeen(uuids: string[]) {
    if (!uuids.length) return
    const now = new Date().toISOString()
    const next = { ...lastSeen.value }
    for (const u of uuids) next[u] = now
    lastSeen.value = next
    try {
      localStorage.setItem(lastSeenKey(), JSON.stringify(next))
    } catch {
      // Nur Komfort – ohne Speicher eben ohne „zuletzt online“.
    }
  }
  /** Umhang-Angebote an mich / von mir (geladen, sobald es welche gibt oder die Seite sie braucht). */
  const capeOffers = ref<TrsCapeOffers | null>(null)
  let knownOffers: Set<string> | null = null
  /** Zählt Änderungen an der eigenen Umhang-Sammlung (Angebot angenommen …) – die Umhang-Liste lädt dann neu. */
  const capesRevision = ref(0)
  /** Stand der Synchronisation mit dem TRS-Konto (Hinweis auf der Skins-Seite). */
  const sync = ref<TrsSyncStatus | null>(null)
  /** Zählt Abgleiche, die die Skin-Sammlung geändert haben – die Skins-Seite lädt dann neu. */
  const skinsRevision = ref(0)
  let syncListening = false

  const enabled = computed(() => status.value?.consent === 'accepted')
  const undecided = computed(() => status.value !== null && status.value.consent === null)
  const isAdmin = computed(() => enabled.value && me.value?.admin === true)
  const offerCount = computed(() => capeOffers.value?.incoming.length ?? friends.value?.capeOffers ?? 0)
  /** Anfragen + Umhang-Angebote (Zähler in der Leiste). */
  const incomingCount = computed(() => (friends.value?.requests.incoming.length ?? 0) + offerCount.value)

  /** Freundschaftsanfrage beantworten (auch aus einer Benachrichtigung). */
  async function answerRequest(from: TrsUserRef, accept: boolean) {
    try {
      if (accept) {
        await backend.trs.acceptFriend(from.uuid)
        useToasts().ok(t('friends.toasts.nowFriends', { name: from.name }))
      } else {
        await backend.trs.declineFriend(from.uuid)
      }
    } catch (e) {
      useToasts().error(e)
    }
    await loadFriends()
  }

  /** Umhang-Angebot annehmen/ablehnen (auch aus einer Benachrichtigung). */
  async function answerOffer(offer: TrsIncomingOffer, accept: boolean) {
    try {
      if (accept) {
        await backend.trs.acceptCapeOffer(offer.cape.id)
        useToasts().ok(t('capeShare.toasts.accepted', { cape: offer.cape.name }))
      } else {
        await backend.trs.declineCapeOffer(offer.cape.id)
      }
    } catch (e) {
      useToasts().error(e)
    }
    await capeSharesChanged()
  }

  function notifyRequest(from: TrsUserRef) {
    void useSocialToasts().notify('friendRequest', {
      key: 'fr:' + from.uuid,
      title: from.name,
      body: t('social.toasts.friendRequest'),
      face: from,
      open: () => void navigateTo({ path: '/social', query: { tab: 'friends' } }),
      actions: [
        { label: t('social.friends.accept'), primary: true, run: () => answerRequest(from, true) },
        { label: t('social.friends.decline'), run: () => answerRequest(from, false) },
      ],
    })
  }

  function notifyOffer(offer: TrsIncomingOffer) {
    void useSocialToasts().notify('capeOffer', {
      key: 'offer:' + offer.cape.id + ':' + offer.from.uuid,
      title: offer.from.name,
      body: t('social.toasts.capeOffer', { cape: offer.cape.name }),
      face: offer.from,
      open: () => void navigateTo({ path: '/social', query: { tab: 'friends' } }),
      actions: [
        { label: t('social.friends.accept'), primary: true, run: () => answerOffer(offer, true) },
        { label: t('social.friends.decline'), run: () => answerOffer(offer, false) },
      ],
    })
  }

  function note(e: unknown) {
    const kind = e instanceof BackendError ? e.kind : ''
    if (kind === 'trs_offline') problem.value = 'offline'
    else if (kind === 'trs_banned') problem.value = 'banned'
    else if (kind === 'trs_auth') problem.value = 'auth'
  }

  async function refreshStatus() {
    try {
      status.value = await backend.trs.status()
    } catch {
      status.value = null
    }
    return status.value
  }

  async function loadMe() {
    if (!enabled.value || !status.value?.account) {
      me.value = null
      return null
    }
    try {
      me.value = await backend.trs.me()
      problem.value = null
    } catch (e) {
      note(e)
      if (!(e instanceof BackendError && e.kind === 'trs_offline')) me.value = null
    }
    return me.value
  }

  /** Freunde laden; neue Anfragen melden (nicht beim ersten Laden). */
  async function loadFriends() {
    if (!enabled.value || !status.value?.account) {
      friends.value = null
      return null
    }
    try {
      const view = await backend.trs.friends()
      const incoming = new Set(view.requests.incoming.map((r) => r.uuid))
      // Neue Anfragen melden – über den Echtzeit-Kanal kommt dafür ein eigenes Ereignis.
      if (knownIncoming && !useLiveStore().connected) {
        const fresh = view.requests.incoming.filter((r) => !knownIncoming!.has(r.uuid))
        for (const r of fresh) notifyRequest(r)
      }
      knownIncoming = incoming
      friends.value = view
      markSeen(view.friends.filter((f) => f.presence).map((f) => f.uuid))
      problem.value = null
      // Angebote nur nachladen, wenn sich die Zahl geändert hat (oder noch nichts geladen ist).
      const loaded = capeOffers.value?.incoming.length ?? null
      if (view.capeOffers !== loaded && (view.capeOffers > 0 || loaded !== null)) void loadCapeOffers()
    } catch (e) {
      note(e)
    }
    return friends.value
  }

  const offerKey = (capeId: string, from: string) => `${capeId}:${from}`

  /** Umhang-Angebote laden; neue melden (nicht beim ersten Laden). */
  async function loadCapeOffers() {
    if (!enabled.value || !status.value?.account) {
      capeOffers.value = null
      return null
    }
    try {
      const offers = await backend.trs.capeOffers()
      const keys = new Set(offers.incoming.map((o) => offerKey(o.cape.id, o.from.uuid)))
      if (knownOffers) {
        for (const o of offers.incoming) {
          if (!knownOffers.has(offerKey(o.cape.id, o.from.uuid))) notifyOffer(o)
        }
      }
      knownOffers = keys
      capeOffers.value = offers
    } catch (e) {
      note(e)
    }
    return capeOffers.value
  }

  /** Nach Annehmen/Ablehnen/Teilen: Angebote + Freunde neu, Umhang-Liste neu laden lassen. */
  async function capeSharesChanged() {
    capesRevision.value++
    await Promise.all([loadCapeOffers(), loadFriends()])
  }

  async function loadBlocked() {
    try {
      blocked.value = await backend.trs.blocks()
    } catch (e) {
      note(e)
    }
    return blocked.value
  }

  /** Präsenz eines Freundes ohne Neuladen setzen. */
  function setPresence(uuid: string, presence: TrsPresence | null) {
    const view = friends.value
    if (!view) return
    const i = view.friends.findIndex((f) => f.uuid === uuid)
    if (i < 0) return
    const list = [...view.friends]
    // Wer gerade offline geht, war bis eben online.
    if (presence || list[i]!.presence) markSeen([uuid])
    list[i] = { ...list[i]!, presence }
    friends.value = { ...view, friends: list }
  }

  /** Ereignis aus dem Echtzeit-Kanal: Freunde, Präsenz, Umhänge, Einstellungen. */
  async function onLiveEvent(e: LiveEvent) {
    switch (e.type) {
      case 'friend_request':
        if (knownIncoming) knownIncoming.add(e.from.uuid)
        await loadFriends()
        break
      case 'friend_request_cancelled':
        if (friends.value) {
          const incoming = friends.value.requests.incoming.filter((r) => r.uuid !== e.uuid)
          friends.value = { ...friends.value, requests: { ...friends.value.requests, incoming } }
        }
        break
      case 'friend_added':
      case 'friend_removed':
      case 'friends_changed':
        await loadFriends()
        if (e.type !== 'friend_added' && blocked.value) await loadBlocked()
        break
      case 'presence':
        setPresence(e.uuid, e.presence)
        break
      case 'friend_online':
        setPresence(e.friend.uuid, e.presence)
        break
      case 'cape_offer':
      case 'cape_offer_accepted':
      case 'cape_share_removed':
        await capeSharesChanged()
        break
      case 'settings':
        if (me.value) me.value = { ...me.value, settings: e.settings }
        break
      default:
        break
    }
  }

  async function refreshSync() {
    try {
      sync.value = await backend.trs.syncStatus()
    } catch {
      sync.value = null
    }
    return sync.value
  }

  /**
   * Nach einem Abgleich (Event `trs-sync` aus dem Kern): betroffene Daten neu
   * laden – Skins-Seite, Presets, Theme/Akzent/Sprache sofort anwenden.
   */
  function onSyncEvent(payload: unknown) {
    const parsed = trsSyncEventSchema.safeParse(payload)
    if (!parsed.success) return
    const { changes, status: next } = parsed.data
    sync.value = next
    if (changes.skins) skinsRevision.value++
    if (changes.presets) void usePresetsStore().load(true).catch(() => {})
    if (changes.settings) void useSettingsStore().reloadFromSync().catch(() => {})
  }

  async function listenSync() {
    if (syncListening || !isTauri()) return
    syncListening = true
    await listen('trs-sync', (e) => onSyncEvent(e.payload))
  }

  /** Beim Start und nach Account-Wechseln: alles neu. */
  async function init() {
    knownIncoming = null
    knownOffers = null
    friends.value = null
    capeOffers.value = null
    blocked.value = null
    void listenSync()
    await refreshStatus()
    loadLastSeen()
    void refreshSync()
    const chat = useChatStore()
    if (enabled.value) {
      await loadMe()
      chat.setAccount(me.value?.uuid ?? null)
      await loadFriends()
      if (me.value) {
        void chat.loadList().catch(() => {})
        void chat.loadModeration()
      }
    } else {
      me.value = null
      chat.setAccount(null)
    }
    // Echtzeit-Kanal (startet nur einmal; Account-Wechsel erledigt der Kern).
    void useLiveStore().start()
  }

  async function setConsent(accepted: boolean) {
    status.value = await backend.trs.setConsent(accepted)
    consentOpen.value = false
    await init()
  }

  /** Nach dem Löschen aller Daten sind die Dienste aus. */
  async function deleteAll() {
    status.value = await backend.trs.deleteMe()
    await init()
  }

  function askConsent() {
    consentOpen.value = true
  }

  function openWebLogin() {
    webLoginOpen.value = true
  }

  return {
    status,
    me,
    friends,
    problem,
    consentOpen,
    webLoginOpen,
    openWebLogin,
    enabled,
    undecided,
    isAdmin,
    incomingCount,
    offerCount,
    capeOffers,
    capesRevision,
    loadCapeOffers,
    capeSharesChanged,
    init,
    refreshStatus,
    loadMe,
    loadFriends,
    setConsent,
    deleteAll,
    askConsent,
    blocked,
    loadBlocked,
    lastSeen,
    answerRequest,
    answerOffer,
    onLiveEvent,
    sync,
    skinsRevision,
    refreshSync,
    onSyncEvent,
  }
})
