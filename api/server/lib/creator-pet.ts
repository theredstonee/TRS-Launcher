import { rmSync } from 'node:fs'
import { join } from 'node:path'
import type { AppContext } from './context'
import { canUseCosmetic, getCosmetic, renderable, v2Assets, v2Fields, type CosmeticRow } from './cosmetics'
import { all, one, run, tx } from './db'
import { petCount, petCountForFans } from './creator-lookup'
import { emitCosmetics } from './playerevents'

/**
 * Creator-Haustiere (§35.13): NUR der Creator, dem ein Haustier gehört (`creator_pets`), bekommt es automatisch
 * (`user_cosmetics.source = 'creator'`), solange er aktiv ist – beim ersten Zuteilen auch ausgerüstet, wenn sein Platz
 * `pet` leer ist. Die Tier-Anzahl richtet sich nach seinen Fans (mindestens 1). Endet der Besitz (Team nimmt es weg,
 * Creator entzogen/gesperrt/gelöscht), fällt NUR diese Zuteilung weg – Code-/Team-Freischaltungen und die bis Migration 35
 * automatisch vergebenen Pinguine (seitdem `admin`) bleiben. `creators.pet_count` hält die zuletzt gemeldete Stufe (0 =
 * kein eigenes Haustier); ändert sie sich, bekommen Beobachter ein `cosmetics`-Ereignis. Ist das Teil nicht geladen
 * (private Dateien fehlen) oder ausgemustert, bleibt alles, wie es ist.
 */

/** Zum Zuteilen taugliches Haustier (mitgeliefert, geladen, nicht ausgemustert). */
export function usablePet(ctx: AppContext, id: string): CosmeticRow | undefined {
  const pet = getCosmetic(ctx, id)
  return pet && pet.kind === 'builtin' && !pet.retired && renderable(ctx, pet) ? pet : undefined
}

export function syncCreatorPet(ctx: AppContext, creator: string): void {
  const c = one<{ state: string, pet_count: number }>(ctx.db, 'SELECT state, pet_count FROM creators WHERE uuid = ?', creator)
  const owned = c ? one<{ cosmetic_id: string }>(ctx.db, 'SELECT cosmetic_id FROM creator_pets WHERE creator_uuid = ?', creator) : undefined
  const pet = owned ? getCosmetic(ctx, owned.cosmetic_id) : undefined
  // Eigenes Haustier vorhanden, aber (noch) nicht geladen: nichts anfassen.
  if (owned && (!pet || !usablePet(ctx, pet.id))) return
  const active = c?.state === 'active' && pet !== undefined
  const fans = active
    ? one<{ n: number }>(ctx.db, 'SELECT COUNT(*) AS n FROM creator_supports WHERE creator_uuid = ?', creator)!.n
    : 0
  const want = active ? Math.max(1, petCountForFans(fans)) : 0
  const visual = tx(ctx.db, () => {
    let changed = false
    // Zuerst Besitz-Zuteilungen für andere Teile (früheres Haustier) bzw. alle, wenn der Creator nicht (mehr) zählt –
    // so wird der Platz `pet` frei, bevor das neue Haustier ausgerüstet wird.
    const stale = all<{ cosmetic_id: string }>(
      ctx.db,
      "SELECT cosmetic_id FROM user_cosmetics WHERE uuid = ? AND source = 'creator' AND cosmetic_id <> ?",
      creator, active ? pet!.id : '',
    )
    for (const s of stale) {
      run(ctx.db, "DELETE FROM user_cosmetics WHERE uuid = ? AND cosmetic_id = ? AND source = 'creator'", creator, s.cosmetic_id)
      const row = getCosmetic(ctx, s.cosmetic_id)
      if (!row || !canUseCosmetic(ctx, creator, row)) {
        if (run(ctx.db, 'DELETE FROM equipped_cosmetics WHERE uuid = ? AND cosmetic_id = ?', creator, s.cosmetic_id) > 0) changed = true
      }
    }
    if (active && one(ctx.db, 'SELECT 1 AS x FROM users WHERE uuid = ?', creator) !== undefined) {
      const fresh = run(
        ctx.db,
        "INSERT INTO user_cosmetics (uuid, cosmetic_id, source, granted_at) VALUES (?, ?, 'creator', ?) ON CONFLICT DO NOTHING",
        creator, pet!.id, ctx.now(),
      ) > 0
      if (fresh) {
        changed = run(ctx.db, "INSERT INTO equipped_cosmetics (uuid, slot, cosmetic_id) VALUES (?, 'pet', ?) ON CONFLICT DO NOTHING", creator, pet!.id) > 0
      }
    }
    if (c && c.pet_count !== want) {
      run(ctx.db, 'UPDATE creators SET pet_count = ? WHERE uuid = ?', want, creator)
      // Die Anzahl ändert sich nur sichtbar, wenn er gerade ein Haustier trägt.
      if (one(ctx.db, "SELECT 1 AS x FROM equipped_cosmetics WHERE uuid = ? AND slot = 'pet'", creator)) changed = true
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
 * Alle abgleichen (Start + alle 10 min): fängt Änderungen ab, die nicht über creators.ts laufen (z. B. ein Fan
 * löscht sein Konto, ein Haustier wird erst jetzt geladen). Billig: nur Creator-Zeilen und Besitz-Zuteilungen.
 */
export function syncAllCreatorPets(ctx: AppContext): void {
  const ids = new Set<string>()
  for (const r of all<{ uuid: string }>(ctx.db, 'SELECT uuid FROM creators WHERE pet_count > 0 OR uuid IN (SELECT creator_uuid FROM creator_pets)')) ids.add(r.uuid)
  for (const r of all<{ uuid: string }>(ctx.db, "SELECT DISTINCT uuid FROM user_cosmetics WHERE source = 'creator'")) ids.add(r.uuid)
  for (const id of ids) syncCreatorPet(ctx, id)
}

// ---------------------------------------------------------------- Sicht + Dateien (für creators.ts ohne Import-Kreis)

/** Eigenes Haustier eines Creators (Dashboard, §35.13). `available = false`: Teil gerade nicht geladen/ausgemustert. */
export interface CreatorPetRef {
  cosmeticId: string
  name: string
  /** Vorschaubild (Format v2) oder `null`. */
  card: string | null
  /** Tiere, die er gerade bekommt (1…4 nach Fans; 1 ohne Fans). */
  count: number
  available: boolean
  assignedAt: string
}

export function creatorPetRef(ctx: AppContext, creator: string): CreatorPetRef | null {
  const r = one<{ cosmetic_id: string, assigned_at: number, state: string }>(
    ctx.db,
    'SELECT p.cosmetic_id, p.assigned_at, c.state FROM creator_pets p JOIN creators c ON c.uuid = p.creator_uuid WHERE p.creator_uuid = ?',
    creator,
  )
  if (!r) return null
  const row = getCosmetic(ctx, r.cosmetic_id)
  const v2 = row ? v2Assets(ctx, row) : undefined
  const fans = one<{ n: number }>(ctx.db, 'SELECT COUNT(*) AS n FROM creator_supports WHERE creator_uuid = ?', creator)!.n
  return {
    cosmeticId: r.cosmetic_id,
    name: row?.name ?? r.cosmetic_id,
    card: v2 ? v2Fields(v2, ctx.config.publicBaseUrl).card : null,
    count: r.state === 'active' ? petCount(r.cosmetic_id, { cosmeticId: r.cosmetic_id, fans }) : 1,
    available: usablePet(ctx, r.cosmetic_id) !== undefined,
    assignedAt: new Date(r.assigned_at).toISOString(),
  }
}

export function petImageDir(ctx: AppContext): string {
  return join(ctx.config.dataDir, 'creator-pet-images')
}

export const petImageExt = (mime: string): 'png' | 'jpg' => (mime === 'image/png' ? 'png' : 'jpg')

/** Bilddatei löschen, wenn kein Wunsch sie mehr nutzt. */
export function dropPetImage(ctx: AppContext, sha: string, mime: string): void {
  if (one(ctx.db, 'SELECT 1 AS x FROM creator_pet_requests WHERE image_sha256 = ? AND image_mime = ?', sha, mime)) return
  rmSync(join(petImageDir(ctx), `${sha}.${petImageExt(mime)}`), { force: true })
}

/** Bilder der Wünsche eines Creators (vor dem Löschen einsammeln, danach mit `dropPetImage` aufräumen). */
export function petImagesOf(ctx: AppContext, creator: string): { sha: string, mime: string }[] {
  return all<{ sha: string, mime: string }>(
    ctx.db,
    'SELECT DISTINCT image_sha256 AS sha, image_mime AS mime FROM creator_pet_requests WHERE creator_uuid = ? AND image_sha256 IS NOT NULL',
    creator,
  )
}
