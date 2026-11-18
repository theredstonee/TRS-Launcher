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
  type CraftingModsResult,
  type CraftingServer,
  type CraftingSettings,
  type CraftingSettingsPatch,
  type CraftingStats,
  type NewCraftingServer,
  type WorldUploadProgress,
  POLL_FAST_MS,
  cleanHostingCommand,
  joinVersion,
  modrinthProjects,
  pollInterval,
} from '../utils/craftinghosting'
import { formatTime } from '../utils/format'
import { t } from '../utils/i18n'
import { trsIsQuiet } from '../utils/trs'

/** Länger wartet „Beitreten“ nicht auf den Start. */
const JOIN_GIVE_UP_MS = 5 * 60_000
/** Fehler, bei denen der Bereich gerade nicht nutzbar ist (Hinweis statt Toast). */
const UNAVAILABLE = ['craftinghosting_unavailable', 'craftinghosting_maintenance', 'craftinghosting_upstream']

/** Takt der Live-Werte und der Konsole, solange der Server läuft. */
const STATS_MS = 5_000
const LOGS_MS = 3_000
const LOG_LINES = 200

type Busy = 'create' | 'start' | 'stop' | 'delete' | 'access' | 'command' | 'rename' | 'settings' | 'mods' | 'world'

/** Ergebnis von „Mods aus Instanz übernehmen“ samt den Mods ohne Modrinth-Quelle. */
export interface ModsTransfer {
  result: CraftingModsResult | null
  other: string[]
}

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
  const settings = ref<CraftingSettings | null>(null)
  const stats = ref<CraftingStats | null>(null)
  const logs = ref<string[]>([])
  /** Konsole sichtbar: Log im 3-s-Takt, solange der Server läuft. */
  const consoleOpen = ref(false)
  const modsTransfer = ref<ModsTransfer | null>(null)
  const worldProgress = ref<WorldUploadProgress | null>(null)
  let joinAfterEula = false
  let pollTimer: ReturnType<typeof setTimeout> | null = null
  let statsTimer: ReturnType<typeof setInterval> | null = null
  let logsTimer: ReturnType<typeof setInterval> | null = null
  let watching = false
  let listening = false
  let visibilityHooked = false

  const needsEula = computed(() => !!server.value && !server.value.eulaAccepted)

  function setServer(next: CraftingServer | null) {
    server.value = next
    loadedAt.value = Date.now()
    if (next?.state !== 'running') stats.value = null
    syncLive()
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
    if (done) {
      useToasts().ok(t('craftinghosting.console.sent'))
      // Die Antwort steht kurz darauf im Log.
      if (consoleOpen.value) setTimeout(() => void fetchLogs(), 1_000)
    }
    return !!done
  }

  async function rename(name: string): Promise<boolean> {
    if (!server.value || server.value.name === name.trim()) return true
    const updated = await run('rename', () => backend.craftinghosting.rename(name.trim()))
    if (updated) setServer(updated)
    return !!updated
  }

  // --- Einstellungen, Mods, Welt --------------------------------------------------------

  async function loadSettings() {
    try {
      settings.value = await backend.craftinghosting.settings()
    } catch (e) {
      settings.value = null
      report(e)
    }
  }

  async function saveSettings(patch: CraftingSettingsPatch): Promise<boolean> {
    if (!Object.keys(patch).length) return true
    const saved = await run('settings', () => backend.craftinghosting.saveSettings(patch))
    if (saved) {
      settings.value = saved
      useToasts().ok(t('craftinghosting.settings.saved'))
    }
    return !!saved
  }

  /** Modrinth-Mods einer Instanz auf den Server; ohne Modrinth-Quelle bleibt nur die Liste. */
  async function transferMods(instanceId: string): Promise<ModsTransfer | null> {
    modsTransfer.value = null
    const outcome = await run('mods', async () => {
      const { projects, other } = modrinthProjects(await backend.listContent(instanceId, 'mod'))
      const result = projects.length ? await backend.craftinghosting.mods(projects) : null
      return { result, other }
    })
    modsTransfer.value = outcome
    return outcome
  }

  /** Welt einer Instanz einspielen (ersetzt die Welt des Servers). */
  async function uploadWorld(instanceId: string, folder: string): Promise<boolean> {
    worldProgress.value = { phase: 'zip', percent: 0 }
    try {
      const updated = await run('world', () => backend.craftinghosting.uploadWorld(instanceId, folder, (p) => (worldProgress.value = p)))
      if (updated) {
        setServer(updated)
        useToasts().ok(t('craftinghosting.world.done'))
      }
      return !!updated
    } finally {
      worldProgress.value = null
    }
  }

  // --- Live-Werte und Konsole -----------------------------------------------------------

  function pageVisible() {
    return typeof document === 'undefined' || document.visibilityState !== 'hidden'
  }

  async function fetchStats() {
    try {
      stats.value = await backend.craftinghosting.stats()
    } catch {
      stats.value = null
    }
  }

  async function fetchLogs() {
    try {
      logs.value = await backend.craftinghosting.logs(LOG_LINES)
    } catch {
      // Konsole bleibt beim letzten Stand.
    }
  }

  /** Stats (5 s) und Log (3 s) nur, solange die Seite sichtbar ist und der Server läuft. */
  function syncLive() {
    const live = watching && server.value?.state === 'running' && pageVisible()
    if (live && !statsTimer) {
      void fetchStats()
      statsTimer = setInterval(fetchStats, STATS_MS)
    } else if (!live && statsTimer) {
      clearInterval(statsTimer)
      statsTimer = null
    }
    const tail = live && consoleOpen.value
    if (tail && !logsTimer) {
      void fetchLogs()
      logsTimer = setInterval(fetchLogs, LOGS_MS)
    } else if (!tail && logsTimer) {
      clearInterval(logsTimer)
      logsTimer = null
    }
  }

  function setConsoleOpen(open: boolean) {
    consoleOpen.value = open
    // Auch ohne laufenden Server einmal den letzten Stand zeigen.
    if (open && server.value && server.value.state !== 'installing') void fetchLogs()
    syncLive()
  }

  function hookVisibility() {
    if (visibilityHooked || typeof document === 'undefined') return
    visibilityHooked = true
    document.addEventListener('visibilitychange', () => syncLive())
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
    hookVisibility()
    void listenForChanges()
    await load()
    schedule()
    syncLive()
  }

  function unwatch() {
    watching = false
    if (pollTimer) clearTimeout(pollTimer)
    pollTimer = null
    syncLive()
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
    settings.value = null
    logs.value = []
    modsTransfer.value = null
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
    settings,
    stats,
    logs,
    consoleOpen,
    modsTransfer,
    worldProgress,
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
    rename,
    loadSettings,
    saveSettings,
    transferMods,
    uploadWorld,
    setConsoleOpen,
    command,
    join,
    watch,
    unwatch,
    reset,
  }
})
