import { z } from 'zod'
import { audit } from './audit'
import type { AppContext } from './context'
import { getCosmetic, slotOf, v2Assets, v2Fields } from './cosmetics'
import type { CreatorState, CreatorTier } from './creator-lookup'
import { petCosmeticOptions, type PetCosmeticOption } from './creator-pets'
import { actorFor, auditActor, notifyCreator, parse } from './creators'
import { all, one, run, tx } from './db'
import { badRequest, notFound } from './errors'
import { isUsablePetRow, MAX_PET_EXTRAS, petScaleOf } from './pet-extras-lookup'
import { emitCosmetics } from './playerevents'
import type { Staff } from './team'
import { assertCan } from './team'

/**
 * Begleiter-Gefolge (API.md §35.13): Das Team ordnet einem Creator zusätzlich zu seinem Creator-Haustier bis zu
 * {@link MAX_PET_EXTRAS} weitere Begleiter (Kosmetik mit Platz `pet`) in fester Reihenfolge zu. Nur der Creator selbst
 * zeigt sie (keine Codes, keine Fans), und nur solange er aktiv ist, einen Begleiter trägt und „Gefolge zeigen“ an ist.
 * Ein Extra darf ein Teil sein, das sonst niemandem gehört (Team-Pet), oder dasselbe wie bei anderen Creatorn.
 * Jede sichtbare Änderung schickt Beobachtern ein `cosmetics`-Ereignis (Lookup-Form mit `pet.extras`).
 */

const cosmeticIdSchema = z.string().trim().min(1).max(64).regex(/^[A-Za-z0-9_.-]+$/, 'invalid cosmetic id')

export const adminPetExtrasBody = z.strictObject({
  /** Vollständige Liste in Reihenfolge (ersetzt die bisherige; `[]` entfernt alle). */
  cosmeticIds: z
    .array(cosmeticIdSchema)
    .max(MAX_PET_EXTRAS)
    .refine((l) => new Set(l).size === l.length, 'duplicate cosmetic id'),
})
export const showPetExtrasBody = z.strictObject({ show: z.boolean() })

export interface PetExtraView {
  cosmeticId: string
  name: string
  /** Vorschaubild (Format v2) oder `null`. */
  card: string | null
  /** Größe relativ zu anderen Begleitern (1 = normal). */
  petScale: number
  /** Gerade darstellbar (geladen, nicht ausgemustert, Platz `pet`) – sonst wird es übersprungen. */
  available: boolean
}

export interface PetExtrasState {
  extras: PetExtraView[]
  /** Schalter „Gefolge zeigen“ des Creators (Standard an). */
  showExtras: boolean
  max: number
}

export interface AdminPetExtrasEntry extends PetExtrasState {
  creator: { uuid: string, name: string, tier: CreatorTier, state: CreatorState }
  updatedAt: string
  updatedBy: { uuid: string, name: string }
}

/** Zugeordnete Extras eines Creators in Reihenfolge (auch gerade nicht darstellbare). */
export function petExtrasOf(ctx: AppContext, creator: string): PetExtraView[] {
  return all<{ cosmetic_id: string }>(ctx.db, 'SELECT cosmetic_id FROM creator_pet_extras WHERE creator_uuid = ? ORDER BY position', creator).map((r) => {
    const row = getCosmetic(ctx, r.cosmetic_id)
    const v2 = row ? v2Assets(ctx, row) : undefined
    return {
      cosmeticId: r.cosmetic_id,
      name: row?.name ?? r.cosmetic_id,
      card: v2 ? v2Fields(v2, ctx.config.publicBaseUrl).card : null,
      petScale: petScaleOf(ctx, r.cosmetic_id),
      available: row !== undefined && isUsablePetRow(ctx, row),
    }
  })
}

function showOf(ctx: AppContext, creator: string): boolean {
  return (one<{ v: number }>(ctx.db, 'SELECT show_pet_extras AS v FROM creators WHERE uuid = ?', creator)?.v ?? 1) === 1
}

/** Gefolge + Schalter (Dashboard `GET /v1/creator/pet`, Antworten der Änderungs-Routen). */
export function petExtrasState(ctx: AppContext, creator: string): PetExtrasState {
  return { extras: petExtrasOf(ctx, creator), showExtras: showOf(ctx, creator), max: MAX_PET_EXTRAS }
}

/** Trägt der Creator gerade einen Begleiter (nur dann ändert sich für Beobachter etwas)? */
function wearsPet(ctx: AppContext, creator: string): boolean {
  return one(ctx.db, "SELECT 1 AS x FROM equipped_cosmetics WHERE uuid = ? AND slot = 'pet'", creator) !== undefined
}

// ---------------------------------------------------------------- Team

/** `GET /v1/admin/creators/pet-extras`: alle Creator mit Gefolge + wählbare Begleiter (Platz `pet`). */
export function adminPetExtrasOverview(ctx: AppContext, staff: Staff): { creators: AdminPetExtrasEntry[], cosmetics: PetCosmeticOption[], max: number } {
  assertCan(staff, 'creators.manage')
  const rows = all<{ uuid: string, tier: CreatorTier, state: CreatorState, name: string | null, at: number, by: string, by_name: string | null }>(
    ctx.db,
    `SELECT c.uuid, c.tier, c.state, u.name, x.at, x.by, b.name AS by_name
     FROM (SELECT creator_uuid, MAX(assigned_at) AS at, assigned_by AS by FROM creator_pet_extras GROUP BY creator_uuid) x
     JOIN creators c ON c.uuid = x.creator_uuid
     LEFT JOIN users u ON u.uuid = c.uuid LEFT JOIN users b ON b.uuid = x.by
     ORDER BY x.at DESC`,
  )
  return {
    creators: rows.map((r) => ({
      creator: { uuid: r.uuid, name: r.name ?? '', tier: r.tier, state: r.state },
      ...petExtrasState(ctx, r.uuid),
      updatedAt: new Date(r.at).toISOString(),
      updatedBy: { uuid: r.by, name: r.by_name ?? '' },
    })),
    cosmetics: petCosmeticOptions(ctx),
    max: MAX_PET_EXTRAS,
  }
}

/**
 * `PUT /v1/admin/creators/{uuid}/pet-extras`: Liste ersetzen (hinzufügen, entfernen, sortieren in einem). Nur mitgelieferte,
 * nicht ausgemusterte Teile mit Platz `pet`; höchstens 4, keine doppelten.
 */
export function adminSetPetExtras(ctx: AppContext, staff: Staff, creator: string, raw: unknown): PetExtrasState {
  assertCan(staff, 'creators.manage')
  const input = parse(adminPetExtrasBody, raw)
  if (!one(ctx.db, 'SELECT 1 AS x FROM creators WHERE uuid = ?', creator)) throw notFound('creator_not_found', 'Creator not found')
  for (const id of input.cosmeticIds) {
    const c = getCosmetic(ctx, id)
    if (!c || c.kind !== 'builtin' || c.retired) throw notFound('cosmetic_not_found', `Cosmetic not found: ${id}`)
    if (slotOf(ctx, c) !== 'pet') throw badRequest('not_a_pet', 'Only cosmetics in the slot "pet" can be extra companions')
  }
  const before = all<{ cosmetic_id: string }>(ctx.db, 'SELECT cosmetic_id FROM creator_pet_extras WHERE creator_uuid = ? ORDER BY position', creator).map((r) => r.cosmetic_id)
  const same = before.length === input.cosmeticIds.length && before.every((id, i) => id === input.cosmeticIds[i])
  if (!same) {
    const t = ctx.now()
    const kept = new Map(all<{ cosmetic_id: string, assigned_at: number, assigned_by: string }>(
      ctx.db,
      'SELECT cosmetic_id, assigned_at, assigned_by FROM creator_pet_extras WHERE creator_uuid = ?',
      creator,
    ).map((r) => [r.cosmetic_id, r]))
    tx(ctx.db, () => {
      run(ctx.db, 'DELETE FROM creator_pet_extras WHERE creator_uuid = ?', creator)
      input.cosmeticIds.forEach((id, i) => {
        // Unverändert an derselben Stelle: Zeitpunkt + Team-Mitglied bleiben; sonst zählt es als neue Zuordnung.
        const old = before[i] === id ? kept.get(id) : undefined
        run(
          ctx.db,
          'INSERT INTO creator_pet_extras (creator_uuid, cosmetic_id, position, assigned_at, assigned_by) VALUES (?, ?, ?, ?, ?)',
          creator, id, i, old?.assigned_at ?? t, old?.assigned_by ?? staff.uuid,
        )
      })
      audit(ctx, staff.uuid, 'creator.pet.extras', creator, `${before.join(', ') || '–'} → ${input.cosmeticIds.join(', ') || '–'}`)
    })
    if (wearsPet(ctx, creator)) emitCosmetics(ctx, creator)
    notifyCreator(ctx, creator, 'pet_extras_changed')
  }
  return petExtrasState(ctx, creator)
}

// ---------------------------------------------------------------- Creator

/**
 * `PUT /v1/creator/pet/extras`: Schalter „Gefolge zeigen“ – der Creator selbst oder das Team im Team-Modus (§35.9,
 * protokolliert), keine Helfer.
 */
export function setShowPetExtras(ctx: AppContext, user: string, raw: unknown, as?: string | null): PetExtrasState {
  const a = actorFor(ctx, user, as, 'owner')
  const c = a.creator
  const input = parse(showPetExtrasBody, raw)
  const changed = run(ctx.db, 'UPDATE creators SET show_pet_extras = ? WHERE uuid = ? AND show_pet_extras <> ?', input.show ? 1 : 0, c.uuid, input.show ? 1 : 0) > 0
  if (changed) auditActor(ctx, a, 'pet.extras.show', input.show ? 'on' : 'off')
  if (changed && wearsPet(ctx, c.uuid) && one(ctx.db, 'SELECT 1 AS x FROM creator_pet_extras WHERE creator_uuid = ?', c.uuid)) emitCosmetics(ctx, c.uuid)
  return petExtrasState(ctx, c.uuid)
}
