// Bewegtes in der Fuchsbau-Szene – nur Zahlen, ohne Canvas (tests/fox-design.test.ts).
// Ein Takt = ein Bild (80 ms): treibende Blätter und ein paar Kirschblüten, Glühwürmchen
// über der Wiese und Funken, die vom Lagerfeuer aufsteigen.

export interface Leaf {
  x: number
  y: number
  vy: number
  sway: number
  phase: number
  /** Blüte (rosa) statt Blatt. */
  petal: boolean
  /** Farbindex in der Palette. */
  tone: number
  /** 0 = fern (1 Texel), 1 = nah (2 Texel, dreht sich). */
  size: 0 | 1
}

/** Wind je Takt: ein leichter Zug nach rechts, der langsam an- und abschwillt. */
export function breeze(tick: number): number {
  return 0.05 + 0.08 * Math.sin(tick * 0.007) + 0.04 * Math.sin(tick * 0.031 + 2)
}

export class Leaves {
  readonly leaves: Leaf[] = []

  constructor(
    private w: number,
    private h: number,
    private readonly rand: () => number,
    density = 1,
  ) {
    const n = Math.max(6, Math.min(90, Math.round((w / 14) * density)))
    for (let i = 0; i < n; i++) this.leaves.push(this.spawn(true))
  }

  private spawn(anywhere: boolean): Leaf {
    const petal = this.rand() < 0.22
    const size: Leaf['size'] = this.rand() < 0.35 ? 1 : 0
    return {
      x: this.rand() * this.w,
      y: anywhere ? this.rand() * this.h : -2 - this.rand() * 10,
      vy: (size ? 0.2 : 0.12) * (0.75 + this.rand() * 0.5),
      sway: 0.25 + this.rand() * 0.45,
      phase: this.rand() * Math.PI * 2,
      petal,
      tone: Math.floor(this.rand() * 4),
      size,
    }
  }

  step(tick: number) {
    const wnd = breeze(tick)
    for (const f of this.leaves) {
      f.y += f.vy * (0.8 + 0.4 * Math.sin(f.phase + tick * 0.05))
      f.x += wnd * (0.6 + f.size * 0.6) + Math.sin(f.phase + tick * 0.06) * f.sway * 0.35
      if (f.y > this.h + 2) Object.assign(f, this.spawn(false))
      if (f.x < -3) f.x += this.w + 6
      else if (f.x > this.w + 3) f.x -= this.w + 6
    }
  }
}

/** Blätter drehen sich im Fallen: breit (2 × 1) oder schmal (1 × 1) – je Takt. */
export function leafWide(f: Leaf, tick: number): boolean {
  return f.size === 1 && Math.sin(f.phase * 3 + tick * 0.18) > -0.2
}

export interface Firefly {
  /** Ruhelage, um die das Glühwürmchen kreist. */
  hx: number
  hy: number
  phase: number
  speed: number
  /** Blinkt in eigenem Takt. */
  blink: number
}

/** Glühwürmchen: kreisen langsam um ihre Ruhelage und leuchten im eigenen Rhythmus auf. */
export function makeFireflies(rand: () => number, n: number, box: { x0: number; x1: number; y0: number; y1: number }): Firefly[] {
  const out: Firefly[] = []
  for (let i = 0; i < n; i++) {
    out.push({
      hx: box.x0 + rand() * (box.x1 - box.x0),
      hy: box.y0 + rand() * (box.y1 - box.y0),
      phase: rand() * 40,
      speed: 0.6 + rand() * 0.8,
      blink: 0.7 + rand() * 0.9,
    })
  }
  return out
}

/** Lage und Helligkeit (0…1) zur Zeit `seconds`; ruhig = stehen still und glimmen gleichmäßig. */
export function fireflyAt(f: Firefly, seconds: number, calm = false) {
  if (calm) return { x: Math.round(f.hx), y: Math.round(f.hy), a: 0.7 }
  const t = seconds * f.speed + f.phase
  const x = f.hx + Math.sin(t * 0.45) * 7 + Math.sin(t * 1.3) * 1.5
  const y = f.hy + Math.cos(t * 0.6) * 3 + Math.sin(t * 1.7) * 1
  const a = Math.max(0, Math.sin(seconds * f.blink + f.phase))
  return { x: Math.round(x), y: Math.round(y), a: a * a }
}

export interface Spark {
  x: number
  y: number
  vx: number
  vy: number
  life: number
  max: number
}

/** Funken vom Lagerfeuer: steigen auf, flackern, vergehen. `power` 0…1 (mehr, wenn das Spiel läuft). */
export class Sparks {
  sparks: Spark[] = []

  constructor(private readonly rand: () => number) {}

  step(origin: { x: number; y: number; w: number } | null, tick: number, power = 0.5) {
    const wnd = breeze(tick)
    for (const s of this.sparks) {
      s.x += s.vx + wnd * 0.5
      s.y += s.vy
      s.vy *= 0.97
      s.life--
    }
    this.sparks = this.sparks.filter((s) => s.life > 0)
    if (!origin) return
    const every = power > 0.8 ? 2 : power > 0.4 ? 4 : 7
    if (tick % every === 0 && this.sparks.length < 30) {
      const max = 14 + Math.floor(this.rand() * 18)
      this.sparks.push({ x: origin.x + this.rand() * origin.w, y: origin.y, vx: (this.rand() - 0.5) * 0.25, vy: -(0.45 + this.rand() * 0.5), life: max, max })
    }
  }
}
