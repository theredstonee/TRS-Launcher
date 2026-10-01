import { createHash } from 'node:crypto'
import { getRouterParam, type H3Event } from 'h3'
import { validateModel, type CosmeticModel } from '../../app/utils/cosmetic-v2/format'
import { notFound } from './errors'
import { COSMETIC_ID, sha256Hex } from './ids'
import { BUILTIN_MAX_SCALE, inspectPng } from './png'

/**
 * Kosmetik-Format v2 (§11.9): mitgelieferte 3D-Modelle aus dem TRS Studio. Die Dateien liegen fest unter
 * `assets/cosmetics/v2/` und werden beim Start geprüft (Port von `validateModel`) und im Speicher gehalten.
 * Uploads bleiben Format 1 (Vorlage + Textur).
 */

/** Platzhalter in `cosmetics.template` für v2-Zeilen (der alte CHECK verlangt eine Vorlage, Migration 20). */
export const V2_TEMPLATE = '@v2'

export interface CosmeticV2Files {
  modelJson: Buffer
  texture: Buffer
  glow: Buffer | null
  card: Buffer
  cardNight: Buffer
}

export interface BuiltinCosmeticV2 {
  format: 2
  id: string
  name: string
  unlock: 'free' | 'code' | 'admin'
  sort: number
  hidden?: boolean
  /** Event-Teil (z. B. `halloween`): gratis abholbar, solange das Event für den Spieler aktiv ist. */
  event?: string
  model: CosmeticModel
  files: CosmeticV2Files
  /** sha256 (hex) über model.json + Textur + Leucht-Streifen – ändert sich mit jedem der drei. */
  hash: string
  cardHash: string
  cardNightHash: string
}

/** Was die Routen und Sichten brauchen (im Speicher, `ctx.cosmeticsV2`). */
export type CosmeticV2Assets = BuiltinCosmeticV2

/** sha256 über model.json, Textur und (falls vorhanden) Leucht-Streifen, je mit Längen-Präfix. */
export function v2Hash(files: Pick<CosmeticV2Files, 'modelJson' | 'texture' | 'glow'>): string {
  const h = createHash('sha256')
  for (const part of [files.modelJson, files.texture, files.glow ?? Buffer.alloc(0)]) {
    const len = Buffer.alloc(4)
    len.writeUInt32BE(part.length)
    h.update(len)
    h.update(part)
  }
  return h.digest('hex')
}

export interface V2EntryInput {
  id: string
  name: string
  unlock: 'free' | 'code' | 'admin'
  sort: number
  hidden?: boolean
  event?: string
  frames: number
  frameTimeMs?: number | null
  glowFrames: number
  glowFrameTimeMs?: number | null
  files: CosmeticV2Files
}

/** Prüft ein v2-Teil vollständig; wirft mit allen Fehlern (der Server startet dann nicht). */
export function buildV2Cosmetic(e: V2EntryInput): BuiltinCosmeticV2 {
  const fail = (msg: string): never => {
    throw new Error(`builtin cosmetic ${e.id} (format 2): ${msg}`)
  }
  let json: unknown
  try {
    json = JSON.parse(e.files.modelJson.toString('utf8'))
  } catch {
    fail('model.json is not valid JSON')
  }
  const size = (buf: Buffer, what: string) => {
    try {
      const { header } = inspectPng(buf)
      return { width: header.width, height: header.height }
    } catch {
      return fail(`${what} is not a valid PNG`)
    }
  }
  const texture = size(e.files.texture, 'texture')
  const glow = e.files.glow ? size(e.files.glow, 'glow') : null
  const r = validateModel(json, { texture, glow })
  if (!r.ok) fail(r.errors.join('; '))
  const model = json as CosmeticModel
  if (model.id !== e.id) fail(`model id "${model.id}" does not match the catalog id`)
  if ((model.slot !== 'hat' && model.slot !== 'companion') || model.attach !== 'head') fail('only slot hat or companion / attach head are supported')
  if (model.texture.scale > BUILTIN_MAX_SCALE) fail(`texture scale ${model.texture.scale} > ${BUILTIN_MAX_SCALE} (needs a DB migration)`)
  const texFrames = model.texture.frames ?? 1
  if (texFrames !== e.frames) fail(`catalog frames ${e.frames} ≠ model texture.frames ${texFrames}`)
  if (texFrames > 1 && (e.frameTimeMs ?? null) !== (model.texture.frameTimeMs ?? null)) fail('catalog frameTimeMs ≠ model texture.frameTimeMs')
  const glowFrames = model.glow ? (model.glow.frames ?? 1) : 0
  if (glowFrames !== e.glowFrames) fail(`catalog glowFrames ${e.glowFrames} ≠ model glow.frames ${glowFrames}`)
  if (glowFrames > 1 && (e.glowFrameTimeMs ?? null) !== (model.glow?.frameTimeMs ?? null)) fail('catalog glowFrameTimeMs ≠ model glow.frameTimeMs')
  if (!!model.glow !== !!e.files.glow) fail('glow.png and model.glow must both exist or both be missing')
  for (const [what, buf] of [['card', e.files.card], ['card-night', e.files.cardNight]] as const) {
    const s = size(buf, what)
    if (s.width > 512 || s.height > 512) fail(`${what}.png is ${s.width}×${s.height}, at most 512 px`)
  }
  return {
    format: 2,
    id: e.id,
    name: e.name,
    unlock: e.unlock,
    sort: e.sort,
    hidden: e.hidden ?? false,
    ...(e.event ? { event: e.event } : {}),
    model,
    files: e.files,
    hash: v2Hash(e.files),
    cardHash: sha256Hex(e.files.card),
    cardNightHash: sha256Hex(e.files.cardNight),
  }
}

/** `{id}` aus der Route; ungültig → 404 (wie `GET /v1/cosmetics/{id}.png`). */
export function v2IdParam(event: H3Event): string {
  const id = getRouterParam(event, 'id') ?? ''
  if (id.length > 48 || !COSMETIC_ID.test(id)) throw notFound('cosmetic_not_found', 'Cosmetic not found')
  return id
}

/** Kurzform für `?v=` (12 Hex wie bei Umhängen). */
export const shortHash = (h: string): string => h.slice(0, 12)

export type V2File = 'model' | 'glow' | 'card' | 'cardNight'

/** Datei + Hash eines v2-Teils für die Routen. */
export function v2File(a: CosmeticV2Assets, which: V2File): { body: Buffer, sha256: string } | null {
  switch (which) {
    case 'model': return { body: a.files.modelJson, sha256: a.hash }
    case 'glow': return a.files.glow ? { body: a.files.glow, sha256: a.hash } : null
    case 'card': return { body: a.files.card, sha256: a.cardHash }
    case 'cardNight': return { body: a.files.cardNight, sha256: a.cardNightHash }
  }
}
