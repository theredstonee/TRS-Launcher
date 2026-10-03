import { createHash, createHmac, randomBytes, randomInt } from 'node:crypto'
import type { AppContext } from './context'
import { all, one, run, tx } from './db'
import { ApiError, badRequest, conflict, forbidden, notFound } from './errors'
import { safeEqual, sha256Hex } from './ids'

/**
 * PC-Fernbedienung (API.md §34): Das Handy steuert den Launcher auf dem PC – beide mit DEMSELBEN TRS-Konto.
 *
 * - **Geräte:** Desktop und Handy melden sich einmal an (`registerDevice`) und bekommen eine Geräte-ID + ein
 *   Geheimnis. Jede Fernbedienungs-Anfrage trägt Bearer-Token UND `X-TRS-Device: <id>.<geheimnis>`; das Gerät muss
 *   zum Konto des Tokens gehören. Gespeichert wird nur SHA-256 des Geheimnisses.
 * - **Koppeln:** Der Desktop erzeugt einen Code (2 min, einmalig, als QR `trs-launcher://remote-pair/<code>`), das
 *   Handy bestätigt ihn. Fremde Konten können nicht koppeln (`remote_wrong_account`); Fehlversuche zählen streng.
 * - **Befehle:** Nur gekoppelte Handys, nur bekannte Arten mit geprüften Argumenten, Idempotenz-Schlüssel je Handy,
 *   60 s gültig. Zustellung über `/v1/events/me` als `remote_command` mit HMAC-Signatur (Schlüssel = SHA-256 des
 *   Desktop-Geheimnisses) – nur der Ziel-Desktop kann sie prüfen. Vor dem Ausführen holt der Desktop den Befehl ab
 *   (`claim`): genau einmal und nur vor dem Ablauf – der Server entscheidet, nicht die Uhr des PCs.
 * - **Status:** Der Desktop meldet seine Instanzen (Name, Version, Symbol-Hash, läuft?) und die laufende Aufgabe; das
 *   Handy bekommt `remote_status`. Ohne Meldung seit {@link REMOTE_ONLINE_MS} gilt der PC als offline.
 */

export const REMOTE_PAIR_TTL_MS = 2 * 60_000
export const REMOTE_COMMAND_TTL_MS = 60_000
/** Ergebnis eines abgeholten Befehls darf so lange danach noch kommen (Modpack-Installation dauert). */
export const REMOTE_RESULT_WINDOW_MS = 2 * 60 * 60_000
/** Der Desktop meldet sich spätestens alle 60 s – ohne Meldung so lange gilt er als offline. */
export const REMOTE_ONLINE_MS = 150_000
/** Erledigte/abgelaufene Befehle bleiben so lange (Idempotenz, Ergebnis), dann weg. */
const COMMAND_KEEP_MS = 3 * 60 * 60_000
export const MAX_DEVICES_PER_KIND = 10
export const MAX_PAIRINGS_PER_DESKTOP = 10
export const MAX_STATUS_INSTANCES = 200
export const DEVICE_HEADER = 'x-trs-device'

export const DEVICE_ID = /^[A-Za-z0-9_-]{22}$/
const DEVICE_SECRET = /^[A-Za-z0-9_-]{43}$/
export const COMMAND_ID = DEVICE_ID
export const IDEMPOTENCY_KEY = /^[A-Za-z0-9_-]{8,64}$/
/** Instanz-IDs des Launchers (Ordnernamen): `[a-z0-9-]`, ohne Bindestrich am Rand, höchstens 64 Zeichen. */
export const INSTANCE_ID = /^[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$/
/** Pack-Code (§27) ohne `TRS-`: Crockford-Base32. */
const PACK_CODE = /^[0-9A-HJKMNP-TV-Z]{8}$/

export const PAIR_CODE_ALPHABET = '23456789ABCDEFGHJKMNPQRSTVWXYZ'
export const PAIR_CODE_LENGTH = 6
const PAIR_CODE = new RegExp(`^[${PAIR_CODE_ALPHABET}]{${PAIR_CODE_LENGTH}}$`)

export type DeviceKind = 'desktop' | 'phone'
export const COMMAND_TYPES = ['launch_instance', 'stop_instance', 'install_pack_code', 'ping'] as const
export type CommandType = (typeof COMMAND_TYPES)[number]
export type CommandArgs = { instanceId: string } | { code: string } | Record<string, never>
export const ERROR_CODE = /^[a-z][a-z0-9_]{0,63}$/

interface DeviceRow {
  id: string
  uuid: string
  kind: DeviceKind
  name: string
  secret_hash: string
  created_at: number
  last_seen_at: number
  status: string | null
  status_at: number | null
}

interface CommandRow {
  id: string
  uuid: string
  desktop_id: string
  phone_id: string
  idem_key: string
  type: CommandType
  args: string
  state: 'pending' | 'running' | 'done' | 'failed'
  error: string | null
  created_at: number
  expires_at: number
  claimed_at: number | null
}

export interface DeviceView {
  id: string
  kind: DeviceKind
  name: string
  createdAt: string
}

export interface StatusInstance {
  id: string
  name: string
  version: string
  loader: string
  /** Hash des Instanz-Symbols (Handy kann ein schon geladenes Symbol wiederverwenden) oder `null`. */
  iconHash: string | null
  running: boolean
}

export interface StatusTask {
  title: string
  /** 0–1, `null` = unbestimmt. */
  progress: number | null
  instanceId: string | null
}

export interface RemoteStatus {
  /** `false` = Fernbedienung am PC ausgeschaltet (oder Launcher beendet). */
  online: boolean
  allow: { launch: boolean, install: boolean }
  instances: StatusInstance[]
  tasks: StatusTask[]
}

export interface PeerView extends DeviceView {
  pairedAt: string
  lastSeenAt: string
  /** Nur bei PCs: meldet sich gerade und hat die Fernbedienung an. */
  online?: boolean
  /** Nur bei PCs: letzter gemeldeter Stand (`null` = noch keiner). */
  status?: RemoteStatus | null
  statusAt?: string | null
}

export interface AuthedDevice {
  device: DeviceRow
}

const iso = (t: number) => new Date(t).toISOString()

/** Freitext von Geräten (Namen, Instanz-Namen): ohne Steuer-/Bidi-Zeichen, Leerraum zusammengefasst, gekürzt. */
export function cleanLabel(raw: unknown, max: number): string {
  if (typeof raw !== 'string') return ''
  const s = raw
    .slice(0, max * 4)
    .normalize('NFC')
    .replace(/[\p{Cc}\p{Cf}\p{Co}\p{Cn}]/gu, '')
    .replace(/\s+/g, ' ')
    .trim()
  return [...s].slice(0, max).join('')
}

/** Eingabe → Code ohne Striche/Leerzeichen in Großbuchstaben, sonst `null`. */
export function normalizePairCode(input: string): string | null {
  if (typeof input !== 'string' || input.length > 80) return null
  let s = input.trim()
  // Ganzer QR-Link eingefügt? Dann nur den Code dahinter.
  const link = /^trs-launcher:\/\/remote-pair\/([A-Za-z0-9-]{6,8})\/?$/i.exec(s)
  if (link) s = link[1]!
  const c = s.toUpperCase().replace(/[\s-]/g, '')
  return PAIR_CODE.test(c) ? c : null
}

export function formatPairCode(code: string): string {
  return `${code.slice(0, 3)}-${code.slice(3)}`
}

function newPairCode(): string {
  let c = ''
  for (let i = 0; i < PAIR_CODE_LENGTH; i++) c += PAIR_CODE_ALPHABET[randomInt(PAIR_CODE_ALPHABET.length)]
  return c
}

function deviceView(r: DeviceRow): DeviceView {
  return { id: r.id, kind: r.kind, name: r.name, createdAt: iso(r.created_at) }
}

/** HMAC-Schlüssel eines Desktops = SHA-256 seines Geheimnisses (roh) – der Desktop rechnet ihn selbst aus. */
export function commandSignature(secretHashHex: string, payload: string): string {
  return createHmac('sha256', Buffer.from(secretHashHex, 'hex')).update(payload, 'utf8').digest('base64url')
}

/** Nur für Tests/Clients: Schlüssel aus dem Klartext-Geheimnis. */
export function secretKeyHex(secret: string): string {
  return createHash('sha256').update(secret, 'utf8').digest('hex')
}

// ---------------------------------------------------------------- Geräte

/**
 * Gerät-Kopfzeile prüfen: Gerät muss existieren, zum Konto gehören und (falls angegeben) die passende Art haben.
 * Fehlendes/falsches Gerät → `403 remote_device_invalid` (kein Hinweis, ob es die ID gibt).
 */
export function authenticateDevice(ctx: AppContext, uuid: string, header: string | undefined, kind?: DeviceKind): DeviceRow {
  // 403 statt 401: ein 401 hieße für Clients „TRS-Token abgelaufen“ (neu anmelden) – hier fehlt nur das Gerät.
  const invalid = () => forbidden('remote_device_invalid', 'This device is not registered for remote control (register it again)')
  if (!header || header.length > 80) throw invalid()
  const dot = header.indexOf('.')
  const id = header.slice(0, dot)
  const secret = header.slice(dot + 1)
  if (dot < 0 || !DEVICE_ID.test(id) || !DEVICE_SECRET.test(secret)) throw invalid()
  const r = one<DeviceRow>(ctx.db, 'SELECT * FROM remote_devices WHERE id = ?', id)
  if (!r || !safeEqual(sha256Hex(secret), r.secret_hash)) throw invalid()
  if (r.uuid !== uuid) throw forbidden('remote_wrong_account', 'This device belongs to another TRS account')
  if (kind && r.kind !== kind) throw forbidden('remote_wrong_device', `Only a ${kind} can do this`)
  const t = ctx.now()
  if (t - r.last_seen_at > 60_000) run(ctx.db, 'UPDATE remote_devices SET last_seen_at = ? WHERE id = ?', t, r.id)
  return r
}

/** Neues Gerät für das Konto. Bei mehr als {@link MAX_DEVICES_PER_KIND} je Art fällt das am längsten stille weg. */
export function registerDevice(ctx: AppContext, uuid: string, kind: DeviceKind, rawName: string): { device: DeviceView, secret: string } {
  const name = cleanLabel(rawName, 48)
  if (!name) throw badRequest('invalid_name', 'Device name: 1 to 48 characters')
  const id = randomBytes(16).toString('base64url')
  const secret = randomBytes(32).toString('base64url')
  const t = ctx.now()
  const dropped: string[] = []
  tx(ctx.db, () => {
    run(
      ctx.db,
      'INSERT INTO remote_devices (id, uuid, kind, name, secret_hash, created_at, last_seen_at) VALUES (?, ?, ?, ?, ?, ?, ?)',
      id, uuid, kind, name, sha256Hex(secret), t, t,
    )
    const extra = all<{ id: string }>(
      ctx.db,
      `SELECT id FROM remote_devices WHERE uuid = ? AND kind = ? AND id NOT IN (
         SELECT id FROM remote_devices WHERE uuid = ? AND kind = ? ORDER BY last_seen_at DESC, created_at DESC LIMIT ?)`,
      uuid, kind, uuid, kind, MAX_DEVICES_PER_KIND,
    )
    for (const e of extra) dropped.push(e.id)
  })
  for (const d of dropped) removeDevice(ctx, uuid, d)
  return { device: { id, kind, name, createdAt: iso(t) }, secret }
}

/** Gerät abmelden: Kopplungen, offene Codes und Befehle verschwinden; die Gegenstellen bekommen `remote_pairing`. */
export function removeDevice(ctx: AppContext, uuid: string, deviceId: string): void {
  const pairs = all<{ desktop_id: string, phone_id: string }>(
    ctx.db, 'SELECT desktop_id, phone_id FROM remote_pairings WHERE desktop_id = ? OR phone_id = ?', deviceId, deviceId,
  )
  // Kopplungen, Codes und Befehle hängen per ON DELETE CASCADE am Gerät.
  if (run(ctx.db, 'DELETE FROM remote_devices WHERE id = ? AND uuid = ?', deviceId, uuid) === 0) return
  for (const p of pairs) {
    ctx.events.publish(uuid, { type: 'remote_pairing', action: 'removed', desktopId: p.desktop_id, phoneId: p.phone_id }, { meOnly: true })
  }
}

// ---------------------------------------------------------------- Koppeln

export interface NewPairCode {
  code: string
  link: string
  expiresAt: string
  expiresIn: number
}

/** Desktop: neuer Kopplungs-Code (ersetzt einen offenen Code dieses Desktops). */
export function createPairCode(ctx: AppContext, desktop: DeviceRow): NewPairCode {
  const t = ctx.now()
  const paired = one<{ n: number }>(ctx.db, 'SELECT COUNT(*) AS n FROM remote_pairings WHERE desktop_id = ?', desktop.id)!.n
  if (paired >= MAX_PAIRINGS_PER_DESKTOP) throw conflict('remote_pairing_limit', `At most ${MAX_PAIRINGS_PER_DESKTOP} phones per PC – remove one first`)
  let code = ''
  tx(ctx.db, () => {
    run(ctx.db, 'DELETE FROM remote_pair_codes WHERE desktop_id = ? OR expires_at <= ?', desktop.id, t)
    for (let attempt = 0; ; attempt++) {
      code = newPairCode()
      if (!one(ctx.db, 'SELECT 1 AS x FROM remote_pair_codes WHERE code = ?', code)) break
      if (attempt >= 20) throw new ApiError(503, 'busy', 'Could not create a pairing code, try again')
    }
    run(
      ctx.db,
      'INSERT INTO remote_pair_codes (code, desktop_id, uuid, created_at, expires_at) VALUES (?, ?, ?, ?, ?)',
      code, desktop.id, desktop.uuid, t, t + REMOTE_PAIR_TTL_MS,
    )
  })
  return {
    code: formatPairCode(code),
    link: `trs-launcher://remote-pair/${code}`,
    expiresAt: iso(t + REMOTE_PAIR_TTL_MS),
    expiresIn: REMOTE_PAIR_TTL_MS / 1000,
  }
}

/** Desktop: offenen Code zurückziehen (Dialog geschlossen). */
export function cancelPairCode(ctx: AppContext, desktop: DeviceRow): void {
  run(ctx.db, 'DELETE FROM remote_pair_codes WHERE desktop_id = ?', desktop.id)
}

/**
 * Handy: Code einlösen. `null` = kein offener Code (die Route zählt das als Fehlversuch). Code eines anderen Kontos →
 * `403 remote_wrong_account` (zählt ebenfalls). Der Code gilt genau einmal.
 */
export function confirmPairCode(ctx: AppContext, phone: DeviceRow, input: string): PeerView | null {
  const code = normalizePairCode(input)
  if (!code) throw badRequest('invalid_code', 'This is not a valid pairing code')
  const t = ctx.now()
  const r = one<{ code: string, desktop_id: string, uuid: string }>(
    ctx.db, 'SELECT code, desktop_id, uuid FROM remote_pair_codes WHERE code = ? AND expires_at > ?', code, t,
  )
  if (!r) return null
  if (r.uuid !== phone.uuid) throw forbidden('remote_wrong_account', 'This PC is signed in with another TRS account – use the same account on both')
  const desktop = one<DeviceRow>(ctx.db, "SELECT * FROM remote_devices WHERE id = ? AND kind = 'desktop'", r.desktop_id)
  if (!desktop) return null
  const used = tx(ctx.db, () => {
    if (run(ctx.db, 'DELETE FROM remote_pair_codes WHERE code = ?', code) !== 1) return false
    const paired = one<{ n: number }>(ctx.db, 'SELECT COUNT(*) AS n FROM remote_pairings WHERE desktop_id = ? AND phone_id <> ?', desktop.id, phone.id)!.n
    if (paired >= MAX_PAIRINGS_PER_DESKTOP) throw conflict('remote_pairing_limit', `At most ${MAX_PAIRINGS_PER_DESKTOP} phones per PC – remove one first`)
    run(
      ctx.db,
      `INSERT INTO remote_pairings (desktop_id, phone_id, uuid, created_at) VALUES (?, ?, ?, ?)
       ON CONFLICT(desktop_id, phone_id) DO UPDATE SET created_at = excluded.created_at`,
      desktop.id, phone.id, phone.uuid, t,
    )
    return true
  })
  if (!used) return null
  const desktopPeer = peerOf(ctx, desktop, t, true)
  ctx.events.publish(phone.uuid, {
    type: 'remote_pairing',
    action: 'added',
    desktopId: desktop.id,
    phoneId: phone.id,
    desktop: desktopPeer,
    phone: peerOf(ctx, phone, t, false),
  }, { meOnly: true })
  return desktopPeer
}

function parseStatus(raw: string | null): RemoteStatus | null {
  if (!raw) return null
  try {
    return JSON.parse(raw) as RemoteStatus
  } catch {
    return null
  }
}

function isOnline(ctx: AppContext, r: Pick<DeviceRow, 'status' | 'status_at'>): boolean {
  const s = parseStatus(r.status)
  return !!s?.online && r.status_at !== null && ctx.now() - r.status_at < REMOTE_ONLINE_MS
}

function peerOf(ctx: AppContext, r: DeviceRow, pairedAt: number, withStatus: boolean): PeerView {
  const base: PeerView = { ...deviceView(r), pairedAt: iso(pairedAt), lastSeenAt: iso(r.last_seen_at) }
  if (!withStatus || r.kind !== 'desktop') return base
  return { ...base, online: isOnline(ctx, r), status: parseStatus(r.status), statusAt: r.status_at === null ? null : iso(r.status_at) }
}

/** Gekoppelte Gegenstellen: für einen PC die Handys, für ein Handy die PCs (mit Status). */
export function listPeers(ctx: AppContext, self: DeviceRow): { self: DeviceView, peers: PeerView[] } {
  const rows = self.kind === 'desktop'
    ? all<DeviceRow & { paired_at: number }>(
      ctx.db,
      `SELECT d.*, p.created_at AS paired_at FROM remote_pairings p JOIN remote_devices d ON d.id = p.phone_id
       WHERE p.desktop_id = ? AND d.uuid = ? ORDER BY p.created_at`,
      self.id, self.uuid,
    )
    : all<DeviceRow & { paired_at: number }>(
      ctx.db,
      `SELECT d.*, p.created_at AS paired_at FROM remote_pairings p JOIN remote_devices d ON d.id = p.desktop_id
       WHERE p.phone_id = ? AND d.uuid = ? ORDER BY p.created_at`,
      self.id, self.uuid,
    )
  return { self: deviceView(self), peers: rows.map((r) => peerOf(ctx, r, r.paired_at, true)) }
}

/** Kopplung lösen (von beiden Seiten aus). */
export function unpair(ctx: AppContext, self: DeviceRow, peerId: string): void {
  if (!DEVICE_ID.test(peerId)) throw notFound('remote_peer_not_found', 'This device is not paired')
  const [desktopId, phoneId] = self.kind === 'desktop' ? [self.id, peerId] : [peerId, self.id]
  const n = run(ctx.db, 'DELETE FROM remote_pairings WHERE desktop_id = ? AND phone_id = ? AND uuid = ?', desktopId, phoneId, self.uuid)
  if (n === 0) throw notFound('remote_peer_not_found', 'This device is not paired')
  ctx.events.publish(self.uuid, { type: 'remote_pairing', action: 'removed', desktopId, phoneId }, { meOnly: true })
}

// ---------------------------------------------------------------- Status

/** Gemeldeten Stand prüfen und säubern (Texte gekürzt, unbekannte Felder fallen weg). */
export function cleanStatus(input: {
  online: boolean
  allow: { launch: boolean, install: boolean }
  instances: { id: string, name: string, version: string, loader: string, iconHash?: string | null, running: boolean }[]
  tasks?: { title: string, progress: number | null, instanceId?: string | null }[]
}): RemoteStatus {
  const seen = new Set<string>()
  const instances: StatusInstance[] = []
  for (const i of input.instances.slice(0, MAX_STATUS_INSTANCES)) {
    if (!INSTANCE_ID.test(i.id) || seen.has(i.id)) continue
    seen.add(i.id)
    instances.push({
      id: i.id,
      name: cleanLabel(i.name, 64) || i.id,
      version: cleanLabel(i.version, 32),
      loader: cleanLabel(i.loader, 16).toLowerCase(),
      iconHash: i.iconHash && /^[0-9a-f]{8,64}$/.test(i.iconHash) ? i.iconHash : null,
      running: i.running,
    })
  }
  const tasks: StatusTask[] = (input.tasks ?? []).slice(0, 10).map((t) => ({
    title: cleanLabel(t.title, 80),
    progress: t.progress === null || !Number.isFinite(t.progress) ? null : Math.min(1, Math.max(0, t.progress)),
    instanceId: t.instanceId && INSTANCE_ID.test(t.instanceId) ? t.instanceId : null,
  })).filter((t) => t.title)
  return { online: input.online, allow: { launch: input.allow.launch, install: input.allow.install }, instances, tasks }
}

/** Desktop meldet seinen Stand – geht als `remote_status` an alle Geräte des Kontos (flüchtig). */
export function putStatus(ctx: AppContext, desktop: DeviceRow, status: RemoteStatus): void {
  const t = ctx.now()
  run(ctx.db, 'UPDATE remote_devices SET status = ?, status_at = ?, last_seen_at = ? WHERE id = ?', JSON.stringify(status), t, t, desktop.id)
  const paired = one(ctx.db, 'SELECT 1 AS x FROM remote_pairings WHERE desktop_id = ? LIMIT 1', desktop.id)
  if (!paired) return
  ctx.events.publish(desktop.uuid, { type: 'remote_status', desktopId: desktop.id, online: status.online, status, at: iso(t) }, { ephemeral: true, meOnly: true })
}

// ---------------------------------------------------------------- Befehle

export interface CommandView {
  id: string
  desktopId: string
  type: CommandType
  state: CommandRow['state']
  createdAt: string
  expiresAt: string
}

function commandView(r: CommandRow): CommandView {
  return { id: r.id, desktopId: r.desktop_id, type: r.type, state: r.state, createdAt: iso(r.created_at), expiresAt: iso(r.expires_at) }
}

/** Argumente je Art prüfen und auf das Nötige kürzen. */
export function cleanArgs(type: CommandType, args: unknown): CommandArgs {
  const a = (typeof args === 'object' && args !== null ? args : {}) as Record<string, unknown>
  const bad = () => badRequest('invalid_args', 'Invalid arguments for this command')
  switch (type) {
    case 'launch_instance':
    case 'stop_instance': {
      const id = a.instanceId
      if (typeof id !== 'string' || !INSTANCE_ID.test(id)) throw bad()
      return { instanceId: id }
    }
    case 'install_pack_code': {
      if (typeof a.code !== 'string' || a.code.length > 40) throw bad()
      let s = a.code.trim().toUpperCase().replace(/[\s-]/g, '')
      if (s.startsWith('TRS') && s.length === 11) s = s.slice(3)
      s = s.replace(/O/g, '0').replace(/[IL]/g, '1')
      if (!PACK_CODE.test(s)) throw bad()
      return { code: `TRS-${s.slice(0, 4)}-${s.slice(4)}` }
    }
    case 'ping':
      return {}
  }
}

/**
 * Handy: Befehl an einen gekoppelten PC. Gleicher Idempotenz-Schlüssel (je Handy) → derselbe Befehl, ohne erneute
 * Zustellung (`duplicate: true`). Der PC muss online sein und die Art erlauben (laut letztem Status).
 */
export function sendCommand(
  ctx: AppContext,
  phone: DeviceRow,
  desktopId: string,
  input: { type: CommandType, args: unknown, idempotencyKey: string },
): { command: CommandView, duplicate: boolean } {
  const t = ctx.now()
  const same = one<CommandRow>(ctx.db, 'SELECT * FROM remote_commands WHERE phone_id = ? AND idem_key = ?', phone.id, input.idempotencyKey)
  if (same) {
    if (same.desktop_id !== desktopId || same.type !== input.type) throw conflict('idempotency_conflict', 'This idempotency key was used for another command')
    return { command: commandView(same), duplicate: true }
  }
  if (!DEVICE_ID.test(desktopId)) throw notFound('remote_peer_not_found', 'This PC is not paired with this phone')
  const desktop = one<DeviceRow>(
    ctx.db,
    `SELECT d.* FROM remote_pairings p JOIN remote_devices d ON d.id = p.desktop_id
     WHERE p.desktop_id = ? AND p.phone_id = ? AND d.kind = 'desktop'`,
    desktopId, phone.id,
  )
  if (!desktop) throw notFound('remote_peer_not_found', 'This PC is not paired with this phone')
  // Gleiches Konto – immer, auch wenn sich ein Gerät zwischendurch anders angemeldet hat.
  if (desktop.uuid !== phone.uuid) throw forbidden('remote_wrong_account', 'This PC belongs to another TRS account')
  const args = cleanArgs(input.type, input.args)
  if (input.type !== 'ping' && !isOnline(ctx, desktop)) throw conflict('remote_offline', 'The PC is offline or remote control is switched off there')
  const status = parseStatus(desktop.status)
  const needs = input.type === 'install_pack_code' ? 'install' : input.type === 'ping' ? null : 'launch'
  if (needs && status && !status.allow[needs]) throw forbidden('remote_command_disabled', 'This is switched off in the launcher on the PC')

  const id = randomBytes(16).toString('base64url')
  const expires = t + REMOTE_COMMAND_TTL_MS
  run(
    ctx.db,
    `INSERT INTO remote_commands (id, uuid, desktop_id, phone_id, idem_key, type, args, state, created_at, expires_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, 'pending', ?, ?)`,
    id, phone.uuid, desktop.id, phone.id, input.idempotencyKey, input.type, JSON.stringify(args), t, expires,
  )
  // Signierter Inhalt als fertiger String: der PC prüft genau diese Bytes und liest erst dann.
  const payload = JSON.stringify({
    v: 1,
    id,
    desktopId: desktop.id,
    phoneId: phone.id,
    phoneName: phone.name,
    type: input.type,
    args,
    issuedAt: t,
    expiresAt: expires,
  })
  ctx.events.publish(phone.uuid, {
    type: 'remote_command',
    desktopId: desktop.id,
    payload,
    sig: commandSignature(desktop.secret_hash, payload),
  }, { meOnly: true })
  const row = one<CommandRow>(ctx.db, 'SELECT * FROM remote_commands WHERE id = ?', id)!
  return { command: commandView(row), duplicate: false }
}

function ownCommand(ctx: AppContext, desktop: DeviceRow, id: string): CommandRow {
  const r = COMMAND_ID.test(id) ? one<CommandRow>(ctx.db, 'SELECT * FROM remote_commands WHERE id = ?', id) : undefined
  if (!r || r.desktop_id !== desktop.id || r.uuid !== desktop.uuid) throw notFound('remote_command_not_found', 'Unknown command')
  return r
}

function publishUpdate(ctx: AppContext, r: CommandRow, state: CommandRow['state'], error: string | null): void {
  ctx.events.publish(r.uuid, {
    type: 'remote_command_update',
    commandId: r.id,
    desktopId: r.desktop_id,
    phoneId: r.phone_id,
    commandType: r.type,
    state,
    error,
  }, { meOnly: true })
}

/**
 * Desktop: Befehl abholen, bevor er ausgeführt wird – genau einmal und nur vor dem Ablauf. Liefert die geprüften
 * Argumente (so muss der PC dem Ereignis nicht blind vertrauen).
 */
export function claimCommand(ctx: AppContext, desktop: DeviceRow, id: string): { type: CommandType, args: CommandArgs } {
  const r = ownCommand(ctx, desktop, id)
  const t = ctx.now()
  if (r.state !== 'pending') throw conflict('remote_command_claimed', 'This command was already handled')
  if (r.expires_at <= t) throw new ApiError(410, 'remote_command_expired', 'This command expired')
  if (run(ctx.db, "UPDATE remote_commands SET state = 'running', claimed_at = ? WHERE id = ? AND state = 'pending'", t, id) !== 1) {
    throw conflict('remote_command_claimed', 'This command was already handled')
  }
  publishUpdate(ctx, r, 'running', null)
  return { type: r.type, args: JSON.parse(r.args) as CommandArgs }
}

/** Desktop: Ergebnis eines abgeholten Befehls (`error` = stabiler Code wie `instance_not_found`). */
export function finishCommand(ctx: AppContext, desktop: DeviceRow, id: string, ok: boolean, error: string | null): void {
  const r = ownCommand(ctx, desktop, id)
  const t = ctx.now()
  if (r.state !== 'running' || r.claimed_at === null) throw conflict('remote_command_not_running', 'This command is not running')
  if (t - r.claimed_at > REMOTE_RESULT_WINDOW_MS) throw new ApiError(410, 'remote_command_expired', 'This command expired')
  const state = ok ? 'done' : 'failed'
  const err = ok ? null : error && ERROR_CODE.test(error) ? error : 'failed'
  if (run(ctx.db, "UPDATE remote_commands SET state = ?, error = ? WHERE id = ? AND state = 'running'", state, err, id) !== 1) {
    throw conflict('remote_command_not_running', 'This command is not running')
  }
  publishUpdate(ctx, r, state, err)
}

/** Abgelaufene Codes und alte Befehle wegräumen. */
export function sweepRemote(ctx: AppContext): void {
  const t = ctx.now()
  run(ctx.db, 'DELETE FROM remote_pair_codes WHERE expires_at <= ?', t)
  run(ctx.db, 'DELETE FROM remote_commands WHERE created_at <= ?', t - COMMAND_KEEP_MS)
}
