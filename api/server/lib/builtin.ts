import { z } from 'zod'
import type { BuiltinCape } from './capes'
import { CAPE_ID } from './ids'
import { MAX_SCALE } from './png'

const entry = z
  .object({
    id: z.string().regex(CAPE_ID).refine((s) => !/^u[0-9a-f]{20}$/.test(s), 'reserved for uploads'),
    name: z.string().min(1).max(32),
    unlock: z.enum(['free', 'code', 'admin']),
    file: z.string().regex(/^[a-z0-9_-]+\.png$/),
    scale: z.int().min(1).max(MAX_SCALE).default(1),
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
