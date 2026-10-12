// Schneefall und Schornsteinrauch der Winterszene – nur Zahlen, ohne Canvas
// (tests/winter-scene.test.ts). Ein Takt = ein Bild (80 ms).

export interface Flake {
  x: number
  y: number
  /** Fallgeschwindigkeit in Texeln je Takt. */
  vy: number
  /** Stärke des Hin-und-her-Pendelns. */
  sway: number
  phase: number
  /** 0 = fern (blass), 1 = mittel, 2 = nah (größer, schneller). */
  size: 0 | 1 | 2
}

export interface Puff {
  x: number
  y: number
  life: number
  max: number
}

const SPEED = [0.28, 0.55, 0.95] as const

/** Wind je Takt: meist ein leichter Zug nach rechts, ab und zu dreht er kurz. */
export function wind(tick: number): number {
  return 0.06 + 0.12 * Math.sin(tick * 0.009) + 0.05 * Math.sin(tick * 0.041 + 1)
}

export class Snowfall {
  readonly flakes: Flake[] = []

  constructor(
    private w: number,
    private h: number,
    private readonly rand: () => number,
    density = 1,
  ) {
    const n = Math.max(12, Math.min(700, Math.round(((w * h) / 300) * density)))
    for (let i = 0; i < n; i++) this.flakes.push(this.spawn(true))
  }

  private spawn(anywhere: boolean): Flake {
    const r = this.rand()
    const size: Flake['size'] = r < 0.55 ? 0 : r < 0.9 ? 1 : 2
    return {
      x: this.rand() * this.w,
      y: anywhere ? this.rand() * this.h : -2 - this.rand() * 8,
      vy: SPEED[size] * (0.8 + this.rand() * 0.4),
      sway: 0.15 + this.rand() * 0.35,
      phase: this.rand() * Math.PI * 2,
      size,
    }
  }

  step(tick: number) {
    const wnd = wind(tick)
    for (const f of this.flakes) {
      f.y += f.vy
      f.x += wnd * (0.4 + f.size * 0.4) + Math.sin(f.phase + tick * 0.08) * f.sway * 0.3
      if (f.y > this.h + 2) Object.assign(f, this.spawn(false))
      if (f.x < -3) f.x += this.w + 6
      else if (f.x > this.w + 3) f.x -= this.w + 6
    }
  }
}

/** Rauchwölkchen: steigen auf, treiben mit dem Wind und vergehen. */
export class Smoke {
  puffs: Puff[] = []

  constructor(private readonly rand: () => number) {}

  step(origin: { x: number; y: number } | null, tick: number) {
    const wnd = wind(tick)
    for (const p of this.puffs) {
      p.y -= 0.22
      p.x += 0.05 + wnd * 0.6 + (this.rand() - 0.5) * 0.15
      p.life--
    }
    this.puffs = this.puffs.filter((p) => p.life > 0)
    if (origin && tick % 7 === 0 && this.puffs.length < 24) {
      const max = 46 + Math.floor(this.rand() * 18)
      this.puffs.push({ x: origin.x + (this.rand() - 0.5), y: origin.y, life: max, max })
    }
  }
}
