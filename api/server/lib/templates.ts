import { z } from 'zod'
import { sha256Hex } from './ids'
import { encodeRgba } from './png'

/**
 * Kosmetik-Vorlagen (feste 3D-Form + Texturmaße). Quelle: `assets/cosmetics/templates.json`.
 * Launcher (three.js) und Mod rendern beide aus genau diesen Daten – Koordinaten
 * und UV-Netz sind in API.md §11 beschrieben.
 */

export const WEARABLE_SLOTS = ['hat', 'wings', 'back', 'aura'] as const
export type WearableSlot = (typeof WEARABLE_SLOTS)[number]
export type CosmeticSlot = WearableSlot | 'emote'

export const TEMPLATE_ID = /^[a-z][a-z0-9_]{0,31}$/

/** Koordinate in Modell-Einheiten (1 = ein Skin-Pixel = 1/16 Block), Vielfaches von 0,5. */
const coord = z
  .number()
  .min(-64)
  .max(64)
  .refine((v) => Number.isInteger(v * 2), 'must be a multiple of 0.5')
const vec3 = z.tuple([coord, coord, coord])
const uv = z.tuple([z.int().min(0).max(255), z.int().min(0).max(255)])
const texSize = z.int().min(8).max(128)

const cubeSchema = z
  .strictObject({
    from: vec3,
    to: vec3,
    uv,
    attach: z.enum(['head', 'body', 'back']),
    pivot: vec3.optional(),
    /**
     * flap/bob/spin laufen nach der Wanduhr. Nur mit `rig` (Tier-Kosmetik, reagiert auf den Träger):
     * look = folgt dem Kopf des Tiers (Drehpunkt `rig.neck`), quack = Unterschnabel klappt um `pivot` auf,
     * blink = Auge schließt sich (um die Würfelmitte), wing = Flügel schlägt im Sprung um `pivot`.
     */
    anim: z.enum(['flap', 'bob', 'spin', 'look', 'quack', 'blink', 'wing']).optional(),
  })
  .superRefine((c, ctx) => {
    for (let a = 0; a < 3; a++) {
      const size = c.to[a]! - c.from[a]!
      if (!Number.isInteger(size) || size < 1 || size > 32) {
        ctx.addIssue({ code: 'custom', message: 'cube size must be a whole number 1..32 on every axis', path: ['to', a] })
      }
    }
    if ((c.anim === 'flap' || c.anim === 'spin' || c.anim === 'quack' || c.anim === 'wing') && !c.pivot) {
      ctx.addIssue({ code: 'custom', message: `anim "${c.anim}" needs a pivot`, path: ['pivot'] })
    }
    if ((c.anim === 'flap' || c.anim === 'wing') && c.pivot && (c.from[0] + c.to[0]) / 2 === c.pivot[0]) {
      ctx.addIssue({ code: 'custom', message: 'flap needs the cube centre left or right of the pivot', path: ['pivot'] })
    }
  })

const spriteSchema = z.strictObject({
  uv,
  size: z.tuple([z.int().min(1).max(64), z.int().min(1).max(64)]),
})

const particleBase = {
  attach: z.enum(['root', 'head', 'body']),
  /** Kantenlänge des Partikel-Quads in Modell-Einheiten (längere Sprite-Seite). */
  size: z.number().min(0.25).max(16),
  orientation: z.enum(['billboard', 'ground']),
  sprites: z.array(spriteSchema).min(1).max(16),
}

const particlesSchema = z.discriminatedUnion('pattern', [
  z.strictObject({
    pattern: z.literal('ring'),
    ...particleBase,
    center: vec3,
    radius: z.number().min(0.5).max(32),
    count: z.int().min(1).max(32),
    speedDegPerSec: z.number().min(-720).max(720),
  }),
  z.strictObject({
    pattern: z.literal('orbit'),
    ...particleBase,
    center: vec3,
    radius: z.number().min(0.5).max(32),
    count: z.int().min(1).max(32),
    speedDegPerSec: z.number().min(-720).max(720),
    height: z.number().min(0).max(32),
    periodMs: z.int().min(200).max(60_000),
  }),
  z.strictObject({
    pattern: z.literal('trail'),
    ...particleBase,
    offset: vec3,
    sideOffset: z.number().min(0).max(8),
    spacingBlocks: z.number().min(0.1).max(4),
    lifetimeMs: z.int().min(100).max(10_000),
    maxParticles: z.int().min(1).max(64),
  }),
])

const base = {
  id: z.string().regex(TEMPLATE_ID),
  name: z.string().min(1).max(32),
  textureWidth: texSize,
  textureHeight: texSize,
}

const templateSchema = z.discriminatedUnion('kind', [
  z.strictObject({
    ...base,
    kind: z.literal('model'),
    slot: z.enum(WEARABLE_SLOTS),
    cubes: z.array(cubeSchema).min(1).max(32),
    /**
     * Tier-Kosmetik mit eigenem Verhalten (watscheln, umschauen, blinzeln, Flügel, quaken) – die Mod bewegt
     * das ganze Modell nach dem Träger. `neck` = Drehpunkt des Tierkopfs für `look`/`quack`/`blink`.
     */
    rig: z.strictObject({ type: z.literal('duck'), neck: vec3 }).optional(),
  }),
  z.strictObject({
    ...base,
    kind: z.literal('particles'),
    slot: z.literal('aura'),
    particles: particlesSchema,
  }),
])

export type Cube = z.infer<typeof cubeSchema>
export type ParticleDef = z.infer<typeof particlesSchema>
export type Template = z.infer<typeof templateSchema>

export interface Rect {
  x: number
  y: number
  w: number
  h: number
}

export const FACES = ['top', 'bottom', 'right', 'front', 'left', 'back'] as const
export type FaceName = (typeof FACES)[number]

/**
 * Vanilla-Box-UV-Netz (wie `ModelPart` / Skin-Kopf) für einen Würfel, in Einheiten
 * der Textur bei scale 1. w = Größe auf x, h = auf y, d = auf z.
 */
export function cubeFaces(c: Pick<Cube, 'from' | 'to' | 'uv'>): Record<FaceName, Rect> {
  const w = c.to[0] - c.from[0]
  const h = c.to[1] - c.from[1]
  const d = c.to[2] - c.from[2]
  const [u, v] = c.uv
  return {
    top: { x: u + d, y: v, w, h: d },
    bottom: { x: u + d + w, y: v, w, h: d },
    right: { x: u, y: v + d, w: d, h },
    front: { x: u + d, y: v + d, w, h },
    left: { x: u + d + w, y: v + d, w: d, h },
    back: { x: u + 2 * d + w, y: v + d, w, h },
  }
}

/** Alle Texturbereiche, die eine Vorlage tatsächlich benutzt (Würfelflächen bzw. Sprites). */
export function usedRects(t: Template): Rect[] {
  if (t.kind === 'model') return t.cubes.flatMap((c) => Object.values(cubeFaces(c)).filter((r) => r.w > 0 && r.h > 0))
  return t.particles.sprites.map((s) => ({ x: s.uv[0], y: s.uv[1], w: s.size[0], h: s.size[1] }))
}

/** Maske der benutzten Pixel (Breite × Höhe bei `scale`), 1 = wird gerendert. */
export function usedMask(t: Template, scale: number): Uint8Array {
  const w = t.textureWidth * scale
  const mask = new Uint8Array(w * t.textureHeight * scale)
  for (const r of usedRects(t)) {
    for (let y = r.y * scale; y < (r.y + r.h) * scale; y++) {
      mask.fill(1, y * w + r.x * scale, y * w + (r.x + r.w) * scale)
    }
  }
  return mask
}

const overlaps = (a: Rect, b: Rect) => a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h

/** Prüft eine Vorlage über das Schema hinaus: Bereiche in der Textur, keine Überlappungen. */
function checkLayout(t: Template, report: (msg: string) => void): void {
  const rects = usedRects(t)
  for (const r of rects) {
    if (r.x + r.w > t.textureWidth || r.y + r.h > t.textureHeight) {
      report(`${t.id}: texture region ${r.x},${r.y} ${r.w}x${r.h} exceeds ${t.textureWidth}x${t.textureHeight}`)
    }
  }
  for (let i = 0; i < rects.length; i++) {
    for (let j = i + 1; j < rects.length; j++) {
      if (overlaps(rects[i]!, rects[j]!)) report(`${t.id}: texture regions ${i} and ${j} overlap`)
    }
  }
}

export const templatesFileSchema = z
  .strictObject({
    version: z.literal(1),
    templates: z.array(templateSchema).min(1).max(64),
  })
  .superRefine((f, ctx) => {
    const ids = new Set<string>()
    for (const t of f.templates) {
      if (ids.has(t.id)) ctx.addIssue({ code: 'custom', message: `duplicate template id ${t.id}` })
      if (t.kind === 'model' && !t.rig && t.cubes.some((c) => c.anim === 'look' || c.anim === 'quack' || c.anim === 'blink' || c.anim === 'wing')) {
        ctx.addIssue({ code: 'custom', message: `${t.id}: look/quack/blink/wing need a rig` })
      }
      ids.add(t.id)
      checkLayout(t, (message) => ctx.addIssue({ code: 'custom', message }))
    }
  })

export type TemplatesFile = z.infer<typeof templatesFileSchema>

/** Geladene Vorlagen + fertig serialisierte Antwort (mit ETag) für `GET /v1/cosmetics/templates`. */
export class TemplateSet {
  readonly byId: ReadonlyMap<string, Template>
  readonly list: readonly Template[]
  readonly json: string
  readonly etag: string

  constructor(file: TemplatesFile) {
    this.list = file.templates
    this.byId = new Map(file.templates.map((t) => [t.id, t]))
    this.json = JSON.stringify({ version: file.version, templates: file.templates })
    this.etag = `"${sha256Hex(this.json).slice(0, 32)}"`
  }

  static empty(): TemplateSet {
    return Object.assign(Object.create(TemplateSet.prototype) as TemplateSet, {
      byId: new Map(),
      list: [],
      json: JSON.stringify({ version: 1, templates: [] }),
      etag: '"empty"',
    })
  }

  get(id: string): Template | undefined {
    return this.byId.get(id)
  }
}

export function parseTemplates(raw: unknown): TemplateSet {
  return new TemplateSet(templatesFileSchema.parse(raw))
}

// ------------------------------------------------------------ Mal-Vorlage (Guide)

/** Farben je Fläche (hell = oben, dunkel = unten), damit das Netz beim Malen erkennbar ist. */
const FACE_COLORS: Record<FaceName, [number, number, number]> = {
  top: [120, 200, 255],
  bottom: [40, 90, 150],
  right: [255, 170, 70],
  front: [230, 60, 60],
  left: [120, 210, 110],
  back: [170, 110, 230],
}
const SPRITE_COLOR: [number, number, number] = [240, 200, 60]

const guideCache = new Map<string, Buffer>()

/**
 * PNG, das die benutzten Bereiche einer Vorlage farbig zeigt (Rahmen = Kante der
 * Fläche). Grundlage für Mal-Editoren; alles Transparente wird nie gerendert.
 */
export function guidePng(t: Template, scale: number): Buffer {
  const key = `${t.id}:${scale}:${t.textureWidth}x${t.textureHeight}`
  const hit = guideCache.get(key)
  if (hit) return hit
  const w = t.textureWidth * scale
  const h = t.textureHeight * scale
  const data = Buffer.alloc(w * h * 4)
  const paint = (r: Rect, rgb: [number, number, number], shade: number) => {
    for (let y = r.y * scale; y < (r.y + r.h) * scale; y++) {
      for (let x = r.x * scale; x < (r.x + r.w) * scale; x++) {
        const edge = x === r.x * scale || y === r.y * scale || x === (r.x + r.w) * scale - 1 || y === (r.y + r.h) * scale - 1
        const f = (edge ? 0.6 : 1) * shade
        const i = (y * w + x) * 4
        data[i] = Math.round(rgb[0] * f)
        data[i + 1] = Math.round(rgb[1] * f)
        data[i + 2] = Math.round(rgb[2] * f)
        data[i + 3] = 255
      }
    }
  }
  if (t.kind === 'model') {
    t.cubes.forEach((c, n) => {
      const faces = cubeFaces(c)
      for (const f of FACES) paint(faces[f], FACE_COLORS[f], n % 2 === 0 ? 1 : 0.8)
    })
  } else {
    t.particles.sprites.forEach((s, n) =>
      paint({ x: s.uv[0], y: s.uv[1], w: s.size[0], h: s.size[1] }, SPRITE_COLOR, n % 2 === 0 ? 1 : 0.8))
  }
  const png = encodeRgba(w, h, data)
  if (guideCache.size > 256) guideCache.clear()
  guideCache.set(key, png)
  return png
}
