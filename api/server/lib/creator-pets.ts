import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { z } from 'zod'
import { audit } from './audit'
import { writeAtomic } from './capes'
import { createCodes, revokeCode } from './codes'
import type { AppContext } from './context'
import { getCosmetic, renderable, slotOf, v2Assets, v2Fields } from './cosmetics'
import {
  creatorPetRef,
  dropPetImage,
  petImageDir,
  petImageExt,
  syncCreatorPet,
  syncCreatorPets,
  usablePet,
  type CreatorPetRef,
} from './creator-pet'
import type { CreatorState, CreatorTier } from './creator-lookup'
import { actorFor, auditActor, isoTime, multiline, nameOf, notifyCreator, parse, TIERS, type CreatorActor } from './creators'
import { all, one, run, tx } from './db'
import { petExtrasState, type PetExtraView } from './pet-extras'
import { ApiError, badRequest, conflict, notFound, tooMany, unsupportedMedia } from './errors'
import { sha256Hex } from './ids'
import { processImage, sniffImage, type ImageLimits } from './images'
import { RULES } from './ratelimit'
import { assertNotSanctioned } from './sanctions'
import { plainText } from './schemas'
import { assertCan, type Staff } from './team'

/**
 * Creator-Haustiere (API.md §35.13): Ein Creator hat höchstens EIN eigenes Haustier (Kosmetik mit Platz `pet`), ein
 * Haustier gehört höchstens EINEM Creator; das Team ordnet zu. Der Besitzer bekommt es automatisch (Anzahl nach Fans,
 * creator-pet.ts) und gibt selbst Codes dafür aus – über das normale Code-System (`codes`, Herkunft `creator_uuid`,
 * Einlösen wie Team-Codes per `POST /v1/redeem`), begrenzt je Stufe und Kalendermonat (UTC). Andere Creator wünschen sich
 * ein eigenes Haustier (Text + optionales Referenzbild), das Team baut es in TRS Studio und ordnet es zu.
 */

const DAY = 86_400_000
export const PET_CODE_MAX_COUNT = 20
export const PET_CODE_MAX_USES = 100
export const PET_IMAGE_MAX_BYTES = 2 * 1024 * 1024
/** Referenzbild: höchstens 2 MiB, neu kodiert, längste Kante 2048 px. */
const PET_IMAGE_LIMITS: ImageLimits = { maxBytes: PET_IMAGE_MAX_BYTES, maxOutputEdge: 2048, quality: 90, thumbEdge: 256, thumbQuality: 70 }
export const PET_REQUEST_STATUSES = ['open', 'in_progress', 'done', 'declined'] as const
export type PetRequestStatus = (typeof PET_REQUEST_STATUSES)[number]
/** Vorgabe der Monatsgrenzen (Einlösungen insgesamt), im Admin änderbar (`creator_pet_limits`). */
export const DEFAULT_PET_LIMITS: Record<CreatorTier, number> = { starter: 10, partner: 50, top: 200 }

// ---------------------------------------------------------------- Schemas

const cosmeticIdSchema = z.string().trim().min(1).max(64).regex(/^[A-Za-z0-9_.-]+$/, 'invalid cosmetic id')

export const petCodesBody = z.strictObject({
  count: z.number().int().min(1).max(PET_CODE_MAX_COUNT).default(1),
  maxUses: z.number().int().min(1).max(PET_CODE_MAX_USES).default(1),
  expiresAt: isoTime.optional(),
  note: plainText(100).optional(),
})
export const petRequestBody = z.strictObject({
  species: plainText(60),
  description: multiline(1000).refine((s) => s.length > 0, 'must not be empty'),
  colors: plainText(200).optional(),
})
export const adminAssignPetBody = z.strictObject({ cosmeticId: cosmeticIdSchema })
export const adminPetRequestBody = z
  .strictObject({
    status: z.enum(PET_REQUEST_STATUSES),
    reply: plainText(500).nullable().optional(),
    /** Nur mit `done`: dieses Haustier dem Creator gleich zuordnen. */
    cosmeticId: cosmeticIdSchema.optional(),
  })
  .refine((o) => o.cosmeticId === undefined || o.status === 'done', { message: 'cosmeticId only together with status "done"', path: ['cosmeticId'] })
export const petRequestListQuery = z.strictObject({ status: z.enum(PET_REQUEST_STATUSES).optional() })
const limitValue = z.number().int().min(0).max(1_000_000)
export const petLimitsBody = z.strictObject({ starter: limitValue, partner: limitValue, top: limitValue })

// ---------------------------------------------------------------- Zeilen + Sichten

interface PetRequestRow {
  id: number
  creator_uuid: string
  status: PetRequestStatus
  species: string
  description: string
  colors: string
  image_sha256: string | null
  image_mime: 'image/png' | 'image/jpeg' | null
  image_width: number | null
  image_height: number | null
  reply: string | null
  cosmetic_id: string | null
  created_at: number
  updated_at: number
  decided_at: number | null
  decided_by: string | null
}

interface PetCodeRow {
  id: number
  hint: string
  max_uses: number
  uses: number
  expires_at: number | null
  revoked_at: number | null
  note: string | null
  created_at: number
  created_by: string
  created_by_name: string | null
}

export interface PetQuota {
  /** Kalendermonat (UTC) `YYYY-MM`. */
  month: string
  /** Einlösungen, die der Creator in diesem Monat insgesamt ausgeben darf (Stufe). */
  limit: number
  /** Schon vergeben: Summe `maxUses` der Codes dieses Monats (widerrufene nur mit ihren tatsächlichen Einlösungen). */
  used: number
  remaining: number
  /** Beginn des nächsten Monats. */
  resetsAt: string
}

export interface PetCodeView {
  id: number
  /** Letzte 4 Zeichen; den Klartext gibt es nur in der Antwort beim Erstellen. */
  hint: string
  maxUses: number
  uses: number
  expiresAt: string | null
  revokedAt: string | null
  note: string | null
  createdAt: string
  /** Creator selbst oder ein Helfer mit dem Recht `codes`. */
  createdBy: { uuid: string, name: string }
}

export interface PetImageView {
  /** Relativ zur API-Basis; nur für den Creator und das Team (Bearer oder Website-Sitzung). */
  url: string
  mime: 'image/png' | 'image/jpeg'
  width: number
  height: number
}

export interface PetRequestView {
  id: number
  status: PetRequestStatus
  species: string
  description: string
  colors: string
  image: PetImageView | null
  reply: string | null
  /** Zugeordnetes Haustier, wenn der Wunsch mit „fertig“ abgeschlossen wurde. */
  cosmeticId: string | null
  createdAt: string
  updatedAt: string
  decidedAt: string | null
}

export interface CreatorPetState {
  pet: CreatorPetRef | null
  quota: PetQuota
  /** `null` für Helfer ohne das Recht `codes`. */
  codes: PetCodeView[] | null
  /** Eigene Wünsche (neueste zuerst); leer für Helfer. */
  requests: PetRequestView[]
  /** Darf jetzt einen Wunsch stellen (Creator selbst, noch kein Haustier, kein offener Wunsch). */
  canRequest: boolean
  /** Begleiter-Gefolge (vom Team zugeordnet, in Reihenfolge; leer = keins). */
  extras: PetExtraView[]
  /** Schalter „Gefolge zeigen“ (`PUT /v1/creator/pet/extras`). */
  showExtras: boolean
}

const iso = (t: number) => new Date(t).toISOString()

function petImageView(r: PetRequestRow): PetImageView | null {
  if (!r.image_sha256 || !r.image_mime) return null
  return {
    url: `/v1/creator-pet-images/${r.image_sha256}.${petImageExt(r.image_mime)}`,
    mime: r.image_mime,
    width: r.image_width ?? 0,
    height: r.image_height ?? 0,
  }
}

function requestView(r: PetRequestRow): PetRequestView {
  return {
    id: r.id,
    status: r.status,
    species: r.species,
    description: r.description,
    colors: r.colors,
    image: petImageView(r),
    reply: r.reply,
    cosmeticId: r.cosmetic_id,
    createdAt: iso(r.created_at),
    updatedAt: iso(r.updated_at),
    decidedAt: r.decided_at === null ? null : iso(r.decided_at),
  }
}

function petCodeView(r: PetCodeRow): PetCodeView {
  return {
    id: r.id,
    hint: r.hint,
    maxUses: r.max_uses,
    uses: r.uses,
    expiresAt: r.expires_at === null ? null : iso(r.expires_at),
    revokedAt: r.revoked_at === null ? null : iso(r.revoked_at),
    note: r.note,
    createdAt: iso(r.created_at),
    createdBy: { uuid: r.created_by, name: r.created_by_name ?? '' },
  }
}

// ---------------------------------------------------------------- Monatsgrenze

export function monthStart(t: number): number {
  const d = new Date(t)
  return Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), 1)
}

function nextMonthStart(t: number): number {
  const d = new Date(t)
  return Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + 1, 1)
}

export function petLimits(ctx: AppContext): Record<CreatorTier, number> {
  const out = { ...DEFAULT_PET_LIMITS }
  for (const r of all<{ tier: CreatorTier, monthly: number }>(ctx.db, 'SELECT tier, monthly FROM creator_pet_limits')) out[r.tier] = r.monthly
  return out
}

export function petQuota(ctx: AppContext, creator: string, tier: CreatorTier): PetQuota {
  const t = ctx.now()
  const from = monthStart(t)
  const limit = petLimits(ctx)[tier]
  const used = one<{ n: number }>(
    ctx.db,
    'SELECT COALESCE(SUM(CASE WHEN revoked_at IS NULL THEN max_uses ELSE uses END), 0) AS n FROM codes WHERE creator_uuid = ? AND created_at >= ?',
    creator, from,
  )!.n
  return {
    month: new Date(from).toISOString().slice(0, 7),
    limit,
    used,
    remaining: Math.max(0, limit - used),
    resetsAt: iso(nextMonthStart(t)),
  }
}

// ---------------------------------------------------------------- Creator: Codes

function petCodesOf(ctx: AppContext, creator: string): PetCodeView[] {
  return all<PetCodeRow>(
    ctx.db,
    `SELECT k.id, k.hint, k.max_uses, k.uses, k.expires_at, k.revoked_at, k.note, k.created_at, k.created_by, u.name AS created_by_name
     FROM codes k LEFT JOIN users u ON u.uuid = k.created_by WHERE k.creator_uuid = ? ORDER BY k.id DESC LIMIT 200`,
    creator,
  ).map(petCodeView)
}

function requestsOf(ctx: AppContext, creator: string): PetRequestView[] {
  return all<PetRequestRow>(ctx.db, 'SELECT * FROM creator_pet_requests WHERE creator_uuid = ? ORDER BY created_at DESC, id DESC LIMIT 20', creator).map(requestView)
}

function openRequest(ctx: AppContext, creator: string): PetRequestRow | undefined {
  return one<PetRequestRow>(ctx.db, "SELECT * FROM creator_pet_requests WHERE creator_uuid = ? AND status IN ('open', 'in_progress') ORDER BY id DESC LIMIT 1", creator)
}

function stateFor(ctx: AppContext, a: CreatorActor): CreatorPetState {
  const c = a.creator
  const pet = creatorPetRef(ctx, c.uuid)
  const ex = petExtrasState(ctx, c.uuid)
  return {
    pet,
    quota: petQuota(ctx, c.uuid, c.tier),
    codes: a.rights.has('codes') ? petCodesOf(ctx, c.uuid) : null,
    requests: a.helper ? [] : requestsOf(ctx, c.uuid),
    canRequest: !a.helper && !pet && !openRequest(ctx, c.uuid),
    extras: ex.extras,
    showExtras: ex.showExtras,
  }
}

/** `GET /v1/creator/pet`: Haustier, Monatsgrenze, Codes (Recht `codes`), Wünsche (nur der Creator). */
export function creatorPetState(ctx: AppContext, user: string, as?: string | null): CreatorPetState {
  return stateFor(ctx, actorFor(ctx, user, as, null))
}

/**
 * Codes für das eigene Haustier (Creator oder Helfer mit `codes`): `count` × `maxUses` muss in die Monatsgrenze
 * passen. Klartext NUR in dieser Antwort.
 */
export function createPetCodes(
  ctx: AppContext,
  user: string,
  raw: unknown,
  as?: string | null,
): { codes: (PetCodeView & { code: string })[], quota: PetQuota } {
  const a = actorFor(ctx, user, as, 'codes')
  const c = a.creator
  const input = parse(petCodesBody, raw)
  const own = one<{ cosmetic_id: string }>(ctx.db, 'SELECT cosmetic_id FROM creator_pets WHERE creator_uuid = ?', c.uuid)
  if (!own) throw conflict('creator_pet_missing', 'You do not have an own pet yet')
  if (!usablePet(ctx, own.cosmetic_id)) throw conflict('creator_pet_unavailable', 'Your pet is not available right now')
  if (input.expiresAt !== undefined) {
    const at = Date.parse(input.expiresAt)
    if (at <= ctx.now()) throw badRequest('invalid_request', 'expiresAt must be in the future')
    if (at > ctx.now() + 366 * DAY) throw badRequest('invalid_request', 'expiresAt must be within one year')
  }
  const quota = petQuota(ctx, c.uuid, c.tier)
  const want = input.count * input.maxUses
  if (want > quota.remaining) {
    throw new ApiError(403, 'creator_pet_limit', `Only ${quota.remaining} more redemption(s) this month`, {
      limit: quota.limit, used: quota.used, remaining: quota.remaining, resetsAt: quota.resetsAt,
    })
  }
  // Prüfen und Anlegen laufen ohne Unterbrechung (node:sqlite ist synchron) – kein Wettlauf um die Grenze.
  const made = createCodes(ctx, user, {
    cosmeticId: own.cosmetic_id,
    maxUses: input.maxUses,
    count: input.count,
    expiresAt: input.expiresAt,
    note: input.note,
    creatorUuid: c.uuid,
  })
  audit(ctx, user, 'creator.pet.codes', c.uuid, `${input.count}x ${own.cosmetic_id} (max ${input.maxUses})${a.helper ? ' (helper)' : a.team ? ' (team)' : ''}`)
  if (a.team) auditActor(ctx, a, 'pet.code.create', `${input.count}x max ${input.maxUses}`)
  const name = nameOf(ctx, user)
  return {
    codes: made.codes.map((k) => ({
      id: k.id,
      hint: k.hint,
      maxUses: k.maxUses,
      uses: k.uses,
      expiresAt: k.expiresAt,
      revokedAt: k.revokedAt,
      note: k.note,
      createdAt: k.createdAt,
      createdBy: { uuid: user, name },
      code: k.code,
    })),
    quota: petQuota(ctx, c.uuid, c.tier),
  }
}

export function listPetCodes(ctx: AppContext, user: string, as?: string | null): { codes: PetCodeView[], quota: PetQuota } {
  const a = actorFor(ctx, user, as, 'codes')
  return { codes: petCodesOf(ctx, a.creator.uuid), quota: petQuota(ctx, a.creator.uuid, a.creator.tier) }
}

/** Eigenen Haustier-Code widerrufen (sofort ungültig; nicht genutzte Einlösungen zählen nicht mehr gegen den Monat). */
export function revokePetCode(ctx: AppContext, user: string, id: number, as?: string | null): { codes: PetCodeView[], quota: PetQuota } {
  const a = actorFor(ctx, user, as, 'codes')
  const c = a.creator
  if (!one(ctx.db, 'SELECT 1 AS x FROM codes WHERE id = ? AND creator_uuid = ?', id, c.uuid)) throw notFound('code_not_found', 'Code not found')
  revokeCode(ctx, id)
  auditActor(ctx, a, 'pet.code.revoke', String(id))
  return { codes: petCodesOf(ctx, c.uuid), quota: petQuota(ctx, c.uuid, c.tier) }
}

// ---------------------------------------------------------------- Creator: Wunsch

/** Wunsch „Eigenes Haustier“ (nur der Creator): einer offen/in Arbeit zur Zeit, nicht mit eigenem Haustier. */
export function createPetRequest(ctx: AppContext, user: string, raw: unknown, as?: string | null): { requests: PetRequestView[] } {
  const a = actorFor(ctx, user, as, 'owner')
  const c = a.creator
  const input = parse(petRequestBody, raw)
  if (one(ctx.db, 'SELECT 1 AS x FROM creator_pets WHERE creator_uuid = ?', c.uuid)) throw conflict('creator_pet_owned', 'You already have an own pet')
  if (openRequest(ctx, c.uuid)) throw conflict('creator_request_open', 'You already have an open pet request')
  const t = ctx.now()
  run(
    ctx.db,
    "INSERT INTO creator_pet_requests (creator_uuid, status, species, description, colors, created_at, updated_at) VALUES (?, 'open', ?, ?, ?, ?, ?)",
    c.uuid, input.species, input.description, input.colors ?? '', t, t,
  )
  auditActor(ctx, a, 'pet.request.create', input.species)
  return { requests: requestsOf(ctx, c.uuid) }
}

function ownOpenRequest(ctx: AppContext, creator: string, id: number): PetRequestRow {
  const r = one<PetRequestRow>(ctx.db, 'SELECT * FROM creator_pet_requests WHERE id = ? AND creator_uuid = ?', id, creator)
  if (!r) throw notFound('request_not_found', 'Request not found')
  if (r.status !== 'open') throw conflict('request_closed', 'The team is already working on this request')
  return r
}

/** Wunsch zurückziehen (nur solange er offen ist). */
export function withdrawPetRequest(ctx: AppContext, user: string, id: number, as?: string | null): void {
  const a = actorFor(ctx, user, as, 'owner')
  const c = a.creator
  const r = ownOpenRequest(ctx, c.uuid, id)
  run(ctx.db, 'DELETE FROM creator_pet_requests WHERE id = ?', r.id)
  auditActor(ctx, a, 'pet.request.withdraw', String(r.id))
  if (r.image_sha256 && r.image_mime) dropPetImage(ctx, r.image_sha256, r.image_mime)
}

let imagesRunning = 0

/**
 * Referenzbild zum offenen Wunsch (PNG oder JPEG ≤ 2 MiB; Typ ↔ Magic Bytes geprüft, ohne Metadaten neu kodiert,
 * längste Kante 2048 px). Ersetzt ein vorhandenes. Höchstens 5 Bilder je Creator in 24 h.
 */
export async function uploadPetRequestImage(
  ctx: AppContext,
  user: string,
  id: number,
  body: Buffer,
  contentType: string,
  as?: string | null,
): Promise<{ request: PetRequestView }> {
  const a = actorFor(ctx, user, as, 'owner')
  const c = a.creator
  ownOpenRequest(ctx, c.uuid, id)
  assertNotSanctioned(ctx, c.uuid, 'upload_ban')
  if (a.team) assertNotSanctioned(ctx, user, 'upload_ban')
  // WebP versteht processImage zwar, hier sind nur PNG/JPEG erlaubt – auch wenn der Content-Type lügt.
  const kind = sniffImage(body)
  if (kind !== 'png' && kind !== 'jpeg' && body.length > 0) throw unsupportedMedia('Only PNG and JPEG images are allowed')
  const lim = ctx.limiter.take(`creator-petimg:${c.uuid}`, RULES.creatorUploadUser)
  if (!lim.ok) throw tooMany(lim.retryAfter)
  if (imagesRunning >= 2) throw new ApiError(503, 'busy', 'The server is busy processing images, try again in a few seconds', { retryAfter: 3 }, { 'Retry-After': '3' })
  imagesRunning++
  let img: Awaited<ReturnType<typeof processImage>>
  try {
    img = await processImage(body, contentType, PET_IMAGE_LIMITS)
  } finally {
    imagesRunning--
  }
  // Während des Kodierens könnte der Wunsch bearbeitet/zurückgezogen worden sein.
  const r = ownOpenRequest(ctx, c.uuid, id)
  const sha = sha256Hex(img.full.data)
  writeAtomic(petImageDir(ctx), `${sha}.${petImageExt(img.full.mime)}`, img.full.data)
  run(
    ctx.db,
    'UPDATE creator_pet_requests SET image_sha256 = ?, image_mime = ?, image_width = ?, image_height = ?, updated_at = ? WHERE id = ?',
    sha, img.full.mime, img.full.width, img.full.height, ctx.now(), r.id,
  )
  if (r.image_sha256 && r.image_mime && !(r.image_sha256 === sha && r.image_mime === img.full.mime)) dropPetImage(ctx, r.image_sha256, r.image_mime)
  auditActor(ctx, a, 'pet.request.image', `${r.id}: ${sha}`)
  return { request: requestView(one<PetRequestRow>(ctx.db, 'SELECT * FROM creator_pet_requests WHERE id = ?', r.id)!) }
}

export function deletePetRequestImage(ctx: AppContext, user: string, id: number, as?: string | null): { request: PetRequestView } {
  const a = actorFor(ctx, user, as, 'owner')
  const c = a.creator
  const r = ownOpenRequest(ctx, c.uuid, id)
  auditActor(ctx, a, 'pet.request.image.delete', String(r.id))
  run(ctx.db, 'UPDATE creator_pet_requests SET image_sha256 = NULL, image_mime = NULL, image_width = NULL, image_height = NULL, updated_at = ? WHERE id = ?', ctx.now(), r.id)
  if (r.image_sha256 && r.image_mime) dropPetImage(ctx, r.image_sha256, r.image_mime)
  return { request: requestView(one<PetRequestRow>(ctx.db, 'SELECT * FROM creator_pet_requests WHERE id = ?', r.id)!) }
}

/** Bild ausliefern: nur an den Creator des Wunsches und Team-Mitglieder mit Prüfrecht (`viewer.admin`). */
export function readPetRequestImage(
  ctx: AppContext,
  sha: string,
  ext: 'png' | 'jpg',
  viewer: { uuid: string, admin: boolean } | null,
): { body: Buffer, mime: string, sha256: string } {
  const mime = ext === 'png' ? 'image/png' : 'image/jpeg'
  const r = one<{ creator_uuid: string }>(ctx.db, 'SELECT creator_uuid FROM creator_pet_requests WHERE image_sha256 = ? AND image_mime = ? LIMIT 1', sha, mime)
  const mine = viewer && r && one(ctx.db, 'SELECT 1 AS x FROM creator_pet_requests WHERE image_sha256 = ? AND image_mime = ? AND creator_uuid = ?', sha, mime, viewer.uuid)
  if (!r || !viewer || (!viewer.admin && !mine)) throw notFound('image_not_found', 'Image not found')
  try {
    return { body: readFileSync(join(petImageDir(ctx), `${sha}.${ext}`)), mime, sha256: sha }
  } catch {
    throw notFound('image_not_found', 'Image not found')
  }
}

// ---------------------------------------------------------------- Team

export interface PetCosmeticOption {
  id: string
  name: string
  unlock: string
  hidden: boolean
  card: string | null
  /** Dateien geladen (sonst kann der Besitzer keine Codes erstellen und bekommt es nicht automatisch). */
  available: boolean
  owner: { uuid: string, name: string } | null
}

export interface AdminPetAssignment {
  creator: { uuid: string, name: string, tier: CreatorTier, state: CreatorState }
  pet: CreatorPetRef
  assignedBy: { uuid: string, name: string }
  quota: PetQuota
}

export interface AdminPetRequestView extends PetRequestView {
  creator: { uuid: string, name: string, tier: CreatorTier, state: CreatorState }
}

function creatorOrThrow(ctx: AppContext, uuid: string): { uuid: string, tier: CreatorTier, state: CreatorState } {
  const c = one<{ uuid: string, tier: CreatorTier, state: CreatorState }>(ctx.db, 'SELECT uuid, tier, state FROM creators WHERE uuid = ?', uuid)
  if (!c) throw notFound('creator_not_found', 'Creator not found')
  return c
}

/** Alle Kosmetik-Teile mit Platz `pet` (mitgeliefert, nicht ausgemustert) samt Besitzer – für die Zuordnung. */
export function petCosmeticOptions(ctx: AppContext): PetCosmeticOption[] {
  const owners = new Map(all<{ cosmetic_id: string, uuid: string, name: string | null }>(
    ctx.db,
    'SELECT p.cosmetic_id, p.creator_uuid AS uuid, u.name FROM creator_pets p LEFT JOIN users u ON u.uuid = p.creator_uuid',
  ).map((r) => [r.cosmetic_id, { uuid: r.uuid, name: r.name ?? '' }]))
  const rows = all<{ id: string, name: string, slot: string, unlock: string, hidden: number, format: number, template: string | null }>(
    ctx.db,
    "SELECT id, name, slot, unlock, hidden, format, template FROM cosmetics WHERE kind = 'builtin' AND retired = 0 ORDER BY sort, id",
  )
  const out: PetCosmeticOption[] = []
  for (const r of rows) {
    if (slotOf(ctx, r as Parameters<typeof slotOf>[1]) !== 'pet') continue
    const v2 = v2Assets(ctx, r)
    out.push({
      id: r.id,
      name: r.name,
      unlock: r.unlock,
      hidden: r.hidden === 1,
      card: v2 ? v2Fields(v2, ctx.config.publicBaseUrl).card : null,
      available: renderable(ctx, r as Parameters<typeof renderable>[1]),
      owner: owners.get(r.id) ?? null,
    })
  }
  return out
}

/** `GET /v1/admin/creators/pets`: Zuordnungen, wählbare Haustiere, Monatsgrenzen. */
export function adminPetOverview(ctx: AppContext, staff: Staff): { pets: AdminPetAssignment[], cosmetics: PetCosmeticOption[], limits: Record<CreatorTier, number> } {
  assertCan(staff, 'creators.manage')
  const rows = all<{ creator_uuid: string, assigned_by: string, tier: CreatorTier, state: CreatorState, name: string | null, by_name: string | null }>(
    ctx.db,
    `SELECT p.creator_uuid, p.assigned_by, c.tier, c.state, u.name, b.name AS by_name FROM creator_pets p
     JOIN creators c ON c.uuid = p.creator_uuid LEFT JOIN users u ON u.uuid = p.creator_uuid LEFT JOIN users b ON b.uuid = p.assigned_by
     ORDER BY p.assigned_at DESC`,
  )
  return {
    pets: rows.map((r) => ({
      creator: { uuid: r.creator_uuid, name: r.name ?? '', tier: r.tier, state: r.state },
      pet: creatorPetRef(ctx, r.creator_uuid)!,
      assignedBy: { uuid: r.assigned_by, name: r.by_name ?? '' },
      quota: petQuota(ctx, r.creator_uuid, r.tier),
    })),
    cosmetics: petCosmeticOptions(ctx),
    limits: petLimits(ctx),
  }
}

/**
 * Haustier zuordnen OHNE eigene Transaktion: nur Platz `pet`, eins je Creator (ein vorhandenes wird ersetzt), eins je
 * Haustier (`409 creator_pet_taken`). Offene Wünsche des Creators werden „fertig“. Liefert die Nacharbeit.
 */
function assignInTx(ctx: AppContext, staff: Staff, creator: string, cosmeticId: string): () => void {
  const pet = getCosmetic(ctx, cosmeticId)
  if (!pet || pet.kind !== 'builtin' || pet.retired) throw notFound('cosmetic_not_found', 'Cosmetic not found')
  if (slotOf(ctx, pet) !== 'pet') throw badRequest('not_a_pet', 'Only cosmetics in the slot "pet" can be creator pets')
  const owner = one<{ creator_uuid: string }>(ctx.db, 'SELECT creator_uuid FROM creator_pets WHERE cosmetic_id = ?', pet.id)
  if (owner && owner.creator_uuid !== creator) {
    throw new ApiError(409, 'creator_pet_taken', 'This pet already belongs to another creator', {
      owner: { uuid: owner.creator_uuid, name: nameOf(ctx, owner.creator_uuid) },
    })
  }
  const prev = one<{ cosmetic_id: string }>(ctx.db, 'SELECT cosmetic_id FROM creator_pets WHERE creator_uuid = ?', creator)
  const t = ctx.now()
  run(
    ctx.db,
    `INSERT INTO creator_pets (creator_uuid, cosmetic_id, assigned_at, assigned_by) VALUES (?, ?, ?, ?)
     ON CONFLICT(creator_uuid) DO UPDATE SET cosmetic_id = excluded.cosmetic_id, assigned_at = excluded.assigned_at, assigned_by = excluded.assigned_by`,
    creator, pet.id, t, staff.uuid,
  )
  run(
    ctx.db,
    "UPDATE creator_pet_requests SET status = 'done', cosmetic_id = ?, updated_at = ?, decided_at = ?, decided_by = ? WHERE creator_uuid = ? AND status IN ('open', 'in_progress')",
    pet.id, t, t, staff.uuid, creator,
  )
  audit(ctx, staff.uuid, 'creator.pet.assign', creator, prev && prev.cosmetic_id !== pet.id ? `${prev.cosmetic_id} → ${pet.id}` : pet.id)
  const changed = prev?.cosmetic_id !== pet.id
  return () => {
    syncCreatorPet(ctx, creator)
    if (changed) notifyCreator(ctx, creator, 'pet_assigned')
  }
}

export function adminAssignPet(ctx: AppContext, staff: Staff, creator: string, raw: unknown): { pet: CreatorPetRef } {
  assertCan(staff, 'creators.manage')
  const input = parse(adminAssignPetBody, raw)
  creatorOrThrow(ctx, creator)
  const after = tx(ctx.db, () => assignInTx(ctx, staff, creator, input.cosmeticId))
  after()
  return { pet: creatorPetRef(ctx, creator)! }
}

/** Zuordnung lösen: die Besitz-Zuteilung fällt weg, seine Haustier-Codes gelten nicht mehr. */
export function adminRemovePet(ctx: AppContext, staff: Staff, creator: string): void {
  assertCan(staff, 'creators.manage')
  creatorOrThrow(ctx, creator)
  const prev = one<{ cosmetic_id: string }>(ctx.db, 'SELECT cosmetic_id FROM creator_pets WHERE creator_uuid = ?', creator)
  if (!prev) throw notFound('creator_pet_missing', 'This creator has no own pet')
  tx(ctx.db, () => {
    run(ctx.db, 'DELETE FROM creator_pets WHERE creator_uuid = ?', creator)
    audit(ctx, staff.uuid, 'creator.pet.remove', creator, prev.cosmetic_id)
  })
  syncCreatorPets(ctx, [creator])
  notifyCreator(ctx, creator, 'pet_removed')
}

export function adminListPetRequests(ctx: AppContext, staff: Staff, rawQuery: unknown): { requests: AdminPetRequestView[] } {
  assertCan(staff, 'creators.manage')
  const q = parse(petRequestListQuery, rawQuery)
  const rows = all<PetRequestRow & { name: string | null, tier: CreatorTier, state: CreatorState }>(
    ctx.db,
    `SELECT r.*, u.name, c.tier, c.state FROM creator_pet_requests r JOIN creators c ON c.uuid = r.creator_uuid LEFT JOIN users u ON u.uuid = r.creator_uuid
     ${q.status ? 'WHERE r.status = ?' : ''}
     ORDER BY CASE r.status WHEN 'open' THEN 0 WHEN 'in_progress' THEN 1 ELSE 2 END, r.created_at DESC LIMIT 200`,
    ...(q.status ? [q.status] : []),
  )
  return {
    requests: rows.map((r) => ({ ...requestView(r), creator: { uuid: r.creator_uuid, name: r.name ?? '', tier: r.tier, state: r.state } })),
  }
}

/**
 * Wunsch bearbeiten: Status (offen/in Arbeit/fertig/abgelehnt) + Antwort an den Creator; mit `done` + `cosmeticId` wird
 * das Haustier gleich zugeordnet. Der Creator bekommt `creator_updated { kind: "pet_request_answered" }`.
 */
export function adminDecidePetRequest(ctx: AppContext, staff: Staff, id: number, raw: unknown): { requests: AdminPetRequestView[] } {
  assertCan(staff, 'creators.manage')
  const input = parse(adminPetRequestBody, raw)
  const r = one<PetRequestRow>(ctx.db, 'SELECT * FROM creator_pet_requests WHERE id = ?', id)
  if (!r) throw notFound('request_not_found', 'Request not found')
  const t = ctx.now()
  const closed = input.status === 'done' || input.status === 'declined'
  const reply = input.reply === undefined ? r.reply : input.reply
  const after = tx(ctx.db, () => {
    run(
      ctx.db,
      'UPDATE creator_pet_requests SET status = ?, reply = ?, updated_at = ?, decided_at = ?, decided_by = ? WHERE id = ?',
      input.status, reply, t, closed ? t : null, closed ? staff.uuid : null, r.id,
    )
    audit(ctx, staff.uuid, `creator.pet.request.${input.status}`, r.creator_uuid, `#${r.id}${reply ? `: ${reply}` : ''}`)
    if (input.cosmeticId === undefined) return () => {}
    const f = assignInTx(ctx, staff, r.creator_uuid, input.cosmeticId)
    run(ctx.db, 'UPDATE creator_pet_requests SET cosmetic_id = ? WHERE id = ?', input.cosmeticId, r.id)
    return f
  })
  after()
  if (input.status !== r.status || reply !== r.reply) notifyCreator(ctx, r.creator_uuid, 'pet_request_answered', { reason: reply })
  return adminListPetRequests(ctx, staff, {})
}

export function adminSetPetLimits(ctx: AppContext, staff: Staff, raw: unknown): { limits: Record<CreatorTier, number> } {
  assertCan(staff, 'creators.manage')
  const input = parse(petLimitsBody, raw)
  const before = petLimits(ctx)
  tx(ctx.db, () => {
    for (const tier of TIERS) {
      run(ctx.db, 'INSERT INTO creator_pet_limits (tier, monthly) VALUES (?, ?) ON CONFLICT(tier) DO UPDATE SET monthly = excluded.monthly', tier, input[tier])
    }
    audit(ctx, staff.uuid, 'creator.pet.limits', null, TIERS.map((x) => `${x} ${before[x]} → ${input[x]}`).join(', '))
  })
  return { limits: petLimits(ctx) }
}
