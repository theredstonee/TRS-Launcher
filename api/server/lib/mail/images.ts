import { PNG } from 'pngjs'
import type { AppContext } from '../context'
import { all } from '../db'
import { hmacHex, safeEqual } from '../ids'
import { decodeRgba } from '../png'
import { SKIN_URL_RE } from '../skins'
import { renderTitle, type RenderedTitle } from './pixelfont'
import { THEMES, isLook, type Look } from './theme'

/**
 * Bilder in den Mails, vom Server gerendert und unter stabilen, signierten Adressen ausgeliefert:
 * - Pixel-Überschriften `GET /v1/mail/px/<sig>.png?d=<payload>` (Text + Look + Größe im Payload, HMAC mit SECRET_KEY –
 *   kein offener Renderer für beliebigen Text).
 * - Creator-Köpfe `GET /v1/mail/head/<uuid>.png?s=<sig>` (Gesicht + Hut-Ebene aus dem gespeicherten Mojang-Skin).
 * Ergebnisse liegen im Speicher (begrenzt); die Antworten sind ein Jahr cachebar.
 */

export type TitleKind = 'h1' | 'h2' | 'logo'

export interface TitleSpec {
  text: string
  kind: TitleKind
  look: Look
  /** Größte Breite in Bildschirm-Pixeln (sonst je Art). */
  w?: number
}

const KIND_STYLE: Record<TitleKind, { pixel: number, maxWidth: number, lineGap: number }> = {
  h1: { pixel: 3, maxWidth: 536, lineGap: 3 },
  h2: { pixel: 2, maxWidth: 536, lineGap: 3 },
  logo: { pixel: 2, maxWidth: 300, lineGap: 2 },
}

export const MAX_TITLE_CHARS = 140

const cache = new Map<string, RenderedTitle>()
const CACHE_MAX = 600

function remember<T>(map: Map<string, T>, key: string, value: T): T {
  if (map.size >= CACHE_MAX) map.delete(map.keys().next().value as string)
  map.set(key, value)
  return value
}

function payloadOf(spec: TitleSpec): string {
  const w = spec.w && spec.w !== KIND_STYLE[spec.kind].maxWidth ? spec.w : undefined
  return Buffer.from(JSON.stringify({ t: spec.text.slice(0, MAX_TITLE_CHARS), k: spec.kind, l: spec.look, ...(w ? { w } : {}) })).toString('base64url')
}

const sigOf = (ctx: AppContext, scope: string, data: string) => hmacHex(ctx.config.secretKey, `${scope}:${data}`).slice(0, 24)

export function renderTitleSpec(spec: TitleSpec): RenderedTitle {
  const key = JSON.stringify([spec.text, spec.kind, spec.look, spec.w ?? 0])
  const hit = cache.get(key)
  if (hit) return hit
  const base = KIND_STYLE[spec.kind]
  const t = THEMES[spec.look]
  return remember(cache, key, renderTitle(spec.text, {
    pixel: base.pixel,
    maxWidth: Math.max(80, Math.min(base.maxWidth, spec.w ?? base.maxWidth)),
    lineGap: base.lineGap,
    color: t.text,
    accent: t.accent2,
    shadow: t.badge,
  }))
}

/** Adresse + Anzeigegröße einer Pixel-Überschrift. */
export function titleImage(ctx: AppContext, spec: TitleSpec): { url: string, width: number, height: number } {
  const d = payloadOf(spec)
  const r = renderTitleSpec(spec)
  return { url: `${ctx.config.siteUrl}/v1/mail/px/${sigOf(ctx, 'mailpx', d)}.png?d=${d}`, width: r.width, height: r.height }
}

/** Payload einer Überschrift prüfen (Signatur + Inhalt) → Spezifikation oder `null`. */
export function verifyTitle(ctx: AppContext, sig: string, d: string): TitleSpec | null {
  if (!/^[0-9a-f]{24}$/.test(sig) || !/^[A-Za-z0-9_-]{1,1200}$/.test(d)) return null
  if (!safeEqual(sig, sigOf(ctx, 'mailpx', d))) return null
  try {
    const j = JSON.parse(Buffer.from(d, 'base64url').toString('utf8')) as { t?: unknown, k?: unknown, l?: unknown, w?: unknown }
    if (typeof j.t !== 'string' || j.t.length > MAX_TITLE_CHARS) return null
    if (j.k !== 'h1' && j.k !== 'h2' && j.k !== 'logo') return null
    if (!isLook(j.l)) return null
    const w = typeof j.w === 'number' && Number.isInteger(j.w) && j.w >= 80 && j.w <= 600 ? j.w : undefined
    return { text: j.t, kind: j.k, look: j.l, ...(w ? { w } : {}) }
  } catch {
    return null
  }
}

// ---------------------------------------------------------------- Köpfe

export function headUrl(ctx: AppContext, uuid: string): string {
  return `${ctx.config.siteUrl}/v1/mail/head/${uuid}.png?s=${sigOf(ctx, 'mailhead', uuid).slice(0, 16)}`
}

export function verifyHead(ctx: AppContext, uuid: string, sig: string): boolean {
  return /^[0-9a-f]{32}$/.test(uuid) && /^[0-9a-f]{16}$/.test(sig) && safeEqual(sig, sigOf(ctx, 'mailhead', uuid).slice(0, 16))
}

const heads = new Map<string, Buffer>()
const HEAD_SIZE = 16 // Bildpixel je Skin-Pixel → 128 × 128 (@2x für 64 × 64)

/** Standardkopf (Steve-ähnlich), wenn kein Skin bekannt oder Mojang nicht erreichbar ist. */
function defaultFace(): Buffer {
  const skin = '#6a4b35 #6a4b35 #6a4b35 #6a4b35 #6a4b35 #6a4b35 #6a4b35 #6a4b35|#6a4b35 #6a4b35 #6a4b35 #6a4b35 #6a4b35 #6a4b35 #6a4b35 #6a4b35|#6a4b35 #c4977a #c4977a #c4977a #c4977a #c4977a #c4977a #6a4b35|#c4977a #ffffff #3b3bbb #c4977a #c4977a #3b3bbb #ffffff #c4977a|#c4977a #c4977a #c4977a #8a5a44 #8a5a44 #c4977a #c4977a #c4977a|#c4977a #c4977a #8a4b3a #8a4b3a #8a4b3a #8a4b3a #c4977a #c4977a|#c4977a #c4977a #6a3a2a #c4977a #c4977a #6a3a2a #c4977a #c4977a|#c4977a #c4977a #c4977a #c4977a #c4977a #c4977a #c4977a #c4977a'
  const rows = skin.split('|').map((r) => r.split(' '))
  const data = Buffer.alloc(8 * 8 * 4)
  rows.forEach((r, y) => r.forEach((h, x) => {
    const o = (y * 8 + x) * 4
    data[o] = Number.parseInt(h.slice(1, 3), 16)
    data[o + 1] = Number.parseInt(h.slice(3, 5), 16)
    data[o + 2] = Number.parseInt(h.slice(5, 7), 16)
    data[o + 3] = 255
  }))
  return scaleFace(data)
}

function scaleFace(face: Buffer): Buffer {
  const png = new PNG({ width: 8 * HEAD_SIZE, height: 8 * HEAD_SIZE })
  for (let y = 0; y < 8 * HEAD_SIZE; y++) {
    for (let x = 0; x < 8 * HEAD_SIZE; x++) {
      const s = (Math.floor(y / HEAD_SIZE) * 8 + Math.floor(x / HEAD_SIZE)) * 4
      const o = (y * 8 * HEAD_SIZE + x) * 4
      face.copy(png.data, o, s, s + 4)
    }
  }
  return PNG.sync.write(png)
}

/** Gesicht (8×8 ab 8,8) mit Hut-Ebene (ab 40,8) aus einer 64×64- oder 64×32-Skin-Textur. */
export function faceFromSkin(skinPng: Buffer): Buffer {
  const { width, height, data } = decodeRgba(skinPng, 64 * 64)
  if (width !== 64 || (height !== 64 && height !== 32)) throw new Error('unexpected skin size')
  const face = Buffer.alloc(8 * 8 * 4)
  for (let y = 0; y < 8; y++) {
    for (let x = 0; x < 8; x++) {
      const b = ((8 + y) * 64 + 8 + x) * 4
      const h = ((8 + y) * 64 + 40 + x) * 4
      const o = (y * 8 + x) * 4
      const a = data[h + 3]! / 255
      for (let c = 0; c < 3; c++) face[o + c] = Math.round(data[b + c]! * (1 - a) + data[h + c]! * a)
      face[o + 3] = 255
    }
  }
  return scaleFace(face)
}

/** Kopf-PNG eines Kontos (gespeicherter Skin, sonst Standardkopf). `fetcher` nur für Tests. */
export async function headPng(ctx: AppContext, uuid: string, fetcher: typeof fetch = fetch): Promise<Buffer> {
  const row = all<{ skin_url: string | null }>(ctx.db, 'SELECT skin_url FROM users WHERE uuid = ?', uuid)[0]
  const url = row?.skin_url && SKIN_URL_RE.test(row.skin_url) ? row.skin_url : null
  const key = `${uuid}:${url ?? ''}`
  const hit = heads.get(key)
  if (hit) return hit
  if (!url) return remember(heads, key, defaultFace())
  try {
    const res = await fetcher(url, { signal: AbortSignal.timeout(8000), redirect: 'error' })
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    const buf = Buffer.from(await res.arrayBuffer())
    if (buf.length > 128 * 1024) throw new Error('skin too large')
    return remember(heads, key, faceFromSkin(buf))
  } catch {
    // Nicht zwischenspeichern: beim nächsten Abruf erneut versuchen.
    return defaultFace()
  }
}
