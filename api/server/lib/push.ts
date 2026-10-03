import { randomBytes } from 'node:crypto'
import { lookup as dnsLookup } from 'node:dns'
import { lookup as dnsLookupAll } from 'node:dns/promises'
import { request as httpsRequest } from 'node:https'
import { isIP, type LookupFunction } from 'node:net'
import type { AppContext } from './context'
import { all, one, run, tx } from './db'
import { badRequest, conflict, notFound, unavailable } from './errors'
import type { ApiEvent, EventTap } from './events'
import { b64uDecode, encryptWebPush, isP256Point, VapidSigner } from './push-crypto'
import { clip, pushLang, pushText, type PushLang } from './push-texts'
import { isPublicIp } from './serverping'

/**
 * Push-Benachrichtigungen für die TRS-Apps (API.md §33).
 *
 * - Android: UnifiedPush. Die App gibt uns die Endpunkt-URL ihres Verteilers (ntfy, NextPush …) und ihre
 *   Web-Push-Schlüssel. Wir verschlüsseln nach RFC 8291 (der Verteiler sieht keinen Inhalt) und melden uns per
 *   VAPID (RFC 8292) an.
 * - iOS (Sideload, ohne APNs): „poll“-Geräte. Benachrichtigungen landen verschlüsselt in `push_pending`; die App
 *   holt sie per Hintergrundabruf (`GET /v1/push/pending`).
 *
 * Quelle sind dieselben Ereignisse wie für `GET /v1/events/me` (EventHub-Abzweig, {@link EventTap}). Ein Gerät ist
 * an die Sitzung gebunden, die es angemeldet hat (Abmelden, Ablauf, Sperre → Gerät weg).
 */

export const PUSH_CATEGORIES = ['chat', 'friends', 'friend_online', 'invites', 'hosting', 'packs', 'team', 'achievements'] as const
export type PushCategory = (typeof PUSH_CATEGORIES)[number]
export type Categories = Record<PushCategory, boolean>

/** Vorgabe für neue Geräte: alles an außer „Freund ist online“ (zu häufig). */
export const DEFAULT_CATEGORIES: Categories = {
  chat: true,
  friends: true,
  friend_online: false,
  invites: true,
  hosting: true,
  packs: true,
  team: true,
  achievements: true,
}

export type Urgency = 'very-low' | 'low' | 'normal' | 'high'
export type PushPlatform = 'android' | 'ios'
export type PushKind = 'unifiedpush' | 'poll'

export const DEVICE_ID = /^d[0-9a-f]{20}$/
export const MAX_ENDPOINT_LENGTH = 2048

/** Nach so vielen Fehlschlägen in Folge (ohne 404/410) wird ein Gerät entfernt. */
export const MAX_FAILURES = 25
const SEND_TIMEOUT_MS = 10_000
const MAX_RETRY_AFTER_MS = 10 * 60_000

export interface DeviceRow {
  id: string
  uuid: string
  session_hash: string
  platform: PushPlatform
  kind: PushKind
  endpoint: string | null
  p256dh: string | null
  auth: string | null
  device_name: string
  app_version: string
  locale: string
  categories: string
  preview: number
  push_while_playing: number
  created_at: number
  updated_at: number
  last_seen_at: number
  last_success_at: number | null
  last_failure_at: number | null
  failures: number
}

export interface PushDeviceView {
  id: string
  platform: PushPlatform
  kind: PushKind
  /** Nur der Hostname des Verteilers (die volle URL ist ein Geheimnis des Geräts). */
  endpointHost: string | null
  deviceName: string
  appVersion: string
  locale: string
  categories: Categories
  preview: boolean
  pushWhilePlaying: boolean
  /** Mit der Sitzung dieser Anfrage angemeldet. */
  current: boolean
  createdAt: string
  updatedAt: string
  lastSeenAt: string
  lastSuccessAt: string | null
  /** Die letzte Zustellung ist fehlgeschlagen. */
  failing: boolean
}

/** Was in der (verschlüsselten) Push-Nachricht steht bzw. `GET /v1/push/pending` liefert. */
export interface PushPayload {
  v: 1
  /** Ereignis-ID wie in `GET /v1/events/me` (`<epoch>.<n>`). */
  id: string
  type: ApiEvent['type']
  category: PushCategory
  title: string
  body: string
  /** App-Route, z. B. `/chat/c…` (§33.5). */
  target: string
  /** Gleicher Wert = gleiche Benachrichtigung ersetzen/gruppieren (z. B. eine Unterhaltung). */
  collapse: string | null
  at: string
}

export interface PushSpec {
  type: ApiEvent['type']
  category: PushCategory
  urgency: Urgency
  ttlSec: number
  target: string
  collapse: string | null
  render: (lang: PushLang, preview: boolean) => { title: string, body: string }
}

const iso = (t: number) => new Date(t).toISOString()
const isoOrNull = (t: number | null) => (t === null ? null : iso(t))
const H = 3600
const DAY = 24 * H

export function parseCategories(raw: string): Categories {
  const out = { ...DEFAULT_CATEGORIES }
  try {
    const v = JSON.parse(raw) as Record<string, unknown>
    for (const c of PUSH_CATEGORIES) if (typeof v[c] === 'boolean') out[c] = v[c]
  } catch {
    // Vorgabe
  }
  return out
}

// ---------------------------------------------------------------- Ereignis → Benachrichtigung

type MessageEvent = Extract<ApiEvent, { type: 'chat_message' }>

function chatPreview(m: MessageEvent['message'], lang: PushLang): string {
  if (m.text && m.text.trim() !== '') return clip(m.text, 120)
  if (m.attachments.length > 0) return pushText('chatPicture', lang)
  if (m.invite) return pushText('chatServerInvite', lang)
  if (m.world) return pushText('chatWorldInvite', lang)
  if (m.waypoint) return pushText('chatWaypoint', lang)
  return pushText('chatNew', lang)
}

function groupName(ctx: AppContext, id: string, blob: Uint8Array | null): string | null {
  if (!blob) return null
  try {
    return ctx.cipher.decryptText(blob, `grp:${id}`)
  } catch {
    return null
  }
}

/**
 * Welche Ereignisse eine Benachrichtigung werden (alles andere: keine). `meOnly` = Abgleich der eigenen Geräte
 * (z. B. `friend_added` nach eigenem Annehmen) – dafür nie.
 */
export function describeEvent(ctx: AppContext, uuid: string, e: ApiEvent, meOnly: boolean): PushSpec | null {
  const name = (s: string) => clip(s, 32)
  switch (e.type) {
    case 'chat_message': {
      const m = e.message
      if (m.kind !== 'text' || m.deleted || m.hidden || !m.sender || m.sender.uuid === uuid) return null
      const member = one<{ muted_until: number | null }>(
        ctx.db, 'SELECT muted_until FROM chat_members WHERE conversation_id = ? AND uuid = ?', e.conversationId, uuid,
      )
      if (!member || (member.muted_until !== null && member.muted_until > ctx.now())) return null
      const conv = one<{ kind: 'dm' | 'group', name: Uint8Array | null }>(ctx.db, 'SELECT kind, name FROM chat_conversations WHERE id = ?', e.conversationId)
      if (!conv) return null
      const group = conv.kind === 'group'
      const sender = name(m.sender.name)
      return {
        type: e.type,
        category: 'chat',
        urgency: 'high',
        ttlSec: DAY,
        target: `/chat/${e.conversationId}`,
        collapse: `chat:${e.conversationId}`,
        render: (lang, preview) => {
          // Ohne Vorschau: nur Absender + „Neue Nachricht“ – kein Inhalt, kein Gruppenname.
          if (!preview) return { title: sender, body: pushText(group ? 'chatNewGroup' : 'chatNew', lang) }
          const g = group ? groupName(ctx, e.conversationId, conv.name) : null
          return { title: g ? `${sender} · ${clip(g, 32)}` : sender, body: chatPreview(m, lang) }
        },
      }
    }
    case 'friend_request':
      return {
        type: e.type, category: 'friends', urgency: 'normal', ttlSec: 3 * DAY, target: '/friends/requests', collapse: `friend:${e.from.uuid}`,
        render: (lang) => ({ title: pushText('friendRequestTitle', lang), body: pushText('friendRequestBody', lang, { name: name(e.from.name) }) }),
      }
    case 'friend_added':
      if (meOnly) return null
      return {
        type: e.type, category: 'friends', urgency: 'normal', ttlSec: 3 * DAY, target: '/friends', collapse: `friend:${e.friend.uuid}`,
        render: (lang) => ({ title: pushText('friendAddedTitle', lang), body: pushText('friendAddedBody', lang, { name: name(e.friend.name) }) }),
      }
    case 'friend_online':
      return {
        type: e.type, category: 'friend_online', urgency: 'low', ttlSec: 300, target: '/friends', collapse: `online:${e.friend.uuid}`,
        render: (lang) => ({
          title: pushText('friendOnlineTitle', lang, { name: name(e.friend.name) }),
          body: e.presence.state === 'in-game' && e.presence.game
            ? pushText('friendOnlinePlaying', lang, { version: clip(e.presence.game.version, 24) })
            : pushText('friendOnlineLauncher', lang),
        }),
      }
    case 'cape_offer':
      return {
        type: e.type, category: 'invites', urgency: 'normal', ttlSec: 3 * DAY, target: '/capes/offers', collapse: `cape:${e.offer.cape.id}`,
        render: (lang) => ({
          title: pushText('capeOfferTitle', lang),
          body: pushText('capeOfferBody', lang, { name: name(e.offer.from.name), cape: clip(e.offer.cape.name, 40) }),
        }),
      }
    case 'hosting_invite':
      return {
        type: e.type, category: 'invites', urgency: 'high', ttlSec: 30 * 60, target: `/worlds/${e.room.id}`, collapse: `world:${e.room.id}`,
        render: (lang) => ({
          title: pushText('worldInviteTitle', lang),
          body: pushText('worldInviteBody', lang, { name: name(e.from.name), world: clip(e.room.name, 40) }),
        }),
      }
    case 'hosting_join_request':
      return {
        type: e.type, category: 'hosting', urgency: 'high', ttlSec: 10 * 60, target: `/worlds/${e.roomId}/requests`, collapse: `join:${e.roomId}:${e.from.uuid}`,
        render: (lang) => ({ title: pushText('joinRequestTitle', lang), body: pushText('joinRequestBody', lang, { name: name(e.from.name) }) }),
      }
    case 'hosting_join_accepted':
      return {
        type: e.type, category: 'hosting', urgency: 'high', ttlSec: 10 * 60, target: `/worlds/${e.room.id}`, collapse: `world:${e.room.id}`,
        render: (lang) => ({ title: pushText('joinAcceptedTitle', lang), body: pushText('joinAcceptedBody', lang, { world: clip(e.room.name, 40) }) }),
      }
    case 'hosting_kicked':
      return {
        type: e.type, category: 'hosting', urgency: 'normal', ttlSec: H, target: '/worlds', collapse: `world:${e.roomId}`,
        render: (lang) => ({ title: pushText('kickedTitle', lang), body: pushText('kickedBody', lang) }),
      }
    case 'pack_shared':
      return {
        type: e.type, category: 'packs', urgency: 'normal', ttlSec: 3 * DAY, target: `/packs/${e.pack.id}`, collapse: `pack:${e.pack.id}`,
        render: (lang) => ({ title: pushText('packTitle', lang), body: pushText('packBody', lang, { name: name(e.from.name), pack: clip(e.pack.name, 40) }) }),
      }
    case 'sanction_added':
      return {
        type: e.type, category: 'team', urgency: 'normal', ttlSec: 3 * DAY, target: `/moderation/sanctions/${e.sanction.id}`, collapse: `sanction:${e.sanction.id}`,
        render: (lang) => ({ title: pushText('moderationTitle', lang), body: pushText(e.sanction.kind === 'warn' ? 'sanctionWarn' : 'sanctionOther', lang) }),
      }
    case 'appeal_decided':
      return {
        type: e.type, category: 'team', urgency: 'normal', ttlSec: 3 * DAY, target: `/moderation/sanctions/${e.sanctionId}`, collapse: `sanction:${e.sanctionId}`,
        render: (lang) => ({ title: pushText('appealTitle', lang), body: pushText('appealBody', lang) }),
      }
    case 'report_update':
      if (e.report.status !== 'resolved') return null
      return {
        type: e.type, category: 'team', urgency: 'low', ttlSec: 3 * DAY, target: '/moderation/reports', collapse: `report:${e.report.id}`,
        render: (lang) => ({ title: pushText('reportTitle', lang), body: pushText('reportBody', lang) }),
      }
    case 'application_updated':
      // Eigene Schritte (abschicken, zurückziehen) nicht – nur Antworten des Teams.
      if (e.application.status === 'new' || e.application.status === 'withdrawn') return null
      return {
        type: e.type, category: 'team', urgency: 'normal', ttlSec: 3 * DAY, target: `/team/applications/${e.application.id}`, collapse: `application:${e.application.id}`,
        render: (lang) => ({ title: pushText('applicationTitle', lang), body: pushText('applicationBody', lang) }),
      }
    case 'circuit_submission_updated': {
      const s = e.submission
      if (s.status === 'pending') return null
      const ok = s.status === 'approved'
      return {
        type: e.type, category: 'team', urgency: 'low', ttlSec: 3 * DAY, target: `/circuits/submissions/${s.id}`, collapse: `circuit:${s.id}`,
        render: (lang) => ({
          title: pushText(ok ? 'circuitAcceptedTitle' : 'circuitRejectedTitle', lang),
          body: pushText(ok ? 'circuitAcceptedBody' : 'circuitRejectedBody', lang, { name: clip(s.name, 40) }),
        }),
      }
    }
    case 'issue_updated':
      return {
        type: e.type, category: 'team', urgency: 'low', ttlSec: 3 * DAY, target: `/issues/${e.issue.number}`, collapse: `issue:${e.issue.number}`,
        render: (lang) => {
          const title = pushText('issueTitle', lang, { number: e.issue.number })
          if (e.change === 'team_comment') return { title, body: pushText('issueComment', lang) }
          if (e.change === 'fixed') {
            return { title, body: e.fixedIn ? pushText('issueFixed', lang, { version: clip(e.fixedIn, 24) }) : pushText('issueFixedNoVersion', lang) }
          }
          if (e.change === 'merged' && e.mergedInto) return { title, body: pushText('issueMerged', lang, { number: e.mergedInto.number }) }
          return { title, body: pushText('issueStatus', lang) }
        },
      }
    case 'achievement_unlocked':
      return {
        type: e.type, category: 'achievements', urgency: 'low', ttlSec: DAY, target: `/achievements/${e.achievement.id}`, collapse: `achievement:${e.achievement.id}`,
        render: (lang) => ({
          title: pushText('achievementTitle', lang),
          body: clip(e.achievement.title?.[lang] ?? e.achievement.title?.en ?? e.achievement.id, 80),
        }),
      }
    default:
      return null
  }
}

// ---------------------------------------------------------------- Doppelte vermeiden

interface Slot {
  open: number
  last: number
}

/**
 * Wer gerade (oder vor < `windowMs`) einen `GET /v1/events/me`-Stream offen hatte: die App selbst
 * (`?pushDevice=<id>`) oder ein Desktop-Client (Launcher, TRS Client – ohne `pushDevice`). Nur RAM.
 */
export class PushActivity {
  private users = new Map<string, { desktop: Slot, devices: Map<string, Slot> }>()
  constructor(
    private readonly now: () => number,
    private readonly windowMs: number,
  ) {}

  /** Stream geöffnet; Rückgabe schließt ihn (mehrfach aufrufbar). */
  open(uuid: string, deviceId: string | null): () => void {
    let u = this.users.get(uuid)
    if (!u) {
      u = { desktop: { open: 0, last: 0 }, devices: new Map() }
      this.users.set(uuid, u)
    }
    let slot = u.desktop
    if (deviceId !== null) {
      slot = u.devices.get(deviceId) ?? { open: 0, last: 0 }
      u.devices.set(deviceId, slot)
    }
    slot.open++
    let done = false
    return () => {
      if (done) return
      done = true
      slot.open = Math.max(0, slot.open - 1)
      slot.last = this.now()
    }
  }

  private live(s: Slot | undefined): boolean {
    return !!s && (s.open > 0 || (s.last > 0 && this.now() - s.last < this.windowMs))
  }

  /** Die App auf diesem Gerät ist offen (zeigt es selbst an). */
  deviceActive(uuid: string, deviceId: string): boolean {
    return this.live(this.users.get(uuid)?.devices.get(deviceId))
  }

  desktopActive(uuid: string): boolean {
    return this.live(this.users.get(uuid)?.desktop)
  }

  sweep(): void {
    for (const [uuid, u] of this.users) {
      for (const [id, s] of u.devices) if (!this.live(s)) u.devices.delete(id)
      if (u.devices.size === 0 && !this.live(u.desktop)) this.users.delete(uuid)
    }
  }
}

export type SkipReason = 'category_off' | 'in_app' | 'playing'

/**
 * Regel je Gerät: Kategorie aus → nichts. App auf dem Gerät offen → nichts (zeigt sie selbst).
 * Spieler spielt gerade am PC (Desktop-Stream aktiv UND Präsenz `in-game`) → nichts, außer das Gerät will
 * es trotzdem (`pushWhilePlaying`). Der Launcher allein (im Hintergrund offen) unterdrückt nichts.
 */
export function skipReason(
  d: Pick<DeviceRow, 'id' | 'categories' | 'push_while_playing'>,
  category: PushCategory,
  state: { inApp: boolean, playing: boolean },
): SkipReason | null {
  if (!parseCategories(d.categories)[category]) return 'category_off'
  if (state.inApp) return 'in_app'
  if (state.playing && d.push_while_playing !== 1) return 'playing'
  return null
}

// ---------------------------------------------------------------- Endpunkt prüfen (SSRF)

const BLOCKED_SUFFIXES = ['.localhost', '.local', '.internal', '.lan', '.home', '.home.arpa', '.intranet', '.corp', '.private', '.test', '.invalid', '.example']

export const endpointInvalid = () => badRequest('endpoint_invalid', 'Endpoint must be a public https URL')
export const endpointNotAllowed = () => badRequest('endpoint_not_allowed', 'Endpoint points to a private or reserved address')

/** Hosts der eigenen API/Website – dorthin schicken wir nie etwas. */
export function ownHosts(ctx: AppContext): Set<string> {
  const out = new Set<string>(ctx.config.apiOnlyHosts)
  for (const u of [ctx.config.publicBaseUrl, ctx.config.siteUrl]) {
    try {
      out.add(new URL(u).hostname.toLowerCase())
    } catch {
      // egal
    }
  }
  return out
}

/** Syntax: nur `https:`, kein Login in der URL, kein Fragment, Port 443 oder ≥ 1024, öffentlicher Host. */
export function checkEndpointSyntax(raw: string, own: ReadonlySet<string>): { url: URL, host: string, ip: boolean } {
  if (raw.length === 0 || raw.length > MAX_ENDPOINT_LENGTH || /[\s\0]/.test(raw)) throw endpointInvalid()
  let u: URL
  try {
    u = new URL(raw)
  } catch {
    throw endpointInvalid()
  }
  if (u.protocol !== 'https:' || u.username !== '' || u.password !== '' || u.hash !== '') throw endpointInvalid()
  if (u.port !== '' && Number(u.port) !== 443 && Number(u.port) < 1024) throw endpointInvalid()
  const host = u.hostname.toLowerCase().replace(/^\[|\]$/g, '').replace(/\.$/, '')
  if (host === '') throw endpointInvalid()
  if (isIP(host)) {
    if (!isPublicIp(host)) throw endpointNotAllowed()
    return { url: u, host, ip: true }
  }
  if (host === 'localhost' || !host.includes('.') || BLOCKED_SUFFIXES.some((s) => host.endsWith(s)) || own.has(host)) throw endpointNotAllowed()
  return { url: u, host, ip: false }
}

export type PushResolve = (host: string) => Promise<string[]>

const defaultResolve: PushResolve = async (host) => (await dnsLookupAll(host, { all: true, verbatim: true })).map((a) => a.address)

/** Syntax + DNS: alle Adressen des Hosts müssen öffentlich sein. Beim Senden prüft {@link guardedLookup} erneut. */
export async function checkEndpoint(ctx: AppContext, raw: string): Promise<string> {
  const { host, ip } = checkEndpointSyntax(raw, ownHosts(ctx))
  if (ip) return host
  let addrs: string[]
  try {
    addrs = await ctx.push.resolve(host)
  } catch {
    throw badRequest('endpoint_unresolvable', 'Endpoint host could not be resolved')
  }
  if (addrs.length === 0) throw badRequest('endpoint_unresolvable', 'Endpoint host could not be resolved')
  if (addrs.some((a) => !isPublicIp(a))) throw endpointNotAllowed()
  return host
}

/** DNS beim Verbinden: verbunden wird nur mit geprüften öffentlichen Adressen (kein DNS-Rebinding). */
export const guardedLookup = ((hostname: string, options: { all?: boolean } | undefined, cb: (...args: unknown[]) => void) => {
  dnsLookup(hostname, { all: true, verbatim: true }, (err, addrs) => {
    if (err) return cb(err)
    if (addrs.length === 0 || addrs.some((a) => !isPublicIp(a.address))) {
      return cb(Object.assign(new Error('Push endpoint resolves to a blocked address'), { code: 'EPUSHBLOCKED' }))
    }
    if (options?.all) return cb(null, addrs)
    cb(null, addrs[0]!.address, addrs[0]!.family)
  })
}) as unknown as LookupFunction

// ---------------------------------------------------------------- Senden

export interface PushRequest {
  url: string
  headers: Record<string, string>
  body: Buffer
  timeoutMs: number
}

export type PushTransport = (req: PushRequest) => Promise<{ status: number, retryAfter?: string | null }>

/** HTTPS-POST mit SSRF-Schutz beim Verbinden, ohne Weiterleitungen, Antwort höchstens 8 KiB (wird verworfen). */
export const httpsTransport: PushTransport = (req) => new Promise((resolve, reject) => {
  const u = new URL(req.url)
  const host = u.hostname.replace(/^\[|\]$/g, '')
  if (isIP(host) && !isPublicIp(host)) return reject(new Error('blocked address'))
  const r = httpsRequest(u, {
    method: 'POST',
    headers: { ...req.headers, 'Content-Length': String(req.body.length) },
    lookup: guardedLookup,
    timeout: req.timeoutMs,
  }, (res) => {
    let n = 0
    res.on('data', (c: Buffer) => {
      n += c.length
      if (n > 8192) res.destroy()
    })
    const done = () => {
      const ra = res.headers['retry-after']
      resolve({ status: res.statusCode ?? 0, retryAfter: typeof ra === 'string' ? ra : null })
    }
    res.on('end', done)
    res.on('close', done)
    res.on('error', done)
  })
  const deadline = setTimeout(() => r.destroy(new Error('timeout')), req.timeoutMs)
  deadline.unref?.()
  r.on('close', () => clearTimeout(deadline))
  r.on('timeout', () => r.destroy(new Error('timeout')))
  r.on('error', reject)
  r.end(req.body)
})

export type SendOutcome = 'ok' | 'gone' | 'retry' | 'fail'

/** 2xx ok; 404/410 = Abo weg (Gerät löschen); Netzfehler/408/429/5xx = später nochmal; sonst Fehlschlag. */
export function classifyPushStatus(status: number | null): SendOutcome {
  if (status === null) return 'retry'
  if (status >= 200 && status < 300) return 'ok'
  if (status === 404 || status === 410) return 'gone'
  if (status === 408 || status === 425 || status === 429 || status >= 500) return 'retry'
  return 'fail'
}

interface Job {
  deviceId: string
  endpoint: string
  plaintext: Buffer
  ttlSec: number
  urgency: Urgency
  attempt: number
}

interface Intake {
  uuid: string
  e: ApiEvent
  id: string
  meOnly: boolean
}

export class PushService implements EventTap {
  /** Austauschbar für Tests. */
  transport: PushTransport = httpsTransport
  resolve: PushResolve = defaultResolve
  retryDelaysMs = [5_000, 30_000, 120_000]
  readonly activity: PushActivity
  readonly signer: VapidSigner | null
  /** Verworfene Sendungen (Warteschlange voll) seit dem Start. */
  dropped = 0
  private intake: Intake[] = []
  private scheduled = false
  private queue: Job[] = []
  private running = 0
  private timers = new Set<ReturnType<typeof setTimeout>>()

  constructor(private readonly ctx: AppContext) {
    const lim = ctx.config.limits
    this.activity = new PushActivity(ctx.now, lim.pushActiveWindowMs)
    this.signer = ctx.config.vapid ? new VapidSigner(ctx.config.vapid, ctx.now) : null
  }

  get enabled(): boolean {
    return this.signer !== null
  }

  /** Hat der Nutzer ein Push-Gerät? (Dann lohnt sich jedes Ereignis, auch ohne offenen Stream.) */
  wants(uuid: string): boolean {
    return one(this.ctx.db, 'SELECT 1 AS x FROM push_devices WHERE uuid = ? LIMIT 1', uuid) !== undefined
  }

  /** Vom EventHub (synchron, evtl. mitten in einer Transaktion): nur vormerken, verarbeitet wird danach. */
  deliver(uuid: string, e: ApiEvent, id: string, opts: { meOnly?: boolean }): void {
    if (this.intake.length >= this.ctx.config.limits.pushQueueMax) {
      this.dropped++
      return
    }
    this.intake.push({ uuid, e, id, meOnly: opts.meOnly === true })
    if (!this.scheduled) {
      this.scheduled = true
      queueMicrotask(() => this.drain())
    }
  }

  private drain(): void {
    this.scheduled = false
    for (const item of this.intake.splice(0)) {
      try {
        this.process(item)
      } catch (err) {
        console.error('[trs-api] push: event failed', err)
      }
    }
  }

  private process({ uuid, e, id, meOnly }: Intake): void {
    const spec = describeEvent(this.ctx, uuid, e, meOnly)
    if (!spec) return
    const devices = all<DeviceRow>(this.ctx.db, 'SELECT * FROM push_devices WHERE uuid = ?', uuid)
    if (devices.length === 0) return
    const t = this.ctx.now()
    const playing = this.activity.desktopActive(uuid) && this.ctx.presence.isInGame(uuid)
    for (const d of devices) {
      if (skipReason(d, spec.category, { inApp: this.activity.deviceActive(uuid, d.id), playing })) continue
      const { title, body } = spec.render(pushLang(d.locale), d.preview === 1)
      const payload: PushPayload = {
        v: 1, id, type: spec.type, category: spec.category, title: clip(title, 80), body: clip(body, 200),
        target: spec.target, collapse: spec.collapse, at: iso(t),
      }
      if (d.kind === 'poll') this.store(d.id, payload, t + Math.min(spec.ttlSec * 1000, this.ctx.config.limits.pushPendingTtlMs))
      else if (d.endpoint && this.signer) {
        this.enqueue({ deviceId: d.id, endpoint: d.endpoint, plaintext: Buffer.from(JSON.stringify(payload)), ttlSec: spec.ttlSec, urgency: spec.urgency, attempt: 0 })
      }
    }
  }

  /** Für Abruf-Geräte: verschlüsselt ablegen (wie Chat-Inhalte), höchstens N je Gerät. */
  private store(deviceId: string, payload: PushPayload, expiresAt: number): void {
    const t = this.ctx.now()
    const blob = this.ctx.cipher.encrypt(JSON.stringify(payload), `push:${deviceId}`)
    tx(this.ctx.db, () => {
      run(this.ctx.db, 'INSERT INTO push_pending (device_id, payload, created_at, expires_at) VALUES (?, ?, ?, ?)', deviceId, blob, t, expiresAt)
      run(
        this.ctx.db,
        `DELETE FROM push_pending WHERE device_id = ? AND id NOT IN (
           SELECT id FROM push_pending WHERE device_id = ? ORDER BY id DESC LIMIT ?)`,
        deviceId, deviceId, this.ctx.config.limits.maxPushPendingPerDevice,
      )
    })
  }

  private enqueue(job: Job): void {
    if (this.queue.length >= this.ctx.config.limits.pushQueueMax) {
      this.dropped++
      return
    }
    this.queue.push(job)
    this.pump()
  }

  private pump(): void {
    while (this.running < this.ctx.config.limits.pushConcurrency && this.queue.length > 0) {
      const job = this.queue.shift()!
      this.running++
      void this.send(job)
        .catch((err: unknown) => console.error('[trs-api] push: send failed', err))
        .finally(() => {
          this.running--
          this.pump()
        })
    }
  }

  private async send(job: Job): Promise<void> {
    const db = this.ctx.db
    const d = one<DeviceRow>(db, 'SELECT * FROM push_devices WHERE id = ?', job.deviceId)
    // Inzwischen gelöscht oder neuer Endpunkt → diese Sendung verfällt.
    if (!d || d.kind !== 'unifiedpush' || d.endpoint !== job.endpoint || !d.p256dh || !d.auth || !this.signer) return
    let status: number | null
    let retryAfter: string | null | undefined
    try {
      const body = encryptWebPush(job.plaintext, b64uDecode(d.p256dh)!, b64uDecode(d.auth)!)
      const r = await this.transport({
        url: d.endpoint,
        headers: {
          'Content-Type': 'application/octet-stream',
          'Content-Encoding': 'aes128gcm',
          TTL: String(job.ttlSec),
          Urgency: job.urgency,
          Authorization: this.signer.header(d.endpoint),
        },
        body,
        timeoutMs: SEND_TIMEOUT_MS,
      })
      status = r.status
      retryAfter = r.retryAfter
    } catch {
      status = null
    }
    const t = this.ctx.now()
    let outcome = classifyPushStatus(status)
    if (outcome === 'retry') {
      if (job.attempt < this.retryDelaysMs.length) {
        let wait = this.retryDelaysMs[job.attempt]!
        if (retryAfter && /^\d{1,6}$/.test(retryAfter)) wait = Math.max(wait, Math.min(Number(retryAfter) * 1000, MAX_RETRY_AFTER_MS))
        this.later({ ...job, attempt: job.attempt + 1 }, wait)
        return
      }
      outcome = 'fail'
    }
    if (outcome === 'ok') run(db, 'UPDATE push_devices SET failures = 0, last_success_at = ? WHERE id = ?', t, d.id)
    else if (outcome === 'gone') run(db, 'DELETE FROM push_devices WHERE id = ?', d.id)
    else {
      run(db, 'UPDATE push_devices SET failures = failures + 1, last_failure_at = ? WHERE id = ?', t, d.id)
      run(db, 'DELETE FROM push_devices WHERE id = ? AND failures >= ?', d.id, MAX_FAILURES)
    }
  }

  private later(job: Job, ms: number): void {
    const timer = setTimeout(() => {
      this.timers.delete(timer)
      this.enqueue(job)
    }, ms)
    timer.unref?.()
    this.timers.add(timer)
  }

  /** Für Tests: wartet, bis alles verarbeitet und gesendet ist (inkl. Wiederholungen). */
  async flush(): Promise<void> {
    for (let i = 0; i < 10_000; i++) {
      if (this.scheduled) this.drain()
      if (this.queue.length === 0 && this.running === 0 && this.timers.size === 0 && this.intake.length === 0) return
      await new Promise((r) => setTimeout(r, 1))
    }
  }

  /** Beim Herunterfahren: geplante Wiederholungen verwerfen. */
  stop(): void {
    for (const t of this.timers) clearTimeout(t)
    this.timers.clear()
    this.queue.length = 0
  }
}

// ---------------------------------------------------------------- Geräte verwalten

export interface DeviceInput {
  platform: PushPlatform
  kind: PushKind
  endpoint?: string
  keys?: { p256dh: string, auth: string }
  deviceName: string
  appVersion: string
  locale: string
  categories?: Partial<Categories>
  preview?: boolean
  pushWhilePlaying?: boolean
}

export interface DevicePatch {
  endpoint?: string
  keys?: { p256dh: string, auth: string }
  deviceName?: string
  appVersion?: string
  locale?: string
  categories?: Partial<Categories>
  preview?: boolean
  pushWhilePlaying?: boolean
}

const newDeviceId = () => `d${randomBytes(10).toString('hex')}`
export const deviceNotFound = () => notFound('device_not_found', 'Push device not found')

/** p256dh = P-256-Punkt (65 Byte, auf der Kurve), auth = 16 Byte – beides base64url. */
export function checkKeys(keys: { p256dh: string, auth: string }): void {
  const p = b64uDecode(keys.p256dh)
  const a = b64uDecode(keys.auth)
  if (!p || !isP256Point(p) || !a || a.length !== 16) throw badRequest('invalid_keys', 'p256dh must be a P-256 public key and auth 16 bytes (base64url)')
}

function hostOf(endpoint: string | null): string | null {
  if (!endpoint) return null
  try {
    return new URL(endpoint).hostname
  } catch {
    return null
  }
}

export function deviceView(d: DeviceRow, tokenHash: string): PushDeviceView {
  return {
    id: d.id,
    platform: d.platform,
    kind: d.kind,
    endpointHost: hostOf(d.endpoint),
    deviceName: d.device_name,
    appVersion: d.app_version,
    locale: d.locale,
    categories: parseCategories(d.categories),
    preview: d.preview === 1,
    pushWhilePlaying: d.push_while_playing === 1,
    current: d.session_hash === tokenHash,
    createdAt: iso(d.created_at),
    updatedAt: iso(d.updated_at),
    lastSeenAt: iso(d.last_seen_at),
    lastSuccessAt: isoOrNull(d.last_success_at),
    failing: d.failures > 0,
  }
}

const getDevice = (ctx: AppContext, id: string) => one<DeviceRow>(ctx.db, 'SELECT * FROM push_devices WHERE id = ?', id)

function ownDevice(ctx: AppContext, uuid: string, id: string): DeviceRow {
  const d = getDevice(ctx, id)
  if (!d || d.uuid !== uuid) throw deviceNotFound()
  return d
}

/**
 * Gerät anmelden. UnifiedPush: gleicher Endpunkt = dasselbe Gerät (aktualisiert, ggf. von einem anderen Konto
 * übernommen – wer den Endpunkt hat, ist das Gerät). `created` = neu angelegt.
 */
export async function registerDevice(
  ctx: AppContext,
  auth: { uuid: string, tokenHash: string },
  input: DeviceInput,
): Promise<{ device: PushDeviceView, created: boolean }> {
  if (input.kind === 'unifiedpush') {
    if (!ctx.push.enabled) throw unavailable('push_unavailable', 'Push notifications are not configured on this server')
    checkKeys(input.keys!)
    await checkEndpoint(ctx, input.endpoint!)
  }
  const t = ctx.now()
  const cats = JSON.stringify({ ...DEFAULT_CATEGORIES, ...input.categories })
  const result = tx(ctx.db, () => {
    const existing = input.kind === 'unifiedpush'
      ? one<DeviceRow>(ctx.db, 'SELECT * FROM push_devices WHERE endpoint = ?', input.endpoint!)
      : undefined
    if (existing && existing.uuid === auth.uuid) {
      const merged = input.categories ? JSON.stringify({ ...parseCategories(existing.categories), ...input.categories }) : existing.categories
      run(
        ctx.db,
        `UPDATE push_devices SET session_hash = ?, platform = ?, p256dh = ?, auth = ?, device_name = ?, app_version = ?, locale = ?,
           categories = ?, preview = ?, push_while_playing = ?, updated_at = ?, last_seen_at = ?, failures = 0 WHERE id = ?`,
        auth.tokenHash, input.platform, input.keys!.p256dh, input.keys!.auth, input.deviceName, input.appVersion, input.locale,
        merged, input.preview === undefined ? existing.preview : input.preview ? 1 : 0,
        input.pushWhilePlaying === undefined ? existing.push_while_playing : input.pushWhilePlaying ? 1 : 0, t, t, existing.id,
      )
      return { id: existing.id, created: false }
    }
    if (existing) run(ctx.db, 'DELETE FROM push_devices WHERE id = ?', existing.id)
    const n = one<{ n: number }>(ctx.db, 'SELECT COUNT(*) AS n FROM push_devices WHERE uuid = ?', auth.uuid)!.n
    if (n >= ctx.config.limits.maxPushDevices) throw conflict('too_many_devices', 'Too many push devices – remove one first')
    const id = newDeviceId()
    run(
      ctx.db,
      `INSERT INTO push_devices (id, uuid, session_hash, platform, kind, endpoint, p256dh, auth, device_name, app_version, locale,
         categories, preview, push_while_playing, created_at, updated_at, last_seen_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      id, auth.uuid, auth.tokenHash, input.platform, input.kind,
      input.kind === 'unifiedpush' ? input.endpoint! : null,
      input.kind === 'unifiedpush' ? input.keys!.p256dh : null,
      input.kind === 'unifiedpush' ? input.keys!.auth : null,
      input.deviceName, input.appVersion, input.locale, cats, input.preview ? 1 : 0, input.pushWhilePlaying ? 1 : 0, t, t, t,
    )
    return { id, created: true }
  })
  return { device: deviceView(getDevice(ctx, result.id)!, auth.tokenHash), created: result.created }
}

export function listDevices(ctx: AppContext, uuid: string, tokenHash: string): PushDeviceView[] {
  return all<DeviceRow>(ctx.db, 'SELECT * FROM push_devices WHERE uuid = ? ORDER BY created_at, id', uuid).map((d) => deviceView(d, tokenHash))
}

export async function updateDevice(ctx: AppContext, auth: { uuid: string, tokenHash: string }, id: string, patch: DevicePatch): Promise<PushDeviceView> {
  const d = ownDevice(ctx, auth.uuid, id)
  if (patch.endpoint !== undefined) {
    if (d.kind !== 'unifiedpush') throw badRequest('not_unifiedpush', 'Only UnifiedPush devices have an endpoint')
    if (!ctx.push.enabled) throw unavailable('push_unavailable', 'Push notifications are not configured on this server')
    checkKeys(patch.keys!)
    await checkEndpoint(ctx, patch.endpoint)
  }
  const t = ctx.now()
  tx(ctx.db, () => {
    const cur = ownDevice(ctx, auth.uuid, id)
    if (patch.endpoint !== undefined && patch.endpoint !== cur.endpoint) {
      // Endpunkt gehört schon einem anderen Gerät → das alte Gerät weicht (wie beim Anmelden).
      const other = one<{ id: string, uuid: string }>(ctx.db, 'SELECT id, uuid FROM push_devices WHERE endpoint = ?', patch.endpoint)
      if (other) run(ctx.db, 'DELETE FROM push_devices WHERE id = ?', other.id)
    }
    const cats = patch.categories ? JSON.stringify({ ...parseCategories(cur.categories), ...patch.categories }) : cur.categories
    run(
      ctx.db,
      `UPDATE push_devices SET endpoint = ?, p256dh = ?, auth = ?, device_name = ?, app_version = ?, locale = ?, categories = ?,
         preview = ?, push_while_playing = ?, updated_at = ?, failures = CASE WHEN ? THEN 0 ELSE failures END WHERE id = ?`,
      patch.endpoint ?? cur.endpoint, patch.keys?.p256dh ?? cur.p256dh, patch.keys?.auth ?? cur.auth,
      patch.deviceName ?? cur.device_name, patch.appVersion ?? cur.app_version, patch.locale ?? cur.locale, cats,
      patch.preview === undefined ? cur.preview : patch.preview ? 1 : 0,
      patch.pushWhilePlaying === undefined ? cur.push_while_playing : patch.pushWhilePlaying ? 1 : 0,
      t, patch.endpoint !== undefined ? 1 : 0, id,
    )
  })
  return deviceView(getDevice(ctx, id)!, auth.tokenHash)
}

export function deleteDevice(ctx: AppContext, uuid: string, id: string): void {
  const n = run(ctx.db, 'DELETE FROM push_devices WHERE id = ? AND uuid = ?', id, uuid)
  if (n === 0) throw deviceNotFound()
}

export interface PendingResult {
  notifications: PushPayload[]
  /** Beim nächsten Abruf als `since` mitschicken (bestätigt alles bis hier – es wird gelöscht). */
  cursor: string
  more: boolean
}

/** Abruf für „poll“-Geräte: alles nach `since`; alles bis einschließlich `since` gilt als abgeholt und wird gelöscht. */
export function pendingFor(ctx: AppContext, uuid: string, deviceId: string, since: number, limit: number): PendingResult {
  const d = ownDevice(ctx, uuid, deviceId)
  if (d.kind !== 'poll') throw badRequest('not_poll_device', 'This device receives push messages; only poll devices fetch them')
  const t = ctx.now()
  const rows = tx(ctx.db, () => {
    if (since > 0) run(ctx.db, 'DELETE FROM push_pending WHERE device_id = ? AND id <= ?', d.id, since)
    run(ctx.db, 'UPDATE push_devices SET last_seen_at = ? WHERE id = ?', t, d.id)
    return all<{ id: number, payload: Uint8Array }>(
      ctx.db,
      'SELECT id, payload FROM push_pending WHERE device_id = ? AND id > ? AND expires_at > ? ORDER BY id LIMIT ?',
      d.id, since, t, limit + 1,
    )
  })
  const more = rows.length > limit
  const page = rows.slice(0, limit)
  const notifications: PushPayload[] = []
  for (const r of page) {
    try {
      notifications.push(JSON.parse(ctx.cipher.decryptText(r.payload, `push:${d.id}`)) as PushPayload)
    } catch {
      // nicht mehr entschlüsselbar (Schlüssel entfernt) → überspringen
    }
  }
  return { notifications, cursor: String(page.length > 0 ? page.at(-1)!.id : since), more }
}

/** Abgelaufene Abruf-Einträge + alte Stream-Vermerke entfernen. */
export function sweepPush(ctx: AppContext): void {
  run(ctx.db, 'DELETE FROM push_pending WHERE expires_at <= ?', ctx.now())
  ctx.push.activity.sweep()
}

/** Für `GET /v1/events/me?pushDevice=…`: gehört das Gerät dem Nutzer? */
export function isOwnDevice(ctx: AppContext, uuid: string, id: string): boolean {
  return getDevice(ctx, id)?.uuid === uuid
}
