import { isTauri } from '@tauri-apps/api/core'
import { listen } from '@tauri-apps/api/event'
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
// Relativ importiert, damit tests/tasks.test.ts den Store ohne Nuxt laden kann.
import type { NewTaskRecord, TaskKind, TaskProgressEvent, TaskRecord } from '../types'
import { BackendError, backend, errorMessage, isCancelled } from '../utils/backend'
import { SpeedMeter } from '../utils/tasks'
import { useToasts } from './toasts'

/**
 * Hintergrund-Aufgaben (Modpacks, Java, Importe, Spielstart …) gehören diesem
 * Store – nicht der Seite, die sie gestartet hat. Seiten starten Aufgaben
 * über `run()` und lesen den Zustand per `get(key)`; Verlassen der Seite
 * verliert oder beendet also nichts. Fortschritt kommt über die Rückrufe der
 * Aufgabe, Bytes/Geschwindigkeit über das Event `task-progress` aus dem Kern.
 */

export type TaskStatus = 'running' | 'done' | 'failed' | 'cancelled'

export interface Task {
  /** Eindeutig je Vorgang (z. B. `modpack:<projekt>`); zugleich die Aufgaben-ID im Kern. */
  key: string
  kind: TaskKind
  title: string
  /** Was gerade passiert („Mods werden geladen“). */
  stage: string
  /** 0–100; `null` = unbestimmt. */
  percent: number | null
  status: TaskStatus
  error: string | null
  startedAt: number
  finishedAt: number | null
  instanceId: string | null
  iconUrl: string | null
  /** Freies Merkmal für Seiten, z. B. die installierte Versions-ID. */
  tag: string | null
  cancellable: boolean
  pausable: boolean
  paused: boolean
  cancelling: boolean
  doneBytes: number
  totalBytes: number
  /** Bytes pro Sekunde (gleitend gemessen). */
  speed: number
  /** Eintrag im Verlauf, sobald die Aufgabe fertig ist. */
  recordId: string | null
}

export interface TaskSpec {
  key: string
  kind: TaskKind
  title: string
  stage?: string
  instanceId?: string | null
  iconUrl?: string | null
  tag?: string | null
  cancellable?: boolean
  pausable?: boolean
  /** Im Verlauf („Fertig“) festhalten – Standard: ja. */
  record?: boolean
  /** Toast am Ende – Standard: ja. */
  notify?: boolean
  /** Text des Erfolgs-Toasts (Standard: „<Titel> ist fertig“). */
  doneText?: string
}

type Patch = Partial<Pick<Task, 'title' | 'stage' | 'instanceId' | 'iconUrl' | 'tag' | 'cancellable' | 'pausable'>>

export interface TaskContext {
  /** ID für den Kern (`taskId` der Commands). */
  readonly taskId: string
  progress(percent: number | null, stage?: string): void
  update(patch: Patch & { doneText?: string }): void
  /** Wurde „Abbrechen“ gedrückt? Für Schleifen im Frontend. */
  cancelled(): boolean
  /** Ohne Spuren verwerfen – etwa wenn der Dateidialog abgebrochen wurde. */
  discard(): void
}

export type TaskResult<T> =
  | { ok: true; value: T }
  | { ok: false; cancelled: boolean; discarded: boolean; error: unknown }

/** Wirft den Abbruch-Fehler, den auch der Kern liefert. */
export function cancelledError(): BackendError {
  return new BackendError('cancelled', 'Vorgang abgebrochen')
}

export const useTasksStore = defineStore('tasks', () => {
  const tasks = ref<Record<string, Task>>({})
  const history = ref<TaskRecord[]>([])
  const panelOpen = ref(false)
  const focused = ref<string | null>(null)

  const pending = new Map<string, Promise<TaskResult<unknown>>>()
  const meters = new Map<string, SpeedMeter>()
  /** „Erneut versuchen“ – nur für Fehlschläge dieser Sitzung. */
  const retries = new Map<string, () => void>()
  let navigate: ((path: string) => void) | null = null
  let initialized = false
  let ticker: ReturnType<typeof setInterval> | undefined

  const active = computed(() =>
    Object.values(tasks.value)
      .filter((t) => t.status === 'running')
      .sort((a, b) => a.startedAt - b.startedAt),
  )
  const totalSpeed = computed(() => active.value.reduce((sum, t) => sum + (t.paused ? 0 : t.speed), 0))

  function get(key: string): Task | null {
    return tasks.value[key] ?? null
  }

  function isRunning(key: string): boolean {
    return tasks.value[key]?.status === 'running'
  }

  function openPanel(key: string | null = null) {
    focused.value = key
    panelOpen.value = true
  }

  function closePanel() {
    panelOpen.value = false
    focused.value = null
  }

  function openInstance(id: string) {
    closePanel()
    navigate?.(`/instances/${id}`)
  }

  /** Geschwindigkeit fällt ab, wenn keine neuen Bytes mehr kommen. */
  function tick() {
    const now = Date.now()
    for (const t of active.value) {
      const meter = meters.get(t.key)
      if (meter && !t.paused) t.speed = meter.add(t.doneBytes, now)
    }
    if (!active.value.length) stopTicker()
  }

  function startTicker() {
    ticker ??= setInterval(tick, 1000)
  }

  function stopTicker() {
    if (ticker !== undefined) clearInterval(ticker)
    ticker = undefined
  }

  function onProgress(e: TaskProgressEvent) {
    const t = tasks.value[e.taskId]
    if (!t || t.status !== 'running') return
    t.doneBytes = e.doneBytes
    t.totalBytes = e.totalBytes
    t.paused = e.paused
    const meter = meters.get(t.key)
    if (e.paused) {
      meter?.reset()
      t.speed = 0
    } else if (meter) {
      t.speed = meter.add(e.doneBytes, Date.now())
    }
  }

  async function addRecord(record: NewTaskRecord): Promise<TaskRecord> {
    let saved: TaskRecord
    try {
      saved = await backend.recordTask(record)
    } catch {
      // Ohne Kern (Browser-Vorschau) nur für diese Sitzung.
      saved = {
        id: `local${Date.now().toString(16)}${Math.floor(Math.random() * 1e6).toString(16)}`,
        kind: record.kind,
        title: record.title,
        outcome: record.outcome,
        finishedAt: new Date().toISOString(),
        ...(record.instanceId ? { instanceId: record.instanceId } : {}),
        ...(record.iconUrl ? { iconUrl: record.iconUrl } : {}),
        ...(record.detail ? { detail: record.detail } : {}),
      }
    }
    history.value = [saved, ...history.value.filter((r) => r.id !== saved.id)].slice(0, 50)
    return saved
  }

  /** Sofortige Ereignisse (neue Instanz angelegt) direkt in den Verlauf. */
  function note(record: NewTaskRecord) {
    addRecord(record).catch(() => {})
  }

  function notify(t: Task, spec: TaskSpec, doneText: string | undefined) {
    if (spec.notify === false) return
    const toasts = useToasts()
    if (t.status === 'done') {
      const id = t.instanceId
      toasts.ok(
        doneText ?? `${t.title} ist fertig`,
        id ? { label: 'Instanz öffnen', run: () => openInstance(id) } : { label: 'Aufgaben anzeigen', run: () => openPanel() },
      )
    } else if (t.status === 'failed') {
      toasts.error(`${t.title}: ${t.error ?? 'fehlgeschlagen'}`, { label: 'Details', run: () => openPanel(t.key) })
    }
  }

  function run<T>(spec: TaskSpec, work: (ctx: TaskContext) => Promise<T>): Promise<TaskResult<T>> {
    const running = pending.get(spec.key)
    if (running && tasks.value[spec.key]?.status === 'running') {
      // Derselbe Vorgang läuft schon – nicht doppelt starten, sondern zeigen.
      openPanel(spec.key)
      return running as Promise<TaskResult<T>>
    }

    tasks.value[spec.key] = {
      key: spec.key,
      kind: spec.kind,
      title: spec.title,
      stage: spec.stage ?? 'Wird vorbereitet',
      percent: null,
      status: 'running',
      error: null,
      startedAt: Date.now(),
      finishedAt: null,
      instanceId: spec.instanceId ?? null,
      iconUrl: spec.iconUrl ?? null,
      tag: spec.tag ?? null,
      cancellable: spec.cancellable ?? false,
      pausable: spec.pausable ?? false,
      paused: false,
      cancelling: false,
      doneBytes: 0,
      totalBytes: 0,
      speed: 0,
      recordId: null,
    }
    // Über den Store-Proxy ändern, damit alles reaktiv bleibt.
    const t = tasks.value[spec.key]!
    meters.set(spec.key, new SpeedMeter())
    startTicker()

    let discarded = false
    let doneText = spec.doneText
    const ctx: TaskContext = {
      taskId: spec.key,
      progress(percent, stage) {
        if (t.status !== 'running') return
        t.percent = percent === null ? null : Math.max(0, Math.min(100, Math.floor(percent)))
        if (stage) t.stage = stage
      },
      update(patch) {
        const { doneText: text, ...rest } = patch
        if (text !== undefined) doneText = text
        Object.assign(t, rest)
      },
      cancelled: () => t.cancelling,
      discard() {
        discarded = true
      },
    }

    const promise = (async (): Promise<TaskResult<T>> => {
      try {
        const value = await work(ctx)
        if (discarded) {
          delete tasks.value[spec.key]
          return { ok: false, cancelled: true, discarded: true, error: null }
        }
        t.status = 'done'
        t.percent = 100
        t.finishedAt = Date.now()
        notify(t, spec, doneText)
        if (spec.record !== false) await remember(t, spec)
        return { ok: true, value }
      } catch (e) {
        if (discarded) {
          delete tasks.value[spec.key]
          return { ok: false, cancelled: true, discarded: true, error: e }
        }
        t.finishedAt = Date.now()
        if (isCancelled(e)) {
          // Selbst abgebrochen – kein Toast, kein Verlaufseintrag.
          t.status = 'cancelled'
          return { ok: false, cancelled: true, discarded: false, error: e }
        }
        t.status = 'failed'
        t.error = errorMessage(e)
        notify(t, spec, doneText)
        if (spec.record !== false) {
          await remember(t, spec)
          if (t.recordId) retries.set(t.recordId, () => void run(spec, work))
        }
        return { ok: false, cancelled: false, discarded: false, error: e }
      } finally {
        t.paused = false
        t.speed = 0
        t.cancelling = false
        pending.delete(spec.key)
        meters.delete(spec.key)
      }
    })()
    pending.set(spec.key, promise)
    return promise
  }

  async function remember(t: Task, spec: TaskSpec) {
    const record = await addRecord({
      kind: spec.kind,
      title: t.title,
      outcome: t.status === 'done' ? 'done' : 'failed',
      instanceId: t.instanceId,
      iconUrl: t.iconUrl,
      detail: t.status === 'failed' ? t.error : null,
      bytes: t.doneBytes > 0 ? t.doneBytes : null,
    })
    t.recordId = record.id
  }

  async function cancel(key: string) {
    const t = tasks.value[key]
    if (!t || t.status !== 'running' || !t.cancellable || t.cancelling) return
    t.cancelling = true
    t.stage = 'Wird abgebrochen …'
    try {
      await backend.cancelTask(key)
    } catch {
      // Läuft die Aufgabe nur im Frontend, prüft sie `ctx.cancelled()` selbst.
    }
  }

  async function pause(key: string, paused: boolean) {
    const t = tasks.value[key]
    if (!t || t.status !== 'running' || !t.pausable || t.cancelling) return
    t.paused = paused
    if (paused) {
      meters.get(key)?.reset()
      t.speed = 0
    }
    try {
      await backend.pauseTask(key, paused)
    } catch {
      t.paused = false
    }
  }

  /** Fertige Aufgabe dieser Sitzung vergessen (Seiten zeigen dann wieder den Normalzustand). */
  function forget(key: string) {
    if (tasks.value[key]?.status !== 'running') delete tasks.value[key]
  }

  function canRetry(recordId: string): boolean {
    return retries.has(recordId)
  }

  function retry(recordId: string) {
    const again = retries.get(recordId)
    if (!again) return
    retries.delete(recordId)
    removeRecord(recordId)
    again()
  }

  async function loadHistory() {
    try {
      const saved = await backend.taskHistory()
      // Einträge dieser Sitzung, die schon da sind, behalten ihre Reihenfolge.
      const known = new Set(history.value.map((r) => r.id))
      history.value = [...history.value, ...saved.filter((r) => !known.has(r.id))].slice(0, 50)
    } catch {
      // Ohne Kern bleibt der Verlauf leer.
    }
  }

  function removeRecord(id: string) {
    history.value = history.value.filter((r) => r.id !== id)
    retries.delete(id)
    if (!id.startsWith('local')) backend.removeTaskRecord(id).catch(() => {})
  }

  function clearHistory() {
    history.value = []
    retries.clear()
    backend.clearTaskHistory().catch(() => {})
  }

  /** Einmal beim App-Start (Titelleiste): Events abonnieren, Verlauf laden. */
  async function init(nav: (path: string) => void) {
    navigate = nav
    if (initialized) return
    initialized = true
    if (isTauri()) {
      await listen<TaskProgressEvent>('task-progress', (e) => onProgress(e.payload))
    }
    await loadHistory()
  }

  return {
    tasks,
    history,
    panelOpen,
    focused,
    active,
    totalSpeed,
    get,
    isRunning,
    run,
    cancel,
    pause,
    forget,
    note,
    canRetry,
    retry,
    removeRecord,
    clearHistory,
    openPanel,
    closePanel,
    openInstance,
    init,
    // Für Tests und das Event aus dem Kern.
    onProgress,
  }
})
