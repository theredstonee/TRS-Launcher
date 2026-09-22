import { isTauri } from '@tauri-apps/api/core'
import { listen } from '@tauri-apps/api/event'
import { defineStore } from 'pinia'
import type { GameEvent, LogLine, StageProgress } from '~/types'

export type GamePhase = 'idle' | 'preparing' | 'running'

export interface GameState {
  phase: GamePhase
  progress: StageProgress | null
  error: string | null
  logs: LogLine[]
  lastExit: { exitCode: number | null; crashed: boolean } | null
}

// Das Backend hält dieselbe Menge vor; mehr bremst nur das Rendering.
const MAX_LOG_LINES = 5000

function emptyState(): GameState {
  return { phase: 'idle', progress: null, error: null, logs: [], lastExit: null }
}

export const useGamesStore = defineStore('games', () => {
  const states = ref<Record<string, GameState>>({})
  let initialized = false

  function state(id: string): GameState {
    return (states.value[id] ??= emptyState())
  }

  function onEvent(event: GameEvent) {
    const s = state(event.instanceId)
    if (event.type === 'started') {
      s.phase = 'running'
      s.progress = null
    } else if (event.type === 'logs') {
      s.logs.push(...event.lines)
      if (s.logs.length > MAX_LOG_LINES) s.logs.splice(0, s.logs.length - MAX_LOG_LINES)
    } else {
      s.phase = 'idle'
      s.lastExit = { exitCode: event.exitCode, crashed: event.crashed }
      if (event.crashed) useToasts().error('Das Spiel wurde unerwartet beendet – die Logs zeigen meist die Ursache.')
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
        s.logs = await backend.getGameLogs(game.instanceId)
      }
    } catch {
      // Ohne laufende Spiele gibt es nichts zu übernehmen.
    }
  }

  /** `joinServer`: ID aus der Server-Liste – das Spiel verbindet sich nach dem Start direkt. */
  async function launch(id: string, joinServer: string | null = null) {
    const s = state(id)
    if (s.phase !== 'idle') return
    s.phase = 'preparing'
    s.error = null
    s.lastExit = null
    s.logs = []
    s.progress = { stage: 'version', percent: 0, doneFiles: 0, totalFiles: 0 }
    try {
      await backend.launchInstance(id, joinServer, (p) => (s.progress = p))
      // Das `started`-Event kann vor oder nach der Antwort ankommen.
      if (s.phase === 'preparing') s.phase = 'running'
    } catch (e) {
      s.phase = 'idle'
      s.error = errorMessage(e)
      useToasts().error(e)
    } finally {
      s.progress = null
    }
  }

  async function stop(id: string) {
    try {
      await backend.stopInstance(id)
    } catch (e) {
      state(id).error = errorMessage(e)
    }
  }

  const runningCount = computed(() => Object.values(states.value).filter((s) => s.phase !== 'idle').length)

  return { states, state, init, launch, stop, runningCount }
})
