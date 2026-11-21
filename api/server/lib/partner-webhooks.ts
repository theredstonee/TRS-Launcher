import { createHmac, randomBytes } from 'node:crypto'
import { request as httpsRequest } from 'node:https'
import { isIP } from 'node:net'
import { z } from 'zod'
import type { AppContext } from './context'
import { all, one, run } from './db'
import { badRequest, notFound } from './errors'
import { defaultPingDeps, isPublicIp, type PingDeps } from './serverping'

/**
 * Ausgehende Partner-Webhooks (§44.6): signierte POSTs an eine https-Adresse des Partners, Zustellungen in der DB
 * (überleben Neustarts), Wiederholungen nach festem Plan, danach `failed` mit Knopf „Erneut senden“.
 * SSRF-Schutz: Adresse wird vor JEDEM Versuch aufgelöst; nur öffentliche Adressen, verbunden wird mit genau der geprüften.
 */

const MIN = 60_000
const HOUR = 60 * MIN
export const WEBHOOK_EVENTS = ['code.redeemed', 'partner.supported', 'partner.left', 'quota.low', 'test.ping'] as const
export type WebhookEvent = (typeof WEBHOOK_EVENTS)[number]
/** Pause nach dem 1., 2., … Fehlversuch. Nach dem letzten Eintrag: `failed`. */
export const RETRY_DELAYS_MS = [MIN, 5 * MIN, 30 * MIN, 2 * HOUR, 6 * HOUR, 24 * HOUR] as const
export const DELIVERY_LOG_SIZE = 50
export const WEBHOOK_TIMEOUT_MS = 10_000
const RESPONSE_MAX = 200

export interface WebhookResponse {
  status: number
  body: string
}

/** Schickt einen Body an `url`; wirft bei Netz-/SSRF-Fehlern (`code` = Grund). */
export type WebhookSender = (url: string, headers: Record<string, string>, body: string) => Promise<WebhookResponse>

interface WebhookRow {
  partner_id: number
  url: string
  events: string
  secret: string
  updated_at: number
}

interface DeliveryRow {
  id: number
  partner_id: number
  event: string
  body: string
  status: 'pending' | 'delivered' | 'failed'
  attempts: number
  next_at: number | null
  last_status: number | null
  last_error: string | null
  response: string | null
  created_at: number
  updated_at: number
}

export interface DeliveryView {
  id: number
  event: string
  status: 'pending' | 'delivered' | 'failed'
  attempts: number
  lastStatus: number | null
  error: string | null
  response: string | null
  nextAt: string | null
  createdAt: string
  updatedAt: string
}

export interface WebhookView {
  url: string
  events: WebhookEvent[]
  /** Nur die letzten 4 Zeichen. */
  secretHint: string
  updatedAt: string
}

const iso = (t: number) => new Date(t).toISOString()

export function signWebhook(secret: string, timestamp: number, body: string): string {
  return `sha256=${createHmac('sha256', secret).update(`${timestamp}.${body}`).digest('hex')}`
}

export function newWebhookSecret(): string {
  return `whsec_${randomBytes(24).toString('base64url')}`
}

function parseEvents(raw: string): WebhookEvent[] {
  try {
    const v = JSON.parse(raw) as unknown
    return Array.isArray(v) ? WEBHOOK_EVENTS.filter((e) => v.includes(e)) : []
  } catch {
    return []
  }
}

function webhookRow(ctx: AppContext, partnerId: number): WebhookRow | undefined {
  return one<WebhookRow>(ctx.db, 'SELECT * FROM partner_webhooks WHERE partner_id = ?', partnerId)
}

export function webhookView(ctx: AppContext, partnerId: number): WebhookView | null {
  const w = webhookRow(ctx, partnerId)
  if (!w) return null
  return { url: w.url, events: parseEvents(w.events), secretHint: w.secret.slice(-4), updatedAt: iso(w.updated_at) }
}

function deliveryView(d: DeliveryRow): DeliveryView {
  return {
    id: d.id,
    event: d.event,
    status: d.status,
    attempts: d.attempts,
    lastStatus: d.last_status,
    error: d.last_error,
    response: d.response,
    nextAt: d.next_at === null || d.status !== 'pending' ? null : iso(d.next_at),
    createdAt: iso(d.created_at),
    updatedAt: iso(d.updated_at),
  }
}

export function deliveriesOf(ctx: AppContext, partnerId: number): DeliveryView[] {
  return all<DeliveryRow>(ctx.db, 'SELECT * FROM partner_deliveries WHERE partner_id = ? ORDER BY id DESC LIMIT ?', partnerId, DELIVERY_LOG_SIZE).map(deliveryView)
}

// ---------------------------------------------------------------- Adresse prüfen (SSRF)

export const webhookUrl = z
  .string()
  .trim()
  .max(500)
  .refine((s) => {
    try {
      const u = new URL(s)
      return u.protocol === 'https:' && !u.username && !u.password && !!u.hostname && !u.hash
    } catch {
      return false
    }
  }, 'must be an https URL without credentials')

function hostOf(url: string): { host: string, port: number } {
  const u = new URL(url)
  return { host: u.hostname.replace(/^\[|\]$/g, '').toLowerCase(), port: u.port ? Number(u.port) : 443 }
}

/** Alle Adressen öffentlich? Sonst Fehler mit `code`. Liefert die erste (damit wird verbunden). */
export async function resolvePublic(host: string, deps: Pick<PingDeps, 'lookup' | 'allowPrivate'>): Promise<string> {
  if (host === 'localhost' || host.endsWith('.localhost') || host.endsWith('.local') || host.endsWith('.internal')) {
    throw Object.assign(new Error('private address'), { code: 'private_address' })
  }
  let ips: string[]
  if (isIP(host) !== 0) ips = [host]
  else {
    try {
      ips = await deps.lookup(host)
    } catch {
      throw Object.assign(new Error('unresolvable'), { code: 'unresolvable' })
    }
  }
  if (ips.length === 0) throw Object.assign(new Error('unresolvable'), { code: 'unresolvable' })
  if (!deps.allowPrivate && ips.some((ip) => !isPublicIp(ip))) throw Object.assign(new Error('private address'), { code: 'private_address' })
  return ips[0]!
}

/** Webhook-Adresse beim Speichern prüfen (Form + DNS + öffentliche Adresse). */
export async function checkWebhookUrl(ctx: AppContext, url: string): Promise<void> {
  const { host } = hostOf(url)
  try {
    await resolvePublic(host, ctx.partnerPingDeps ?? defaultPingDeps)
  } catch (err) {
    const code = (err as { code?: string }).code ?? 'unresolvable'
    throw badRequest('invalid_webhook_url', code === 'private_address' ? 'The address resolves to a private network' : 'The host name cannot be resolved', { reason: code })
  }
}

/** Standard-Versand: https mit fest aufgelöster, geprüfter IP, ohne Weiterleitungen, 10 s Zeitlimit. */
export function defaultSender(deps: Pick<PingDeps, 'lookup' | 'allowPrivate'> = defaultPingDeps): WebhookSender {
  return async (url, headers, body) => {
    const u = new URL(url)
    if (u.protocol !== 'https:') throw Object.assign(new Error('https only'), { code: 'invalid_url' })
    const { host, port } = hostOf(url)
    const ip = await resolvePublic(host, deps)
    const family = isIP(ip)
    return await new Promise<WebhookResponse>((resolve, reject) => {
      const req = httpsRequest({
        host,
        port,
        path: `${u.pathname}${u.search}`,
        method: 'POST',
        headers: { ...headers, 'Content-Length': String(Buffer.byteLength(body)) },
        agent: false,
        servername: isIP(host) === 0 ? host : undefined,
        lookup: ((_h: string, opts: { all?: boolean }, cb: (...a: unknown[]) => void) => {
          if (opts?.all) cb(null, [{ address: ip, family }])
          else cb(null, ip, family)
        }) as never,
      }, (res) => {
        let text = ''
        res.setEncoding('utf8')
        res.on('data', (chunk: string) => {
          if (text.length < RESPONSE_MAX) text += chunk
          if (text.length >= RESPONSE_MAX) res.destroy()
        })
        const done = () => resolve({ status: res.statusCode ?? 0, body: text.slice(0, RESPONSE_MAX) })
        res.on('end', done)
        res.on('close', done)
        res.on('error', done)
      })
      req.setTimeout(WEBHOOK_TIMEOUT_MS, () => req.destroy(Object.assign(new Error('timeout'), { code: 'timeout' })))
      req.on('error', (err) => reject(err))
      req.end(body)
    })
  }
}

// ---------------------------------------------------------------- Konfiguration

export const webhookBody = z.strictObject({
  url: webhookUrl,
  events: z.array(z.enum(WEBHOOK_EVENTS)).max(WEBHOOK_EVENTS.length),
})

/** Speichern; ohne bisherige Konfiguration wird ein Geheimnis erzeugt und EINMAL zurückgegeben. */
export async function saveWebhook(ctx: AppContext, partnerId: number, input: z.output<typeof webhookBody>): Promise<{ webhook: WebhookView, secret?: string }> {
  await checkWebhookUrl(ctx, input.url)
  const events = JSON.stringify(WEBHOOK_EVENTS.filter((e) => e !== 'test.ping' && input.events.includes(e)))
  const t = ctx.now()
  const old = webhookRow(ctx, partnerId)
  let secret: string | undefined
  if (old) run(ctx.db, 'UPDATE partner_webhooks SET url = ?, events = ?, updated_at = ? WHERE partner_id = ?', input.url, events, t, partnerId)
  else {
    secret = newWebhookSecret()
    run(ctx.db, 'INSERT INTO partner_webhooks (partner_id, url, events, secret, updated_at) VALUES (?, ?, ?, ?, ?)', partnerId, input.url, events, secret, t)
  }
  return { webhook: webhookView(ctx, partnerId)!, ...(secret ? { secret } : {}) }
}

export function rotateWebhookSecret(ctx: AppContext, partnerId: number): { webhook: WebhookView, secret: string } {
  if (!webhookRow(ctx, partnerId)) throw notFound('webhook_not_found', 'Set up the webhook first')
  const secret = newWebhookSecret()
  run(ctx.db, 'UPDATE partner_webhooks SET secret = ?, updated_at = ? WHERE partner_id = ?', secret, ctx.now(), partnerId)
  return { webhook: webhookView(ctx, partnerId)!, secret }
}

export function deleteWebhook(ctx: AppContext, partnerId: number): void {
  run(ctx.db, 'DELETE FROM partner_webhooks WHERE partner_id = ?', partnerId)
  run(ctx.db, "UPDATE partner_deliveries SET status = 'failed', next_at = NULL, last_error = 'webhook_removed', updated_at = ? WHERE partner_id = ? AND status = 'pending'", ctx.now(), partnerId)
}

// ---------------------------------------------------------------- Zustellen

function prune(ctx: AppContext, partnerId: number): void {
  run(
    ctx.db,
    `DELETE FROM partner_deliveries WHERE partner_id = ? AND status <> 'pending' AND id NOT IN (
       SELECT id FROM partner_deliveries WHERE partner_id = ? ORDER BY id DESC LIMIT ?)`,
    partnerId, partnerId, DELIVERY_LOG_SIZE,
  )
}

/**
 * Ereignis einreihen (nur wenn ein Webhook eingerichtet und das Ereignis gewählt ist; `test.ping` immer). Nie mit
 * Spielernamen oder UUIDs. Zugestellt wird vom Hintergrund-Takt (`processDeliveries`). Liefert die Zustell-ID.
 */
export function queueWebhook(ctx: AppContext, partnerId: number, type: WebhookEvent, payload: Record<string, unknown> = {}): number | null {
  const w = webhookRow(ctx, partnerId)
  if (!w) return null
  if (type !== 'test.ping' && !parseEvents(w.events).includes(type)) return null
  const t = ctx.now()
  const body = JSON.stringify({ type, at: iso(t), ...payload })
  const id = one<{ id: number }>(
    ctx.db,
    "INSERT INTO partner_deliveries (partner_id, event, body, status, attempts, next_at, created_at, updated_at) VALUES (?, ?, ?, 'pending', 0, ?, ?, ?) RETURNING id",
    partnerId, type, body, t, t, t,
  )!.id
  prune(ctx, partnerId)
  return id
}

const inFlight = new WeakMap<AppContext, Set<number>>()

/** Ein Versuch für eine Zustellung (egal ob fällig). Liefert die neue Sicht. */
export async function deliverOne(ctx: AppContext, id: number): Promise<DeliveryView> {
  const busy = inFlight.get(ctx) ?? new Set<number>()
  inFlight.set(ctx, busy)
  const d = one<DeliveryRow & { url: string | null, secret: string | null }>(
    ctx.db,
    'SELECT d.*, w.url, w.secret FROM partner_deliveries d LEFT JOIN partner_webhooks w ON w.partner_id = d.partner_id WHERE d.id = ?',
    id,
  )
  if (!d) throw notFound('delivery_not_found', 'Delivery not found')
  if (busy.has(id)) return deliveryView(d)
  busy.add(id)
  try {
    let status: number | null = null
    let error: string | null = null
    let response: string | null = null
    if (!d.url || !d.secret) error = 'webhook_removed'
    else {
      const ts = Math.floor(ctx.now() / 1000)
      const headers = {
        'Content-Type': 'application/json',
        'User-Agent': 'TRS-Webhooks/1',
        'X-TRS-Timestamp': String(ts),
        'X-TRS-Signature': signWebhook(d.secret, ts, d.body),
      }
      try {
        const send = ctx.partnerWebhookSend ?? defaultSender(ctx.partnerPingDeps ?? defaultPingDeps)
        const r = await send(d.url, headers, d.body)
        status = r.status
        response = r.body.slice(0, RESPONSE_MAX) || null
        if (status < 200 || status >= 300) error = `http_${status}`
      } catch (err) {
        const code = (err as { code?: string }).code
        error = code === 'private_address' || code === 'unresolvable' || code === 'timeout' ? code : code === 'ETIMEDOUT' ? 'timeout' : 'connection_failed'
      }
    }
    const t = ctx.now()
    const attempts = d.attempts + 1
    if (error === null) {
      run(ctx.db, "UPDATE partner_deliveries SET status = 'delivered', attempts = ?, next_at = NULL, last_status = ?, last_error = NULL, response = ?, updated_at = ? WHERE id = ?", attempts, status, response, t, id)
    } else {
      // Manuell neu gesendet (schon über dem Plan): kein weiterer automatischer Versuch.
      const wait = !d.url || d.attempts > RETRY_DELAYS_MS.length ? undefined : RETRY_DELAYS_MS[attempts - 1]
      if (wait === undefined) {
        run(ctx.db, "UPDATE partner_deliveries SET status = 'failed', attempts = ?, next_at = NULL, last_status = ?, last_error = ?, response = ?, updated_at = ? WHERE id = ?", attempts, status, error, response, t, id)
      } else {
        run(ctx.db, "UPDATE partner_deliveries SET status = 'pending', attempts = ?, next_at = ?, last_status = ?, last_error = ?, response = ?, updated_at = ? WHERE id = ?", attempts, t + wait, status, error, response, t, id)
      }
    }
    return deliveryView(one<DeliveryRow>(ctx.db, 'SELECT * FROM partner_deliveries WHERE id = ?', id)!)
  } finally {
    busy.delete(id)
  }
}

/** Fällige Zustellungen abarbeiten (Hintergrund-Takt). Höchstens `max` je Durchgang, 4 gleichzeitig. */
export async function processDeliveries(ctx: AppContext, max = 40): Promise<number> {
  const due = all<{ id: number }>(ctx.db, "SELECT id FROM partner_deliveries WHERE status = 'pending' AND next_at <= ? ORDER BY next_at, id LIMIT ?", ctx.now(), max)
  for (let i = 0; i < due.length; i += 4) {
    await Promise.all(due.slice(i, i + 4).map((d) => deliverOne(ctx, d.id).catch((err) => console.error('[trs-api] partner webhook failed', err))))
  }
  return due.length
}

/** „Erneut senden“ für eine fehlgeschlagene Zustellung: sofort ein Versuch (scheitert er, bleibt sie `failed`). */
export async function retryDelivery(ctx: AppContext, partnerId: number, id: number): Promise<DeliveryView> {
  const d = one<DeliveryRow>(ctx.db, 'SELECT * FROM partner_deliveries WHERE id = ? AND partner_id = ?', id, partnerId)
  if (!d) throw notFound('delivery_not_found', 'Delivery not found')
  if (d.status !== 'failed') throw badRequest('delivery_not_failed', 'Only failed deliveries can be sent again')
  run(ctx.db, "UPDATE partner_deliveries SET status = 'pending', next_at = ?, updated_at = ? WHERE id = ?", ctx.now(), ctx.now(), id)
  return deliverOne(ctx, id)
}

/** „Test senden“: `test.ping` einreihen und sofort zustellen. */
export async function sendTestWebhook(ctx: AppContext, partnerId: number): Promise<DeliveryView> {
  const id = queueWebhook(ctx, partnerId, 'test.ping')
  if (id === null) throw notFound('webhook_not_found', 'Set up the webhook first')
  return deliverOne(ctx, id)
}
