import { defineStore } from 'pinia'
import { ref } from 'vue'
import { backend } from '../utils/backend'
import type { GameId } from '../utils/minigames/replay'
import type { FinishedRun } from '../utils/minigames/session'
import { useTrsStore } from './trs'

export interface LeaderboardEntry { rank: number, uuid: string, name: string, score: number, at: string }
export interface Leaderboard { entries: LeaderboardEntry[], me: { rank: number, score: number } | null, total: number }
export type SubmitResult =
  | { kind: 'ranked', rank: number, score: number, improved: boolean }
  | { kind: 'rejected' }
  | { kind: 'failed' }
  | { kind: 'local' }

const BEST_KEY = 'trs.minigames.best'

function readBest(): Record<string, number> {
  try {
    const parsed = JSON.parse(localStorage.getItem(BEST_KEY) ?? '{}') as unknown
    return parsed && typeof parsed === 'object' ? (parsed as Record<string, number>) : {}
  }
  catch {
    return {}
  }
}

export const useMinigamesStore = defineStore('minigames', () => {
  const best = ref<Record<string, number>>(readBest())
  /** Gewertete Partien: Startwert → Kennung vom Server. Ohne Eintrag bleibt die Partie lokal. */
  const pending = new Map<number, string>()

  function localBest(game: GameId): number {
    return best.value[game] ?? 0
  }

  function recordLocal(game: GameId, score: number) {
    if (score <= localBest(game)) return
    best.value = { ...best.value, [game]: score }
    try {
      localStorage.setItem(BEST_KEY, JSON.stringify(best.value))
    }
    catch {
      // Ohne Speicher gilt der Rekord nur für diese Sitzung.
    }
  }

  /** Holt einen Startwert vom Server. Ohne Dienste oder Verbindung läuft die Partie lokal, nur ohne Wertung. */
  async function nextSeed(game: GameId): Promise<number> {
    if (useTrsStore().enabled) {
      try {
        const { runId, seed } = await backend.minigames.start(game)
        pending.set(seed, runId)
        return seed
      }
      catch {
        // Spielen soll auch ohne Server gehen.
      }
    }
    return Math.floor(Math.random() * 0x1_0000_0000)
  }

  async function submit(run: FinishedRun): Promise<SubmitResult> {
    const runId = pending.get(run.seed)
    if (!runId) return { kind: 'local' }
    pending.delete(run.seed)
    try {
      const result = await backend.minigames.submit(run.game, runId, run.inputs, run.score)
      return result.accepted ? { kind: 'ranked', rank: result.rank, score: result.best, improved: result.improved } : { kind: 'rejected' }
    }
    catch {
      return { kind: 'failed' }
    }
  }

  async function leaderboard(game: GameId): Promise<Leaderboard> {
    return backend.minigames.leaderboard(game)
  }

  async function remove(game: GameId, uuid: string, reason: string): Promise<void> {
    await backend.minigames.remove(game, uuid, reason)
  }

  return { best, localBest, recordLocal, nextSeed, submit, leaderboard, remove }
})
