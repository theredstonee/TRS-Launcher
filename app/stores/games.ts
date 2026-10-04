import { isTauri } from '@tauri-apps/api/core'
import { listen } from '@tauri-apps/api/event'
import { defineStore } from 'pinia'
import type { Diagnosis, GameEvent, LogLine, StageProgress } from '~/types'
import type { HostedWorld } from '~/utils/hosting'
import { askDuplicateMods } from '~/utils/duplicateMods'
import { MOD_CONFLICT_CODE, isModConflictError } from '~/utils/modConflicts'
import { aggregatePhase, isExtraKey } from '~/utils/processes'
import { platformCaps } from '~/utils/system'

export type GamePhase = 'idle' | 'preparing' | 'running'

/** Ein weiterer Prozess einer laufenden Instanz („Nochmal starten“). */
export interface ExtraProcess {
  key: string
  pid: number | null
  startedAt: number
  /** Konto-ID, mit der er gestartet wurde (nach einem Launcher-Neustart unbekannt) */
  accountId: string | null
}

export interface GameState {
  phase: GamePhase
  progress: StageProgress | null
  error: string | null
  /** Übersetzungs-Code des Startfehlers (z. B. Mod-Konflikt → „Helfer öffnen“) */
  errorCode?: string | null
  logs: LogLine[]
  /**
   * Wie viele Zeilen seit dem Start angekommen sind (auch bereits vorne
   * abgeschnittene). Die Log-Ansicht erkennt daran neue Zeilen und einen Neustart.
   */
  logTotal: number
  lastExit: { exitCode: number | null; crashed: boolean; diagnosis: Diagnosis | null; crashId?: string | null } | null
  /** Startzeit (ms) des laufenden Spiels – für die Laufzeit in der Titelleiste. */
  startedAt: number | null
  /** Läuft der erste Prozess (Schlüssel = Instanz-ID)? Weitere stehen in `extras`. */
  mainAlive: boolean
  /** Konto des ersten Prozesses, falls bekannt */
  mainAccountId: string | null
}

// Die Log-Ansicht ist virtualisiert – viele Zeilen kosten nur Speicher.
// Gekürzt wird in Schritten, nicht bei jeder Zeile.
const MAX_LOG_LINES = 100_000
const TRIM_STEP = 5_000
/** Instanz, deren Start gerade auf die Doppel-Mod-Frage wartet. */
const duplicateGate = new Set<string>()

function emptyState(): GameState {
  return { phase: 'idle', progress: null, error: null, logs: [], logTotal: 0, lastExit: null, startedAt: null, mainAlive: false, mainAccountId: null }
}

export const useGamesStore = defineStore('games', () => {
  const states = ref<Record<string, GameState>>({})
  /** Weitere Prozesse je Instanz (zusätzlich zum ersten) */
  const extras = ref<Record<string, ExtraProcess[]>>({})
  /** Logs der weiteren Prozesse je Schlüssel */
  const extraLogs = ref<Record<string, GameState>>({})
  /** Instanz, bei der gerade „Nochmal starten“ bzw. „Welchen Prozess beenden?“ gefragt wird (Dialog im Layout) */
  const extraPrompt = ref<string | null>(null)
  const stopPrompt = ref<string | null>(null)
  const extraBusy = ref<Set<string>>(new Set())
  let initialized = false

  function state(id: string): GameState {
    return (states.value[id] ??= emptyState())
  }

  /** Phase neu bestimmen, wenn sich die Prozesse einer Instanz ändern. */
  function refresh(id: string) {
    const s = state(id)
    const list = extras.value[id] ?? []
    s.phase = aggregatePhase(s.phase, s.mainAlive, list.length)
    if (s.phase === 'running') s.startedAt = s.mainAlive ? s.startedAt : Math.min(...list.map((e) => e.startedAt))
    else if (s.phase === 'idle') s.startedAt = null
  }

  function onExtraEvent(event: Exclude<GameEvent, { type: 'notice' | 'crashAnalyzed' }>) {
    const id = event.instanceId
    if (event.type === 'started') {
      const list = (extras.value[id] ??= [])
      if (!list.some((e) => e.key === event.key)) {
        const known = pendingExtraAccount.get(id) ?? null
        pendingExtraAccount.delete(id)
        list.push({ key: event.key, pid: event.pid, startedAt: Date.now(), accountId: known })
      }
      extraLogs.value[event.key] = emptyState()
      refresh(id)
    } else if (event.type === 'logs') {
      const s = (extraLogs.value[event.key] ??= emptyState())
      for (const line of event.lines) s.logs.push(markRaw(line))
      s.logTotal += event.lines.length
      if (s.logs.length > MAX_LOG_LINES + TRIM_STEP) s.logs.splice(0, s.logs.length - MAX_LOG_LINES)
    } else {
      extras.value[id] = (extras.value[id] ?? []).filter((e) => e.key !== event.key)
      if (!extras.value[id]!.length) delete extras.value[id]
      const s = (extraLogs.value[event.key] ??= emptyState())
      s.lastExit = { exitCode: event.exitCode, crashed: event.crashed, diagnosis: event.diagnosis, crashId: event.crashId ?? null }
      refresh(id)
      if (event.crashed) useToasts().error(event.diagnosis ? userErrorText(event.diagnosis) : t('game.crashed'))
      useInstancesStore().load()
    }
  }
  /** Konto, mit dem der gerade angeforderte weitere Prozess startet – das `started`-Event trägt es nicht. */
  const pendingExtraAccount = new Map<string, string | null>()

  function onEvent(event: GameEvent) {
    // Hook oder Synchronisierung nach dem Beenden fehlgeschlagen.
    if (event.type === 'notice') {
      useToasts().error(userErrorText(event))
      return
    }
    // Absturz-Helfer fertig: Dialog öffnet sich.
    // Während „Schuldige Mod finden“ wertet die Suche den Absturz selbst – der Dialog bleibt zu.
    if (event.type === 'crashAnalyzed') {
      useCrashHelperStore().received(event.crash, !useBisectStore().isActive(event.crash.instanceId))
      return
    }
    if (isExtraKey(event.instanceId, event.key)) {
      onExtraEvent(event)
      return
    }
    const s = state(event.instanceId)
    if (event.type === 'started') {
      s.mainAlive = true
      s.phase = 'running'
      s.progress = null
      s.startedAt = Date.now()
    } else if (event.type === 'logs') {
      // markRaw: 100 000 Zeilen ohne Proxy je Objekt.
      for (const line of event.lines) s.logs.push(markRaw(line))
      s.logTotal += event.lines.length
      if (s.logs.length > MAX_LOG_LINES + TRIM_STEP) s.logs.splice(0, s.logs.length - MAX_LOG_LINES)
    } else {
      s.mainAlive = false
      s.phase = 'idle'
      s.startedAt = null
      s.mainAccountId = null
      refresh(event.instanceId)
      s.lastExit = { exitCode: event.exitCode, crashed: event.crashed, diagnosis: event.diagnosis, crashId: event.crashId ?? null }
      const bisecting = useBisectStore().gameExited(event.instanceId, event.crashed)
      if (event.crashed && !bisecting) {
        const toast = () => useToasts().error(event.diagnosis ? userErrorText(event.diagnosis) : t('game.crashed'))
        // Der Absturz-Helfer meldet sich gleich mit einem Dialog – nur wenn er
        // ausbleibt, gibt es den kurzen Hinweis.
        if (event.crashId) {
          const id = event.crashId
          setTimeout(() => {
            if (useCrashHelperStore().latest[event.instanceId]?.id !== id) toast()
          }, 10_000)
        } else toast()
      }
      // Spielzeit und "zuletzt gespielt" haben sich geändert.
      useInstancesStore().load()
    }
  }

  /** Einmal beim App-Start: Events abonnieren und laufende Spiele übernehmen. */
  async function init() {
    if (initialized || !isTauri()) return
    initialized = true
    await listen<GameEvent>('game-event', (e) => onEvent(e.payload))
    try {
      for (const game of await backend.runningGames()) {
        const startedAt = Date.parse(game.startedAt) || Date.now()
        const logs = (await backend.getGameLogs(game.key ?? game.instanceId)).map((l) => markRaw(l))
        if (isExtraKey(game.instanceId, game.key)) {
          const list = (extras.value[game.instanceId] ??= [])
          if (!list.some((e) => e.key === game.key)) list.push({ key: game.key, pid: game.pid, startedAt, accountId: null })
          const l = (extraLogs.value[game.key] ??= emptyState())
          l.logs = logs
          l.logTotal = logs.length
          refresh(game.instanceId)
          continue
        }
        const s = state(game.instanceId)
        s.mainAlive = true
        s.phase = 'running'
        s.startedAt = startedAt
        s.logs = logs
        s.logTotal = s.logs.length
      }
    } catch {
      // Ohne laufende Spiele gibt es nichts zu übernehmen.
    }
  }

  /**
   * `joinServer`: ID aus der Server-Liste – das Spiel verbindet sich nach dem Start direkt.
   * `joinAddress`: freie Adresse (Server eines Freundes); prüft der Kern.
   * `joinWorld`: gehostete Welt eines Freundes – geht über den TRS-Link ans Spiel.
   * Die Vorbereitung läuft als Aufgabe (Titelleiste: Fortschritt, Pause, Abbrechen).
   * `skipModCheck`: „Trotzdem starten“ aus dem Mod-Konflikt-Helfer (einmal ohne Versions-Prüfung).
   * Liefert, ob das Spiel gestartet wurde.
   */
  async function launch(
    id: string,
    joinServer: string | null = null,
    joinAddress: string | null = null,
    joinWorld: HostedWorld | null = null,
    options: { skipModCheck?: boolean } = {},
  ): Promise<boolean> {
    // Am Handy gibt es (noch) kein Spiel zu starten.
    if (!platformCaps.value.gameLaunch) return false
    const s = state(id)
    if (s.phase !== 'idle') return false
    // Modpack lädt noch Dateien: nicht halb installiert starten.
    if (useTasksStore().installingInstance(id)) return false
    if (duplicateGate.has(id)) return false
    duplicateGate.add(id)
    try {
      // Zwei Jars derselben Mod-ID (nicht nur gleicher Dateiname) laden beide.
      // Das verzögert das Fenster und stürzt im Vollbild oft ab. Die Frage kommt
      // vor der Vorbereitung, damit Java nicht schon startet.
      const groups = await backend.duplicateMods(id).catch(() => [])
      if (groups.length) {
        const choice = await askDuplicateMods(id, groups)
        if (choice === 'cancel') return false
        if (choice === 'fix') {
          try {
            const files = await backend.resolveDuplicateMods(id)
            if (files.length) useToasts().ok(t('content.duplicates.done', { files: files.join(', ') }))
          } catch (e) {
            useToasts().error(e)
            return false
          }
        }
      }
    } finally {
      duplicateGate.delete(id)
    }
    if (s.phase !== 'idle' || useTasksStore().installingInstance(id)) return false
    s.phase = 'preparing'
    s.mainAccountId = useAccountsStore().active?.id ?? null
    s.error = null
    s.errorCode = null
    s.lastExit = null
    s.logs = []
    s.logTotal = 0
    s.progress = { stage: 'version', percent: 0, doneFiles: 0, totalFiles: 0 }
    const instance = useInstancesStore().items.find((i) => i.id === id)
    const result = await useTasksStore().run(
      {
        key: taskKey('launch', id),
        kind: 'launch',
        title: instance?.name ?? id,
        stage: t('game.preparing'),
        instanceId: id,
        cancellable: true,
        pausable: true,
        // Das Spiel selbst ist die Rückmeldung; Fehler meldet dieser Store.
        record: false,
        notify: false,
      },
      (ctx) =>
        backend.launchInstance(
          id,
          joinServer,
          (p) => {
            s.progress = p
            ctx.progress(overallPercent(p.stage, p.percent), stageLabel(p.stage))
            // Ab hier startet das Spiel – nichts mehr anzuhalten.
            if (p.stage === 'starting') ctx.update({ cancellable: false, pausable: false })
          },
          ctx.taskId,
          joinAddress,
          joinWorld,
          false,
          null,
          options.skipModCheck ?? false,
        ),
    )
    s.progress = null
    if (result.ok) {
      // Das `started`-Event kann vor oder nach der Antwort ankommen.
      if (s.phase === 'preparing') s.phase = 'running'
      s.startedAt ??= Date.now()
      return true
    }
    s.phase = 'idle'
    refresh(id)
    if (!result.cancelled) {
      s.error = errorMessage(result.error)
      // Mod-Konflikt: statt nur einer Meldung gleich der Helfer.
      if (isModConflictError(result.error)) {
        s.errorCode = MOD_CONFLICT_CODE
        void useModConflictsStore().open(id, 'launch')
      } else {
        useToasts().error(result.error)
      }
    }
    return false
  }

  /** Vorbereitung abbrechen (solange das Spiel noch nicht startet). */
  function cancelLaunch(id: string) {
    return useTasksStore().cancel(taskKey('launch', id))
  }

  /**
   * Instanz noch einmal starten, während sie läuft (eigener Prozess, eigene Logs).
   * `accountId`: Konto für diesen Start (null = das aktive). Liefert, ob es geklappt hat.
   */
  async function launchExtra(id: string, accountId: string | null): Promise<boolean> {
    if (state(id).phase !== 'running' || extraBusy.value.has(id)) return false
    extraBusy.value = new Set([...extraBusy.value, id])
    const instance = useInstancesStore().items.find((i) => i.id === id)
    pendingExtraAccount.set(id, accountId ?? useAccountsStore().active?.id ?? null)
    try {
      const result = await useTasksStore().run(
        {
          key: taskKey('launch', id, 'extra'),
          kind: 'launch',
          title: t('play.again.taskTitle', { name: instance?.name ?? id }),
          stage: t('game.preparing'),
          instanceId: id,
          cancellable: true,
          pausable: true,
          record: false,
          notify: false,
        },
        (ctx) =>
          backend.launchInstance(
            id,
            null,
            (p) => {
              ctx.progress(overallPercent(p.stage, p.percent), stageLabel(p.stage))
              if (p.stage === 'starting') ctx.update({ cancellable: false, pausable: false })
            },
            ctx.taskId,
            null,
            null,
            true,
            accountId,
          ),
      )
      if (!result.ok && !result.cancelled) useToasts().error(result.error)
      return result.ok
    } finally {
      pendingExtraAccount.delete(id)
      const next = new Set(extraBusy.value)
      next.delete(id)
      extraBusy.value = next
    }
  }

  /** Alle Prozesse der Instanz beenden – oder nur den mit `key`. */
  async function stop(id: string, key: string | null = null) {
    try {
      await backend.stopInstance(id, key)
    } catch (e) {
      state(id).error = errorMessage(e)
    }
  }

  /** Alle laufenden Prozesse einer Instanz (der erste zuerst). */
  function processes(id: string): { key: string; startedAt: number; accountId: string | null; main: boolean }[] {
    const s = state(id)
    const list = (extras.value[id] ?? []).map((e) => ({ key: e.key, startedAt: e.startedAt, accountId: e.accountId, main: false }))
    if (s.mainAlive) list.unshift({ key: id, startedAt: s.startedAt ?? Date.now(), accountId: s.mainAccountId, main: true })
    return list
  }

  /** „Beenden“: bei einem Prozess sofort, bei mehreren fragt der Dialog (einen oder alle). */
  function requestStop(id: string) {
    if (processes(id).length > 1) stopPrompt.value = id
    else void stop(id)
  }

  const runningCount = computed(() => Object.values(states.value).filter((s) => s.phase !== 'idle').length)

  /** Laufende Spiele, zuerst gestartetes zuerst (das erste ist das „Haupt“-Spiel). */
  const running = computed(() =>
    Object.entries(states.value)
      .filter(([, s]) => s.phase === 'running')
      .map(([instanceId, s]) => ({ instanceId, startedAt: s.startedAt ?? Date.now() }))
      .sort((a, b) => a.startedAt - b.startedAt),
  )

  return {
    states,
    state,
    extras,
    extraLogs,
    extraPrompt,
    stopPrompt,
    extraBusy,
    init,
    launch,
    launchExtra,
    cancelLaunch,
    stop,
    processes,
    requestStop,
    runningCount,
    running,
  }
})
