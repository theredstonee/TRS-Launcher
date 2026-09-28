import { z } from 'zod'
import type { AppContext } from './context'

/**
 * Modrinth-Angaben für die Inhaltsliste geteilter Packs (§27.6): Projektname und -symbol, Versionsnummer.
 * Der Server fragt die öffentliche Modrinth-API (nur Projekt-/Versions-IDs aus dem Pack, keine Daten der Besucher)
 * und merkt sich die Antworten einen Tag im RAM. Symbole leitet er selbst weiter (`/v1/modrinth/icon/<id>`), damit
 * Besucher-Browser keine Verbindung zu Modrinth aufbauen und die CSP der Website streng bleibt.
 */

const API = 'https://api.modrinth.com/v2'
const UA = 'theredstonee/TRS-Launcher (trs-launcher.theredstonee.de)'
const TTL_MS = 24 * 60 * 60 * 1000
/** Nach einem Fehler von Modrinth eine Weile nicht erneut fragen. */
const RETRY_MS = 5 * 60 * 1000
const TIMEOUT_MS = 6000
const BATCH = 100
const MAX_ENTRIES = 20_000
const ICON_MAX_BYTES = 512 * 1024
const ICON_CACHE = 400

export const MODRINTH_ID = /^[A-Za-z0-9]{8}$/

export interface ModrinthProject {
  title: string
  slug: string
  type: string
  /** Nur `https://cdn.modrinth.com/…`, sonst `null`. */
  iconUrl: string | null
}

const projectSchema = z.object({
  id: z.string().regex(MODRINTH_ID),
  slug: z.string().max(100),
  title: z.string().max(200),
  project_type: z.string().max(40),
  icon_url: z.string().max(500).nullish(),
}).loose()
const versionSchema = z.object({
  id: z.string().regex(MODRINTH_ID),
  version_number: z.string().max(100),
}).loose()

const projects = new Map<string, { at: number, value: ModrinthProject | null }>()
const versions = new Map<string, { at: number, value: string | null }>()
const icons = new Map<string, { type: string, data: Buffer }>()
let blockedUntil = 0

function cdnUrl(u: string | null | undefined): string | null {
  if (!u) return null
  try {
    const url = new URL(u)
    return url.protocol === 'https:' && url.hostname === 'cdn.modrinth.com' && !url.username && !url.password && !url.port ? url.href : null
  } catch {
    return null
  }
}

function trim<V>(map: Map<string, V>, max: number): void {
  while (map.size > max) map.delete(map.keys().next().value!)
}

async function getJson(ctx: AppContext, path: string, ids: string[]): Promise<unknown[] | null> {
  const f = ctx.modrinthFetch ?? fetch
  const url = `${API}/${path}?ids=${encodeURIComponent(JSON.stringify(ids))}`
  try {
    const res = await f(url, { headers: { 'User-Agent': UA, Accept: 'application/json' }, redirect: 'error', signal: AbortSignal.timeout(TIMEOUT_MS) })
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    const body: unknown = JSON.parse(await res.text())
    return Array.isArray(body) ? body : null
  } catch (err) {
    blockedUntil = ctx.now() + RETRY_MS
    console.warn(`[trs-api] Modrinth ${path} failed: ${(err as Error).message}`)
    return null
  }
}

/** Fehlende Projekte/Versionen bei Modrinth nachschlagen (in Blöcken); Fehler lassen die Liste einfach ohne Namen. */
export async function loadModrinth(ctx: AppContext, projectIds: string[], versionIds: string[]): Promise<boolean> {
  const t = ctx.now()
  const fresh = <V>(m: Map<string, { at: number, value: V }>, id: string) => {
    const e = m.get(id)
    return e !== undefined && t - e.at < TTL_MS
  }
  const needP = [...new Set(projectIds.filter((id) => MODRINTH_ID.test(id) && !fresh(projects, id)))]
  const needV = [...new Set(versionIds.filter((id) => MODRINTH_ID.test(id) && !fresh(versions, id)))]
  if (!needP.length && !needV.length) return true
  if (t < blockedUntil) return false
  let ok = true
  for (let i = 0; i < needP.length; i += BATCH) {
    const part = needP.slice(i, i + BATCH)
    const list = await getJson(ctx, 'projects', part)
    if (!list) {
      ok = false
      break
    }
    for (const id of part) projects.set(id, { at: t, value: null })
    for (const raw of list) {
      const r = projectSchema.safeParse(raw)
      if (r.success) projects.set(r.data.id, { at: t, value: { title: r.data.title.trim(), slug: r.data.slug, type: r.data.project_type, iconUrl: cdnUrl(r.data.icon_url) } })
    }
  }
  for (let i = 0; ok && i < needV.length; i += BATCH) {
    const part = needV.slice(i, i + BATCH)
    const list = await getJson(ctx, 'versions', part)
    if (!list) {
      ok = false
      break
    }
    for (const id of part) versions.set(id, { at: t, value: null })
    for (const raw of list) {
      const r = versionSchema.safeParse(raw)
      if (r.success) versions.set(r.data.id, { at: t, value: r.data.version_number.trim() })
    }
  }
  trim(projects, MAX_ENTRIES)
  trim(versions, MAX_ENTRIES)
  return ok
}

export function modrinthProject(id: string): ModrinthProject | null {
  return projects.get(id)?.value ?? null
}

export function modrinthVersion(id: string): string | null {
  return versions.get(id)?.value ?? null
}

/** Bildtyp an den ersten Bytes erkennen – nur PNG, JPEG, GIF, WebP (kein SVG). */
export function imageType(b: Buffer): string | null {
  if (b.length >= 8 && b.readUInt32BE(0) === 0x89504e47) return 'image/png'
  if (b.length >= 3 && b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff) return 'image/jpeg'
  if (b.length >= 6 && b.toString('latin1', 0, 4) === 'GIF8') return 'image/gif'
  if (b.length >= 12 && b.toString('latin1', 0, 4) === 'RIFF' && b.toString('latin1', 8, 12) === 'WEBP') return 'image/webp'
  return null
}

/**
 * Symbol eines Projekts, das schon in einer Pack-Inhaltsliste vorkam (sonst `null` – kein offener Proxy).
 * Nur von `cdn.modrinth.com`, ≤ 512 KB, nur echte Rasterbilder.
 */
export async function modrinthIcon(ctx: AppContext, id: string): Promise<{ type: string, data: Buffer } | null> {
  const hit = icons.get(id)
  if (hit) return hit
  const url = projects.get(id)?.value?.iconUrl
  if (!url) return null
  const f = ctx.modrinthFetch ?? fetch
  try {
    const res = await f(url, { headers: { 'User-Agent': UA }, redirect: 'error', signal: AbortSignal.timeout(TIMEOUT_MS) })
    if (!res.ok) return null
    const len = Number(res.headers.get('content-length') ?? '0')
    if (len > ICON_MAX_BYTES) return null
    const data = Buffer.from(await res.arrayBuffer())
    if (data.length > ICON_MAX_BYTES) return null
    const type = imageType(data)
    if (!type) return null
    const icon = { type, data }
    icons.set(id, icon)
    trim(icons, ICON_CACHE)
    return icon
  } catch {
    return null
  }
}

/** Für Tests: Zwischenspeicher leeren. */
export function resetModrinthCache(): void {
  projects.clear()
  versions.clear()
  icons.clear()
  blockedUntil = 0
}
