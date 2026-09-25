import { isTauri } from '@tauri-apps/api/core'
import { listen } from '@tauri-apps/api/event'
import { defineStore } from 'pinia'
import { trsSyncEventSchema, type TrsFriends, type TrsMe, type TrsStatus, type TrsSyncStatus } from '~/utils/trs'

/** Takt für Freunde im Hintergrund (Anfragen-Zähler in der Leiste). Die Seite selbst fragt öfter. */
const BACKGROUND_POLL_MS = 90_000

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
  let timer: ReturnType<typeof setInterval> | null = null
  let knownIncoming: Set<string> | null = null
  /** Stand der Synchronisation mit dem TRS-Konto (Hinweis auf der Skins-Seite). */
  const sync = ref<TrsSyncStatus | null>(null)
  /** Zählt Abgleiche, die die Skin-Sammlung geändert haben – die Skins-Seite lädt dann neu. */
  const skinsRevision = ref(0)
  let syncListening = false

  const enabled = computed(() => status.value?.consent === 'accepted')
  const undecided = computed(() => status.value !== null && status.value.consent === null)
  const isAdmin = computed(() => enabled.value && me.value?.admin === true)
  const incomingCount = computed(() => friends.value?.requests.incoming.length ?? 0)

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
      if (knownIncoming) {
        const fresh = view.requests.incoming.filter((r) => !knownIncoming!.has(r.uuid))
        for (const r of fresh) useToasts().info(t('trs.toasts.friendRequest', { name: r.name }))
      }
      knownIncoming = incoming
      friends.value = view
      problem.value = null
    } catch (e) {
      note(e)
    }
    return friends.value
  }

  function stopPolling() {
    if (timer) clearInterval(timer)
    timer = null
  }

  function startPolling() {
    stopPolling()
    if (!enabled.value) return
    timer = setInterval(() => {
      if (document.visibilityState === 'visible') void loadFriends()
    }, BACKGROUND_POLL_MS)
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
    friends.value = null
    void listenSync()
    await refreshStatus()
    void refreshSync()
    if (enabled.value) {
      await loadMe()
      await loadFriends()
      startPolling()
    } else {
      me.value = null
      stopPolling()
    }
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
    init,
    refreshStatus,
    loadMe,
    loadFriends,
    setConsent,
    deleteAll,
    askConsent,
    stopPolling,
    sync,
    skinsRevision,
    refreshSync,
    onSyncEvent,
  }
})
