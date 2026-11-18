import { isTauri } from '@tauri-apps/api/core'
import { listen } from '@tauri-apps/api/event'
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
// Relativ importiert, damit Tests den Store ohne Nuxt laden können (Instanzen, Spiele,
// Beitreten und Toasts kommen in der App über Nuxts Auto-Imports, im Test als Attrappen).
import { BackendError, backend, errorMessage } from '../utils/backend'
import {
  type CraftingAccess,
  type CraftingInfo,
  type CraftingServer,
  type NewCraftingServer,
  POLL_FAST_MS,
  cleanHostingCommand,
  joinVersion,
  pollInterval,
} from '../utils/craftinghosting'
import { formatTime } from '../utils/format'
import { t } from '../utils/i18n'
import { trsIsQuiet } from '../utils/trs'

/** Länger wartet „Beitreten“ nicht auf den Start. */
const JOIN_GIVE_UP_MS = 5 * 60_000
/** Fehler, bei denen der Bereich gerade nicht nutzbar ist (Hinweis statt Toast). */
const UNAVAILABLE = ['craftinghosting_unavailable', 'craftinghosting_maintenance', 'craftinghosting_upstream']

type Busy = 'create' | 'start' | 'stop' | 'delete' | 'access' | 'command'

// Kostenloser Server beim Partner CraftingHosting: laden, anlegen, starten/stoppen,
// Zugang, Konsole, löschen und „Beitreten“ (starten, warten, passende Instanz).
export const useCraftingHostingStore = defineStore('craftinghosting', () => {
  const info = ref<CraftingInfo | null>(null)
  const server = ref<CraftingServer | null>(null)
  /** Zeitpunkt der letzten Server-Antwort (für den Countdown). */
  const loadedAt = ref(Date.now())
  const loaded = ref(false)
  const loading = ref(false)
  /** Bereich nicht nutzbar (`unavailable`/Wartung) oder Ladefehler. */
  const problem = ref<{ code: string | null; text: string } | null>(null)
  const busy = ref<Busy | null>(null)
  /** EULA-Dialog vor dem ersten Start. */
  const eulaOpen = ref(false)
  const joining = ref(false)
  let joinAfterEula = false
  let pollTimer: ReturnType<typeof setTimeout> | null = null
  let watching = false
  let listening = false

  const needsEula = computed(() => !!server.value && !server.value.eulaAccepted)

  function setServer(next: CraftingServer | null) {
    server.value = next
    loadedAt.value = Date.now()
  }

  function apiCode(e: unknown): string | undefined {
    return e instanceof BackendError ? e.apiCode : undefined
  }

  function report(e: unknown) {
    const code = apiCode(e)
    if (code === 'cooldown' && e instanceof BackendError && e.params?.cooldownUntil) {
      useToasts().error(t('craftinghosting.cooldownUntil', { time: formatTime(e.params.cooldownUntil) }))
      return
    }
    useToasts().error(e)
  }

  /** Nach Fehlern, die den Server betreffen, den Stand neu holen. */
  function refreshAfter(e: unknown) {
    const code = apiCode(e)
    if (code && ['no_server', 'not_running', 'cooldown', 'server_installing', 'server_failed', 'server_suspended', 'server_exists'].includes(code)) {
      void refresh()
    }
  }

  async function load() {
    loading.value = true
    try {
      const [i, s] = await Promise.all([backend.craftinghosting.info(), backend.craftinghosting.server()])
      info.value = i
      setServer(s)
      problem.value = null
      loaded.value = true
    } catch (e) {
      const code = apiCode(e) ?? null
      const quiet = e instanceof BackendError && trsIsQuiet(e.kind)
      problem.value = { code, text: quiet ? t('craftinghosting.offline') : errorMessage(e) }
    } finally {
      loading.value = false
    }
  }

  /** Nur den Server neu holen (Abfrage-Takt, Ereignisse). */
  async function refresh() {
    try {
      setServer(await backend.craftinghosting.server())
      if (problem.value && !UNAVAILABLE.includes(problem.value.code ?? '')) problem.value = null
    } catch (e) {
      const code = apiCode(e)
      if (code && UNAVAILABLE.includes(code)) problem.value = { code, text: errorMessage(e) }
    }
  }

  async function run<T>(kind: Busy, action: () => Promise<T>): Promise<T | null> {
    if (busy.value) return null
    busy.value = kind
    try {
      return await action()
    } catch (e) {
      report(e)
      refreshAfter(e)
      return null
    } finally {
      busy.value = null
    }
  }

  async function create(spec: NewCraftingServer): Promise<boolean> {
    const created = await run('create', () => backend.craftinghosting.create(spec))
    if (created) setServer(created)
    schedule()
    return !!created
  }

  /** Start-Knopf: vor dem ersten Start erst die EULA. */
  async function requestStart(): Promise<boolean> {
    if (!server.value) return false
    if (needsEula.value) {
      eulaOpen.value = true
      return false
    }
    return start(false)
  }

  async function start(acceptEula: boolean): Promise<boolean> {
    try {
      const started = await run('start', () => backend.craftinghosting.start(acceptEula))
      if (started) setServer(started)
      return !!started
    } finally {
      schedule()
    }
  }

  async function confirmEula() {
    eulaOpen.value = false
    const ok = await start(true)
    if (ok && joinAfterEula) void join()
    joinAfterEula = false
  }

  function cancelEula() {
    eulaOpen.value = false
    joinAfterEula = false
  }

  async function stop() {
    const stopped = await run('stop', () => backend.craftinghosting.stop())
    if (stopped) setServer(stopped)
    schedule()
  }

  async function remove(): Promise<boolean> {
    const done = await run('delete', async () => {
      await backend.craftinghosting.remove()
      return true
    })
    if (done) {
      setServer(null)
      useToasts().ok(t('craftinghosting.deleted'))
    }
    return !!done
  }

  async function setAccess(access: CraftingAccess) {
    if (!server.value || server.value.access === access) return
    const updated = await run('access', () => backend.craftinghosting.setAccess(access))
    if (updated) setServer(updated)
  }

  /** Befehl an die Konsole (ohne Ausgabe). Liefert, ob er angenommen wurde. */
  async function command(input: string): Promise<boolean> {
    const cmd = cleanHostingCommand(input)
    if (!cmd) {
      useToasts().error(t('errors.craftinghosting.invalidCommand'))
      return false
    }
    const done = await run('command', async () => {
      await backend.craftinghosting.command(cmd)
      return true
    })
    if (done) useToasts().ok(t('craftinghosting.console.sent'))
    return !!done
  }

  // --- Beitreten ------------------------------------------------------------------------

  /** Starten falls nötig, auf `running` warten, dann mit passender Instanz verbinden. */
  async function join() {
    if (!server.value || joining.value) return
    joining.value = true
    try {
      if (server.value.state !== 'running') {
        if (!['starting', 'installing'].includes(server.value.state)) {
          if (needsEula.value) {
            joinAfterEula = true
            eulaOpen.value = true
            return
          }
          if (!(await start(false))) return
        }
        useToasts().info(t('craftinghosting.join.waiting'))
        if (!(await waitForRunning())) return
      }
      const address = server.value?.address
      if (!address) {
        useToasts().error(t('craftinghosting.join.noAddress'))
        return
      }
      await launchWith(address, server.value!.version)
    } finally {
      joining.value = false
    }
  }

  async function waitForRunning(): Promise<boolean> {
    const until = Date.now() + JOIN_GIVE_UP_MS
    while (Date.now() < until) {
      await new Promise((r) => setTimeout(r, POLL_FAST_MS))
      await refresh()
      const state = server.value?.state
      if (state === 'running') return true
      if (!state || ['offline', 'failed', 'suspended'].includes(state)) {
        useToasts().error(t('craftinghosting.join.notStarted'))
        return false
      }
    }
    useToasts().error(t('craftinghosting.join.timeout'))
    return false
  }

  async function launchWith(address: string, serverVersion: string) {
    const instances = useInstancesStore()
    if (!instances.items.length) await instances.load().catch(() => {})
    const version = joinVersion(serverVersion, instances.items)
    const matching = version ? instances.items.filter((i) => i.gameVersion === version) : []
    // Genau eine passende Instanz: gleich starten, sonst fragt der Beitreten-Dialog.
    if (matching.length === 1) {
      const games = useGamesStore()
      const target = matching[0]!
      if (games.state(target.id).phase !== 'idle') {
        useToasts().info(t('social.invite.alreadyRunning', { instance: target.name }))
        return
      }
      useToasts().info(t('social.invite.starting', { instance: target.name, server: address }))
      await games.launch(target.id, null, address)
      return
    }
    await useJoinStore().request(address, version)
  }

  // --- Abfrage-Takt ---------------------------------------------------------------------

  function schedule() {
    if (pollTimer) clearTimeout(pollTimer)
    pollTimer = null
    if (!watching) return
    pollTimer = setTimeout(async () => {
      pollTimer = null
      await refresh()
      schedule()
    }, pollInterval(server.value))
  }

  /** Seite offen: laden und im Takt nachfragen (5 s bei Zustandswechseln, sonst 30 s). */
  async function watch() {
    watching = true
    void listenForChanges()
    await load()
    schedule()
  }

  function unwatch() {
    watching = false
    if (pollTimer) clearTimeout(pollTimer)
    pollTimer = null
  }

  async function listenForChanges() {
    if (listening || !isTauri()) return
    listening = true
    await listen('craftinghosting-changed', () => {
      if (watching) void refresh()
    })
  }

  /** Kontowechsel: alten Stand verwerfen. */
  function reset() {
    info.value = null
    setServer(null)
    loaded.value = false
    problem.value = null
    eulaOpen.value = false
    joinAfterEula = false
  }

  return {
    info,
    server,
    loadedAt,
    loaded,
    loading,
    problem,
    busy,
    eulaOpen,
    joining,
    needsEula,
    load,
    refresh,
    create,
    requestStart,
    start,
    confirmEula,
    cancelEula,
    stop,
    remove,
    setAccess,
    command,
    join,
    watch,
    unwatch,
    reset,
  }
})
