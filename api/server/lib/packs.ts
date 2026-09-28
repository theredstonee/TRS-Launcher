import { randomBytes, randomInt } from 'node:crypto'
import { readdirSync, readFileSync, rmSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { getHeader, type H3Event } from 'h3'
import { writeFileAtomic } from './attachments'
import type { AppContext } from './context'
import { all, one, placeholders, run, tx } from './db'
import { ApiError, badRequest, conflict, notFound, unsupportedMedia } from './errors'
import type { PlayerRef } from './events'
import { areFriends, hasBlocked } from './friends'
import { sha256Hex } from './ids'
import { loadModrinth, modrinthProject, modrinthVersion } from './modrinth'
import { inspectPack, listPackContents, type PackContentItem, type PackContents, type PackLoader } from './packfile'
import { assertNotSanctioned } from './sanctions'
import { ACTIVE_BANS, getUser } from './users'

/**
 * Geteilte Modpacks (§27): Der Launcher exportiert eine Instanz als `.mrpack` (Mod-Liste + Einstellungen) und lädt
 * sie hoch. Andere installieren sie per Code (`TRS-XXXX-XXXX`), Link (`/p/<code>`) oder aus der Liste „an dich
 * geschickt“. Neue Version hochladen = gleicher Code, `revision` + 1 → Launcher zeigen „Update verfügbar“.
 *
 * Laufzeit wählt der Besitzer je Pack: 1, 7 oder 30 Tage oder unbegrenzt. Abgelaufene Packs löscht der Sweep.
 * Öffentlich (ohne Anmeldung) sind nur die Beschreibung und der Name des Besitzers – herunterladen geht nur mit
 * TRS-Konto (Launcher), damit der Server kein anonymer Datei-Verteiler wird.
 * Dateien: `<DATA_DIR>/packs/<xx>/<id>.<revision>.mrpack` (nur die aktuelle Version bleibt liegen).
 */

export const PACK_ID = /^[A-Za-z0-9_-]{22}$/
export const PACK_DURATIONS = ['1d', '7d', '30d', 'forever'] as const
export type PackDuration = (typeof PACK_DURATIONS)[number]

/** Crockford-Base32 (ohne I, L, O, U) – gut vorzulesen und abzutippen. */
const ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ'
const CODE = /^[0-9A-HJKMNP-TV-Z]{8}$/

const DAY = 24 * 60 * 60 * 1000
const DURATION_MS: Record<PackDuration, number | null> = { '1d': DAY, '7d': 7 * DAY, '30d': 30 * DAY, 'forever': null }

export interface PackRow {
  id: string
  code: string
  owner_uuid: string
  name: string
  summary: string | null
  pack_version: string
  mc_version: string
  loader: PackLoader
  loader_version: string | null
  index_files: number
  own_jars: number
  other_files: number
  revision: number
  bytes: number
  sha256: string
  duration: PackDuration
  installs: number
  created_at: number
  updated_at: number
  expires_at: number | null
}

/** Öffentliche Sicht (Website, Code-Eingabe, Update-Prüfung). */
export interface PackView {
  id: string
  /** Anzeigeform `TRS-XXXX-XXXX`. */
  code: string
  url: string
  name: string
  summary: string | null
  packVersion: string
  /** Zählt bei jeder neuen Version hoch – daran erkennt der Launcher Updates. */
  revision: number
  mcVersion: string
  loader: { kind: PackLoader, version: string | null }
  /** Mods & Co., die der Launcher von Modrinth lädt. */
  modrinthFiles: number
  /** Eigene Mod-Dateien im Pack (nicht von Modrinth) – der Launcher weist darauf hin. */
  ownJars: number
  /** Übrige mitgelieferte Dateien (Konfigurationen, Resource Packs …). */
  otherFiles: number
  bytes: number
  sha256: string
  owner: PlayerRef
  createdAt: string
  updatedAt: string
  /** `null` = unbegrenzt. */
  expiresAt: string | null
}

/** Sicht des Besitzers. */
export interface OwnPackView extends PackView {
  duration: PackDuration
  installs: number
  /** An wie viele Freunde geschickt. */
  sentTo: number
}

export interface PackLimitsView {
  active: number
  maxActive: number
  uploadsToday: number
  maxPerDay: number
  maxBytes: number
}

export interface InboxEntry {
  pack: PackView
  from: PlayerRef
  sentAt: string
}

const iso = (t: number) => new Date(t).toISOString()

const PACK_TYPES = new Set(['application/x-modrinth-modpack+zip', 'application/zip', 'application/octet-stream'])

/** Upload-Routen: nur Pack-Dateien (der Inhalt wird danach trotzdem geprüft). */
export function assertPackType(event: H3Event): void {
  const type = (getHeader(event, 'content-type') ?? '').split(';')[0]!.trim().toLowerCase()
  if (!PACK_TYPES.has(type)) throw unsupportedMedia('Content-Type must be application/x-modrinth-modpack+zip')
}

export function newPackId(): string {
  return randomBytes(16).toString('base64url')
}

export function newPackCode(): string {
  let s = ''
  for (let i = 0; i < 8; i++) s += ALPHABET[randomInt(ALPHABET.length)]
  return s
}

export function displayCode(code: string): string {
  return `TRS-${code.slice(0, 4)}-${code.slice(4)}`
}

/** Eingabe → 8 Zeichen: Groß/klein egal, `TRS-`, Striche und Leerzeichen weg, O→0, I/L→1. `null` = kein Code. */
export function normalizePackCode(input: string): string | null {
  let s = input.trim().toUpperCase().replace(/[\s-]/g, '')
  if (s.startsWith('TRS') && s.length === 11) s = s.slice(3)
  s = s.replace(/O/g, '0').replace(/[IL]/g, '1')
  return CODE.test(s) ? s : null
}

function fileOf(ctx: AppContext, r: Pick<PackRow, 'id' | 'revision'>): string {
  return join(ctx.packDir, r.id.slice(0, 2), `${r.id}.${r.revision}.mrpack`)
}

function ownerRef(ctx: AppContext, uuid: string): PlayerRef {
  return { uuid, name: getUser(ctx, uuid)?.name ?? '' }
}

export function packView(ctx: AppContext, r: PackRow): PackView {
  const code = displayCode(r.code)
  return {
    id: r.id,
    code,
    url: `${ctx.config.siteUrl}/p/${code}`,
    name: r.name,
    summary: r.summary,
    packVersion: r.pack_version,
    revision: r.revision,
    mcVersion: r.mc_version,
    loader: { kind: r.loader, version: r.loader_version },
    modrinthFiles: r.index_files,
    ownJars: r.own_jars,
    otherFiles: r.other_files,
    bytes: r.bytes,
    sha256: r.sha256,
    owner: ownerRef(ctx, r.owner_uuid),
    createdAt: iso(r.created_at),
    updatedAt: iso(r.updated_at),
    expiresAt: r.expires_at === null ? null : iso(r.expires_at),
  }
}

function ownView(ctx: AppContext, r: PackRow): OwnPackView {
  const sentTo = one<{ n: number }>(ctx.db, 'SELECT COUNT(*) AS n FROM shared_pack_recipients WHERE pack_id = ?', r.id)!.n
  return { ...packView(ctx, r), duration: r.duration, installs: r.installs, sentTo }
}

/** Aktives Pack (nicht abgelaufen, Besitzer nicht gesperrt). Funktion statt Konstante: users.ts importiert packs.ts. */
const active = () => `(expires_at IS NULL OR expires_at > ?) AND owner_uuid NOT IN (${ACTIVE_BANS})`

export function packByCode(ctx: AppContext, input: string): PackRow | undefined {
  const code = normalizePackCode(input)
  if (!code) return undefined
  const t = ctx.now()
  return one<PackRow>(ctx.db, `SELECT * FROM shared_packs WHERE code = ? AND ${active()}`, code, t, t)
}

export function packById(ctx: AppContext, id: string): PackRow | undefined {
  if (!PACK_ID.test(id)) return undefined
  const t = ctx.now()
  return one<PackRow>(ctx.db, `SELECT * FROM shared_packs WHERE id = ? AND ${active()}`, id, t, t)
}

function ownPack(ctx: AppContext, uuid: string, id: string): PackRow {
  const r = PACK_ID.test(id) ? one<PackRow>(ctx.db, 'SELECT * FROM shared_packs WHERE id = ? AND (expires_at IS NULL OR expires_at > ?)', id, ctx.now()) : undefined
  if (!r || r.owner_uuid !== uuid) throw notFound('pack_not_found', 'Modpack not found')
  return r
}

export function packStorageUsed(ctx: AppContext): number {
  return one<{ n: number | null }>(ctx.db, 'SELECT SUM(bytes) AS n FROM shared_packs')!.n ?? 0
}

export function packLimits(ctx: AppContext, uuid: string): PackLimitsView {
  const lim = ctx.config.limits
  const t = ctx.now()
  const active = one<{ n: number }>(
    ctx.db, 'SELECT COUNT(*) AS n FROM shared_packs WHERE owner_uuid = ? AND (expires_at IS NULL OR expires_at > ?)', uuid, t,
  )!.n
  const uploadsToday = one<{ n: number }>(ctx.db, 'SELECT COUNT(*) AS n FROM shared_pack_uploads WHERE uuid = ? AND at > ?', uuid, t - DAY)!.n
  return { active, maxActive: lim.maxSharedPacks, uploadsToday, maxPerDay: lim.maxPackUploadsPerDay, maxBytes: ctx.config.packMaxBytes }
}

function assertCanUpload(ctx: AppContext, uuid: string, bytes: number, newPack: boolean): void {
  assertNotSanctioned(ctx, uuid, 'upload_ban')
  const l = packLimits(ctx, uuid)
  if (newPack && l.active >= l.maxActive) {
    throw new ApiError(409, 'pack_limit', `You can share at most ${l.maxActive} modpacks – delete an old one first`, { max: l.maxActive })
  }
  if (l.uploadsToday >= l.maxPerDay) {
    const oldest = one<{ at: number }>(
      ctx.db, 'SELECT at FROM shared_pack_uploads WHERE uuid = ? AND at > ? ORDER BY at LIMIT 1', uuid, ctx.now() - DAY,
    )
    const retry = Math.max(1, Math.ceil(((oldest?.at ?? ctx.now()) + DAY - ctx.now()) / 1000))
    throw new ApiError(429, 'pack_daily_limit', `At most ${l.maxPerDay} modpack uploads per day`, { retryAfter: retry, max: l.maxPerDay }, {
      'Retry-After': String(retry),
    })
  }
  if (packStorageUsed(ctx) + bytes > ctx.config.packStorageMaxBytes) {
    console.warn('[trs-api] shared modpack storage is full (PACK_STORAGE_MAX_MB)')
    throw new ApiError(507, 'storage_full', 'Modpack storage is full right now, try again later')
  }
}

function expiry(ctx: AppContext, duration: PackDuration): number | null {
  const ms = DURATION_MS[duration]
  return ms === null ? null : ctx.now() + ms
}

/** Neues Pack teilen. */
export function uploadPack(ctx: AppContext, uuid: string, body: Buffer, duration: PackDuration): OwnPackView {
  if (body.length > ctx.config.packMaxBytes) throw new ApiError(413, 'payload_too_large', 'The modpack is too large')
  assertCanUpload(ctx, uuid, body.length, true)
  const info = inspectPack(body)
  const t = ctx.now()
  const row: PackRow = {
    id: newPackId(),
    code: '',
    owner_uuid: uuid,
    name: info.name,
    summary: info.summary,
    pack_version: info.packVersion,
    mc_version: info.mcVersion,
    loader: info.loader,
    loader_version: info.loaderVersion,
    index_files: info.downloads,
    own_jars: info.ownJars,
    other_files: info.overrides,
    revision: 1,
    bytes: body.length,
    sha256: sha256Hex(body),
    duration,
    installs: 0,
    created_at: t,
    updated_at: t,
    expires_at: expiry(ctx, duration),
  }
  writeFileAtomic(fileOf(ctx, row), body)
  try {
    tx(ctx.db, () => {
      // Code muss eindeutig sein (auch gegenüber abgelaufenen, noch nicht gelöschten Packs).
      for (let i = 0; ; i++) {
        row.code = newPackCode()
        if (!one(ctx.db, 'SELECT 1 AS x FROM shared_packs WHERE code = ?', row.code)) break
        if (i > 20) throw new Error('no free pack code')
      }
      run(
        ctx.db,
        `INSERT INTO shared_packs (id, code, owner_uuid, name, summary, pack_version, mc_version, loader, loader_version, index_files,
           own_jars, other_files, revision, bytes, sha256, duration, installs, created_at, updated_at, expires_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
        row.id, row.code, row.owner_uuid, row.name, row.summary, row.pack_version, row.mc_version, row.loader, row.loader_version,
        row.index_files, row.own_jars, row.other_files, row.revision, row.bytes, row.sha256, row.duration, row.installs,
        row.created_at, row.updated_at, row.expires_at,
      )
      run(ctx.db, 'INSERT INTO shared_pack_uploads (uuid, at) VALUES (?, ?)', uuid, t)
    })
  } catch (err) {
    rmSync(fileOf(ctx, row), { force: true })
    throw err
  }
  return ownView(ctx, row)
}

/** Neue Version eines eigenen Packs (gleicher Code). Empfänger bekommen `pack_updated`. */
export function updatePackFile(ctx: AppContext, uuid: string, id: string, body: Buffer): OwnPackView {
  const old = ownPack(ctx, uuid, id)
  if (body.length > ctx.config.packMaxBytes) throw new ApiError(413, 'payload_too_large', 'The modpack is too large')
  assertCanUpload(ctx, uuid, Math.max(0, body.length - old.bytes), false)
  const info = inspectPack(body)
  const sha = sha256Hex(body)
  if (sha === old.sha256) throw conflict('pack_unchanged', 'This is the same file as the current version')
  const t = ctx.now()
  const row: PackRow = {
    ...old,
    name: info.name,
    summary: info.summary,
    pack_version: info.packVersion,
    mc_version: info.mcVersion,
    loader: info.loader,
    loader_version: info.loaderVersion,
    index_files: info.downloads,
    own_jars: info.ownJars,
    other_files: info.overrides,
    revision: old.revision + 1,
    bytes: body.length,
    sha256: sha,
    updated_at: t,
  }
  writeFileAtomic(fileOf(ctx, row), body)
  try {
    tx(ctx.db, () => {
      const n = run(
        ctx.db,
        `UPDATE shared_packs SET name = ?, summary = ?, pack_version = ?, mc_version = ?, loader = ?, loader_version = ?, index_files = ?,
           own_jars = ?, other_files = ?, revision = ?, bytes = ?, sha256 = ?, updated_at = ?
         WHERE id = ? AND revision = ?`,
        row.name, row.summary, row.pack_version, row.mc_version, row.loader, row.loader_version, row.index_files, row.own_jars,
        row.other_files, row.revision, row.bytes, row.sha256, row.updated_at, row.id, old.revision,
      )
      if (n !== 1) throw conflict('pack_changed', 'The modpack was changed at the same time – try again')
      run(ctx.db, 'INSERT INTO shared_pack_uploads (uuid, at) VALUES (?, ?)', uuid, t)
    })
  } catch (err) {
    rmSync(fileOf(ctx, row), { force: true })
    throw err
  }
  rmSync(fileOf(ctx, old), { force: true })
  const view = packView(ctx, row)
  for (const u of recipients(ctx, row.id)) ctx.events.publish(u, { type: 'pack_updated', pack: view })
  return ownView(ctx, row)
}

/** Laufzeit ändern – zählt ab jetzt neu. */
export function setPackDuration(ctx: AppContext, uuid: string, id: string, duration: PackDuration): OwnPackView {
  const r = ownPack(ctx, uuid, id)
  const expires = expiry(ctx, duration)
  run(ctx.db, 'UPDATE shared_packs SET duration = ?, expires_at = ? WHERE id = ?', duration, expires, r.id)
  return ownView(ctx, { ...r, duration, expires_at: expires })
}

export function listOwnPacks(ctx: AppContext, uuid: string): { packs: OwnPackView[], limits: PackLimitsView } {
  const rows = all<PackRow>(
    ctx.db,
    'SELECT * FROM shared_packs WHERE owner_uuid = ? AND (expires_at IS NULL OR expires_at > ?) ORDER BY updated_at DESC, id',
    uuid, ctx.now(),
  )
  return { packs: rows.map((r) => ownView(ctx, r)), limits: packLimits(ctx, uuid) }
}

export function deletePack(ctx: AppContext, uuid: string, id: string): void {
  removePacks(ctx, [ownPack(ctx, uuid, id)])
}

/** Moderation: Pack löschen (egal wem es gehört). */
export function adminDeletePack(ctx: AppContext, id: string): PackRow | null {
  const r = PACK_ID.test(id) ? one<PackRow>(ctx.db, 'SELECT * FROM shared_packs WHERE id = ?', id) : undefined
  if (!r) return null
  removePacks(ctx, [r])
  return r
}

function recipients(ctx: AppContext, packId: string): string[] {
  return all<{ uuid: string }>(ctx.db, 'SELECT uuid FROM shared_pack_recipients WHERE pack_id = ? AND dismissed_at IS NULL', packId).map((r) => r.uuid)
}

function removePacks(ctx: AppContext, rows: PackRow[], notify = true): void {
  if (rows.length === 0) return
  const told = notify ? rows.map((r) => ({ id: r.id, to: recipients(ctx, r.id) })) : []
  run(ctx.db, `DELETE FROM shared_packs WHERE id IN (${placeholders(rows.length)})`, ...rows.map((r) => r.id))
  removePackFiles(ctx, rows)
  for (const x of told) for (const u of x.to) ctx.events.publish(u, { type: 'pack_removed', packId: x.id })
}

export function removePackFiles(ctx: AppContext, rows: Pick<PackRow, 'id' | 'revision'>[]): void {
  for (const r of rows) rmSync(fileOf(ctx, r), { force: true })
}

/** Datei zum Herunterladen (zählt die Installation). */
export function readPackFile(ctx: AppContext, r: PackRow, count: boolean): Buffer {
  const data = readFileSync(fileOf(ctx, r))
  if (count) run(ctx.db, 'UPDATE shared_packs SET installs = installs + 1 WHERE id = ?', r.id)
  return data
}

/** Letzte Inhaltslisten je Pack-Version (die Datei ändert sich nur mit einer neuen `revision`). */
const contentsCache = new Map<string, PackContents>()

/** Eintrag der Inhaltsliste mit Modrinth-Angaben (Name, Version, Symbol über unseren Server, Link). */
export interface PackContentView extends PackContentItem {
  title: string | null
  version: string | null
  /** `/v1/modrinth/icon/<projectId>` – nur wenn Modrinth ein Symbol hat. */
  icon: string | null
  /** Projektseite auf modrinth.com. */
  url: string | null
}

export interface PackContentsView {
  mods: PackContentView[]
  resourcePacks: PackContentView[]
  shaderPacks: PackContentView[]
}

function baseContents(ctx: AppContext, r: PackRow): PackContents {
  const key = `${r.id}.${r.revision}`
  const hit = contentsCache.get(key)
  if (hit) return hit
  let data: Buffer
  try {
    data = readFileSync(fileOf(ctx, r))
  } catch {
    throw notFound('pack_not_found', 'Modpack not found')
  }
  const list = listPackContents(data)
  contentsCache.set(key, list)
  if (contentsCache.size > 64) contentsCache.delete(contentsCache.keys().next().value!)
  return list
}

/**
 * Mods, Resource Packs und Shader eines Packs für die Website (§27.6) – ohne die Installation zu zählen. Namen,
 * Versionen und Symbole kommen von Modrinth (Zwischenspeicher); fällt Modrinth aus, bleiben die Dateinamen.
 */
export async function packContents(ctx: AppContext, r: PackRow): Promise<PackContentsView> {
  const base = baseContents(ctx, r)
  const all = [...base.mods, ...base.resourcePacks, ...base.shaderPacks]
  await loadModrinth(
    ctx,
    all.map((i) => i.projectId).filter((x): x is string => x !== null),
    all.map((i) => i.versionId).filter((x): x is string => x !== null),
  )
  const view = (i: PackContentItem): PackContentView => {
    const p = i.projectId ? modrinthProject(i.projectId) : null
    return {
      ...i,
      title: p?.title || null,
      version: i.versionId ? modrinthVersion(i.versionId) : null,
      icon: p?.iconUrl ? `/v1/modrinth/icon/${i.projectId}` : null,
      url: p ? `https://modrinth.com/${encodeURIComponent(p.type)}/${encodeURIComponent(p.slug)}` : i.projectId ? `https://modrinth.com/project/${i.projectId}` : null,
    }
  }
  const sorted = (list: PackContentItem[]) => list.map(view).sort((x, y) => (x.title ?? x.name).localeCompare(y.title ?? y.name, 'en', { sensitivity: 'base' }))
  return { mods: sorted(base.mods), resourcePacks: sorted(base.resourcePacks), shaderPacks: sorted(base.shaderPacks) }
}

/** Viele Codes auf einmal (Update-Prüfung im Launcher). Unbekannte/abgelaufene fehlen einfach. */
export function lookupPacks(ctx: AppContext, codes: string[]): PackView[] {
  const norm = [...new Set(codes.map(normalizePackCode).filter((c): c is string => c !== null))]
  if (norm.length === 0) return []
  const t = ctx.now()
  return all<PackRow>(ctx.db, `SELECT * FROM shared_packs WHERE code IN (${placeholders(norm.length)}) AND ${active()}`, ...norm, t, t)
    .map((r) => packView(ctx, r))
}

// ---------------------------------------------------------------- An Freunde schicken

/** Pack an Freunde schicken (jeder, der das Pack sehen kann, an seine eigenen Freunde). */
export function sendPack(
  ctx: AppContext,
  me: string,
  id: string,
  to: string[],
): { sent: PlayerRef[], skipped: string[] } {
  assertNotSanctioned(ctx, me, 'social_ban')
  const r = packById(ctx, id)
  if (!r) throw notFound('pack_not_found', 'Modpack not found')
  const sent: PlayerRef[] = []
  const skipped: string[] = []
  const t = ctx.now()
  const max = ctx.config.limits.maxPackInbox
  const view = packView(ctx, r)
  const from = ownerRef(ctx, me)
  for (const u of [...new Set(to)]) {
    const user = getUser(ctx, u)
    if (!user || u === me || !areFriends(ctx, me, u) || hasBlocked(ctx, u, me) || hasBlocked(ctx, me, u)) {
      skipped.push(u)
      continue
    }
    const inbox = one<{ n: number }>(ctx.db, 'SELECT COUNT(*) AS n FROM shared_pack_recipients WHERE uuid = ? AND dismissed_at IS NULL', u)!.n
    const already = one<{ dismissed_at: number | null }>(
      ctx.db, 'SELECT dismissed_at FROM shared_pack_recipients WHERE pack_id = ? AND uuid = ?', r.id, u,
    )
    if (!already && inbox >= max) {
      skipped.push(u)
      continue
    }
    run(
      ctx.db,
      `INSERT INTO shared_pack_recipients (pack_id, uuid, from_uuid, created_at, dismissed_at) VALUES (?, ?, ?, ?, NULL)
       ON CONFLICT (pack_id, uuid) DO UPDATE SET from_uuid = excluded.from_uuid, created_at = excluded.created_at, dismissed_at = NULL`,
      r.id, u, me, t,
    )
    ctx.events.publish(u, { type: 'pack_shared', pack: view, from, sentAt: iso(t) })
    sent.push({ uuid: u, name: user.name })
  }
  if (sent.length === 0 && to.length > 0) throw badRequest('no_recipients', 'You can only send modpacks to your friends')
  return { sent, skipped }
}

/** An mich geschickte Packs (neueste zuerst). */
export function packInbox(ctx: AppContext, me: string): InboxEntry[] {
  const t = ctx.now()
  const rows = all<PackRow & { r_from: string, r_at: number }>(
    ctx.db,
    `SELECT p.*, r.from_uuid AS r_from, r.created_at AS r_at FROM shared_pack_recipients r JOIN shared_packs p ON p.id = r.pack_id
     WHERE r.uuid = ? AND r.dismissed_at IS NULL AND (p.expires_at IS NULL OR p.expires_at > ?) AND p.owner_uuid NOT IN (${ACTIVE_BANS})
       AND r.from_uuid NOT IN (SELECT blocked FROM blocks WHERE blocker = ?)
     ORDER BY r.created_at DESC LIMIT 200`,
    me, t, t, me,
  )
  return rows.map((r) => ({ pack: packView(ctx, r), from: ownerRef(ctx, r.r_from), sentAt: iso(r.r_at) }))
}

export function dismissInbox(ctx: AppContext, me: string, packId: string): void {
  if (!PACK_ID.test(packId)) throw notFound('pack_not_found', 'Modpack not found')
  const n = run(ctx.db, 'UPDATE shared_pack_recipients SET dismissed_at = ? WHERE pack_id = ? AND uuid = ? AND dismissed_at IS NULL', ctx.now(), packId, me)
  if (n === 0) throw notFound('pack_not_found', 'Modpack not found')
}

// ---------------------------------------------------------------- Aufräumen

/** Für die Kontolöschung: Zeilen verschwinden per FK, die Dateien danach mit {@link removePackFiles}. */
export function packsOf(ctx: AppContext, uuid: string): PackRow[] {
  return all<PackRow>(ctx.db, 'SELECT * FROM shared_packs WHERE owner_uuid = ?', uuid)
}

/** Abgelaufene Packs löschen (Zeilen + Dateien) und das Upload-Protokoll kürzen. */
export function sweepExpiredPacks(ctx: AppContext): number {
  const t = ctx.now()
  const rows = all<PackRow>(ctx.db, 'SELECT * FROM shared_packs WHERE expires_at IS NOT NULL AND expires_at <= ? LIMIT 500', t)
  removePacks(ctx, rows, false)
  run(ctx.db, 'DELETE FROM shared_pack_uploads WHERE at <= ?', t - DAY)
  return rows.length
}

/** Dateien ohne passende Zeile/Version (Absturz, alte Versionen), älter als eine Stunde. */
export function sweepOrphanPackFiles(ctx: AppContext): number {
  let removed = 0
  const cutoff = Date.now() - 60 * 60_000
  let dirs: string[]
  try {
    dirs = readdirSync(ctx.packDir)
  } catch {
    return 0
  }
  for (const d of dirs) {
    let files: string[]
    try {
      files = readdirSync(join(ctx.packDir, d))
    } catch {
      continue
    }
    for (const f of files) {
      const m = /^([A-Za-z0-9_-]{22})\.(\d+)\.mrpack$/.exec(f)
      if (m && one(ctx.db, 'SELECT 1 AS x FROM shared_packs WHERE id = ? AND revision = ?', m[1]!, Number(m[2])) !== undefined) continue
      const full = join(ctx.packDir, d, f)
      try {
        if (statSync(full).mtimeMs > cutoff) continue
        rmSync(full, { force: true })
        removed++
      } catch {
        // weiter
      }
    }
  }
  return removed
}
