import { closeSync, openSync, readSync } from 'node:fs'
import { inflateRawSync } from 'node:zlib'
import { z } from 'zod'
import { ApiError } from './errors'

/**
 * Prüfung geteilter Modpacks (§27): Nur echte Modrinth-Packs (`.mrpack`) werden angenommen – ein Zip mit
 * `modrinth.index.json` und Dateien unter `overrides/`, `client-overrides/` oder `server-overrides/`.
 *
 * Der Server entpackt nichts auf die Platte: Er liest nur das Inhaltsverzeichnis des Zips und den Index
 * (auch aus einer Datei, ohne sie ganz in den Speicher zu laden). Downloads im Index dürfen nur von den
 * erlaubten HTTPS-Hosts kommen (Modrinth, CurseForge, GitHub). Der Server folgt keinen Weiterleitungen –
 * das macht erst der Launcher, und auch nur zu denselben Hosts. Alles andere (Konfigurationen, eigene
 * Mods) liegt als Kopie im Pack.
 */

/** Grenzen gegen Zip-Bomben und Riesen-Indizes. */
export const PACK_LIMITS = {
  maxEntries: 20_000,
  maxIndexBytes: 4 * 1024 * 1024,
  /** Summe aller entpackten Größen – der Launcher entpackt das ja später. */
  maxUnpackedBytes: 1024 * 1024 * 1024,
  maxIndexFiles: 5_000,
  /** Inhaltsverzeichnis des Zips, nicht die Dateien selbst. */
  maxCentralDirectoryBytes: 32 * 1024 * 1024,
} as const

/** Hosts, von denen ein Index Dateien laden darf (genau dieser Name, nur HTTPS, ohne Port und Login). */
export const PACK_DOWNLOAD_HOSTS = [
  'cdn.modrinth.com',
  'edge.forgecdn.net',
  'mediafilez.forgecdn.net',
  'github.com',
  'raw.githubusercontent.com',
] as const

export type PackDownloadHost = (typeof PACK_DOWNLOAD_HOSTS)[number]
export type PackRemoteSource = 'modrinth' | 'curseforge' | 'github'

const HOST_SOURCE: Record<PackDownloadHost, PackRemoteSource> = {
  'cdn.modrinth.com': 'modrinth',
  'edge.forgecdn.net': 'curseforge',
  'mediafilez.forgecdn.net': 'curseforge',
  'github.com': 'github',
  'raw.githubusercontent.com': 'github',
}

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
  /** Dateien im Index (von einem erlaubten Host geladen). */
  downloads: number
  /** Eigene `.jar`-Dateien im Pack (nicht verlinkt, z. B. selbst gebaute Mods). */
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

/** Leser mit wahlfreiem Zugriff – Puffer oder Datei, damit ein 1-GB-Pack nicht ganz im RAM liegen muss. */
interface Reader {
  length: number
  read(offset: number, length: number): Buffer
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

function bufferReader(buf: Buffer): Reader {
  return {
    length: buf.length,
    read(offset, length) {
      if (offset < 0 || length < 0 || offset > buf.length || offset + length > buf.length) throw bad('Broken zip file')
      return buf.subarray(offset, offset + length)
    },
  }
}

class FileReader implements Reader {
  private fd: number
  constructor(path: string, readonly length: number) {
    this.fd = openSync(path, 'r')
  }

  read(offset: number, length: number): Buffer {
    if (offset < 0 || length < 0 || offset > this.length || offset + length > this.length) throw bad('Broken zip file')
    if (length === 0) return Buffer.alloc(0)
    const out = Buffer.alloc(length)
    let got = 0
    while (got < length) {
      const n = readSync(this.fd, out, got, length - got, offset + got)
      if (n <= 0) throw bad('Broken zip file')
      got += n
    }
    return out
  }

  close(): void {
    closeSync(this.fd)
  }
}

function readEntries(r: Reader): ZipEntry[] {
  if (r.length < 22 || r.read(0, 4).readUInt32LE(0) !== 0x04034b50) throw bad('This is not a .mrpack file')
  // Ende des Inhaltsverzeichnisses (EOCD) suchen – höchstens 64 KiB Kommentar.
  const tailLen = Math.min(r.length, 22 + 0xffff)
  const tailOff = r.length - tailLen
  const tail = r.read(tailOff, tailLen)
  let rel = -1
  for (let i = tail.length - 22; i >= 0; i--) {
    if (tail.readUInt32LE(i) === 0x06054b50) {
      rel = i
      break
    }
  }
  if (rel < 0) throw bad('Broken zip file')
  const eocd = tailOff + rel
  const count = tail.readUInt16LE(rel + 10)
  const cdSize = tail.readUInt32LE(rel + 12)
  const cdOffset = tail.readUInt32LE(rel + 16)
  if (count === 0xffff || cdOffset === 0xffffffff || cdSize === 0xffffffff) throw bad('ZIP64 packs are not supported')
  if (count > PACK_LIMITS.maxEntries) throw bad(`Too many files in the pack (max ${PACK_LIMITS.maxEntries})`)
  if (cdSize > PACK_LIMITS.maxCentralDirectoryBytes) throw bad('Broken zip file')
  if (cdOffset + cdSize > eocd) throw bad('Broken zip file')
  const cd = r.read(cdOffset, cdSize)
  const out: ZipEntry[] = []
  let p = 0
  for (let n = 0; n < count; n++) {
    if (p + 46 > cd.length || cd.readUInt32LE(p) !== 0x02014b50) throw bad('Broken zip file')
    const flags = cd.readUInt16LE(p + 8)
    const method = cd.readUInt16LE(p + 10)
    const compressedSize = cd.readUInt32LE(p + 20)
    const size = cd.readUInt32LE(p + 24)
    const nameLen = cd.readUInt16LE(p + 28)
    const extraLen = cd.readUInt16LE(p + 30)
    const commentLen = cd.readUInt16LE(p + 32)
    const localOffset = cd.readUInt32LE(p + 42)
    if (p + 46 + nameLen > cd.length) throw bad('Broken zip file')
    const name = cd.toString('utf8', p + 46, p + 46 + nameLen)
    out.push({ name, method, flags, compressedSize, size, localOffset })
    p += 46 + nameLen + extraLen + commentLen
  }
  return out
}

function readEntry(r: Reader, e: ZipEntry, max: number): Buffer {
  if (e.flags & 0x1) throw bad('Encrypted packs are not supported')
  if (e.size > max) throw bad('modrinth.index.json is too large')
  if (e.compressedSize === 0xffffffff || e.size === 0xffffffff || e.localOffset === 0xffffffff) throw bad('ZIP64 packs are not supported')
  if (e.localOffset + 30 > r.length) throw bad('Broken zip file')
  const hdr = r.read(e.localOffset, 30)
  if (hdr.readUInt32LE(0) !== 0x04034b50) throw bad('Broken zip file')
  const start = e.localOffset + 30 + hdr.readUInt16LE(26) + hdr.readUInt16LE(28)
  const end = start + e.compressedSize
  if (end > r.length) throw bad('Broken zip file')
  const raw = r.read(start, e.compressedSize)
  if (e.method === 0) return Buffer.from(raw)
  if (e.method !== 8) throw bad('Unsupported compression in the pack')
  try {
    return inflateRawSync(raw, { maxOutputLength: max })
  } catch {
    throw bad('Broken zip file')
  }
}

/** Host einer Download-Adresse, wenn sie auf der Allowlist steht. Sonst `null` (kein Abruf, keine Weiterleitung). */
export function packDownloadHost(u: string): PackDownloadHost | null {
  let url: URL
  try {
    url = new URL(u)
  } catch {
    return null
  }
  if (url.protocol !== 'https:' || url.username !== '' || url.password !== '' || url.port !== '') return null
  if (u.includes('\\')) return null
  const host = url.hostname.toLowerCase().replace(/\.$/, '')
  return (PACK_DOWNLOAD_HOSTS as readonly string[]).includes(host) ? host as PackDownloadHost : null
}

/** `modrinth` / `curseforge` / `github` für eine erlaubte Adresse, sonst `null`. */
export function packRemoteSource(u: string): PackRemoteSource | null {
  const host = packDownloadHost(u)
  return host ? HOST_SOURCE[host] : null
}

const sha1 = z.string().regex(/^[0-9a-f]{40}$/i)
const sha512 = z.string().regex(/^[0-9a-f]{128}$/i)
const allowedDownload = z.string().max(1000).refine(
  (u) => packDownloadHost(u) !== null,
  'Downloads must use https on cdn.modrinth.com, edge.forgecdn.net, mediafilez.forgecdn.net, github.com or raw.githubusercontent.com',
)

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
      downloads: z.array(allowedDownload).min(1).max(5),
      fileSize: z.number().int().min(0).max(2 ** 32),
    }).loose())
    .max(PACK_LIMITS.maxIndexFiles),
  dependencies: z.record(z.string().max(40), z.string().trim().min(1).max(64)),
}).loose()

// eslint-disable-next-line no-control-regex
const clean = (s: string, max: number) => s.replace(/[\x00-\x1f\x7f]/g, ' ').replace(/\s+/g, ' ').trim().slice(0, max)

const OVERRIDE_DIRS = ['overrides/', 'client-overrides/', 'server-overrides/']
const MC_VERSION = /^[0-9A-Za-z][0-9A-Za-z._+-]{0,39}$/

function inspectReader(r: Reader): PackInfo {
  const entries = readEntries(r)
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
    parsed = JSON.parse(readEntry(r, index, PACK_LIMITS.maxIndexBytes).toString('utf8'))
  } catch (err) {
    if (err instanceof ApiError) throw err
    throw bad('modrinth.index.json is not valid JSON')
  }
  const result = indexSchema.safeParse(parsed)
  if (!result.success) {
    const issue = result.error.issues[0]
    throw bad(`modrinth.index.json: ${issue?.message ?? 'invalid'}`, { path: issue?.path.join('.') ?? '' })
  }
  const idx = result.data
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

/** Pack prüfen und beschreiben. Wirft `invalid_pack` (422) mit einer verständlichen Meldung. */
export function inspectPack(buf: Buffer): PackInfo {
  return inspectReader(bufferReader(buf))
}

/** Wie {@link inspectPack}, liest aber nur Verzeichnis und Index von der Platte. */
export function inspectPackFile(path: string, size: number): PackInfo {
  const r = new FileReader(path, size)
  try {
    return inspectReader(r)
  } finally {
    r.close()
  }
}

// ---------------------------------------------------------------- Inhalt für die Website (§27.6)

export interface PackContentItem {
  /** Dateiname ohne Endung (bzw. Ordnername bei entpackten Resource Packs/Shadern). */
  name: string
  /** Dateiname wie im Pack. */
  file: string
  /** `modrinth`/`curseforge`/`github` = Link im Index, `pack` = liegt als Kopie im Pack. */
  source: PackRemoteSource | 'pack'
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
    if (packDownloadHost(u) !== 'cdn.modrinth.com') continue
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

function listFrom(r: Reader): PackContents {
  const out: PackContents = { mods: [], resourcePacks: [], shaderPacks: [] }
  const seen = new Set<string>()
  const add = (key: keyof PackContents, item: PackContentItem) => {
    const id = `${key}:${item.file.toLowerCase()}`
    if (seen.has(id) || out[key].length >= PACK_CONTENT_MAX) return
    seen.add(id)
    out[key].push(item)
  }
  const entries = readEntries(r)
  const index = entries.find((e) => e.name === 'modrinth.index.json')
  if (index) {
    let parsed: unknown = null
    try {
      parsed = JSON.parse(readEntry(r, index, PACK_LIMITS.maxIndexBytes).toString('utf8'))
    } catch {
      // kaputter Index – nur die Dateien aus overrides/ zeigen
    }
    const result = indexSchema.safeParse(parsed)
    if (result.success) {
      for (const f of result.data.files) {
        if (f.env?.client === 'unsupported') continue
        const c = CONTENT_DIRS.find((d) => f.path.startsWith(d.dir))
        const file = c ? f.path.slice(c.dir.length) : ''
        if (!c || file.includes('/') || !c.file.test(file)) continue
        const source = packRemoteSource(f.downloads[0] ?? '') ?? 'modrinth'
        add(c.key, { name: clean(file.replace(c.file, ''), 120), file: clean(file, 200), source, ...modrinthIdsOf(f.downloads) })
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

/**
 * Mods, Resource Packs und Shader eines (beim Hochladen schon geprüften) Packs – aus dem Index (Download-Link) und aus
 * `overrides/` (eigene Dateien). Liest nur das Inhaltsverzeichnis und den Index, entpackt nichts.
 */
export function listPackContents(buf: Buffer): PackContents {
  return listFrom(bufferReader(buf))
}

/** Wie {@link listPackContents}, ohne die Datei ganz zu lesen. */
export function listPackContentsFile(path: string, size: number): PackContents {
  const r = new FileReader(path, size)
  try {
    return listFrom(r)
  } finally {
    r.close()
  }
}
