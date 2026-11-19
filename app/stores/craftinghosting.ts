import { isTauri } from '@tauri-apps/api/core'
import { listen } from '@tauri-apps/api/event'
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
// Relativ importiert, damit Tests den Store ohne Nuxt laden können (Instanzen, Spiele,
// Beitreten und Toasts kommen in der App über Nuxts Auto-Imports, im Test als Attrappen).
import { BackendError, backend, errorMessage, isCancelled } from '../utils/backend'
import {
  type CraftingAccess,
  type CraftingBackup,
  type CraftingBackups,
  type CraftingInfo,
  type VersionChange,
  type CraftingModsResult,
  type CraftingServer,
  type CraftingSettings,
  type CraftingSettingsPatch,
  type CraftingStats,
  type HostingOrigin,
  type NewCraftingServer,
  type ServerAddons,
  type ServerProperties,
  type ServerWorlds,
  type SubdomainCheck,
  type WorldDownload,
  type WorldUploadTarget,
  type WorldUploadProgress,
  NO_FEATURES,
  ORIGINS_KEY,
  POLL_FAST_MS,
  cleanHostingCommand,
  hostingBlockedReason,
  joinVersion,
  modrinthProjects,
  parseOrigins,
  pollInterval,
  serverAddress,
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

type Busy =
  | 'create'
  | 'start'
  | 'stop'
  | 'delete'
  | 'access'
  | 'command'
  | 'rename'
  | 'address'
  | 'settings'
  | 'properties'
  | 'mods'
  | 'addons'
  | 'world'
  | 'version'
  | 'backup'

/** So lange wartet „Inhalte übernehmen“ aufs Stoppen. */
const STOP_GIVE_UP_MS = 2 * 60_000

export type TransferStep = 'stop' | 'mods' | 'world' | 'start'
export type TransferStatus = 'pending' | 'running' | 'done' | 'skipped' | 'failed'
export interface TransferRun {
  steps: Record<TransferStep, TransferStatus>
  mods: ModsTransfer | null
  active: boolean
}

/** Ergebnis der Adress-Prüfung; `dns` = eigene Adressen gibt es hier (noch) nicht. */
export type SubdomainAnswer = { kind: 'ok'; check: SubdomainCheck } | { kind: 'dns' } | { kind: 'error' }

function readOrigins(): Record<string, HostingOrigin> {
  try {
    return parseOrigins(localStorage.getItem(ORIGINS_KEY))
  } catch {
    return {}
  }
}

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
  const settingsPending = ref(false)
  const stats = ref<CraftingStats | null>(null)
  const logs = ref<string[]>([])
  /** Konsole sichtbar: Log im 3-s-Takt, solange der Server läuft. */
  const consoleOpen = ref(false)
  const modsTransfer = ref<ModsTransfer | null>(null)
  const worldProgress = ref<WorldUploadProgress | null>(null)
  /** Aus welcher Instanz ein Server angelegt wurde (nur lokal). */
  const origins = ref<Record<string, HostingOrigin>>(readOrigins())
  const transfer = ref<TransferRun | null>(null)
  /** Alle Schlüssel aus server.properties; `propertiesPending` = erst nach dem ersten Start. */
  const properties = ref<ServerProperties | null>(null)
  const propertiesPending = ref(false)
  const addons = ref<ServerAddons | null>(null)
  const addonsLoading = ref(false)
  /** Sprungziel für den Dateien-Reiter; `filesJump` zählt hoch, damit derselbe Ordner erneut greift. */
  const filesDir = ref('/')
  const filesJump = ref(0)
  /** Name der Datei mit ungespeicherten Änderungen im Editor (Rückfrage vor dem Verlassen). */
  const filesDirty = ref<string | null>(null)
  const worlds = ref<ServerWorlds | null>(null)
  const worldsLoading = ref(false)
  /** Laufender Welt-Download (Fortschritt, Abbrechen über `taskId`). */
  const worldDownload = ref<{ name: string; taskId: string; progress: WorldUploadProgress | null } | null>(null)
  let joinAfterEula = false
  let pollTimer: ReturnType<typeof setTimeout> | null = null
  let statsTimer: ReturnType<typeof setInterval> | null = null
  let logsTimer: ReturnType<typeof setInterval> | null = null
  let watching = false
  let listening = false
  let visibilityHooked = false

  const needsEula = computed(() => !!server.value && !server.value.eulaAccepted)
  /** Welche Bereiche die TRS API gerade freigibt (ohne Info: nichts). */
  const features = computed(() => info.value?.features ?? NO_FEATURES)
  const backups = ref<CraftingBackups | null>(null)
  const backupsLoading = ref(false)
  const backupDownload = ref<{ id: string; taskId: string; progress: WorldUploadProgress | null } | null>(null)
  let lastUnavailable = 0
  const origin = computed(() => (server.value ? (origins.value[server.value.id] ?? null) : null))

  function saveOrigins(next: Record<string, HostingOrigin>) {
    origins.value = next
    try {
      localStorage.setItem(ORIGINS_KEY, JSON.stringify(next))
    } catch {
      // Ohne Speicher gilt die Zuordnung nur bis zum Neustart.
    }
  }

  /** Karte „Inhalte übernehmen“ ausblenden. */
  function dismissOrigin() {
    const id = server.value?.id
    const current = id ? origins.value[id] : null
    if (id && current) saveOrigins({ ...origins.value, [id]: { ...current, transferred: true } })
  }

  /** Vorwarnung vor dem Stopp durch das Laufzeit-Limit: einmal, bis wieder genug Zeit da ist. */
  let quotaWarned = false
  const shownAlerts = new Set<string>()
  function warnQuota(next: CraftingServer | null) {
    if (next?.display) {
      for (const a of next.display.alerts) {
        if (shownAlerts.has(a.id)) continue
        shownAlerts.add(a.id)
        const text = fillDisplay(pickText(a.text, currentLocale.value), 0, intlLocale())
        if (a.level === 'warning') useToasts().error(text)
        else useToasts().info(text)
      }
      return
    }
    const warn = (info.value?.rules?.warnMinutes ?? 0) * 60
    const left = next?.quota?.remainingSeconds
    if (!warn || left === undefined || !next?.session.active) return
    if (left > warn) {
      quotaWarned = false
      return
    }
    if (quotaWarned || left <= 0) return
    quotaWarned = true
    useToasts().info(t('craftinghosting.quota.warn', { minutes: Math.max(1, Math.ceil(left / 60)) }))
  }

  function setServer(next: CraftingServer | null) {
    warnQuota(next)
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
    // Vom Team gesteuert: eigene Texte mit den Details, danach Info bzw. Server neu holen.
    if (code === 'hosting_maintenance') {
      const message = e instanceof BackendError ? e.params?.message : undefined
      useToasts().error(message ? t('craftinghosting.team.maintenanceWith', { message }) : t('errors.trsApi.hosting_maintenance'))
      void reloadInfo()
      return
    }
    if (code === 'server_blocked') {
      const reason = e instanceof BackendError ? e.params?.reason : undefined
      useToasts().error(reason ? t('craftinghosting.team.blockedWith', { reason }) : t('errors.trsApi.server_blocked'))
      void refresh()
      return
    }
    if (code === 'feature_disabled') {
      useToasts().info(t('errors.trsApi.feature_disabled'))
      void reloadInfo()
      return
    }
    // Schalter auf dem Server aus: freundlicher Hinweis (höchstens alle 30 s) und Info neu holen.
    if (code === 'feature_unavailable') {
      notAvailable()
      return
    }
    // Welt-Name: was erlaubt ist, entscheidet die API (z. B. reservierte Ordnernamen).
    if (code === 'invalid_request' && busy.value === 'world') {
      useToasts().error(t('craftinghosting.worlds.nameRejected'))
      return
    }
    useToasts().error(e)
  }

  function notAvailable() {
    const now = Date.now()
    if (now - lastUnavailable > 30_000) useToasts().info(t('craftinghosting.notAvailable'))
    lastUnavailable = now
    void reloadInfo()
  }

  async function reloadInfo() {
    try {
      info.value = await backend.craftinghosting.info()
    } catch {
      // bleibt beim alten Stand
    }
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

  /** Anlegen; mit `instanceId` merkt sich der Launcher die Instanz fürs spätere Übernehmen. */
  async function create(spec: NewCraftingServer, instanceId?: string): Promise<boolean> {
    const created = await run('create', () => backend.craftinghosting.create(spec))
    if (created) {
      setServer(created)
      if (instanceId) saveOrigins({ ...origins.value, [created.id]: { instanceId, transferred: false } })
    }
    schedule()
    return !!created
  }

  /** Ist die Adresse frei? Fehler landen nicht als Toast im Tippen. */
  async function checkSubdomain(name: string): Promise<SubdomainAnswer> {
    try {
      return { kind: 'ok', check: await backend.craftinghosting.checkSubdomain(name) }
    } catch (e) {
      return apiCode(e) === 'dns_unavailable' ? { kind: 'dns' } : { kind: 'error' }
    }
  }

  async function setSubdomain(name: string): Promise<boolean> {
    const updated = await run('address', () => backend.craftinghosting.setSubdomain(name))
    if (updated) {
      setServer(updated)
      useToasts().ok(t('craftinghosting.address.changed'))
    }
    return !!updated
  }

  /** Wartung oder vom Team gesperrt: Hinweis statt Anfrage. */
  function teamStop(): boolean {
    const why = hostingBlockedReason(info.value, server.value)
    if (!why) return false
    useToasts().error(why === 'maintenance' ? t('craftinghosting.team.maintenance') : t('errors.trsApi.server_blocked'))
    return true
  }

  /** Start-Knopf: vor dem ersten Start erst die EULA. */
  async function requestStart(): Promise<boolean> {
    if (!server.value || teamStop()) return false
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

  async function stop(): Promise<boolean> {
    const stopped = await run('stop', () => backend.craftinghosting.stop())
    if (stopped) setServer(stopped)
    schedule()
    return !!stopped
  }

  async function remove(): Promise<boolean> {
    const done = await run('delete', async () => {
      await backend.craftinghosting.remove()
      return true
    })
    if (done) {
      const id = server.value?.id
      if (id && origins.value[id]) saveOrigins(Object.fromEntries(Object.entries(origins.value).filter(([k]) => k !== id)))
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
    settingsPending.value = false
    try {
      settings.value = await backend.craftinghosting.settings()
    } catch (e) {
      settings.value = null
      // Der Partner liefert Einstellungen erst nach dem ersten Start.
      if (apiCode(e) === 'server_installing') settingsPending.value = true
      else report(e)
    }
  }

  async function saveSettings(patch: CraftingSettingsPatch): Promise<boolean> {
    if (!Object.keys(patch).length) return true
    const saved = await run('settings', () => backend.craftinghosting.saveSettings(patch))
    if (saved) {
      settings.value = saved
      useToasts().ok(t('craftinghosting.settings.saved'))
      if (properties.value) void loadProperties()
    }
    return !!saved
  }

  async function loadProperties() {
    propertiesPending.value = false
    try {
      properties.value = await backend.craftinghosting.properties()
    } catch (e) {
      properties.value = null
      if (apiCode(e) === 'server_installing') propertiesPending.value = true
      else report(e)
    }
  }

  /** Nur geänderte Schlüssel; danach auch die Schnell-Einstellungen neu holen. */
  async function saveProperties(changes: Record<string, string>): Promise<boolean> {
    if (!Object.keys(changes).length) return true
    const saved = await run('properties', () => backend.craftinghosting.saveProperties(changes))
    if (saved) {
      properties.value = saved
      useToasts().ok(t('craftinghosting.settings.saved'))
      if (settings.value) void loadSettings()
    }
    return !!saved
  }

  // --- Plugins/Mods und Dateien ----------------------------------------------------------

  async function loadAddons() {
    addonsLoading.value = true
    try {
      addons.value = await backend.craftinghosting.addons()
    } catch (e) {
      addons.value = null
      if (apiCode(e) !== 'server_installing') report(e)
    } finally {
      addonsLoading.value = false
    }
  }

  async function removeAddon(file: string): Promise<boolean> {
    const done = await run('addons', async () => {
      await backend.craftinghosting.removeAddon(file)
      return true
    })
    if (done) {
      if (addons.value) addons.value = { ...addons.value, items: addons.value.items.filter((a) => a.file !== file) }
      useToasts().ok(t('craftinghosting.addons.removed', { file }))
    }
    return !!done
  }

  /** Modrinth-Projekte installieren; Ergebnis (installiert/übersprungen) für die Anzeige. */
  async function installAddons(projects: string[]): Promise<CraftingModsResult | null> {
    const result = await run('mods', () => backend.craftinghosting.mods(projects))
    if (result) void loadAddons()
    return result
  }

  /** Eine bestimmte Version installieren (z. B. „Andere Version“). */
  async function installVersion(project: string, version: string): Promise<CraftingModsResult | null> {
    const result = await run('mods', () => backend.craftinghosting.installVersions([{ project, version }]))
    if (result) void loadAddons()
    return result
  }

  /** Dateien-Reiter auf diesen Ordner stellen (z. B. Konfiguration eines Plugins). */
  function openFiles(dir: string) {
    filesDir.value = dir
    filesJump.value++
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

  /** Welt einer Instanz einspielen; ohne `target` ersetzt sie die Welt `world`. */
  async function uploadWorld(instanceId: string, folder: string, target?: WorldUploadTarget): Promise<boolean> {
    worldProgress.value = { phase: 'zip', percent: 0 }
    try {
      const onProgress = (p: WorldUploadProgress) => (worldProgress.value = p)
      const updated = await run('world', () =>
        target
          ? backend.craftinghosting.uploadWorld(instanceId, folder, onProgress, target)
          : backend.craftinghosting.uploadWorld(instanceId, folder, onProgress),
      )
      if (updated) {
        setServer(updated)
        useToasts().ok(t('craftinghosting.world.done'))
        if (worlds.value) void loadWorlds()
      }
      return !!updated
    } finally {
      worldProgress.value = null
    }
  }

  /** Welt-ZIP oder Weltordner von der Festplatte (Dialog im Kern). */
  async function uploadWorldDisk(kind: 'zip' | 'folder', target: WorldUploadTarget): Promise<boolean> {
    worldProgress.value = null
    try {
      const updated = await run('world', () => backend.craftinghosting.uploadWorldDisk(kind, target, (p) => (worldProgress.value = p)))
      if (updated) {
        setServer(updated)
        useToasts().ok(t('craftinghosting.world.done'))
        void loadWorlds()
      }
      return !!updated
    } finally {
      worldProgress.value = null
    }
  }

  // --- Welten ---------------------------------------------------------------------------

  async function loadWorlds() {
    worldsLoading.value = true
    try {
      worlds.value = await backend.craftinghosting.worlds()
    } catch (e) {
      worlds.value = null
      if (apiCode(e) !== 'server_installing') report(e)
    } finally {
      worldsLoading.value = false
    }
  }

  async function setActiveWorld(name: string): Promise<boolean> {
    const next = await run('world', () => backend.craftinghosting.setActiveWorld(name))
    if (next) {
      worlds.value = next
      useToasts().ok(t('craftinghosting.worlds.activated', { name }))
    }
    return !!next
  }

  /** Löschen; bei der aktiven Welt heißt das „zurücksetzen“ (neu beim nächsten Start). */
  async function deleteWorld(name: string): Promise<boolean> {
    const active = worlds.value?.active === name
    const next = await run('world', () => backend.craftinghosting.deleteWorld(name))
    if (next) {
      worlds.value = next
      useToasts().ok(t(active ? 'craftinghosting.worlds.reset' : 'craftinghosting.worlds.deleted', { name }))
    }
    return !!next
  }

  async function downloadWorld(name: string): Promise<WorldDownload | null> {
    if (worldDownload.value || !server.value) return null
    const job = { name, taskId: `craftinghosting-world:${Date.now()}`, progress: null as WorldUploadProgress | null }
    worldDownload.value = job
    try {
      const result = await backend.craftinghosting.downloadWorld(name, server.value.name, (p) => {
        if (worldDownload.value) worldDownload.value = { ...worldDownload.value, progress: p }
      }, job.taskId)
      if (result) useToasts().ok(t(result.truncated ? 'craftinghosting.worlds.downloadedPartly' : 'craftinghosting.worlds.downloaded', { file: result.fileName }))
      return result
    } catch (e) {
      if (!isCancelled(e)) report(e)
      return null
    } finally {
      worldDownload.value = null
    }
  }

  function cancelWorldDownload() {
    const id = worldDownload.value?.taskId
    if (id) void backend.cancelTask(id).catch(() => {})
  }

  // --- Versionswechsel und Backups (nur mit Schalter) ------------------------------------

  /** Typ/Version wechseln; der Server startet danach neu – wie nach einem Start nachfragen. */
  async function changeVersion(change: VersionChange): Promise<boolean> {
    try {
      const updated = await run('version', () => backend.craftinghosting.changeVersion(change))
      if (updated) {
        setServer(updated)
        useToasts().ok(t('craftinghosting.version.changed'))
      }
      return !!updated
    } finally {
      schedule()
    }
  }

  async function loadBackups() {
    backupsLoading.value = true
    try {
      backups.value = await backend.craftinghosting.backups()
    } catch (e) {
      backups.value = null
      if (apiCode(e) === 'feature_unavailable') void reloadInfo()
      else if (apiCode(e) !== 'server_installing') report(e)
    } finally {
      backupsLoading.value = false
    }
  }

  async function createBackup(): Promise<boolean> {
    const done = await run('backup', async () => {
      await backend.craftinghosting.createBackup()
      return true
    })
    if (done) {
      useToasts().ok(t('craftinghosting.backups.created'))
      void loadBackups()
    }
    return !!done
  }

  async function restoreBackup(backup: CraftingBackup): Promise<boolean> {
    const done = await run('backup', async () => {
      await backend.craftinghosting.restoreBackup(backup.id)
      return true
    })
    if (done) {
      useToasts().ok(t('craftinghosting.backups.restored', { name: backup.name }))
      void refresh()
      if (worlds.value) void loadWorlds()
    }
    return !!done
  }

  async function deleteBackup(backup: CraftingBackup): Promise<boolean> {
    const done = await run('backup', async () => {
      await backend.craftinghosting.deleteBackup(backup.id)
      return true
    })
    if (done && backups.value) backups.value = { ...backups.value, backups: backups.value.backups.filter((b) => b.id !== backup.id) }
    return !!done
  }

  async function downloadBackup(backup: CraftingBackup): Promise<string | null> {
    if (backupDownload.value || !server.value) return null
    const job = { id: backup.id, taskId: `craftinghosting-backup:${Date.now()}`, progress: null as WorldUploadProgress | null }
    backupDownload.value = job
    try {
      const file = await backend.craftinghosting.downloadBackup(backup.id, backup.name, server.value.name, (p) => {
        if (backupDownload.value) backupDownload.value = { ...backupDownload.value, progress: p }
      }, job.taskId)
      if (file) useToasts().ok(t('craftinghosting.backups.downloaded', { file }))
      return file
    } catch (e) {
      if (!isCancelled(e)) report(e)
      return null
    } finally {
      backupDownload.value = null
    }
  }

  function cancelBackupDownload() {
    const id = backupDownload.value?.taskId
    if (id) void backend.cancelTask(id).catch(() => {})
  }

  /** Stoppen und warten, bis der Server aus ist (für Welt-Änderungen). */
  async function stopAndWait(): Promise<boolean> {
    const state = server.value?.state
    if (state === 'offline' || state === 'failed') return true
    if (state !== 'stopping' && !(await stop())) return false
    return waitForOffline()
  }

  /**
   * Inhalte der Ursprungs-Instanz auf den Server: stoppen → Mods → Welt → wieder starten.
   * Bricht beim ersten Fehler ab; der Schritt steht dann auf `failed`.
   */
  async function transferFromInstance(opts: { instanceId: string; mods: boolean; world: string | null }): Promise<boolean> {
    if (!server.value || transfer.value?.active || busy.value) return false
    const steps: Record<TransferStep, TransferStatus> = {
      stop: 'pending',
      mods: opts.mods ? 'pending' : 'skipped',
      world: opts.world ? 'pending' : 'skipped',
      start: 'pending',
    }
    transfer.value = { steps, mods: null, active: true }
    const job = transfer.value
    const step = async (id: TransferStep, action: () => Promise<boolean>) => {
      job.steps[id] = 'running'
      const ok = await action()
      job.steps[id] = ok ? 'done' : 'failed'
      return ok
    }
    try {
      const state = server.value.state
      if (state === 'running' || state === 'starting' || state === 'stopping') {
        const stopped = await step('stop', async () => {
          if (state !== 'stopping' && !(await stop())) return false
          return waitForOffline()
        })
        if (!stopped) return false
      } else {
        job.steps.stop = 'skipped'
      }
      if (opts.mods) {
        const ok = await step('mods', async () => {
          job.mods = await transferMods(opts.instanceId)
          return !!job.mods
        })
        if (!ok) return false
      }
      if (opts.world) {
        const world = opts.world
        if (!(await step('world', () => uploadWorld(opts.instanceId, world)))) return false
      }
      const started = await step('start', () => start(false))
      if (started) dismissOrigin()
      return started
    } finally {
      job.active = false
      schedule()
    }
  }

  async function waitForOffline(): Promise<boolean> {
    const until = Date.now() + STOP_GIVE_UP_MS
    while (Date.now() < until) {
      const state = server.value?.state
      if (state === 'offline' || state === 'failed') return true
      if (!state) return false
      await new Promise((r) => setTimeout(r, POLL_FAST_MS))
      await refresh()
    }
    return false
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
    if (!server.value || joining.value || teamStop()) return
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
      const address = serverAddress(server.value)
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
      // Nach dem ersten Start richtet die API ein und startet einmal neu – so lange warten.
      if (server.value?.setup === 'restarting') continue
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
    transfer.value = null
    properties.value = null
    propertiesPending.value = false
    addons.value = null
    filesDir.value = '/'
    worlds.value = null
    backups.value = null
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
    origin,
    transfer,
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
    checkSubdomain,
    setSubdomain,
    dismissOrigin,
    transferFromInstance,
    loadSettings,
    settingsPending,
    saveSettings,
    properties,
    propertiesPending,
    loadProperties,
    saveProperties,
    addons,
    addonsLoading,
    loadAddons,
    removeAddon,
    installAddons,
    installVersion,
    filesDir,
    filesJump,
    filesDirty,
    openFiles,
    worlds,
    worldsLoading,
    worldDownload,
    loadWorlds,
    setActiveWorld,
    deleteWorld,
    downloadWorld,
    cancelWorldDownload,
    uploadWorldDisk,
    stopAndWait,
    features,
    changeVersion,
    backups,
    backupsLoading,
    backupDownload,
    loadBackups,
    createBackup,
    restoreBackup,
    deleteBackup,
    downloadBackup,
    cancelBackupDownload,
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
