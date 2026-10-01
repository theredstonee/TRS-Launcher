import { z } from 'zod'
import type { BuiltinCape } from './capes'
import type { AnyBuiltinCosmetic } from './cosmetics'
import { buildV2Cosmetic } from './cosmetics-v2'
import { CAPE_ID, COSMETIC_ID } from './ids'
import { BUILTIN_MAX_SCALE } from './png'
import { TEMPLATE_ID } from './templates'

/**
 * Freischaltung eines Katalog-Eintrags: `free` | `code` | `admin` oder `{ "type": "event", "event": "halloween" }`
 * (Event-Teile: gratis abholbar, solange das Event für den Spieler aktiv ist). In der DB steht dann `admin` + Spalte `event`.
 */
const eventUnlock = z.strictObject({ type: z.literal('event'), event: z.string().regex(/^[a-z][a-z0-9_]{0,31}$/) })
const unlockField = z.union([z.enum(['free', 'code', 'admin']), eventUnlock])
const splitUnlock = (u: z.output<typeof unlockField>): { unlock: 'free' | 'code' | 'admin', event?: string } =>
  typeof u === 'string' ? { unlock: u } : { unlock: 'admin', event: u.event }

const entry = z
  .object({
    id: z.string().regex(CAPE_ID).refine((s) => !/^u[0-9a-f]{20}$/.test(s), 'reserved for uploads'),
    name: z.string().min(1).max(32),
    unlock: unlockField,
    file: z.string().regex(/^[a-z0-9_-]+\.png$/),
    scale: z.int().min(1).max(BUILTIN_MAX_SCALE).default(1),
    animated: z.boolean().optional(),
    frames: z.int().min(1).max(64).default(1),
    frameTimeMs: z.int().min(20).max(10_000).nullish(),
  })
  .refine((c) => c.frames === 1 || (c.frameTimeMs !== null && c.frameTimeMs !== undefined), 'animated capes need frameTimeMs')
  .refine((c) => c.animated === undefined || c.animated === c.frames > 1, 'animated must match frames > 1')

/**
 * Format von `assets/capes/catalog.json`: Liste der Standard-Umhänge
 * (oder `{ capes: [...] }`). Unbekannte Zusatzfelder werden ignoriert.
 */
export const catalogSchema = z
  .union([z.array(entry), z.object({ capes: z.array(entry) }).transform((o) => o.capes)])
  .refine((list) => list.length <= 100, 'too many capes')
  .refine((list) => new Set(list.map((c) => c.id)).size === list.length, 'duplicate cape id')

const cosmeticId = z.string().regex(COSMETIC_ID).refine((s) => !/^c[0-9a-f]{20}$/.test(s), 'reserved for uploads')

/**
 * Format-v2-Eintrag (§11.9): Dateien liegen fest unter `v2/<id>.json`, `v2/<id>.png`, `v2/<id>-glow.png`,
 * `v2/<id>-card.png`, `v2/<id>-card-night.png`. `frames`/`glowFrames` (+ Zeiten) müssen zum Modell passen.
 */
const cosmeticV2Entry = z
  .strictObject({
    id: cosmeticId.refine((s) => /^[a-z][a-z0-9_]{0,39}$/.test(s), 'v2 ids: a–z, 0–9, _ (start with a letter)'),
    name: z.string().min(1).max(32),
    format: z.literal(2),
    unlock: unlockField,
    frames: z.int().min(1).max(16).default(1),
    frameTimeMs: z.int().min(16).max(10_000).nullish(),
    glowFrames: z.int().min(0).max(16).default(0),
    glowFrameTimeMs: z.int().min(16).max(10_000).nullish(),
    hidden: z.boolean().default(false),
  })
  .refine((c) => !c.hidden || c.unlock === 'code', 'hidden cosmetics must be unlocked by code')

const cosmeticV1Entry = z
  .object({
    id: cosmeticId,
    name: z.string().min(1).max(32),
    format: z.literal(1).optional(),
    template: z.string().regex(TEMPLATE_ID),
    unlock: z.enum(['free', 'code', 'admin']),
    file: z.string().regex(/^[a-z0-9_-]+\.png$/),
    scale: z.int().min(1).max(BUILTIN_MAX_SCALE).default(1),
    animated: z.boolean().optional(),
    frames: z.int().min(1).max(64).default(1),
    frameTimeMs: z.int().min(20).max(10_000).nullish(),
    emissive: z.boolean().default(false),
    /** Versteckt: erscheint nur bei denen, die es besitzen (per Code). Nur zusammen mit unlock "code". */
    hidden: z.boolean().default(false),
  })
  .refine((c) => !c.hidden || c.unlock === 'code', 'hidden cosmetics must be unlocked by code')
  .refine((c) => c.frames === 1 || (c.frameTimeMs !== null && c.frameTimeMs !== undefined), 'animated cosmetics need frameTimeMs')
  .refine((c) => c.animated === undefined || c.animated === c.frames > 1, 'animated must match frames > 1')

const cosmeticEntry = z.union([cosmeticV2Entry, cosmeticV1Entry])

/** Format von `assets/cosmetics/catalog.json` (Liste oder `{ cosmetics: [...] }`). */
export const cosmeticCatalogSchema = z
  .union([z.array(cosmeticEntry), z.object({ cosmetics: z.array(cosmeticEntry) }).transform((o) => o.cosmetics)])
  .refine((list) => list.length <= 200, 'too many cosmetics')
  .refine((list) => new Set(list.map((c) => c.id)).size === list.length, 'duplicate cosmetic id')

/**
 * Liest den Katalog samt Dateien. v2-Teile werden vollständig geprüft (Modell nach dem Format, Bildmaße,
 * Übereinstimmung mit dem Katalog-Eintrag) – ein ungültiges Modell lässt den Start scheitern.
 */
export async function loadBuiltinCosmetics(
  readJson: () => Promise<unknown>,
  readFile: (name: string) => Promise<Buffer | null>,
): Promise<AnyBuiltinCosmetic[]> {
  const raw = await readJson()
  if (raw === null || raw === undefined) return []
  const list = cosmeticCatalogSchema.parse(raw)
  const out: AnyBuiltinCosmetic[] = []
  let sort = 0
  for (const c of list) {
    if (c.format === 2) {
      const need = async (name: string) => {
        const buf = await readFile(`v2/${name}`)
        if (!buf) throw new Error(`builtin cosmetic ${c.id}: file missing: v2/${name}`)
        return buf
      }
      const modelJson = await need(`${c.id}.json`)
      const texture = await need(`${c.id}.png`)
      const glow = c.glowFrames > 0 ? await need(`${c.id}-glow.png`) : null
      const card = await need(`${c.id}-card.png`)
      const cardNight = await need(`${c.id}-card-night.png`)
      out.push(buildV2Cosmetic({ ...c, ...splitUnlock(c.unlock), sort: sort++, files: { modelJson, texture, glow, card, cardNight } }))
      continue
    }
    const png = await readFile(c.file)
    if (!png) throw new Error(`builtin cosmetic file missing: ${c.file}`)
    out.push({
      id: c.id,
      name: c.name,
      template: c.template,
      unlock: c.unlock,
      sort: sort++,
      scale: c.scale,
      frames: c.frames,
      frameTimeMs: c.frames > 1 ? (c.frameTimeMs ?? null) : null,
      emissive: c.emissive,
      hidden: c.hidden,
      png,
    })
  }
  return out
}

export async function loadBuiltins(
  readJson: () => Promise<unknown>,
  readFile: (name: string) => Promise<Buffer | null>,
): Promise<BuiltinCape[]> {
  const raw = await readJson()
  if (raw === null || raw === undefined) return []
  const list = catalogSchema.parse(raw)
  const out: BuiltinCape[] = []
  let sort = 0
  for (const c of list) {
    const png = await readFile(c.file)
    if (!png) throw new Error(`builtin cape file missing: ${c.file}`)
    out.push({
      id: c.id,
      name: c.name,
      ...splitUnlock(c.unlock),
      sort: sort++,
      scale: c.scale,
      frames: c.frames,
      frameTimeMs: c.frames > 1 ? (c.frameTimeMs ?? null) : null,
      png,
    })
  }
  return out
}
