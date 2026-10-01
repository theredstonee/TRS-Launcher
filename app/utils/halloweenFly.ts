// Gemeinsamer Flug für Startseite und das ganze Fenster (HalloweenFly).
// Nur solange das Event läuft; die Zeiten stehen im Vertrag der Folgeaufgabe:
// Hexe etwa alle 25–45 s, ein Schwarm Fledermäuse alle 40–70 s.
// Eine Hexe zur Zeit entscheidet der Aufrufer (er hält höchstens ein FlyWitch).

import { WITCH_FRAMES } from './halloween'

/** Pause zwischen zwei Hexen-Überflügen. */
export const FLY_WITCH_EVERY_MS = { min: 25_000, max: 45_000 } as const
/** Pause zwischen zwei Fledermaus-Schwärmen. */
export const FLY_BATS_EVERY_MS = { min: 40_000, max: 70_000 } as const

/** Ganzzahlige Skalierung, damit die 48-px-Kacheln scharf bleiben (2× oder 3×). */
export const FLY_WITCH_SCALES = [2, 3] as const

export interface FlySpark {
  x: number
  y: number
  /** Pixel pro Millisekunde, entgegen der Flugrichtung. */
  vx: number
  vy: number
  /** 1 → 0, danach weg. */
  life: number
  /** 0 Rot, 1 Orange. */
  warm: number
}

export interface FlyWitch {
  /** 1 = links nach rechts, -1 = gespiegelt zurück. */
  dir: 1 | -1
  /** Oberkante ohne Wippen, im oberen Bereich des Fensters. */
  y: number
  scale: 2 | 3
  duration: number
  elapsed: number
  sparks: FlySpark[]
  sinceSpark: number
  bob: number
}

export interface FlyBat {
  x: number
  y: number
  vx: number
  phase: number
  scale: number
}

export interface FlySwarm {
  bats: FlyBat[]
  elapsed: number
  duration: number
}

function unit(rand: () => number): number {
  const u = rand()
  if (!Number.isFinite(u)) return 0
  return Math.min(1, Math.max(0, u))
}

/** Zufällige Pause in [min, max]. `rand` liefert 0…1. */
export function flyGap(min: number, max: number, rand: () => number = Math.random): number {
  return min + unit(rand) * (max - min)
}

/** Weiches Ein- und Ausblenden an den Rändern eines Überflugs (0…1). */
export function flyAlpha(elapsed: number, duration: number): number {
  if (duration <= 0) return 0
  const p = elapsed / duration
  if (p <= 0 || p >= 1) return 0
  if (p < 0.07) return p / 0.07
  if (p > 0.93) return (1 - p) / 0.07
  return 1
}

/**
 * Eine Hexe. Meist links → rechts (4 von 5). Die Oberkante liegt so, dass das
 * Sprite im oberen 60-%-Band des Fensters bleibt.
 */
export function createFlyWitch(width: number, height: number, rand: () => number = Math.random): FlyWitch {
  const scale: 2 | 3 = unit(rand) < 0.5 ? 2 : 3
  const size = 48 * scale
  const band = Math.max(0, height * 0.6 - size)
  const y = band * unit(rand)
  const dir: 1 | -1 = unit(rand) < 0.8 ? 1 : -1
  const speed = 170 + unit(rand) * 110
  const distance = Math.max(1, width) + size + 64
  const duration = Math.max(4800, (distance / speed) * 1000)
  return {
    dir,
    y,
    scale,
    duration,
    elapsed: 0,
    sparks: [],
    sinceSpark: 0,
    bob: unit(rand) * Math.PI * 2,
  }
}

/** Linke Kante des Sprites. Am Anfang und Ende außerhalb des Fensters. */
export function flyWitchX(w: FlyWitch, width: number): number {
  const size = 48 * w.scale
  const p = w.duration <= 0 ? 1 : Math.min(1, Math.max(0, w.elapsed / w.duration))
  const from = w.dir === 1 ? -size - 32 : width + 32
  const to = w.dir === 1 ? width + 32 : -size - 32
  return from + (to - from) * p
}

/** Leichtes Sinus-Wippen, gleiche Periode wie der Ladebalken (~1,9 s), ±5 px. */
export function flyWitchBob(w: FlyWitch): number {
  return Math.sin(w.elapsed / 300 + w.bob) * 5
}

/**
 * Rückt die Hexe vor und lässt Redstone-Funken hinter dem Besen entstehen.
 * `false`, wenn der Überflug vorbei ist.
 */
export function stepFlyWitch(w: FlyWitch, dt: number, width: number, rand: () => number = Math.random): boolean {
  const step = Math.min(50, Math.max(0, dt))
  w.elapsed += step
  w.sinceSpark += step
  const size = 48 * w.scale
  const x = flyWitchX(w, width)
  const y = w.y + flyWitchBob(w)
  const p = w.duration <= 0 ? 1 : w.elapsed / w.duration
  // Nur während sie wirklich im Bild ist – sonst kleben Funken am Rand.
  while (w.sinceSpark >= 42 && p > 0.04 && p < 0.96) {
    w.sinceSpark -= 42
    const tail = 8 * w.scale
    const tailX = w.dir === 1 ? x + tail : x + size - tail
    w.sparks.push({
      x: tailX,
      y: y + 38 * w.scale,
      vx: -w.dir * (0.025 + unit(rand) * 0.05),
      vy: 0.01 + unit(rand) * 0.028,
      life: 1,
      warm: unit(rand),
    })
  }
  if (w.sinceSpark >= 42) w.sinceSpark = 42
  for (const s of w.sparks) {
    s.x += s.vx * step
    s.y += s.vy * step
    s.life -= step / 560
  }
  w.sparks = w.sparks.filter((s) => s.life > 0)
  if (w.sparks.length > 36) w.sparks.splice(0, w.sparks.length - 36)
  return w.elapsed < w.duration
}

/** 2–4 kleine Fledermäuse, meist links → rechts, quer über den oberen Rand. */
export function createFlySwarm(width: number, height: number, rand: () => number = Math.random): FlySwarm {
  const count = 2 + Math.floor(unit(rand) * 3)
  const dir: 1 | -1 = unit(rand) < 0.75 ? 1 : -1
  const speed = 0.12 + unit(rand) * 0.08
  // Unter der Titelleiste (die liegt darüber), aber noch im oberen Streifen.
  const spread = Math.max(24, Math.min(Math.max(0, height - 48) * 0.16, 88))
  const bats: FlyBat[] = []
  for (let i = 0; i < count; i++) {
    const gap = 36 + unit(rand) * 48
    bats.push({
      x: dir === 1 ? -30 - i * gap : width + 30 + i * gap,
      y: 40 + unit(rand) * spread,
      vx: dir * speed * (0.82 + unit(rand) * 0.36),
      phase: unit(rand) * Math.PI * 2,
      scale: 2,
    })
  }
  const duration = (Math.max(1, width) + 30 + count * 84 + 80) / speed
  return { bats, elapsed: 0, duration }
}

/** `false`, wenn der Schwarm durch ist. */
export function stepFlySwarm(swarm: FlySwarm, dt: number): boolean {
  const step = Math.min(50, Math.max(0, dt))
  swarm.elapsed += step
  for (const b of swarm.bats) b.x += b.vx * step
  return swarm.elapsed < swarm.duration
}

export function flyBatY(elapsed: number, bat: FlyBat): number {
  return bat.y + Math.sin(elapsed / 260 + bat.phase) * 5
}

export function flyBatWingUp(elapsed: number, bat: FlyBat): boolean {
  return Math.floor(elapsed / 120 + bat.phase) % 2 === 0
}

/** Pixel-Fledermaus: Körper 6×4, Flügel 6×2. `scale` ist der Pixel-Faktor. */
export function drawPixelBat(
  ctx: CanvasRenderingContext2D,
  x: number,
  y: number,
  wingUp: boolean,
  scale: number,
  fill = '#120a1c',
): void {
  const s = scale
  const wing = wingUp ? 3 : -1
  const px = Math.round(x)
  const py = Math.round(y)
  ctx.fillStyle = fill
  ctx.fillRect(px, py, 6 * s, 4 * s)
  ctx.fillRect(px - 6 * s, py - wing * s, 6 * s, 2 * s)
  ctx.fillRect(px + 6 * s, py - wing * s, 6 * s, 2 * s)
}

/** Fledermaus über der Oberfläche: dunkler Körper, lila Rand, damit sie auf Deepslate lesbar bleibt. */
export function drawFlyBat(ctx: CanvasRenderingContext2D, x: number, y: number, wingUp: boolean, scale: number): void {
  drawPixelBat(ctx, x + 1, y + 1, wingUp, scale, 'rgba(176,132,230,0.8)')
  drawPixelBat(ctx, x, y, wingUp, scale, '#140c1c')
  const s = scale
  const px = Math.round(x)
  const py = Math.round(y)
  ctx.fillStyle = '#ff7a1a'
  ctx.fillRect(px + 4 * s, py + s, Math.max(1, s), Math.max(1, s))
}

/** Ein Frame der Hexe. `flip` spiegelt für den Rückflug (scaleX -1). */
export function drawWitchSprite(
  ctx: CanvasRenderingContext2D,
  image: CanvasImageSource,
  frame: number,
  x: number,
  y: number,
  scale: number,
  flip: boolean,
): void {
  const size = 48 * scale
  const index = ((Math.floor(frame) % WITCH_FRAMES) + WITCH_FRAMES) % WITCH_FRAMES
  ctx.imageSmoothingEnabled = false
  ctx.save()
  if (flip) {
    ctx.translate(Math.round(x + size), Math.round(y))
    ctx.scale(-1, 1)
    ctx.drawImage(image, index * 48, 0, 48, 48, 0, 0, size, size)
  } else {
    ctx.drawImage(image, index * 48, 0, 48, 48, Math.round(x), Math.round(y), size, size)
  }
  ctx.restore()
}

/** 2×2-Funken auf dem Pixelraster, Rot oder Orange, mit der Restlebensdauer als Alpha. */
export function drawFlySpark(ctx: CanvasRenderingContext2D, spark: FlySpark): void {
  const a = Math.max(0, Math.min(1, spark.life))
  ctx.fillStyle = spark.warm > 0.45 ? `rgba(255,122,26,${a})` : `rgba(255,42,26,${a})`
  ctx.fillRect(Math.round(spark.x / 2) * 2, Math.round(spark.y / 2) * 2, 2, 2)
}
