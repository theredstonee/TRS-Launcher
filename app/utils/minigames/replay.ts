// Rechnet eine Partie aus Startwert und Eingaben nach. Dieselbe Funktion läuft im Launcher und auf dem Server.

import { Loren } from './loren'
import { Stapler } from './stapler'

export const GAMES = ['stapler', 'loren'] as const
export type GameId = typeof GAMES[number]

/** Obergrenze für die Eingabeliste: Eine Stunde mit rund fünf Tastenwechseln je Sekunde, je Wechsel zwei Zahlen. */
export const MAX_INPUTS = 36_000

export interface ReplayResult { score: number, ticks: number, hash: number, finished: boolean }

/** Eine Eingabeliste ist gültig, wenn sie abwechselnd aus Takt-Abstand und Tastenmaske besteht. */
export function validInputs(game: GameId, inputs: readonly number[]): boolean {
  if (inputs.length % 2 !== 0 || inputs.length > MAX_INPUTS) return false
  const maxMask = game === 'stapler' ? 255 : 31
  for (let i = 0; i < inputs.length; i += 2) {
    const gap = inputs[i]!
    const mask = inputs[i + 1]!
    if (!Number.isInteger(gap) || gap < 0 || !Number.isInteger(mask) || mask < 0 || mask > maxMask) return false
  }
  return true
}

export function replay(game: GameId, seed: number, inputs: readonly number[]): ReplayResult {
  const run = game === 'stapler' ? new Stapler(seed) : new Loren(seed)
  let index = 0
  let mask = 0
  let wait = inputs.length ? inputs[0]! : Number.POSITIVE_INFINITY
  while (!run.finished) {
    while (index < inputs.length && wait === 0) {
      mask = inputs[index + 1]!
      index += 2
      wait = index < inputs.length ? inputs[index]! : Number.POSITIVE_INFINITY
    }
    run.step(mask)
    wait--
  }
  return { score: run.score, ticks: run.tick, hash: run.hash(), finished: run.finished }
}
