import { isIP } from 'node:net'
import { getCookie, getHeader, getQuery, getRouterParam, setResponseHeaders, setResponseStatus, type H3Event } from 'h3'
import type { z } from 'zod'
import { authenticate, authenticateAdmin, type AuthedUser } from './auth'
import { useCtx } from './context'
import { ApiError, badRequest, tooLarge, tooMany, unsupportedMedia } from './errors'
import { RULES, type Rule } from './ratelimit'
import { WEB_SESSION_COOKIE, webSession } from './weblogin'

export const JSON_LIMIT = 16 * 1024

/** Client-IP. Hinter dem Tunnel setzt Cloudflare `CF-Connecting-IP`; die API ist nur über cloudflared erreichbar. */
export function clientIp(event: H3Event): string {
  const ctx = useCtx()
  if (ctx.config.trustProxy === 'cloudflare') {
    const cf = getHeader(event, 'cf-connecting-ip')?.trim()
    if (cf && isIP(cf)) return cf
  }
  return event.node.req.socket.remoteAddress ?? 'unknown'
}

export function limit(key: string, rule: Rule): void {
  const r = useCtx().limiter.take(key, rule)
  if (!r.ok) throw tooMany(r.retryAfter)
}

/** Liest den Body mit harter Obergrenze (auch bei chunked Transfer ohne Content-Length). */
export async function readLimited(event: H3Event, max: number): Promise<Buffer> {
  const declared = Number(getHeader(event, 'content-length') ?? NaN)
  if (Number.isFinite(declared) && declared > max) throw tooLarge()
  const req = event.node.req
  const chunks: Buffer[] = []
  let size = 0
  for await (const chunk of req as AsyncIterable<Buffer>) {
    size += chunk.length
    if (size > max) {
      req.destroy()
      throw tooLarge()
    }
    chunks.push(chunk)
  }
  return Buffer.concat(chunks, size)
}

function mediaType(event: H3Event): string {
  return (getHeader(event, 'content-type') ?? '').split(';')[0]!.trim().toLowerCase()
}

function zodDetails(err: z.ZodError): Record<string, unknown> {
  return {
    fields: err.issues.slice(0, 20).map((i) => ({ path: i.path.join('.') || '(body)', message: i.message })),
  }
}

export function parseWith<S extends z.ZodType>(schema: S, value: unknown): z.output<S> {
  const r = schema.safeParse(value)
  if (!r.success) throw badRequest('invalid_request', 'Request validation failed', zodDetails(r.error))
  return r.data
}

/**
 * JSON-Body lesen + validieren. Leerer Body ist nur erlaubt, wenn das Schema `undefined` akzeptiert.
 * `max` nur für die wenigen Routen mit größeren Bodies (Sync) anheben.
 */
export async function readJson<S extends z.ZodType>(event: H3Event, schema: S, max = JSON_LIMIT): Promise<z.output<S>> {
  const raw = await readLimited(event, max)
  if (raw.length === 0) return parseWith(schema, undefined)
  if (mediaType(event) !== 'application/json') throw unsupportedMedia('Content-Type must be application/json')
  let json: unknown
  try {
    json = JSON.parse(raw.toString('utf8'))
  } catch {
    throw badRequest('invalid_json', 'Request body is not valid JSON')
  }
  return parseWith(schema, json)
}

export async function readPng(event: H3Event, max: number): Promise<Buffer> {
  if (mediaType(event) !== 'image/png') throw unsupportedMedia('Content-Type must be image/png')
  const body = await readLimited(event, max)
  if (body.length === 0) throw badRequest('invalid_png', 'Request body is empty')
  return body
}

export function queryWith<S extends z.ZodType>(event: H3Event, schema: S): z.output<S> {
  return parseWith(schema, { ...getQuery<Record<string, unknown>>(event) })
}

export function paramWith<S extends z.ZodType>(event: H3Event, name: string, schema: S): z.output<S> {
  const v = getRouterParam(event, name, { decode: true })
  const r = schema.safeParse(v)
  if (!r.success) throw new ApiError(404, 'not_found', 'Not found')
  return r.data
}

const USER_RULES = { read: RULES.readUser, write: RULES.writeUser, sync: RULES.syncUser, hosting: RULES.hostingUser } satisfies Record<string, Rule>

/**
 * Angemeldeter Nutzer + Grund-Limit je Konto (lesend/schreibend). `sync` = eigener Topf für
 * `/v1/me/sync*`, damit ein großer Abgleich die übrigen Schreibzugriffe nicht aufbraucht; `hosting` = eigener
 * Topf für `/v1/hosting/*` (Herzschläge und Signale).
 */
export function requireUser(event: H3Event, kind: keyof typeof USER_RULES = 'read'): AuthedUser {
  const ctx = useCtx()
  const auth = authenticate(ctx, getHeader(event, 'authorization'))
  limit(`${kind}:${auth.uuid}`, USER_RULES[kind])
  event.context.uuid = auth.uuid
  return auth
}

/**
 * Optional angemeldet (z. B. für eigene, noch nicht freigegebene Umhang-Texturen).
 * Ein ungültiger/abgelaufener Token macht die Anfrage nur anonym – öffentliche
 * Texturen bleiben so immer ladbar.
 */
export function optionalUser(event: H3Event): AuthedUser | null {
  const header = getHeader(event, 'authorization')
  if (!header) return null
  try {
    return authenticate(useCtx(), header)
  } catch {
    return null
  }
}

/**
 * Wer schaut eine Textur an? Bearer-Nutzer wie bei `optionalUser`, sonst ein Website-Admin mit
 * Sitzungs-Cookie (nur lesend, damit die Admin-Seite wartende Uploads zeigen kann).
 */
export function textureViewer(event: H3Event): { uuid: string, admin: boolean } | null {
  const user = optionalUser(event)
  if (user) return { uuid: user.uuid, admin: user.admin }
  const cookie = getCookie(event, WEB_SESSION_COOKIE)
  if (!cookie || getHeader(event, 'authorization') !== undefined) return null
  try {
    const session = webSession(useCtx(), cookie, undefined, false)
    return { uuid: session.uuid, admin: true }
  } catch {
    return null
  }
}

export function requireAdmin(event: H3Event): string {
  const ctx = useCtx()
  const authorization = getHeader(event, 'authorization')
  const adminKey = getHeader(event, 'x-admin-key')
  // Website-Admin: Sitzung aus dem Cookie (nur ohne Bearer/Schlüssel), ändernde Anfragen mit CSRF-Token.
  if (authorization === undefined && adminKey === undefined && getCookie(event, WEB_SESSION_COOKIE) !== undefined) {
    const mutating = !['GET', 'HEAD'].includes(event.method)
    const session = webSession(ctx, getCookie(event, WEB_SESSION_COOKIE), getHeader(event, 'x-csrf-token'), mutating)
    limit(`admin:${session.uuid}`, RULES.adminActor)
    return session.uuid
  }
  const actor = authenticateAdmin(ctx, {
    authorization: getHeader(event, 'authorization'),
    adminKey: getHeader(event, 'x-admin-key'),
  })
  limit(`admin:${actor}`, RULES.adminActor)
  return actor
}

/**
 * PNG ausliefern mit ETag/304. Öffentliche Texturen sind lange cachebar, wenn
 * `?v=` zum Inhalt passt; private (wartende/abgelehnte) nie.
 */
export function sendPng(event: H3Event, filename: string, tex: { png: Buffer, sha256: string, public: boolean }): Buffer | string {
  const etag = `"${tex.sha256}"`
  const v = getQuery(event).v
  const current = typeof v !== 'string' || tex.sha256.startsWith(v)
  setResponseHeaders(event, {
    'Content-Type': 'image/png',
    'Content-Disposition': `inline; filename="${filename}"`,
    'Cross-Origin-Resource-Policy': 'cross-origin',
    ETag: etag,
    'Cache-Control': !tex.public ? 'private, no-store' : current ? 'public, max-age=31536000, immutable' : 'public, max-age=300',
  })
  if (getHeader(event, 'if-none-match') === etag) {
    setResponseStatus(event, 304)
    return ''
  }
  return tex.png
}

export function noContent(event: H3Event): null {
  setResponseStatus(event, 204)
  return null
}

export function created<T>(event: H3Event, body: T): T {
  setResponseStatus(event, 201)
  return body
}
