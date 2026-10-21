import { createReadStream, readFileSync, statSync } from 'node:fs'
import { getHeader, sendStream, setResponseHeaders, setResponseStatus, type H3Event } from 'h3'
import { WAIT_SONG_UUID, parseWaitTimeline, type WaitTimeline } from '../../shared/wait-song'
import type { AppContext } from './context'
import { ApiError, notFound } from './errors'
import { resolveInside } from './private-assets'
import { teamOf } from './team'

/**
 * Persönliches Musikvideo (shared/wait-song.ts): MP3 + timeline.json liegen privat unter
 * `<PRIVATE_ASSETS_DIR>/wait-song/`. Nur der eine Creator und Owner/Admins dürfen es abrufen; für alle anderen
 * sieht es aus, als gäbe es die Datei nicht (404).
 */

const DIR = 'wait-song'
/** Obergrenze für timeline.json. */
export const WAIT_TIMELINE_MAX_BYTES = 16 * 1024

/** Darf `uuid` das Video sehen? Der Creator selbst, der Owner oder ein Admin. */
export function waitSongAccess(ctx: AppContext, uuid: string): boolean {
  const id = uuid.toLowerCase().replace(/-/g, '')
  if (id === WAIT_SONG_UUID) return true
  const staff = teamOf(ctx, id)
  return !!staff && (staff.owner || staff.role === 'admin')
}

/** Pfad einer Datei im Ordner (ohne Symlink-Ausbruch). `null` = unsicher oder Ordner fehlt. */
function filePath(ctx: AppContext, name: 'song.mp3' | 'timeline.json'): string | null {
  return resolveInside(ctx.config.privateAssetsDir, [DIR, name])
}

/** MP3 mit Größe und Änderungszeit, `null` wenn sie fehlt (oder leer ist). */
export function waitSongFile(ctx: AppContext): { path: string, size: number, mtimeMs: number } | null {
  const path = filePath(ctx, 'song.mp3')
  if (!path) return null
  try {
    const st = statSync(path)
    if (!st.isFile() || st.size === 0) return null
    return { path, size: st.size, mtimeMs: st.mtimeMs }
  } catch {
    return null
  }
}

/** Gemerkte Warnung je Datei-Stand, damit ein kaputtes timeline.json nicht jede Anfrage ins Log schreibt. */
let warnedFor = ''

/** timeline.json lesen. Fehlt sie oder ist ungültig → `null` (ungültig: eine Warnung). Wirft nie. */
export function readWaitTimeline(ctx: AppContext): WaitTimeline | null {
  const path = filePath(ctx, 'timeline.json')
  if (!path) return null
  let key = ''
  const warn = (why: string) => {
    if (warnedFor === key) return
    warnedFor = key
    console.warn(`[trs-api] wait-song timeline.json ${why} – ignored`)
  }
  try {
    const st = statSync(path)
    key = `${path}:${st.size}:${st.mtimeMs}`
    if (!st.isFile()) {
      warn('is not a file')
      return null
    }
    if (st.size > WAIT_TIMELINE_MAX_BYTES) {
      warn('too large')
      return null
    }
    const tl = parseWaitTimeline(JSON.parse(readFileSync(path, 'utf8')))
    if (!tl) warn('invalid')
    return tl
  } catch (err) {
    if ((err as NodeJS.ErrnoException).code === 'ENOENT') return null
    warn('unreadable or not JSON')
    return null
  }
}

/**
 * `Range`-Kopf auswerten (nur ein Bereich: `bytes=a-b`, `bytes=a-`, `bytes=-n`). Kein/unverständlicher Kopf oder
 * mehrere Bereiche → `null` (ganze Datei). Nicht erfüllbar → `'unsatisfiable'`.
 */
export function parseRange(header: string | undefined, size: number): { start: number, end: number } | 'unsatisfiable' | null {
  if (header === undefined) return null
  const h = header.trim().toLowerCase()
  if (!h.startsWith('bytes=')) return null
  const spec = h.slice('bytes='.length).trim()
  if (spec.includes(',')) return null
  const m = /^(\d*)\s*-\s*(\d*)$/.exec(spec)
  if (!m || (m[1] === '' && m[2] === '')) return null
  if (m[1] === '') {
    const n = Number(m[2])
    if (!Number.isSafeInteger(n) || n <= 0 || size <= 0) return 'unsatisfiable'
    return { start: Math.max(0, size - n), end: size - 1 }
  }
  const start = Number(m[1])
  const end = m[2] === '' ? size - 1 : Number(m[2])
  if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end)) return 'unsatisfiable'
  // `a-b` mit b < a ist ungültig → Kopf ignorieren (RFC 9110).
  if (m[2] !== '' && end < start) return null
  if (start >= size) return 'unsatisfiable'
  return { start, end: Math.min(end, size - 1) }
}

/** Passt `If-None-Match` (Liste, `*`, schwache Vergleiche) zum ETag? */
function etagMatches(header: string | undefined, etag: string): boolean {
  if (!header) return false
  const bare = (t: string) => t.trim().replace(/^W\//, '')
  return header.split(',').some((t) => t.trim() === '*' || bare(t) === bare(etag))
}

/** MP3 streamen (nie ganz in den Speicher): ETag/304, `Range` → 206, unerfüllbar → 416. */
export function sendWaitSong(event: H3Event, file: { path: string, size: number, mtimeMs: number }) {
  const etag = `"ws-${file.size.toString(16)}-${Math.floor(file.mtimeMs).toString(16)}"`
  setResponseHeaders(event, {
    'Content-Type': 'audio/mpeg',
    'Accept-Ranges': 'bytes',
    'Cache-Control': 'private, no-cache',
    'ETag': etag,
    'Last-Modified': new Date(file.mtimeMs).toUTCString(),
    'Content-Disposition': 'inline; filename="song.mp3"',
    'X-Content-Type-Options': 'nosniff',
  })
  if (etagMatches(getHeader(event, 'if-none-match'), etag)) {
    setResponseStatus(event, 304)
    return ''
  }
  // If-Range mit anderem Stand → ganze Datei statt Stück.
  const ifRange = getHeader(event, 'if-range')
  const rangeHeader = ifRange !== undefined && ifRange.trim() !== etag ? undefined : getHeader(event, 'range')
  const range = parseRange(rangeHeader, file.size)
  if (range === 'unsatisfiable') {
    throw new ApiError(416, 'range_not_satisfiable', 'Range not satisfiable', undefined, { 'Content-Range': `bytes */${file.size}` })
  }
  const start = range?.start ?? 0
  const end = range?.end ?? file.size - 1
  setResponseHeaders(event, { 'Content-Length': String(end - start + 1) })
  if (range) {
    setResponseHeaders(event, { 'Content-Range': `bytes ${start}-${end}/${file.size}` })
    setResponseStatus(event, 206)
  }
  if (event.method === 'HEAD') return ''
  return sendStream(event, createReadStream(file.path, { start, end }))
}

/** 404 wie bei einer fehlenden Datei (verrät nicht, dass es das Video gibt). */
export const waitSongNotFound = () => notFound()
