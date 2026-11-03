import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { Loren } from '../app/utils/minigames/loren'
import { replay, validInputs, type GameId } from '../app/utils/minigames/replay'
import { MAX_TICKS, Stapler, mulberry, type StaplerEvent } from '../app/utils/minigames/stapler'
import { playLoren, playStapler } from './minigames-bots'

interface Vector { game: GameId, seed: number, inputs: number[], score: number, ticks: number, hash: number }

const FILE = resolve(__dirname, 'fixtures/minigames-vectors.json')
const SEEDS = [1, 7, 42, 12345, 987654321, 2147483648, 4294967295]

function makeVectors(): Vector[] {
  const out: Vector[] = []
  for (const seed of SEEDS) {
    for (const skill of [100, 70]) {
      const s = playStapler(seed, skill)
      out.push({ game: 'stapler', seed, inputs: [...s.inputs], score: s.score, ticks: s.tick, hash: s.hash() })
      const l = playLoren(seed, skill)
      out.push({ game: 'loren', seed, inputs: [...l.inputs], score: l.score, ticks: l.tick, hash: l.hash() })
    }
  }
  return out
}

describe('Minispiele: Zufall', () => {
  // Unabhängige Fassung mit BigInt: So fällt auf, wenn die Rechnung von JavaScripts Zahlenformat abhängt.
  function reference(seed: number): () => number {
    const M = 0xFFFFFFFFn
    let a = BigInt(seed >>> 0)
    return () => {
      a = (a + 0x6D2B79F5n) & M
      let t = ((a ^ (a >> 15n)) * (a | 1n)) & M
      t = ((t + (((t ^ (t >> 7n)) * (t | 61n)) & M)) & M) ^ t
      return Number((t ^ (t >> 14n)) & M)
    }
  }

  it.each(SEEDS)('stimmt mit der Vergleichsfassung überein (Startwert %i)', (seed) => {
    const rng = mulberry(seed)
    const check = reference(seed)
    for (let i = 0; i < 2000; i++) expect(rng()).toBe(check())
  })
})

describe('Minispiele: Nachrechnen', () => {
  it.each(SEEDS)('Stapler mit Startwert %i ergibt beim Nachrechnen dasselbe', (seed) => {
    const g = playStapler(seed, 100)
    expect(g.finished).toBe(true)
    expect(replay('stapler', seed, g.inputs)).toEqual({ score: g.score, ticks: g.tick, hash: g.hash(), finished: true })
  })

  it.each(SEEDS)('Loren mit Startwert %i ergibt beim Nachrechnen dasselbe', (seed) => {
    const g = playLoren(seed, 85)
    expect(g.finished).toBe(true)
    expect(replay('loren', seed, g.inputs)).toEqual({ score: g.score, ticks: g.tick, hash: g.hash(), finished: true })
  })

  it('eine geänderte Eingabe ergibt ein anderes Ergebnis', () => {
    const g = playStapler(42, 100)
    const inputs = [...g.inputs]
    inputs[1] = inputs[1] === 1 ? 2 : 1
    expect(replay('stapler', 42, inputs).hash).not.toBe(g.hash())
  })

  it('das Spiel endet ohne jede Eingabe von selbst', () => {
    expect(replay('stapler', 5, []).finished).toBe(true)
    expect(replay('loren', 5, []).finished).toBe(true)
  })

  it('Reihen hängen nicht von der Reihenfolge der Abfragen ab', () => {
    const a = new Loren(99)
    const b = new Loren(99)
    b.row(300)
    for (let i = 299; i >= 0; i--) b.row(i)
    for (let i = 0; i <= 300; i++) expect(a.row(i)).toEqual(b.row(i))
  })

  it('prüft Eingabelisten', () => {
    expect(validInputs('stapler', [0, 1, 5, 0])).toBe(true)
    expect(validInputs('stapler', [0])).toBe(false)
    expect(validInputs('stapler', [-1, 1])).toBe(false)
    expect(validInputs('stapler', [1.5, 1])).toBe(false)
    expect(validInputs('stapler', [0, 256])).toBe(false)
    expect(validInputs('loren', [0, 32])).toBe(false)
    expect(validInputs('loren', new Array(36_002).fill(0))).toBe(false)
  })
})

describe('Minispiele: Regeln', () => {
  it('Testpartien erreichen alle Redstone-Regeln', () => {
    const seen = new Set<string>()
    for (const seed of SEEDS) {
      const g = playStapler(seed, 100)
      const copy = new Stapler(seed, (e: StaplerEvent) => seen.add(e.type))
      let index = 0
      let mask = 0
      let wait = g.inputs[0] ?? Number.POSITIVE_INFINITY
      while (!copy.finished) {
        while (index < g.inputs.length && wait === 0) {
          mask = g.inputs[index + 1]!
          index += 2
          wait = g.inputs[index] ?? Number.POSITIVE_INFINITY
        }
        copy.step(mask)
        wait--
      }
    }
    for (const type of ['clear', 'ignite', 'explode', 'lamp', 'charge', 'piston', 'pulse', 'level', 'hold', 'over']) expect(seen, type).toContain(type)
  })

  it('Dropper wechseln: Steine kommen nicht immer aus derselben Spalte', () => {
    const columns = new Set<number>()
    const g = new Stapler(3, (e) => {
      if (e.type === 'spawn') columns.add(e.x)
    })
    for (let i = 0; i < 4000 && !g.finished; i++) g.step(i % 3 === 0 ? 32 : 0)
    expect(columns.size).toBeGreaterThan(3)
  })

  it('jede Partie endet spätestens an der Obergrenze', () => {
    const g = new Loren(1)
    g.tick = MAX_TICKS
    g.step(0)
    expect(g.finished).toBe(true)
  })
})

describe('Minispiele: Testpartien für Server und Client', () => {
  it('stimmen mit der gespeicherten Datei überein', () => {
    const fresh = makeVectors()
    if (process.env.MINIGAME_VECTORS === 'write' || !existsSync(FILE)) writeFileSync(FILE, `${JSON.stringify(fresh)}\n`)
    const stored = JSON.parse(readFileSync(FILE, 'utf8')) as Vector[]
    expect(fresh).toEqual(stored)
    for (const v of stored) {
      const { score, ticks, hash } = replay(v.game, v.seed, v.inputs)
      expect({ score, ticks, hash }).toEqual({ score: v.score, ticks: v.ticks, hash: v.hash })
    }
  })
})
