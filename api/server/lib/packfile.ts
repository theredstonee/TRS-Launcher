import { inflateRawSync } from 'node:zlib'
import { z } from 'zod'
import { ApiError } from './errors'

/**
 * Prüfung geteilter Modpacks (§27): Nur echte Modrinth-Packs (`.mrpack`) werden angenommen – ein Zip mit
 * `modrinth.index.json` und Dateien unter `overrides/`, `client-overrides/` oder `server-overrides/`.
 *
 * Der Server entpackt nichts auf die Platte: Er liest nur das Inhaltsverzeichnis des Zips und den Index.
 * Downloads im Index müssen von Modrinths CDN kommen (so exportiert der Launcher; fremde Adressen würden
 * sonst über einen Link beliebige Dateien auf fremde Rechner holen). Alles andere (Konfigurationen, eigene
 * Mods) liegt als Kopie im Pack – das zeigt der Launcher vor der Installation an.
 */

/** Grenzen gegen Zip-Bomben und Riesen-Indizes. */
export const PACK_LIMITS = {
  maxEntries: 20_000,
  maxIndexBytes: 4 * 1024 * 1024,
  /** Summe aller entpackten Größen – der Launcher entpackt das ja später. */
  maxUnpackedBytes: 1024 * 1024 * 1024,
  maxIndexFiles: 5_000,
} as const

export const LOADER_KEYS = ['forge', 'neoforge', 'fabric-loader', 'quilt-loader'] as const
export type PackLoader = 'vanilla' | 'forge' | 'neoforge' | 'fabric' | 'quilt'

const LOADER_OF: Record<(typeof LOADER_KEYS)[number], PackLoader> = {
  'forge': 'forge',
  'neoforge': 'neoforge',
  'fabric-loader': 'fabric',
  'quilt-loader': 'quilt',
}

export interface PackInfo {
  name: string
  summary: string | null
  /** `versionId` aus dem Index (vom Ersteller vergeben, z. B. „1.2.0“). */
  packVersion: string
  mcVersion: string
  loader: PackLoader
  loaderVersion: string | null
  /** Dateien im Index (von Modrinth geladen). */
  downloads: number
  /** Eigene `.jar`-Dateien im Pack (nicht von Modrinth, z. B. selbst gebaute oder CurseForge-Mods). */
  ownJars: number
  /** Übrige mitgelieferte Dateien (Konfigurationen, Resource Packs …). */
  overrides: number
}

interface ZipEntry {
  name: string
  method: number
  flags: number
  compressedSize: number
  size: number
  localOffset: number
}

const bad = (msg: string, details?: Record<string, unknown>) => new ApiError(422, 'invalid_pack', msg, details)

/** Sicherer relativer Pfad (kein `..`, kein Laufwerk, kein Backslash, keine Steuerzeichen). */
export function safeRelPath(p: string): boolean {
  if (p.length === 0 || p.length > 400) return false
  if (p.startsWith('/') || p.includes('\\') || /^[A-Za-z]:/.test(p)) return false
  // eslint-disable-next-line no-control-regex
  if (/[\x00-\x1f\x7f]/.test(p)) return false
  return p.split('/').every((seg, i, all) => seg !== '..' && seg !== '.' && (seg !== '' || i === all.length - 1))
}

function readEntries(buf: Buffer): ZipEntry[] {
  if (buf.length < 22 || buf.readUInt32LE(0) !== 0x04034b50) throw bad('This is not a .mrpack file')
  // Ende des Inhaltsverzeichnisses (EOCD) suchen – höchstens 64 KiB Kommentar.
  let eocd = -1
  for (let i = buf.length - 22; i >= Math.max(0, buf.length - 22 - 0xffff); i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) {
      eocd = i
      break
    }
  }
  if (eocd < 0) throw bad('Broken zip file')
  const count = buf.readUInt16LE(eocd + 10)
  const cdSize = buf.readUInt32LE(eocd + 12)
  const cdOffset = buf.readUInt32LE(eocd + 16)
  if (count === 0xffff || cdOffset === 0xffffffff) throw bad('ZIP64 packs are not supported')
  if (count > PACK_LIMITS.maxEntries) throw bad(`Too many files in the pack (max ${PACK_LIMITS.maxEntries})`)
  if (cdOffset + cdSize > eocd) throw bad('Broken zip file')
  const out: ZipEntry[] = []
  let p = cdOffset
  for (let n = 0; n < count; n++) {
    if (p + 46 > eocd || buf.readUInt32LE(p) !== 0x02014b50) throw bad('Broken zip file')
    const flags = buf.readUInt16LE(p + 8)
    const method = buf.readUInt16LE(p + 10)
    const compressedSize = buf.readUInt32LE(p + 20)
    const size = buf.readUInt32LE(p + 24)
    const nameLen = buf.readUInt16LE(p + 28)
    const extraLen = buf.readUInt16LE(p + 30)
    const commentLen = buf.readUInt16LE(p + 32)
    const localOffset = buf.readUInt32LE(p + 42)
    if (p + 46 + nameLen > eocd) throw bad('Broken zip file')
    const name = buf.toString('utf8', p + 46, p + 46 + nameLen)
    out.push({ name, method, flags, compressedSize, size, localOffset })
    p += 46 + nameLen + extraLen + commentLen
  }
  return out
}

function readEntry(buf: Buffer, e: ZipEntry, max: number): Buffer {
  if (e.flags & 0x1) throw bad('Encrypted packs are not supported')
  if (e.size > max) throw bad('modrinth.index.json is too large')
  const p = e.localOffset
  if (p + 30 > buf.length || buf.readUInt32LE(p) !== 0x04034b50) throw bad('Broken zip file')
  const start = p + 30 + buf.readUInt16LE(p + 26) + buf.readUInt16LE(p + 28)
  const end = start + e.compressedSize
  if (end > buf.length) throw bad('Broken zip file')
  const raw = buf.subarray(start, end)
  if (e.method === 0) return Buffer.from(raw)
  if (e.method !== 8) throw bad('Unsupported compression in the pack')
  try {
    return inflateRawSync(raw, { maxOutputLength: max })
  } catch {
    throw bad('Broken zip file')
  }
}

const sha1 = z.string().regex(/^[0-9a-f]{40}$/i)
const sha512 = z.string().regex(/^[0-9a-f]{128}$/i)
const cdnUrl = z.string().max(1000).refine((u) => {
  try {
    const url = new URL(u)
    return url.protocol === 'https:' && url.hostname === 'cdn.modrinth.com' && url.username === '' && url.password === '' && url.port === ''
  } catch {
    return false
  }
}, 'Downloads must come from cdn.modrinth.com')

const indexSchema = z.object({
  formatVersion: z.literal(1),
  game: z.literal('minecraft'),
  versionId: z.string().trim().min(1).max(64),
  name: z.string().trim().min(1).max(64),
  summary: z.string().max(500).optional().nullable(),
  files: z
    .array(z.object({
      path: z.string().refine(safeRelPath, 'Unsafe file path'),
      hashes: z.object({ sha1, sha512 }).loose(),
      env: z.object({ client: z.string().max(20), server: z.string().max(20) }).loose().optional(),
      downloads: z.array(cdnUrl).min(1).max(5),
      fileSize: z.number().int().min(0).max(2 ** 32),
    }).loose())
    .max(PACK_LIMITS.maxIndexFiles),
  dependencies: z.record(z.string().max(40), z.string().trim().min(1).max(64)),
}).loose()

// eslint-disable-next-line no-control-regex
const clean = (s: string, max: number) => s.replace(/[\x00-\x1f\x7f]/g, ' ').replace(/\s+/g, ' ').trim().slice(0, max)

const OVERRIDE_DIRS = ['overrides/', 'client-overrides/', 'server-overrides/']
const MC_VERSION = /^[0-9A-Za-z][0-9A-Za-z._+-]{0,39}$/

/** Pack prüfen und beschreiben. Wirft `invalid_pack` (422) mit einer verständlichen Meldung. */
export function inspectPack(buf: Buffer): PackInfo {
  const entries = readEntries(buf)
  let unpacked = 0
  let index: ZipEntry | null = null
  let ownJars = 0
  let overrides = 0
  const seen = new Set<string>()
  for (const e of entries) {
    if (!safeRelPath(e.name)) throw bad('The pack contains an unsafe file path', { path: e.name.slice(0, 200) })
    const key = e.name.toLowerCase()
    if (seen.has(key)) throw bad('The pack contains a file twice', { path: e.name.slice(0, 200) })
    seen.add(key)
    unpacked += e.size
    if (unpacked > PACK_LIMITS.maxUnpackedBytes) throw bad('The pack is too large when unpacked')
    if (e.name === 'modrinth.index.json') {
      index = e
      continue
    }
    const dir = OVERRIDE_DIRS.find((d) => e.name.startsWith(d))
    if (!dir) throw bad('Only modrinth.index.json and overrides are allowed in a pack', { path: e.name.slice(0, 200) })
    if (e.name.endsWith('/')) continue
    const rest = e.name.slice(dir.length)
    if (/^mods\/[^/]+\.jar$/i.test(rest)) ownJars++
    else overrides++
  }
  if (!index) throw bad('modrinth.index.json is missing – export the pack from the TRS Launcher')
  let parsed: unknown
  try {
    parsed = JSON.parse(readEntry(buf, index, PACK_LIMITS.maxIndexBytes).toString('utf8'))
  } catch (err) {
    if (err instanceof ApiError) throw err
    throw bad('modrinth.index.json is not valid JSON')
  }
  const r = indexSchema.safeParse(parsed)
  if (!r.success) {
    const issue = r.error.issues[0]
    throw bad(`modrinth.index.json: ${issue?.message ?? 'invalid'}`, { path: issue?.path.join('.') ?? '' })
  }
  const idx = r.data
  const mc = idx.dependencies.minecraft
  if (!mc || !MC_VERSION.test(mc)) throw bad('modrinth.index.json: dependencies.minecraft is missing')
  let loader: PackLoader = 'vanilla'
  let loaderVersion: string | null = null
  for (const key of Object.keys(idx.dependencies)) {
    if (key === 'minecraft') continue
    if (!(LOADER_KEYS as readonly string[]).includes(key)) throw bad(`Unknown dependency “${key.slice(0, 40)}”`)
    if (loader !== 'vanilla') throw bad('A pack can only have one mod loader')
    loader = LOADER_OF[key as (typeof LOADER_KEYS)[number]]
    loaderVersion = clean(idx.dependencies[key]!, 64)
  }
  const summary = idx.summary ? clean(idx.summary, 300) : ''
  return {
    name: clean(idx.name, 64),
    summary: summary || null,
    packVersion: clean(idx.versionId, 64),
    mcVersion: mc,
    loader,
    loaderVersion,
    downloads: idx.files.length,
    ownJars,
    overrides,
  }
}

// ---------------------------------------------------------------- Inhalt für die Website (§27.6)

export interface PackContentItem {
  /** Dateiname ohne Endung (bzw. Ordnername bei entpackten Resource Packs/Shadern). */
  name: string
  /** Dateiname wie im Pack. */
  file: string
  /** `modrinth` = lädt der Launcher von Modrinth, `pack` = liegt als Kopie im Pack. */
  source: 'modrinth' | 'pack'
  /** Modrinth-Projekt-ID aus der Download-Adresse (für den Link zur Projektseite), sonst `null`. */
  projectId: string | null
  /** Modrinth-Versions-ID aus der Download-Adresse, sonst `null`. */
  versionId: string | null
}

export interface PackContents {
  mods: PackContentItem[]
  resourcePacks: PackContentItem[]
  shaderPacks: PackContentItem[]
}

const CONTENT_DIRS: { dir: string, key: keyof PackContents, file: RegExp }[] = [
  { dir: 'mods/', key: 'mods', file: /\.jar$/i },
  { dir: 'resourcepacks/', key: 'resourcePacks', file: /\.zip$/i },
  { dir: 'shaderpacks/', key: 'shaderPacks', file: /\.zip$/i },
]
const MODRINTH_FILE = /^\/data\/([A-Za-z0-9]{8})\/versions\/([A-Za-z0-9]{8})\//
const MODRINTH_PROJECT = /^\/data\/([A-Za-z0-9]{8})\/versions\//
/** Höchstens so viele Einträge je Liste (Index erlaubt 5000 Dateien). */
export const PACK_CONTENT_MAX = 1000

function modrinthIdsOf(urls: string[]): { projectId: string | null, versionId: string | null } {
  for (const u of urls) {
    try {
      const path = new URL(u).pathname
      const full = MODRINTH_FILE.exec(path)
      if (full) return { projectId: full[1]!, versionId: full[2]! }
      const m = MODRINTH_PROJECT.exec(path)
      if (m) return { projectId: m[1]!, versionId: null }
    } catch {
      // ungültige Adresse – ignorieren
    }
  }
  return { projectId: null, versionId: null }
}

/**
 * Mods, Resource Packs und Shader eines (beim Hochladen schon geprüften) Packs – aus dem Index (Modrinth) und aus
 * `overrides/` (eigene Dateien). Liest nur das Inhaltsverzeichnis und den Index, entpackt nichts.
 */
export function listPackContents(buf: Buffer): PackContents {
  const out: PackContents = { mods: [], resourcePacks: [], shaderPacks: [] }
  const seen = new Set<string>()
  const add = (key: keyof PackContents, item: PackContentItem) => {
    const id = `${key}:${item.file.toLowerCase()}`
    if (seen.has(id) || out[key].length >= PACK_CONTENT_MAX) return
    seen.add(id)
    out[key].push(item)
  }
  const entries = readEntries(buf)
  const index = entries.find((e) => e.name === 'modrinth.index.json')
  if (index) {
    let parsed: unknown = null
    try {
      parsed = JSON.parse(readEntry(buf, index, PACK_LIMITS.maxIndexBytes).toString('utf8'))
    } catch {
      // kaputter Index – nur die Dateien aus overrides/ zeigen
    }
    const r = indexSchema.safeParse(parsed)
    if (r.success) {
      for (const f of r.data.files) {
        if (f.env?.client === 'unsupported') continue
        const c = CONTENT_DIRS.find((d) => f.path.startsWith(d.dir))
        const file = c ? f.path.slice(c.dir.length) : ''
        if (!c || file.includes('/') || !c.file.test(file)) continue
        add(c.key, { name: clean(file.replace(c.file, ''), 120), file: clean(file, 200), source: 'modrinth', ...modrinthIdsOf(f.downloads) })
      }
    }
  }
  for (const e of entries) {
    const dir = OVERRIDE_DIRS.find((d) => e.name.startsWith(d))
    if (!dir || dir === 'server-overrides/') continue
    const rest = e.name.slice(dir.length)
    const c = CONTENT_DIRS.find((d) => rest.startsWith(d.dir))
    if (!c) continue
    const inner = rest.slice(c.dir.length)
    if (!inner) continue
    const slash = inner.indexOf('/')
    if (slash < 0) {
      if (c.file.test(inner)) add(c.key, { name: clean(inner.replace(c.file, ''), 120), file: clean(inner, 200), source: 'pack', projectId: null, versionId: null })
    } else if (c.key !== 'mods') {
      // Entpackter Resource Pack/Shader als Ordner.
      const folder = inner.slice(0, slash)
      if (folder) add(c.key, { name: clean(folder, 120), file: clean(`${folder}/`, 200), source: 'pack', projectId: null, versionId: null })
    }
  }
  const byName = (a: PackContentItem, b: PackContentItem) => a.name.localeCompare(b.name, 'en', { sensitivity: 'base' })
  out.mods.sort(byName)
  out.resourcePacks.sort(byName)
  out.shaderPacks.sort(byName)
  return out
}
