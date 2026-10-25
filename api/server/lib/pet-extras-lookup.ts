import type { AppContext } from './context'
import type { CosmeticRow } from './cosmetics'
import { all, placeholders } from './db'

/**
 * Begleiter-Gefolge (§35.13) – nur Lesen, ohne Import von cosmetics.ts/playerevents.ts (lookup.ts und cosmetics.ts
 * nutzen es, ohne einen Import-Kreis zu bilden).
 *
 * Ein AKTIVER Creator mit eingeschaltetem „Gefolge zeigen“ (`creators.show_pet_extras`) zeigt zusätzlich zu seinem
 * Begleiter im Platz `pet` bis zu {@link MAX_PET_EXTRAS} weitere (Team-Zuordnung, Reihenfolge `position`). Nur
 * mitgelieferte, nicht ausgemusterte, geladene Teile (Format 2) mit Modell-Platz `pet` zählen.
 */

export const MAX_PET_EXTRAS = 4

/** Größe eines Begleiters (Katalog `petScale`, Standard 1). Unbekannt/nicht geladen → 1. */
export function petScaleOf(ctx: AppContext, id: string): number {
  return ctx.cosmeticsV2.get(id)?.petScale ?? 1
}

/** Ist das Teil gerade als Begleiter darstellbar (mitgeliefert, Format 2, geladen, Platz `pet`, nicht ausgemustert)? */
export function isUsablePetRow(ctx: AppContext, c: Pick<CosmeticRow, 'id' | 'kind' | 'format' | 'retired'>): boolean {
  return c.kind === 'builtin' && c.format === 2 && !c.retired && ctx.cosmeticsV2.get(c.id)?.model.slot === 'pet'
}

/**
 * Sichtbares Gefolge je Creator unter `uuids` (in Reihenfolge): nur aktive Creator mit Schalter an; nicht darstellbare
 * Teile fehlen. Wer nichts zeigt, fehlt in der Map.
 */
export function visiblePetExtras(ctx: AppContext, uuids: readonly string[]): Map<string, CosmeticRow[]> {
  const out = new Map<string, CosmeticRow[]>()
  if (uuids.length === 0) return out
  const rows = all<CosmeticRow & { ex_uuid: string }>(
    ctx.db,
    `SELECT c.*, x.creator_uuid AS ex_uuid FROM creator_pet_extras x
     JOIN creators k ON k.uuid = x.creator_uuid AND k.state = 'active' AND k.show_pet_extras = 1
     JOIN cosmetics c ON c.id = x.cosmetic_id
     WHERE x.creator_uuid IN (${placeholders(uuids.length)})
     ORDER BY x.creator_uuid, x.position`,
    ...uuids,
  )
  for (const r of rows) {
    if (!isUsablePetRow(ctx, r)) continue
    const list = out.get(r.ex_uuid)
    if (list) list.push(r)
    else out.set(r.ex_uuid, [r])
  }
  return out
}
