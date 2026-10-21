import { createHash } from 'node:crypto'
import { extname } from 'node:path'
import { getHeader, getRouterParam, setResponseHeader, setResponseHeaders, setResponseStatus, type H3Event } from 'h3'
import { useCtx } from './context'
import { isApiError } from './errors'
import { clientIp, limit, requireStaff } from './http'
import { recordPitchView, resolvePitchToken } from './pitch'
import { RULES } from './ratelimit'
import { contentTypeOf } from './wiki'

/**
 * Pitch-Präsentation ausliefern (§38.1): `/pitch/<token>[/<datei>]` mit gültigem Link, `/admin/pitch/preview[/<datei>]`
 * für das Team (Website-Sitzung mit `pitch.manage`). Das Bundle liegt als Server-Asset `pitch` im Server (nicht in
 * public/), geladen über `setPitchBundleLoader` (server/plugins/00.context.ts; Tests setzen eigene Dateien).
 *
 * - Dateien werden nur in einer Tabelle (relativer Pfad → Inhalt) nachgeschlagen – kein Zusammensetzen von
 *   Dateipfaden aus der Adresse, `..`/`\`/`:`/NUL im Pfad → 404.
 * - index.html bekommt `<base href="/pitch/<token>/">`, damit relative Pfade (`assets/…`) auch ohne Schrägstrich am
 *   Ende der Adresse unter dem Token landen. Die Query (`?mode=…&lang=…`) bleibt unberührt.
 * - Jede Antwort (auch 404) mit eigener CSP, `X-Robots-Tag: noindex, nofollow`, `Referrer-Policy: no-referrer`.
 *   nuxt-security ist für diese Pfade abgeschaltet (routeRules in nuxt.config.ts).
 * - Ungültig/abgelaufen/widerrufen/fehlende Datei/kein Recht → dieselbe 404-Seite ohne Hinweis auf den Grund.
 */

export interface PitchFile {
  data: Buffer
  type: string
  etag: string
}
/** Relativer Pfad (`index.html`, `assets/app.js`) → Datei. */
export type PitchBundle = ReadonlyMap<string, PitchFile>

export const PITCH_CSP = [
  "default-src 'self'",
  "script-src 'self'",
  "style-src 'self' 'unsafe-inline'",
  "img-src 'self' data: blob:",
  "media-src 'self' blob:",
  "font-src 'self' data:",
  "object-src 'none'",
  "base-uri 'self'",
  "form-action 'none'",
  "frame-ancestors 'none'",
].join('; ')

export const PITCH_HEADERS: Record<string, string> = {
  'Content-Security-Policy': PITCH_CSP,
  'X-Robots-Tag': 'noindex, nofollow',
  'Referrer-Policy': 'no-referrer',
  'X-Content-Type-Options': 'nosniff',
  'X-Frame-Options': 'DENY',
  'Strict-Transport-Security': 'max-age=63072000; includeSubDomains; preload',
  'Permissions-Policy': 'camera=(), microphone=(), geolocation=(), payment=(), usb=(), fullscreen=(self)',
  'Cross-Origin-Opener-Policy': 'same-origin',
  'Cross-Origin-Resource-Policy': 'same-origin',
}

/** Dateitypen, die die Doku-Tabelle (server/lib/wiki.ts) nicht kennt – Medien einer Präsentation. */
const MEDIA_TYPES: Record<string, string> = {
  '.mp4': 'video/mp4',
  '.webm': 'video/webm',
  '.ogv': 'video/ogg',
  '.mp3': 'audio/mpeg',
  '.m4a': 'audio/mp4',
  '.ogg': 'audio/ogg',
  '.oga': 'audio/ogg',
  '.wav': 'audio/wav',
  '.vtt': 'text/vtt; charset=utf-8',
  '.map': 'application/json; charset=utf-8',
}

export function pitchContentType(path: string): string {
  return MEDIA_TYPES[extname(path).toLowerCase()] ?? contentTypeOf(path)
}

/** Dateien (Pfad → Inhalt) als Bundle: Typ und ETag je Datei. Pfade mit `\`, `..` o. Ä. fallen heraus. */
export function buildPitchBundle(files: ReadonlyMap<string, Buffer>): PitchBundle {
  const out = new Map<string, PitchFile>()
  for (const [rawPath, data] of files) {
    const path = rawPath.replace(/^\/+/, '')
    if (!path || path.split('/').some((s) => !SEGMENT.test(s))) continue
    out.set(path, { data, type: pitchContentType(path), etag: `"${createHash('sha256').update(data).digest('hex').slice(0, 32)}"` })
  }
  return out
}

const SEGMENT = /^[A-Za-z0-9_-][A-Za-z0-9._-]{0,127}$/

let loader: (() => Promise<ReadonlyMap<string, Buffer>>) | null = null
let cached: Promise<PitchBundle> | null = null

/** Quelle der Bundle-Dateien setzen (Server: Server-Asset `pitch`; Tests: eigene Dateien). Leert den Zwischenspeicher. */
export function setPitchBundleLoader(fn: (() => Promise<ReadonlyMap<string, Buffer>>) | null): void {
  loader = fn
  cached = null
}

/** Das Bundle (einmal geladen, danach aus dem Speicher). Ohne Quelle oder bei Fehlern leer. */
export function pitchBundle(): Promise<PitchBundle> {
  if (!cached) {
    const fn = loader
    cached = (fn ? fn() : Promise.resolve(new Map<string, Buffer>()))
      .then(buildPitchBundle)
      .catch((err: unknown) => {
        console.error('[trs-pitch] Bundle konnte nicht geladen werden', err)
        cached = null
        return new Map<string, PitchFile>()
      })
  }
  return cached
}

/**
 * Datei zu einem Pfad aus der Adresse (roh, evtl. %-kodiert). Leer oder `index.html` → `index`. Jedes Segment wird
 * einzeln dekodiert und muss ein einfacher Name sein – sonst `null` (404).
 */
export function resolvePitchPath(bundle: PitchBundle, raw: string): { index: boolean, path: string, file: PitchFile } | null {
  const trimmed = raw.split('?')[0]!.replace(/^\/+/, '').replace(/\/+$/, '')
  const segments: string[] = []
  if (trimmed) {
    for (const s of trimmed.split('/')) {
      let d: string
      try {
        d = decodeURIComponent(s)
      } catch {
        return null
      }
      if (!SEGMENT.test(d)) return null
      segments.push(d)
    }
  }
  const path = segments.length ? segments.join('/') : 'index.html'
  const file = bundle.get(path)
  return file ? { index: path === 'index.html', path, file } : null
}

/** `<base href>` direkt nach `<head>` einsetzen (ohne `<head>` ganz vorne). */
export function withBase(html: string, base: string): string {
  const tag = `<base href="${base}">`
  const head = /<head(?:\s[^>]*)?>/i.exec(html)
  if (!head) return `${tag}${html}`
  const at = head.index + head[0].length
  return `${html.slice(0, at)}${tag}${html.slice(at)}`
}

const NOT_FOUND_HTML = `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><meta name="robots" content="noindex, nofollow"><title>404</title>
<style>html,body{margin:0;height:100%;background:#111116;color:#e4e4e7;font-family:system-ui,sans-serif}main{display:grid;place-content:center;height:100%;text-align:center;gap:.25rem}h1{margin:0;font-size:3rem;color:#ef4444}p{margin:0;color:#a1a1aa}a{color:#f4f4f5}</style>
</head><body><main><h1>404</h1><p>Page not found · Seite nicht gefunden</p><p><a href="/">trs-launcher.theredstonee.de</a></p></main></body></html>
`

function notFoundPage(event: H3Event): string {
  setResponseStatus(event, 404)
  setResponseHeaders(event, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' })
  return NOT_FOUND_HTML
}

/** Einfacher `Range: bytes=a-b` (eine Spanne) für Video/Audio; sonst `null` (ganze Datei) bzw. `'invalid'` (416). */
export function parseRange(header: string | undefined, size: number): { start: number, end: number } | null | 'invalid' {
  if (!header) return null
  const m = /^bytes=(\d*)-(\d*)$/.exec(header.trim())
  if (!m || (m[1] === '' && m[2] === '')) return null
  let start: number
  let end: number
  if (m[1] === '') {
    const suffix = Number(m[2])
    if (suffix === 0) return 'invalid'
    start = Math.max(0, size - suffix)
    end = size - 1
  } else {
    start = Number(m[1])
    end = m[2] === '' ? size - 1 : Math.min(Number(m[2]), size - 1)
  }
  if (!Number.isSafeInteger(start) || start >= size || end < start) return 'invalid'
  return { start, end }
}

function sendFile(event: H3Event, file: PitchFile): Buffer | string {
  setResponseHeaders(event, {
    'Content-Type': file.type,
    'Cache-Control': 'private, max-age=3600',
    'ETag': file.etag,
    'Accept-Ranges': 'bytes',
  })
  if (getHeader(event, 'if-none-match') === file.etag) {
    setResponseStatus(event, 304)
    return ''
  }
  const range = parseRange(getHeader(event, 'range'), file.data.length)
  if (range === 'invalid') {
    setResponseStatus(event, 416)
    setResponseHeader(event, 'Content-Range', `bytes */${file.data.length}`)
    return ''
  }
  if (range) {
    setResponseStatus(event, 206)
    setResponseHeader(event, 'Content-Range', `bytes ${range.start}-${range.end}/${file.data.length}`)
    return file.data.subarray(range.start, range.end + 1)
  }
  return file.data
}

function sendIndex(event: H3Event, file: PitchFile, base: string): string {
  setResponseHeaders(event, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' })
  return withBase(file.data.toString('utf8'), base)
}

/** Raten-Limit überschritten: kurze Textseite mit Retry-After statt der Fehlerseite der Website. */
function tooManyPage(event: H3Event, retryAfter: unknown): string {
  setResponseStatus(event, 429)
  setResponseHeaders(event, { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store', 'Retry-After': String(Number(retryAfter) || 60) })
  return 'Too many requests – please wait a moment.'
}

/** `GET /pitch/<token>` und `GET /pitch/<token>/<datei>`. */
export async function servePitchLink(event: H3Event): Promise<Buffer | string> {
  setResponseHeaders(event, PITCH_HEADERS)
  try {
    const token = getRouterParam(event, 'token', { decode: false }) ?? ''
    const ctx = useCtx()
    const link = resolvePitchToken(ctx, token)
    if (!link) return notFoundPage(event)
    const hit = resolvePitchPath(await pitchBundle(), getRouterParam(event, 'path', { decode: false }) ?? '')
    if (!hit) return notFoundPage(event)
    if (hit.index) {
      limit(`pitch:${clientIp(event)}`, RULES.pitchIp)
      recordPitchView(ctx, link.id)
      return sendIndex(event, hit.file, `/pitch/${token}/`)
    }
    return sendFile(event, hit.file)
  } catch (err) {
    if (isApiError(err) && err.status === 429) return tooManyPage(event, err.details?.retryAfter)
    throw err
  }
}

/** `GET /admin/pitch/preview[/<datei>]`: dasselbe Bundle für das Team (Sitzung + `pitch.manage`), ohne Zählung. */
export async function servePitchPreview(event: H3Event): Promise<Buffer | string> {
  setResponseHeaders(event, PITCH_HEADERS)
  try {
    requireStaff(event, 'pitch.manage')
  } catch (err) {
    if (isApiError(err) && err.status === 429) return tooManyPage(event, err.details?.retryAfter)
    return notFoundPage(event)
  }
  const hit = resolvePitchPath(await pitchBundle(), getRouterParam(event, 'path', { decode: false }) ?? '')
  if (!hit) return notFoundPage(event)
  if (hit.index) return sendIndex(event, hit.file, '/admin/pitch/preview/')
  return sendFile(event, hit.file)
}

