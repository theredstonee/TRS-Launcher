import { isTauri } from '@tauri-apps/api/core'
import { listen } from '@tauri-apps/api/event'
import { defineStore } from 'pinia'
import { computed, ref, shallowRef } from 'vue'
// Relativ importiert, damit Tests den Store ohne Nuxt laden können.
import { backend } from '../utils/backend'
import {
  type LocalServer,
  type LocalServerStatus,
  type ShareStatus,
  appendLog,
  localServerEventSchema,
  offShare,
  shareUpdateSchema,
} from '../utils/serverExport'
import { taskKey } from '../utils/tasks'
import { useTasksStore } from './tasks'
import { useToasts } from './toasts'

/** So viele Log-Zeilen hält die Oberfläche je Server. */
const MAX_LOG_LINES = 5000

/**
 * Lokale Minecraft-Server (Server-Export „lokal anlegen und starten“): Liste,
 * Live-Log und Zustand aus dem Event `local-server`. Laufen lässt sie der Kern;
 * beim Beenden des Launchers stoppt er sie sauber.
 */
export const useLocalServersStore = defineStore('localServers', () => {
  const items = ref<LocalServer[]>([])
  const loaded = ref(false)
  /** Log je Server (flach reaktiv – die Arrays werden ersetzt, nicht verändert). */
  const logs = shallowRef<Record<string, string[]>>({})
  /** Server, deren Log schon vom Kern geholt wurde. */
  const logsLoaded = new Set<string>()
  /** Teilen je Server (TRS Relay, e4mc) – aus dem Event `local-server-share`. */
  const shares = ref<Record<string, ShareStatus>>({})
  let initialized = false

  const running = computed(() => items.value.filter((s) => s.status.state !== 'stopped'))

  async function init() {
    if (initialized || !isTauri()) return
    initialized = true
    await listen<unknown>('local-server', (e) => onEvent(e.payload))
    await listen<unknown>('local-server-share', (e) => {
      const parsed = shareUpdateSchema.safeParse(e.payload)
      if (parsed.success) shares.value = { ...shares.value, [parsed.data.id]: parsed.data.status }
    })
    await refresh().catch(() => {})
  }

  async function refresh() {
    items.value = await backend.localServers.list()
    loaded.value = true
  }

  function setStatus(id: string, status: LocalServerStatus) {
    const index = items.value.findIndex((s) => s.id === id)
    if (index < 0) {
      // Neuer Server (gerade angelegt) – Liste neu holen.
      void refresh().catch(() => {})
      return
    }
    const next = items.value.slice()
    next[index] = { ...next[index]!, status }
    items.value = next
  }

  function onEvent(payload: unknown) {
    const parsed = localServerEventSchema.safeParse(payload)
    if (!parsed.success) return
    const event = parsed.data
    if (event.type === 'logs') {
      logs.value = { ...logs.value, [event.id]: appendLog(logs.value[event.id] ?? [], event.lines, MAX_LOG_LINES) }
    } else {
      if (event.status.state === 'starting' && !logsLoaded.has(event.id)) {
        // Neuer Lauf: altes Log verwerfen.
        logs.value = { ...logs.value, [event.id]: [] }
        logsLoaded.add(event.id)
      }
      setStatus(event.id, event.status)
    }
  }

  /** Log vom Kern holen (laufender oder letzter Lauf) – einmal je Server. */
  async function loadLogs(id: string) {
    if (logsLoaded.has(id)) return
    logsLoaded.add(id)
    try {
      // Der Kern hat das ganze Log des Laufs (auch was schon per Event kam) – ersetzen statt anhängen.
      const lines = await backend.localServers.logs(id)
      logs.value = { ...logs.value, [id]: lines.slice(-MAX_LOG_LINES) }
    } catch (e) {
      logsLoaded.delete(id)
      useToasts().error(e)
    }
  }

  function get(id: string): LocalServer | null {
    return items.value.find((s) => s.id === id) ?? null
  }

  /** Startet (lädt bei Bedarf Java – sichtbar in den Aufgaben, abbrechbar). */
  async function start(id: string) {
    const server = get(id)
    // Neuer Lauf: Log leeren, das Event `starting` setzt es nicht noch einmal zurück.
    logs.value = { ...logs.value, [id]: [] }
    logsLoaded.add(id)
    const result = await useTasksStore().run(
      { key: taskKey('server-start', id), kind: 'server-export', title: server?.name ?? id, cancellable: true, pausable: false, record: false, notify: false },
      async (ctx) => {
        const status = await backend.localServers.start(id, ctx.taskId)
        setStatus(id, status)
        return status
      },
    )
    if (!result.ok && !result.cancelled && result.error) useToasts().error(result.error)
  }

  async function act(work: () => Promise<unknown>) {
    try {
      await work()
    } catch (e) {
      useToasts().error(e)
    }
  }

  function shareOf(id: string): ShareStatus {
    return shares.value[id] ?? offShare
  }

  async function loadShare(id: string) {
    try {
      shares.value = { ...shares.value, [id]: await backend.localServers.shareStatus(id) }
    } catch {
      // egal – der Stand kommt auch per Event
    }
  }

  /** TRS Relay bzw. e4mc an/aus. `true` = geklappt. */
  async function setShared(id: string, kind: 'relay' | 'e4mc', on: boolean): Promise<boolean> {
    try {
      const status = on ? await backend.localServers.share(id, kind) : await backend.localServers.unshare(id, kind)
      shares.value = { ...shares.value, [id]: status }
      return true
    } catch (e) {
      useToasts().error(e)
      void loadShare(id)
      return false
    }
  }

  const stop = (id: string) => act(() => backend.localServers.stop(id))
  const kill = (id: string) => act(() => backend.localServers.kill(id))
  const openFolder = (id: string) => act(() => backend.localServers.openFolder(id))

  async function restart(id: string) {
    await act(async () => setStatus(id, await backend.localServers.restart(id)))
  }

  /** Befehl an die Konsole; `false` bei Fehler (Text bleibt dann stehen). */
  async function command(id: string, text: string): Promise<boolean> {
    try {
      await backend.localServers.command(id, text)
      return true
    } catch (e) {
      useToasts().error(e)
      return false
    }
  }

  async function remove(id: string) {
    await act(async () => {
      await backend.localServers.remove(id)
      items.value = items.value.filter((s) => s.id !== id)
      const { [id]: _gone, ...rest } = logs.value
      logs.value = rest
      logsLoaded.delete(id)
    })
  }

  return {
    items,
    loaded,
    logs,
    shares,
    running,
    init,
    refresh,
    onEvent,
    loadLogs,
    get,
    start,
    stop,
    kill,
    restart,
    command,
    remove,
    openFolder,
    shareOf,
    loadShare,
    setShared,
  }
})
