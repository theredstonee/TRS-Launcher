import type { AppContext } from './context'
import { canUseCosmetic, getCosmetic, renderable } from './cosmetics'
import { all, one, run, tx } from './db'
import { CREATOR_PET_ID, petCountForFans } from './creator-lookup'
import { emitCosmetics } from './playerevents'

/**
 * Creator-Pinguine (Winter 2026): aktive Creator mit ≥ 1 Fan bekommen `penguin` automatisch (`user_cosmetics.source =
 * 'creator'`) und beim ersten Zuteilen ausgerüstet, wenn ihr Platz `pet` leer ist. Fällt ein Creator auf 0 Fans oder ist
 * er nicht mehr aktiv (entzogen, gesperrt, gelöscht), fällt NUR diese Zuteilung weg – eine Code-/Team-Freischaltung bleibt.
 * `creators.pet_count` hält die zuletzt gemeldete Stufe; ändert sie sich, bekommen Beobachter ein `cosmetics`-Ereignis
 * (der Lookup liefert `cosmetics.pet.count`). Ohne geladenes Pinguin-Teil (private Dateien fehlen) passiert nichts.
 */
export function syncCreatorPet(ctx: AppContext, creator: string): void {
  const pet = getCosmetic(ctx, CREATOR_PET_ID)
  if (!pet || pet.kind !== 'builtin' || pet.retired || !renderable(ctx, pet)) return
  const c = one<{ state: string, pet_count: number }>(ctx.db, 'SELECT state, pet_count FROM creators WHERE uuid = ?', creator)
  const fans = c?.state === 'active'
    ? one<{ n: number }>(ctx.db, 'SELECT COUNT(*) AS n FROM creator_supports WHERE creator_uuid = ?', creator)!.n
    : 0
  const want = petCountForFans(fans)
  const visual = tx(ctx.db, () => {
    let changed = false
    if (want > 0) {
      if (one(ctx.db, 'SELECT 1 AS x FROM users WHERE uuid = ?', creator) === undefined) return false
      const fresh = run(
        ctx.db,
        "INSERT INTO user_cosmetics (uuid, cosmetic_id, source, granted_at) VALUES (?, ?, 'creator', ?) ON CONFLICT DO NOTHING",
        creator, pet.id, ctx.now(),
      ) > 0
      if (fresh) {
        changed = run(ctx.db, "INSERT INTO equipped_cosmetics (uuid, slot, cosmetic_id) VALUES (?, 'pet', ?) ON CONFLICT DO NOTHING", creator, pet.id) > 0
      }
    } else {
      const removed = run(ctx.db, "DELETE FROM user_cosmetics WHERE uuid = ? AND cosmetic_id = ? AND source = 'creator'", creator, pet.id) > 0
      if (removed && !canUseCosmetic(ctx, creator, pet)) {
        changed = run(ctx.db, 'DELETE FROM equipped_cosmetics WHERE uuid = ? AND cosmetic_id = ?', creator, pet.id) > 0
      }
    }
    if (c && c.pet_count !== want) {
      run(ctx.db, 'UPDATE creators SET pet_count = ? WHERE uuid = ?', want, creator)
      const wearing = one(ctx.db, "SELECT 1 AS x FROM equipped_cosmetics WHERE uuid = ? AND slot = 'pet' AND cosmetic_id = ?", creator, pet.id)
      if (wearing) changed = true
    }
    return changed
  })
  if (visual) emitCosmetics(ctx, creator)
}

/** Mehrere Creator nacheinander (z. B. alter und neuer Creator eines Fans). `null`/doppelte werden übersprungen. */
export function syncCreatorPets(ctx: AppContext, creators: readonly (string | null | undefined)[]): void {
  for (const c of new Set(creators)) if (c) syncCreatorPet(ctx, c)
}

/**
 * Alle Creator abgleichen (Start + alle 10 min): fängt Änderungen ab, die nicht über creators.ts laufen (z. B. ein Fan
 * löscht sein Konto). Billig: nur Creator-Zeilen und Creator-Zuteilungen.
 */
export function syncAllCreatorPets(ctx: AppContext): void {
  const ids = new Set<string>()
  for (const r of all<{ uuid: string }>(ctx.db, 'SELECT uuid FROM creators')) ids.add(r.uuid)
  for (const r of all<{ uuid: string }>(ctx.db, "SELECT uuid FROM user_cosmetics WHERE cosmetic_id = ? AND source = 'creator'", CREATOR_PET_ID)) ids.add(r.uuid)
  for (const id of ids) syncCreatorPet(ctx, id)
}
