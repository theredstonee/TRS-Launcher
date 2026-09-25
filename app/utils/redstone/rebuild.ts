import { TEX, type Particle } from './paint'
import { cableBox, pickModule, placements, planCable, stencilFor, type Placement } from './scene'
import { floorCell, type Cell, type Circuit } from './sim'

// Der langsame Umbau der Hintergrund-Szene: Alle paar Minuten zerfällt eine
// Schaltung oder ein Kabel Block für Block, dann entsteht dort ein neues. In
// Ticks gerechnet, damit er mit der Szene pausiert.

/** 3 min … 6 min bei 10 Ticks/s. */
export const REBUILD_MIN = 1800
export const REBUILD_SPAN = 1800

export class Rebuilder {
  /** Tick, an dem der nächste Umbau beginnt. */
  nextAt: number
  private job: { spot: Placement; remove: number[]; add: { i: number; cell: Cell }[] | null } | null = null

  constructor(
    private readonly chance: () => number,
    private readonly min = REBUILD_MIN,
    private readonly span = REBUILD_SPAN,
  ) {
    this.nextAt = min + Math.floor(chance() * span)
  }

  get active(): boolean {
    return this.job !== null
  }

  /** Neue Szene (Größe geändert) oder nach einem Fehler: laufenden Umbau vergessen. */
  reset() {
    this.job = null
  }

  /** Sofort umbauen (Strg+Alt+R). */
  trigger(tick: number) {
    if (!this.job) this.nextAt = tick
  }

  /** Ein Tick Umbau; Bruchstücke landen in `particles`. */
  step(circuit: Circuit, tick: number, particles: Particle[]) {
    const w = circuit.w
    if (!this.job) {
      if (tick < this.nextAt) return
      this.nextAt = tick + this.min + Math.floor(this.chance() * this.span)
      const list = placements.get(circuit) ?? []
      if (!list.length) return
      const spot = list[Math.floor(this.chance() * list.length)]!
      const remove: number[] = []
      if (spot.cells) remove.push(...spot.cells.filter((i) => circuit.cells[i] && circuit.cells[i]!.kind !== 'floor'))
      else
        for (let y = spot.y; y < spot.y + spot.h; y++)
          for (let x = spot.x; x < spot.x + spot.w; x++) {
            const c = circuit.at(x, y)
            if (c && c.kind !== 'floor') remove.push(y * w + x)
          }
      // Kabel werden vom Ende her abgebaut, Schaltungen durcheinander.
      if (spot.cells) remove.reverse()
      else remove.sort(() => this.chance() - 0.5)
      this.job = { spot, remove, add: null }
      return
    }
    // Etwa zwei Blöcke pro Sekunde abbauen, dann aufbauen.
    if (tick % 5 !== 0) return
    const job = this.job
    const next = job.remove.shift()
    if (next !== undefined) {
      const x = next % w
      const y = (next / w) | 0
      const old = circuit.cells[next]
      circuit.set(x, y, floorCell())
      for (let k = 0; k < 5; k++) {
        particles.push({
          x: x * TEX + 4 + Math.random() * 8,
          y: y * TEX + 4 + Math.random() * 8,
          vx: (Math.random() - 0.5) * 1.4,
          vy: -Math.random() * 1.2,
          life: 9,
          max: 9,
          power: 15,
          color: old?.kind === 'dust' ? '#b31a12' : '#55555c',
          gravity: 0.15,
        })
      }
      circuit.relink()
      return
    }
    // Abgebaut – jetzt das Neue planen (erst jetzt ist der Platz frei).
    if (!job.add) {
      const spot = job.spot
      if (spot.cells) {
        const planned = planCable(circuit, this.chance) ?? []
        spot.cells = planned.map((p) => p.i)
        if (planned.length) Object.assign(spot, cableBox(circuit, spot.cells))
        job.add = planned
      } else {
        const m = pickModule(this.chance, spot.w, spot.h, spot.name) ?? pickModule(this.chance, spot.w, spot.h)
        const add: { i: number; cell: Cell }[] = []
        if (m) {
          const cells = stencilFor(m, this.chance)
          const oy = spot.y + Math.floor(this.chance() * (spot.h - cells.length + 1))
          const ox = spot.x + Math.floor(this.chance() * (spot.w - cells[0]!.length + 1))
          cells.forEach((row, dy) =>
            row.forEach((cell, dx) => {
              if (cell.kind !== 'floor') add.push({ i: (oy + dy) * w + ox + dx, cell })
            }),
          )
          // Takt-Fackeln zuletzt – erst wenn die Leitung steht, geht es los.
          add.sort((a, b) => Number(a.cell.kind === 'torch') - Number(b.cell.kind === 'torch'))
          spot.name = m.name
        }
        job.add = add
      }
      return
    }
    const put = job.add.shift()
    if (put) {
      circuit.set(put.i % w, (put.i / w) | 0, put.cell)
      circuit.relink()
      return
    }
    this.job = null
  }
}
