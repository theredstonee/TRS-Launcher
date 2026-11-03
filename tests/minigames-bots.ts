// Einfache Spieler für die Testpartien: Sie sollen möglichst viele Regeln auslösen, nicht perfekt spielen.

import { K as LK, Loren, O, ROW_GAP, UNIT } from '../app/utils/minigames/loren'
import { HEIGHT, K as SK, Stapler, WIDTH, mulberry, type Piece } from '../app/utils/minigames/stapler'

function placements(p: Piece): { rot: number, x: number, b: number[][] }[] {
  const out: { rot: number, x: number, b: number[][] }[] = []
  let b = p.b
  for (let rot = 0; rot < 4; rot++) {
    const xs = b.map(c => c[0]!)
    for (let x = -Math.min(...xs); x < WIDTH - Math.max(...xs); x++) out.push({ rot, x, b })
    b = b.map(c => [p.n - 1 - c[1]!, c[0]!])
  }
  return out
}

/** Bewertet eine Lage nach Höhe, Löchern und vollen Reihen. */
function score(g: Stapler, x: number, b: number[][]): number {
  const filled = g.grid.map(row => row.map(Boolean))
  let y = -2
  const fits = (yy: number) => b.every((c) => {
    const cy = yy + c[1]!
    const cx = x + c[0]!
    return cy < HEIGHT && (cy < 0 || !filled[cy]![cx])
  })
  while (fits(y + 1)) y++
  for (const c of b) {
    const cy = y + c[1]!
    if (cy >= 0) filled[cy]![x + c[0]!] = true
  }
  let lines = 0
  let holes = 0
  let height = 0
  for (let xx = 0; xx < WIDTH; xx++) {
    let seen = false
    for (let yy = 0; yy < HEIGHT; yy++) {
      if (filled[yy]![xx]) {
        if (!seen) height += HEIGHT - yy
        seen = true
      }
      else if (seen) {
        holes++
      }
    }
  }
  for (const row of filled) {
    if (row.every(Boolean)) lines++
  }
  return lines * 8 - holes * 6 - height
}

/** Spielt eine Partie: `skill` ist die Wahrscheinlichkeit in Prozent, die beste Lage zu wählen. */
export function playStapler(seed: number, skill: number, maxTicks = 40_000): Stapler {
  const g = new Stapler(seed)
  const rnd = mulberry(seed ^ 0x5bd1e995)
  let plan: { rot: number, x: number } | null = null
  let planned: Piece | null = null
  let press = 0
  while (!g.finished && g.tick < maxTicks) {
    let mask = 0
    if (g.phase === 'fall' && g.cur) {
      if (planned !== g.cur) {
        planned = g.cur
        const options = placements(g.cur)
        options.sort((a, b) => score(g, b.x, b.b) - score(g, a.x, a.b))
        plan = rnd() % 100 < skill ? options[0]! : options[rnd() % options.length]!
      }
      if (press > 0) {
        press--
      }
      else if (g.signal >= 15 && rnd() % 100 < 10) {
        mask = SK.PULSE
        press = 1
      }
      else if (g.canHold && !g.hold && rnd() % 100 < 2) {
        mask = SK.HOLD
        press = 1
      }
      else if (plan!.rot > 0) {
        mask = SK.CW
        plan = { ...plan!, rot: plan!.rot - 1 }
        press = 1
      }
      else if (g.cur.x !== plan!.x) {
        mask = g.cur.x > plan!.x ? SK.LEFT : SK.RIGHT
        press = 1
      }
      else {
        mask = SK.HARD
        press = 1
      }
    }
    g.step(mask)
  }
  // Am Ende stapelt der Bot nur noch, damit jede Partie auch wirklich endet.
  while (!g.finished) g.step(g.tick % 2 ? SK.HARD : 0)
  return g
}

/** Fährt so lange, bis der Bot einen Fehler macht: `skill` ist die Chance in Prozent, ein Hindernis richtig zu lesen. */
export function playLoren(seed: number, skill: number, maxTicks = 60_000): Loren {
  const g = new Loren(seed)
  const rnd = mulberry(seed ^ 0x5bd1e995)
  let press = 0
  let handled = -1
  while (!g.finished && g.tick < maxTicks) {
    let mask = 0
    if (press > 0) {
      press--
    }
    else {
      const ahead = Math.floor(g.pos / (UNIT * ROW_GAP)) + 1
      const row = g.row(ahead)
      const left = (ahead * ROW_GAP * UNIT - g.pos) / Math.max(1, g.speed)
      const o = row.o[g.lane]!
      if (g.signal >= 15) {
        mask = LK.TURBO
      }
      else if (ahead !== handled && o && !row.used[g.lane] && left < 22) {
        handled = ahead
        if (rnd() % 100 < skill) {
          const free = [g.lane - 1, g.lane + 1].filter(l => l >= 0 && l < 3 && !row.o[l])
          if (o === O.BEAM) mask = LK.DUCK
          else if (o !== O.CRATE && left > 14) mask = LK.JUMP
          else if (free.length) mask = free[0]! < g.lane ? LK.LEFT : LK.RIGHT
          else mask = LK.JUMP
        }
      }
      else if (!o && row.item[(g.lane + 1) % 3] && rnd() % 100 < 4) {
        mask = g.lane < 2 ? LK.RIGHT : LK.LEFT
      }
      if (mask) press = 1
    }
    g.step(mask)
  }
  while (!g.finished) g.step(0)
  return g
}
