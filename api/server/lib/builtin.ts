import { z } from 'zod'
import type { BuiltinCape } from './capes'
import type { BuiltinCosmetic } from './cosmetics'
import { CAPE_ID, COSMETIC_ID } from './ids'
import { BUILTIN_MAX_SCALE } from './png'
import { TEMPLATE_ID } from './templates'

const entry = z
  .object({
    id: z.string().regex(CAPE_ID).refine((s) => !/^u[0-9a-f]{20}$/.test(s), 'reserved for uploads'),
    name: z.string().min(1).max(32),
    unlock: z.enum(['free', 'code', 'admin']),
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

const cosmeticEntry = z
  .object({
    id: z.string().regex(COSMETIC_ID).refine((s) => !/^c[0-9a-f]{20}$/.test(s), 'reserved for uploads'),
    name: z.string().min(1).max(32),
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

/** Format von `assets/cosmetics/catalog.json` (Liste oder `{ cosmetics: [...] }`). */
export const cosmeticCatalogSchema = z
  .union([z.array(cosmeticEntry), z.object({ cosmetics: z.array(cosmeticEntry) }).transform((o) => o.cosmetics)])
  .refine((list) => list.length <= 200, 'too many cosmetics')
  .refine((list) => new Set(list.map((c) => c.id)).size === list.length, 'duplicate cosmetic id')

export async function loadBuiltinCosmetics(
  readJson: () => Promise<unknown>,
  readFile: (name: string) => Promise<Buffer | null>,
): Promise<BuiltinCosmetic[]> {
  const raw = await readJson()
  if (raw === null || raw === undefined) return []
  const list = cosmeticCatalogSchema.parse(raw)
  const out: BuiltinCosmetic[] = []
  let sort = 0
  for (const c of list) {
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
      unlock: c.unlock,
      sort: sort++,
      scale: c.scale,
      frames: c.frames,
      frameTimeMs: c.frames > 1 ? (c.frameTimeMs ?? null) : null,
      png,
    })
  }
  return out
}
