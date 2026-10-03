import { existsSync, readFileSync, realpathSync, statSync } from 'node:fs'
import { isAbsolute, relative, resolve } from 'node:path'
import { capeEntrySchema, cosmeticEntrySchema, splitUnlock, toBuiltinCape, toBuiltinCosmeticV1 } from './builtin'
import { assertBuiltinCape, type BuiltinCape } from './capes'
import { assertBuiltinCosmeticV1, assertBuiltinCosmeticV2, type AnyBuiltinCosmetic } from './cosmetics'
import { buildV2Cosmetic } from './cosmetics-v2'
import type { AppContext } from './context'
import { MAX_UPLOAD_BYTES } from './png'
import { parseTemplateEntry, type Template } from './templates'

/**
 * Proprietäre Built-ins liegen außerhalb des Repos (GPL deckt sie nicht). Dieser Loader liest nur
 * bekannte Namen unter `PRIVATE_ASSETS_DIR`, warnt einmal je fehlendem Teil und wirft nie.
 * Vorlagen: `cosmetics/templates.private.json` (gleiches Schema wie die öffentliche `templates.json`).
 * ein fehlender Ordner oder eine fehlende Datei darf den Start nicht abbrechen und keine
 * Besitz-Zeilen löschen. Die öffentlichen Kataloge bleiben streng (die werfen weiterhin).
 */

export interface PrivatePiece<T> {
  item: T
  /** Original-Index. `null` = hinten anhängen. */
  at: number | null
}

export interface PrivateLoad<T> {
  loaded: PrivatePiece<T>[]
  /** Sort-Indizes übersprungener Einträge. Die Lücken bleiben, damit der Rest seine Plätze behält. */
  reserved: number[]
}

type Warn = (message: string) => void

const PART = /^[a-z0-9_-][a-z0-9_.-]{0,79}$/

/** Pfad unter `root`. `null`, wenn ein Teil ungültig ist oder der Zielpfad (auch per Symlink) ausbricht. */
export function resolveInside(root: string, parts: readonly string[]): string | null {
  if (parts.length === 0 || parts.some((p) => !PART.test(p))) return null
  let base: string
  try {
    base = realpathSync(root)
  } catch {
    return null
  }
  const target = resolve(base, ...parts)
  const rel = relative(base, target)
  if (rel === '' || rel.startsWith('..') || isAbsolute(rel)) return null
  if (!existsSync(target)) return target
  let real: string
  try {
    real = realpathSync(target)
  } catch {
    return null
  }
  const rel2 = relative(base, real)
  if (rel2.startsWith('..') || isAbsolute(rel2)) return null
  return real
}

type ReadResult =
  | { kind: 'ok', buf: Buffer }
  | { kind: 'missing' }
  | { kind: 'escape' }
  | { kind: 'large' }
  | { kind: 'invalid' }

function readCapped(root: string, parts: readonly string[], maxBytes: number): ReadResult {
  const path = resolveInside(root, parts)
  if (!path) return { kind: 'escape' }
  let st: ReturnType<typeof statSync>
  try {
    st = statSync(path)
  } catch (err) {
    return (err as NodeJS.ErrnoException).code === 'ENOENT' ? { kind: 'missing' } : { kind: 'invalid' }
  }
  if (!st.isFile()) return { kind: 'invalid' }
  if (st.size > maxBytes) return { kind: 'large' }
  try {
    return { kind: 'ok', buf: readFileSync(path) }
  } catch {
    return { kind: 'invalid' }
  }
}

/** `true`, wenn der Ordner lesbar ist. Sonst genau eine Warnung. */
export function privateAssetsAvailable(root: string, warn: Warn): boolean {
  try {
    const st = statSync(root)
    if (!st.isDirectory()) {
      warn(`private assets path is not a directory (${root}) – private built-ins skipped`)
      return false
    }
    realpathSync(root)
    return true
  } catch (err) {
    const code = (err as NodeJS.ErrnoException).code
    if (code === 'ENOENT') warn(`private assets dir missing (${root}) – private built-ins skipped`)
    else warn(`private assets dir unreadable (${root}) – private built-ins skipped`)
    return false
  }
}

function idOf(raw: unknown): string {
  if (raw && typeof raw === 'object' && 'id' in raw && typeof (raw as { id: unknown }).id === 'string') {
    const id = (raw as { id: string }).id
    return id.length > 0 && id.length <= 40 ? id : '?'
  }
  return '?'
}

function unwrap(json: unknown, key: 'capes' | 'cosmetics'): unknown[] | null {
  if (Array.isArray(json)) return json
  if (json && typeof json === 'object' && key in json && Array.isArray((json as Record<string, unknown>)[key])) {
    return (json as Record<string, unknown[]>)[key]!
  }
  return null
}

function fileProblem(kind: Exclude<ReadResult['kind'], 'ok'>, name: string): string {
  if (kind === 'missing') return `file missing (${name})`
  if (kind === 'large') return `file too large (${name})`
  if (kind === 'escape') return `unsafe path (${name})`
  return `file unreadable (${name})`
}

function empty<T>(): PrivateLoad<T> {
  return { loaded: [], reserved: [] }
}

/**
 * Setzt private Teile an ihren Original-Index, lässt Lücken übersprungener Teile frei und füllt
 * den Rest mit den öffentlichen Teilen in Datei-Reihenfolge. Danach ist `sort` wieder dicht (0…n−1).
 */
export function mergeBuiltins<T extends { id: string, sort: number }>(
  publicItems: T[],
  loaded: PrivatePiece<T>[],
  reserved: readonly number[],
  warn: Warn,
): T[] {
  const seen = new Set(publicItems.map((i) => i.id))
  const placed = new Map<number, T>()
  const tail: T[] = []
  const reservedSet = new Set(reserved.filter((n) => Number.isInteger(n) && n >= 0))
  for (const { item, at } of loaded) {
    if (seen.has(item.id)) {
      warn(`private item ${item.id} skipped: id already in the public catalog`)
      if (at != null && at >= 0) reservedSet.add(at)
      continue
    }
    seen.add(item.id)
    if (at == null || at < 0 || placed.has(at) || reservedSet.has(at)) {
      if (at != null && at >= 0 && (placed.has(at) || reservedSet.has(at))) {
        warn(`private item ${item.id} appended: sort ${at} already used`)
      }
      tail.push(item)
    } else {
      placed.set(at, item)
    }
  }
  const indices = [...placed.keys(), ...reservedSet]
  const maxAt = indices.length ? Math.max(...indices) : -1
  const span = Math.max(maxAt + 1, publicItems.length + placed.size + reservedSet.size)
  const slots: ({ kind: 'item', item: T } | { kind: 'hole' } | undefined)[] = new Array(span)
  for (const at of reservedSet) if (at < span && !placed.has(at)) slots[at] = { kind: 'hole' }
  for (const [at, item] of placed) if (at < span) slots[at] = { kind: 'item', item }
  let p = 0
  for (let i = 0; i < slots.length && p < publicItems.length; i++) {
    if (!slots[i]) slots[i] = { kind: 'item', item: publicItems[p++]! }
  }
  const out: T[] = []
  for (const s of slots) if (s?.kind === 'item') out.push(s.item)
  while (p < publicItems.length) out.push(publicItems[p++]!)
  out.push(...tail)
  return out.map((item, sort) => ({ ...item, sort }))
}

function reserve(reserved: number[], at: number | null): void {
  if (at != null && at >= 0) reserved.push(at)
}

/** Private Umhänge aus `<root>/capes/catalog.private.json`. Wirft nie. */
export function loadPrivateCapes(root: string, warn: Warn): PrivateLoad<BuiltinCape> {
  const cat = readCapped(root, ['capes', 'catalog.private.json'], MAX_UPLOAD_BYTES)
  if (cat.kind === 'missing') {
    warn('private cape catalog missing – private capes skipped')
    return empty()
  }
  if (cat.kind !== 'ok') {
    warn(`private cape catalog ${fileProblem(cat.kind, 'catalog.private.json')} – private capes skipped`)
    return empty()
  }
  let json: unknown
  try {
    json = JSON.parse(cat.buf.toString('utf8'))
  } catch {
    warn('private cape catalog invalid – private capes skipped')
    return empty()
  }
  const list = unwrap(json, 'capes')
  if (!list || list.length > 100) {
    warn('private cape catalog invalid – private capes skipped')
    return empty()
  }
  const loaded: PrivatePiece<BuiltinCape>[] = []
  const reserved: number[] = []
  const seen = new Set<string>()
  for (const raw of list) {
    const parsed = capeEntrySchema.safeParse(raw)
    if (!parsed.success) {
      warn(`private cape ${idOf(raw)} skipped: invalid catalog entry`)
      continue
    }
    const c = parsed.data
    const at = c.sort ?? null
    if (seen.has(c.id)) {
      warn(`private cape ${c.id} skipped: duplicate id`)
      reserve(reserved, at)
      continue
    }
    seen.add(c.id)
    const file = readCapped(root, ['capes', c.file], MAX_UPLOAD_BYTES)
    if (file.kind !== 'ok') {
      warn(`private cape ${c.id} skipped: ${fileProblem(file.kind, c.file)}`)
      reserve(reserved, at)
      continue
    }
    const item = toBuiltinCape(c, file.buf, 0)
    try {
      assertBuiltinCape(item)
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'invalid image'
      warn(`private cape ${c.id} skipped: ${msg}`)
      reserve(reserved, at)
      continue
    }
    loaded.push({ item, at })
  }
  return { loaded, reserved }
}

/** Liste aus `{ version: 1, templates: [...] }` oder einem nackten Array. Sonst `null` (Datei ungültig). */
function templateEntries(json: unknown): unknown[] | null {
  if (Array.isArray(json)) return json.length <= 64 ? json : null
  if (!json || typeof json !== 'object') return null
  const o = json as Record<string, unknown>
  if (Object.keys(o).length !== 2 || o.version !== 1 || !Array.isArray(o.templates) || o.templates.length > 64) return null
  return o.templates
}

/**
 * Private Vorlagen aus `<root>/cosmetics/templates.private.json`. Eine fehlende Datei oder ein
 * ungültiger Eintrag wird übersprungen (eine Warnung). Wirft nie.
 */
export function loadPrivateTemplates(root: string, warn: Warn): Template[] {
  const file = readCapped(root, ['cosmetics', 'templates.private.json'], MAX_UPLOAD_BYTES)
  if (file.kind === 'missing') {
    warn('private template file missing – private templates skipped')
    return []
  }
  if (file.kind !== 'ok') {
    warn(`private templates ${fileProblem(file.kind, 'templates.private.json')} – private templates skipped`)
    return []
  }
  let json: unknown
  try {
    json = JSON.parse(file.buf.toString('utf8'))
  } catch {
    warn('private template file invalid – private templates skipped')
    return []
  }
  const list = templateEntries(json)
  if (!list) {
    warn('private template file invalid – private templates skipped')
    return []
  }
  const out: Template[] = []
  const seen = new Set<string>()
  for (const raw of list) {
    const parsed = parseTemplateEntry(raw)
    if (!parsed.ok) {
      warn(`private template ${parsed.id} skipped: ${parsed.reason}`)
      continue
    }
    if (seen.has(parsed.template.id)) {
      warn(`private template ${parsed.template.id} skipped: duplicate id`)
      continue
    }
    seen.add(parsed.template.id)
    out.push(parsed.template)
  }
  return out
}

/** Private Kosmetik aus `<root>/cosmetics/catalog.private.json` (v1-PNG und v2-Dateien). Wirft nie. */
export function loadPrivateCosmetics(root: string, ctx: AppContext, warn: Warn): PrivateLoad<AnyBuiltinCosmetic> {
  const cat = readCapped(root, ['cosmetics', 'catalog.private.json'], MAX_UPLOAD_BYTES)
  if (cat.kind === 'missing') {
    warn('private cosmetic catalog missing – private cosmetics skipped')
    return empty()
  }
  if (cat.kind !== 'ok') {
    warn(`private cosmetic catalog ${fileProblem(cat.kind, 'catalog.private.json')} – private cosmetics skipped`)
    return empty()
  }
  let json: unknown
  try {
    json = JSON.parse(cat.buf.toString('utf8'))
  } catch {
    warn('private cosmetic catalog invalid – private cosmetics skipped')
    return empty()
  }
  const list = unwrap(json, 'cosmetics')
  if (!list || list.length > 200) {
    warn('private cosmetic catalog invalid – private cosmetics skipped')
    return empty()
  }
  const loaded: PrivatePiece<AnyBuiltinCosmetic>[] = []
  const reserved: number[] = []
  const seen = new Set<string>()
  for (const raw of list) {
    const parsed = cosmeticEntrySchema.safeParse(raw)
    if (!parsed.success) {
      warn(`private cosmetic ${idOf(raw)} skipped: invalid catalog entry`)
      continue
    }
    const c = parsed.data
    const at = c.sort ?? null
    if (seen.has(c.id)) {
      warn(`private cosmetic ${c.id} skipped: duplicate id`)
      reserve(reserved, at)
      continue
    }
    seen.add(c.id)
    try {
      if (c.format === 2) {
        const names = [`${c.id}.json`, `${c.id}.png`, `${c.id}-card.png`, `${c.id}-card-night.png`]
        if (c.glowFrames > 0) names.splice(2, 0, `${c.id}-glow.png`)
        const files = new Map<string, Buffer>()
        const problems: string[] = []
        for (const name of names) {
          const got = readCapped(root, ['cosmetics', 'v2', name], MAX_UPLOAD_BYTES)
          if (got.kind === 'ok') files.set(name, got.buf)
          else problems.push(fileProblem(got.kind, `v2/${name}`))
        }
        if (problems.length > 0) {
          warn(`private cosmetic ${c.id} skipped: ${problems.join(', ')}`)
          reserve(reserved, at)
          continue
        }
        const built = buildV2Cosmetic({
          ...c,
          ...splitUnlock(c.unlock),
          sort: 0,
          files: {
            modelJson: files.get(`${c.id}.json`)!,
            texture: files.get(`${c.id}.png`)!,
            glow: c.glowFrames > 0 ? files.get(`${c.id}-glow.png`)! : null,
            card: files.get(`${c.id}-card.png`)!,
            cardNight: files.get(`${c.id}-card-night.png`)!,
          },
        })
        assertBuiltinCosmeticV2(ctx, built)
        loaded.push({ item: built, at })
      } else {
        const file = readCapped(root, ['cosmetics', c.file], MAX_UPLOAD_BYTES)
        if (file.kind !== 'ok') {
          warn(`private cosmetic ${c.id} skipped: ${fileProblem(file.kind, c.file)}`)
          reserve(reserved, at)
          continue
        }
        const item = toBuiltinCosmeticV1(c, file.buf, 0)
        assertBuiltinCosmeticV1(ctx, item)
        loaded.push({ item, at })
      }
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'invalid item'
      warn(`private cosmetic ${c.id} skipped: ${msg}`)
      reserve(reserved, at)
    }
  }
  return { loaded, reserved }
}
