// Flug der Hexe und der Fledermäuse über die ganze Seite (nur solange Halloween global an ist).
// Reine Rechnung, ohne Canvas: ein requestAnimationFrame läuft nur, solange etwas in der Luft ist.

export const WITCH_SRC = '/halloween/witch-final.png'
export const WITCH_FRAME = 48
export const WITCH_FRAMES = 6
export const WITCH_FRAME_MS = 110
/** Anteil der Flüge von links nach rechts (sie schaut nach rechts; die andere Richtung wird gespiegelt). */
export const WITCH_LTR = 0.78
export const WITCH_SCALES = [2, 2.25, 2.5, 2.75, 3] as const
/** Pause zwischen zwei Überflügen. */
export const WITCH_EVERY_MS = [25_000, 45_000] as const
/** Schwarm über den oberen Rand, danach Pause. */
export const BAT_EVERY_MS = [40_000, 70_000] as const
/** Besen-Ende im Sprite (links unten, hinter ihr, wenn sie nach rechts fliegt). */
export const BROOM = { u: 0.2, v: 0.8 }
export const SPARK_COLORS = ['#ff2a1a', '#ff7a1a', '#e0352b', '#ffb04a'] as const

export type Rnd = () => number

export function flyAllowed(state: { reduced: boolean, hidden: boolean }): boolean {
  return !state.reduced && !state.hidden
}

export function gapMs(range: readonly [number, number], rnd: Rnd = Math.random): number {
  const [min, max] = range
  return min + rnd() * (max - min)
}

export interface WitchPlan {
  dir: 1 | -1
  /** Oberkante des Sprites, innerhalb der oberen 60 % der Fläche. */
  y: number
  scale: number
  duration: number
  bob: number
}

export function planWitch(view: { w: number, h: number }, rnd: Rnd = Math.random): WitchPlan {
  const w = Math.max(1, view.w)
  const h = Math.max(1, view.h)
  const scale = WITCH_SCALES[Math.min(WITCH_SCALES.length - 1, Math.floor(rnd() * WITCH_SCALES.length))]!
  const dir: 1 | -1 = rnd() < WITCH_LTR ? 1 : -1
  const sz = WITCH_FRAME * scale
  const band = Math.max(0, h * 0.6 - sz)
  const y = band * rnd()
  const speed = 150 + rnd() * 70
  const duration = Math.min(12_000, Math.max(3_200, ((w + sz) / speed) * 1000))
  return { dir, y, scale, duration, bob: 5 }
}

export interface WitchPose {
  x: number
  y: number
  sz: number
  frame: number
  alive: boolean
}

export function witchPose(plan: WitchPlan, elapsed: number, viewW: number): WitchPose {
  const sz = WITCH_FRAME * plan.scale
  const p = plan.duration <= 0 ? 1 : elapsed / plan.duration
  const alive = elapsed >= 0 && elapsed <= plan.duration
  const travel = Math.max(1, viewW) + sz
  const along = plan.dir === 1 ? -sz + p * travel : viewW - p * travel
  const y = plan.y + Math.sin((elapsed / 1900) * Math.PI * 2) * plan.bob
  const frame = Math.floor(Math.max(0, elapsed) / WITCH_FRAME_MS) % WITCH_FRAMES
  return { x: along, y, sz, frame, alive }
}

export function witchFrameRect(frame: number): { sx: number, sy: number, sw: number, sh: number } {
  const i = ((frame % WITCH_FRAMES) + WITCH_FRAMES) % WITCH_FRAMES
  return { sx: i * WITCH_FRAME, sy: 0, sw: WITCH_FRAME, sh: WITCH_FRAME }
}

/** Punkt, an dem die Funken den Besen verlassen. `dir` -1 ist bereits gespiegelt. */
export function broomTip(x: number, y: number, sz: number, dir: 1 | -1): { x: number, y: number } {
  const u = dir === 1 ? BROOM.u : 1 - BROOM.u
  return { x: x + u * sz, y: y + BROOM.v * sz }
}

export interface Spark {
  x: number
  y: number
  vx: number
  vy: number
  born: number
  life: number
  color: string
}

export function makeSpark(tip: { x: number, y: number }, dir: 1 | -1, now: number, rnd: Rnd = Math.random): Spark {
  return {
    x: tip.x + (rnd() - 0.5) * 6,
    y: tip.y + (rnd() - 0.5) * 4,
    vx: -dir * (22 + rnd() * 36),
    vy: 10 + rnd() * 22,
    born: now,
    life: 380 + rnd() * 340,
    color: SPARK_COLORS[Math.floor(rnd() * SPARK_COLORS.length) % SPARK_COLORS.length]!,
  }
}

export function sparkAlpha(spark: Spark, now: number): number {
  if (spark.life <= 0) return 0
  const t = (now - spark.born) / spark.life
  if (t >= 1) return 0
  // Gerade entstanden (t <= 0) noch voll da, sonst würde der erste Frame ihn sofort löschen.
  if (t <= 0) return 1
  return 1 - t
}

export function stepSpark(spark: Spark, dtMs: number): void {
  const dt = dtMs / 1000
  spark.x += spark.vx * dt
  spark.y += spark.vy * dt
}

export interface BatPlan {
  lane: number
  lag: number
  phase: number
  size: 2 | 3
}

export interface SwarmPlan {
  dir: 1 | -1
  duration: number
  bats: BatPlan[]
}

export function swarmCount(rnd: Rnd): number {
  return 2 + Math.floor(rnd() * 3)
}

export function planSwarm(rnd: Rnd = Math.random): SwarmPlan {
  const n = swarmCount(rnd)
  const bats: BatPlan[] = []
  for (let i = 0; i < n; i++) {
    bats.push({
      lane: rnd(),
      lag: rnd() * 0.22,
      phase: rnd() * Math.PI * 2,
      size: rnd() < 0.5 ? 2 : 3,
    })
  }
  return { dir: rnd() < 0.5 ? 1 : -1, duration: 5_400 + rnd() * 2_200, bats }
}

export function batPose(
  swarm: SwarmPlan,
  bat: BatPlan,
  elapsed: number,
  view: { w: number, h: number },
): { x: number, y: number, wing: 0 | 1, size: number } {
  const span = 1.22
  const progress = (elapsed / Math.max(1, swarm.duration)) * span - bat.lag
  const pad = 28
  const travel = Math.max(1, view.w) + pad * 2
  const x = swarm.dir === 1 ? -pad + progress * travel : view.w + pad - progress * travel
  const bob = Math.sin(elapsed / 360 + bat.phase) * 3
  const y = Math.max(0, 4 + bat.lane * Math.max(16, view.h * 0.16) + bob)
  const wing: 0 | 1 = Math.floor(elapsed / 140 + bat.phase) % 2 === 0 ? 0 : 1
  return { x, y, wing, size: bat.size }
}
