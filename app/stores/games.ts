import { isTauri } from '@tauri-apps/api/core'
import { listen } from '@tauri-apps/api/event'
import { defineStore } from 'pinia'
import type { Diagnosis, GameEvent, LogLine, StageProgress } from '~/types'
import type { HostedWorld } from '~/utils/hosting'

export type GamePhase = 'idle' | 'preparing' | 'running'

export interface GameState {
  phase: GamePhase
  progress: StageProgress | null
  error: string | null
  logs: LogLine[]
  /**
   * Wie viele Zeilen seit dem Start angekommen sind (auch bereits vorne
   * abgeschnittene). Die Log-Ansicht erkennt daran neue Zeilen und einen Neustart.
   */
  logTotal: number
  lastExit: { exitCode: number | null; crashed: boolean; diagnosis: Diagnosis | null } | null
  /** Startzeit (ms) des laufenden Spiels – für die Laufzeit in der Titelleiste. */
  startedAt: number | null
}

// Die Log-Ansicht ist virtualisiert – viele Zeilen kosten nur Speicher.
// Gekürzt wird in Schritten, nicht bei jeder Zeile.
const MAX_LOG_LINES = 100_000
const TRIM_STEP = 5_000

function emptyState(): GameState {
  return { phase: 'idle', progress: null, error: null, logs: [], logTotal: 0, lastExit: null, startedAt: null }
}

export const useGamesStore = defineStore('games', () => {
  const states = ref<Record<string, GameState>>({})
  let initialized = false

  function state(id: string): GameState {
    return (states.value[id] ??= emptyState())
  }

  function onEvent(event: GameEvent) {
    // Hook oder Synchronisierung nach dem Beenden fehlgeschlagen.
    if (event.type === 'notice') {
      useToasts().error(userErrorText(event))
      return
    }
    const s = state(event.instanceId)
    if (event.type === 'started') {
      s.phase = 'running'
      s.progress = null
      s.startedAt = Date.now()
    } else if (event.type === 'logs') {
      // markRaw: 100 000 Zeilen ohne Proxy je Objekt.
      for (const line of event.lines) s.logs.push(markRaw(line))
      s.logTotal += event.lines.length
      if (s.logs.length > MAX_LOG_LINES + TRIM_STEP) s.logs.splice(0, s.logs.length - MAX_LOG_LINES)
    } else {
      s.phase = 'idle'
      s.startedAt = null
      s.lastExit = { exitCode: event.exitCode, crashed: event.crashed, diagnosis: event.diagnosis }
      if (event.crashed) useToasts().error(event.diagnosis ? userErrorText(event.diagnosis) : t('game.crashed'))
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
        const s = state(game.instanceId)
        s.phase = 'running'
        s.startedAt = Date.parse(game.startedAt) || Date.now()
        s.logs = (await backend.getGameLogs(game.instanceId)).map((l) => markRaw(l))
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
   * Liefert, ob das Spiel gestartet wurde.
   */
  async function launch(
    id: string,
    joinServer: string | null = null,
    joinAddress: string | null = null,
    joinWorld: HostedWorld | null = null,
  ): Promise<boolean> {
    const s = state(id)
    if (s.phase !== 'idle') return false
    s.phase = 'preparing'
    s.error = null
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
    if (!result.cancelled) {
      s.error = errorMessage(result.error)
      useToasts().error(result.error)
    }
    return false
  }

  /** Vorbereitung abbrechen (solange das Spiel noch nicht startet). */
  function cancelLaunch(id: string) {
    return useTasksStore().cancel(taskKey('launch', id))
  }

  async function stop(id: string) {
    try {
      await backend.stopInstance(id)
    } catch (e) {
      state(id).error = errorMessage(e)
    }
  }

  const runningCount = computed(() => Object.values(states.value).filter((s) => s.phase !== 'idle').length)

  /** Laufende Spiele, zuerst gestartetes zuerst (das erste ist das „Haupt“-Spiel). */
  const running = computed(() =>
    Object.entries(states.value)
      .filter(([, s]) => s.phase === 'running')
      .map(([instanceId, s]) => ({ instanceId, startedAt: s.startedAt ?? Date.now() }))
      .sort((a, b) => a.startedAt - b.startedAt),
  )

  return { states, state, init, launch, cancelLaunch, stop, runningCount, running }
})
