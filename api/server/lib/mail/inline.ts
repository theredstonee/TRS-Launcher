import { existsSync, readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import type { AppContext } from '../context'
import { sha256Hex } from '../ids'
import { headPng, renderTitleSpec, verifyHead, verifyTitle } from './images'
import type { MailAttachment, OutgoingMail } from './transport'

/**
 * Bilder der eigenen Website als CID-Anhänge in die Mail legen (`multipart/related`). Viele Mail-Programme
 * (Outlook, Thunderbird, Apple Mail, GMX/Web.de …) laden externe Bilder erst nach einem Klick – eingebettete
 * Bilder zeigen sie sofort. Pixel-Überschriften und Köpfe werden direkt gerendert, die festen Bilder (`/mail/*`)
 * von der Platte gelesen, alles andere von der eigenen Website geholt. Fremde Adressen, zu große oder nicht
 * ladbare Bilder bleiben als normale Adresse stehen.
 */

export const MAX_INLINE_IMAGE = 1024 * 1024
export const MAX_INLINE_TOTAL = 3 * 1024 * 1024

interface Img {
  body: Buffer
  type: string
}

const TYPES: Record<string, string> = { png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif', webp: 'image/webp' }

// Während eines Kampagnen-Versands bekommen alle Empfänger dieselben Bilder: kurz zwischenspeichern.
const cache = new Map<string, { img: Img | null, at: number }>()
const CACHE_MS = 30 * 60_000
const CACHE_MAX = 300

let publicDir: string | null | undefined

/** `.output/public` neben dem Server (Build), sonst `public` im Arbeitsordner (Entwicklung, Tests). */
function findPublicDir(): string | null {
  if (publicDir !== undefined) return publicDir
  const candidates = [
    process.argv[1] ? join(dirname(resolve(process.argv[1])), '..', 'public') : null,
    resolve(process.cwd(), '.output', 'public'),
    resolve(process.cwd(), 'public'),
  ]
  publicDir = candidates.find((c): c is string => !!c && existsSync(join(c, 'mail', 'logo.png'))) ?? null
  return publicDir
}

async function load(ctx: AppContext, url: URL, fetcher: typeof fetch): Promise<Img | null> {
  const path = url.pathname
  let m = /^\/v1\/mail\/px\/([0-9a-f]{24})\.png$/.exec(path)
  if (m) {
    const d = url.searchParams.get('d')
    const spec = d ? verifyTitle(ctx, m[1]!, d) : null
    return spec ? { body: renderTitleSpec(spec).png, type: 'image/png' } : null
  }
  m = /^\/v1\/mail\/head\/([0-9a-f]{32})\.png$/.exec(path)
  if (m) {
    const s = url.searchParams.get('s')
    return s && verifyHead(ctx, m[1]!, s) ? { body: await headPng(ctx, m[1]!), type: 'image/png' } : null
  }
  m = /^\/mail\/([a-z0-9-]+)\.(png|jpg|gif)$/.exec(path)
  const dir = m ? findPublicDir() : null
  if (m && dir) {
    const file = join(dir, 'mail', `${m[1]}.${m[2]}`)
    if (existsSync(file)) return { body: readFileSync(file), type: TYPES[m[2]!]! }
  }
  try {
    const res = await fetcher(url.href, { signal: AbortSignal.timeout(8000), redirect: 'error' })
    if (!res.ok) return null
    const type = (res.headers.get('content-type') ?? '').split(';')[0]!.trim().toLowerCase()
    if (!Object.values(TYPES).includes(type)) return null
    if (Number(res.headers.get('content-length') ?? 0) > MAX_INLINE_IMAGE) return null
    const body = Buffer.from(await res.arrayBuffer())
    return body.length > MAX_INLINE_IMAGE ? null : { body, type }
  } catch {
    return null
  }
}

async function cached(ctx: AppContext, url: URL, fetcher: typeof fetch): Promise<Img | null> {
  const hit = cache.get(url.href)
  if (hit && ctx.now() - hit.at < CACHE_MS) return hit.img
  const img = await load(ctx, url, fetcher)
  if (cache.size >= CACHE_MAX) cache.delete(cache.keys().next().value as string)
  cache.set(url.href, { img, at: ctx.now() })
  return img
}

const unescapeAttr = (s: string) => s.replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>')

/** `fetcher` nur für Tests. */
export async function inlineImages(ctx: AppContext, mail: OutgoingMail, fetcher: typeof fetch = fetch): Promise<OutgoingMail> {
  const origin = new URL(ctx.config.siteUrl).origin
  const byUrl = new Map<string, string | null>()
  const attachments: MailAttachment[] = []
  let total = 0
  const re = /(<img\b[^>]*?\ssrc=")([^"]+)(")/gi
  for (const match of mail.html.matchAll(re)) {
    const raw = unescapeAttr(match[2]!)
    if (byUrl.has(raw)) continue
    byUrl.set(raw, null)
    let url: URL
    try {
      url = new URL(raw)
    } catch {
      continue
    }
    if (url.origin !== origin) continue
    const img = await cached(ctx, url, fetcher)
    if (!img || img.body.length > MAX_INLINE_IMAGE || total + img.body.length > MAX_INLINE_TOTAL) continue
    total += img.body.length
    const cid = `img${attachments.length + 1}.${sha256Hex(img.body).slice(0, 12)}@${url.hostname}`
    const ext = Object.entries(TYPES).find(([, t]) => t === img.type)?.[0] ?? 'png'
    attachments.push({ cid, filename: `image${attachments.length + 1}.${ext}`, content: img.body, contentType: img.type })
    byUrl.set(raw, cid)
  }
  if (attachments.length === 0) return mail
  const html = mail.html.replace(re, (all, a: string, src: string, b: string) => {
    const cid = byUrl.get(unescapeAttr(src))
    return cid ? `${a}cid:${cid}${b}` : all
  })
  return { ...mail, html, attachments: [...(mail.attachments ?? []), ...attachments] }
}
