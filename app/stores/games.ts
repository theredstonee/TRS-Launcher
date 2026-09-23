import { isTauri } from '@tauri-apps/api/core'
import { listen } from '@tauri-apps/api/event'
import { defineStore } from 'pinia'
import type { Diagnosis, GameEvent, LogLine, StageProgress } from '~/types'

export type GamePhase = 'idle' | 'preparing' | 'running'

export interface GameState {
  phase: GamePhase
  progress: StageProgress | null
  error: string | null
  logs: LogLine[]
  lastExit: { exitCode: number | null; crashed: boolean; diagnosis: Diagnosis | null } | null
  /** Startzeit (ms) des laufenden Spiels – für die Laufzeit in der Titelleiste. */
  startedAt: number | null
}

// Das Backend hält dieselbe Menge vor; mehr bremst nur das Rendering.
const MAX_LOG_LINES = 5000

function emptyState(): GameState {
  return { phase: 'idle', progress: null, error: null, logs: [], lastExit: null, startedAt: null }
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
      useToasts().error(event.message)
      return
    }
    const s = state(event.instanceId)
    if (event.type === 'started') {
      s.phase = 'running'
      s.progress = null
      s.startedAt = Date.now()
    } else if (event.type === 'logs') {
      s.logs.push(...event.lines)
      if (s.logs.length > MAX_LOG_LINES) s.logs.splice(0, s.logs.length - MAX_LOG_LINES)
    } else {
      s.phase = 'idle'
      s.startedAt = null
      s.lastExit = { exitCode: event.exitCode, crashed: event.crashed, diagnosis: event.diagnosis }
      if (event.crashed) useToasts().error(event.diagnosis?.message ?? 'Das Spiel wurde unerwartet beendet – die Logs zeigen meist die Ursache.')
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
        s.logs = await backend.getGameLogs(game.instanceId)
      }
    } catch {
      // Ohne laufende Spiele gibt es nichts zu übernehmen.
    }
  }

  /**
   * `joinServer`: ID aus der Server-Liste – das Spiel verbindet sich nach dem Start direkt.
   * `joinAddress`: freie Adresse (Server eines Freundes); prüft der Kern.
   * Die Vorbereitung läuft als Aufgabe (Titelleiste: Fortschritt, Pause, Abbrechen).
   */
  async function launch(id: string, joinServer: string | null = null, joinAddress: string | null = null) {
    const s = state(id)
    if (s.phase !== 'idle') return
    s.phase = 'preparing'
    s.error = null
    s.lastExit = null
    s.logs = []
    s.progress = { stage: 'version', percent: 0, doneFiles: 0, totalFiles: 0 }
    const instance = useInstancesStore().items.find((i) => i.id === id)
    const result = await useTasksStore().run(
      {
        key: taskKey('launch', id),
        kind: 'launch',
        title: instance?.name ?? id,
        stage: 'Minecraft wird vorbereitet',
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
            ctx.progress(overallPercent(p.stage, p.percent), stageLabels[p.stage])
            // Ab hier startet das Spiel – nichts mehr anzuhalten.
            if (p.stage === 'starting') ctx.update({ cancellable: false, pausable: false })
          },
          ctx.taskId,
          joinAddress,
        ),
    )
    s.progress = null
    if (result.ok) {
      // Das `started`-Event kann vor oder nach der Antwort ankommen.
      if (s.phase === 'preparing') s.phase = 'running'
      s.startedAt ??= Date.now()
      return
    }
    s.phase = 'idle'
    if (!result.cancelled) {
      s.error = errorMessage(result.error)
      useToasts().error(result.error)
    }
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
