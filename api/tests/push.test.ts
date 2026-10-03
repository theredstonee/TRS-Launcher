import { createDecipheriv, createECDH, createPublicKey, hkdfSync, verify, type ECDH } from 'node:crypto'
import { describe, expect, it } from 'vitest'
import { authenticate, logout } from '../server/lib/auth'
import { openDm, sendMessage } from '../server/lib/chat'
import { ConfigError, loadConfig } from '../server/lib/config'
import { all, run } from '../server/lib/db'
import { migratePush } from '../server/lib/migrations'
import { EventHub } from '../server/lib/events'
import { sendRequest } from '../server/lib/friends'
import { parseWith } from '../server/lib/http'
import { reportPresence } from '../server/lib/playerevents'
import {
  checkEndpoint,
  checkEndpointSyntax,
  classifyPushStatus,
  deleteDevice,
  httpsTransport,
  listDevices,
  pendingFor,
  registerDevice,
  skipReason,
  updateDevice,
  type DeviceInput,
  type PushPayload,
  type PushRequest,
} from '../server/lib/push'
import { b64u, encryptWebPush, generateVapidKeys, parseVapidKeys, VapidSigner } from '../server/lib/push-crypto'
import { pushLang } from '../server/lib/push-texts'
import { pushDeviceBody, pushDevicePatch, pushPendingQuery } from '../server/lib/schemas'
import { getUser } from '../server/lib/users'
import { befriend, codeAsync } from './chathelpers'
import { login, makeEnv, type TestEnv } from './helpers'

const fromB64u = (s: string) => Buffer.from(s.replace(/\s+/g, ''), 'base64url')

/** Empfänger-Seite (wie die App): RFC 8291/8188 entschlüsseln. */
function decryptWebPush(msg: Buffer, ua: ECDH, authSecret: Buffer): Buffer {
  const salt = msg.subarray(0, 16)
  expect(msg.readUInt32BE(16)).toBe(4096)
  const idlen = msg[20]!
  const asPublic = msg.subarray(21, 21 + idlen)
  const ct = msg.subarray(21 + idlen)
  const secret = ua.computeSecret(asPublic)
  const keyInfo = Buffer.concat([Buffer.from('WebPush: info\0'), ua.getPublicKey(), asPublic])
  const ikm = Buffer.from(hkdfSync('sha256', secret, authSecret, keyInfo, 32))
  const cek = Buffer.from(hkdfSync('sha256', ikm, salt, Buffer.from('Content-Encoding: aes128gcm\0'), 16))
  const nonce = Buffer.from(hkdfSync('sha256', ikm, salt, Buffer.from('Content-Encoding: nonce\0'), 12))
  const d = createDecipheriv('aes-128-gcm', cek, nonce)
  d.setAuthTag(ct.subarray(ct.length - 16))
  const rec = Buffer.concat([d.update(ct.subarray(0, ct.length - 16)), d.final()])
  let end = rec.length - 1
  while (end >= 0 && rec[end] === 0) end--
  expect(rec[end]).toBe(2)
  return rec.subarray(0, end)
}

const VAPID = generateVapidKeys()
const VAPID_ENV = { VAPID_PUBLIC_KEY: VAPID.publicKey, VAPID_PRIVATE_KEY: VAPID.privateKey, VAPID_SUBJECT: 'mailto:push@example.org' }

interface Phone {
  ecdh: ECDH
  authSecret: Buffer
  keys: { p256dh: string, auth: string }
}

function phone(): Phone {
  const ecdh = createECDH('prime256v1')
  ecdh.generateKeys()
  const authSecret = Buffer.from('0123456789abcdef')
  return { ecdh, authSecret, keys: { p256dh: b64u(ecdh.getPublicKey()), auth: b64u(authSecret) } }
}

/** Umgebung mit VAPID, Attrappen für DNS + Push-Dienst (sammelt die Anfragen). */
function pushEnv(opts: { vapid?: boolean } = {}) {
  const env = makeEnv({ env: opts.vapid === false ? {} : VAPID_ENV })
  const sent: PushRequest[] = []
  const statuses: number[] = []
  env.ctx.push.resolve = async (host) => (host.endsWith('.evil.org') ? ['10.0.0.5'] : ['93.184.216.34'])
  env.ctx.push.transport = async (req) => {
    sent.push(req)
    return { status: statuses.shift() ?? 201 }
  }
  env.ctx.push.retryDelaysMs = [0, 0, 0]
  return { env, sent, statuses }
}

async function account(env: TestEnv, name: string) {
  const r = await login(env, name)
  return { auth: authenticate(env.ctx, `Bearer ${r.token}`), user: getUser(env.ctx, r.user.uuid)! }
}

const upInput = (p: Phone, extra: Partial<DeviceInput> = {}): DeviceInput => ({
  platform: 'android', kind: 'unifiedpush', endpoint: 'https://ntfy.example.org/upAbCdEf123?up=1', keys: p.keys,
  deviceName: 'Pixel 8', appVersion: '1.0.0', locale: 'de-DE', ...extra,
})

const pollInput = (extra: Partial<DeviceInput> = {}): DeviceInput => ({
  platform: 'ios', kind: 'poll', deviceName: 'iPhone', appVersion: '1.0.0', locale: 'es', ...extra,
})

function payloads(sent: PushRequest[], p: Phone): PushPayload[] {
  return sent.map((r) => JSON.parse(decryptWebPush(r.body, p.ecdh, p.authSecret).toString('utf8')) as PushPayload)
}

describe('web push crypto', () => {
  it('matches the RFC 8291 appendix A test vector', () => {
    const out = encryptWebPush(
      fromB64u('V2hlbiBJIGdyb3cgdXAsIEkgd2FudCB0byBiZSBhIHdhdGVybWVsb24'),
      fromB64u('BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4'),
      fromB64u('BTBZMqHH6r4Tts7J_aSIgg'),
      { salt: fromB64u('DGv6ra1nlYgDCS1FRnbzlw'), asPrivateKey: fromB64u('yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw'), padBlock: 0 },
    )
    expect(out.toString('base64url')).toBe(
      'DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27ml'
      + 'mlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPT'
      + 'pK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN',
    )
  })

  it('round-trips with random keys and pads the length', () => {
    const p = phone()
    const text = Buffer.from(JSON.stringify({ hello: 'wörld' }))
    const a = encryptWebPush(text, p.ecdh.getPublicKey(), p.authSecret)
    const b = encryptWebPush(text, p.ecdh.getPublicKey(), p.authSecret)
    expect(a.equals(b)).toBe(false)
    expect((a.length - 86 - 16) % 64).toBe(0)
    expect(decryptWebPush(a, p.ecdh, p.authSecret).equals(text)).toBe(true)
    expect(() => encryptWebPush(Buffer.alloc(5000), p.ecdh.getPublicKey(), p.authSecret)).toThrow()
  })

  it('signs a VAPID header (ES256) that verifies with the public key', () => {
    const keys = parseVapidKeys(VAPID.publicKey, VAPID.privateKey)!
    const t = Date.UTC(2026, 9, 3)
    const signer = new VapidSigner({ ...keys, subject: 'mailto:a@b.de' }, () => t)
    const h = signer.header('https://ntfy.example.org/upXYZ?x=1')
    const m = /^vapid t=([\w-]+)\.([\w-]+)\.([\w-]+), k=([\w-]+)$/.exec(h)!
    expect(m[4]).toBe(VAPID.publicKey)
    expect(JSON.parse(fromB64u(m[1]!).toString())).toEqual({ typ: 'JWT', alg: 'ES256' })
    const claims = JSON.parse(fromB64u(m[2]!).toString()) as { aud: string, exp: number, sub: string }
    expect(claims.aud).toBe('https://ntfy.example.org')
    expect(claims.sub).toBe('mailto:a@b.de')
    expect(claims.exp - t / 1000).toBeGreaterThan(0)
    expect(claims.exp - t / 1000).toBeLessThanOrEqual(24 * 3600)
    const pub = fromB64u(VAPID.publicKey)
    const jwk = { kty: 'EC', crv: 'P-256', x: b64u(pub.subarray(1, 33)), y: b64u(pub.subarray(33)) }
    const ok = verify('sha256', Buffer.from(`${m[1]}.${m[2]}`), { key: createPublicKey({ key: jwk, format: 'jwk' }), dsaEncoding: 'ieee-p1363' }, fromB64u(m[3]!))
    expect(ok).toBe(true)
    // Cache je Origin.
    expect(signer.header('https://ntfy.example.org/other')).toBe(h)
  })

  it('rejects broken or mismatched VAPID configuration', () => {
    const other = generateVapidKeys()
    expect(parseVapidKeys(VAPID.publicKey, other.privateKey)).toBeNull()
    expect(parseVapidKeys('nope', VAPID.privateKey)).toBeNull()
    const base = { DATA_DIR: '/tmp/x', SECRET_KEY: 'test-secret-key-0123456789abcdef-0123456789' }
    expect(loadConfig(base).vapid).toBeNull()
    expect(loadConfig({ ...base, ...VAPID_ENV }).vapid?.publicKey).toBe(VAPID.publicKey)
    expect(() => loadConfig({ ...base, VAPID_PUBLIC_KEY: VAPID.publicKey })).toThrow(ConfigError)
    expect(() => loadConfig({ ...base, ...VAPID_ENV, VAPID_PRIVATE_KEY: other.privateKey })).toThrow(ConfigError)
    expect(() => loadConfig({ ...base, ...VAPID_ENV, VAPID_SUBJECT: 'http://insecure.example' })).toThrow(ConfigError)
  })
})

describe('push endpoint (SSRF guard)', () => {
  const own = new Set(['trs-launcher.theredstonee.de'])
  const codeOf = (url: string) => {
    try {
      checkEndpointSyntax(url, own)
      return 'ok'
    } catch (e) {
      return (e as { code: string }).code
    }
  }

  it('accepts public https endpoints only', () => {
    expect(codeOf('https://ntfy.sh/upAbC123?up=1')).toBe('ok')
    expect(codeOf('https://push.example.org:8443/x')).toBe('ok')
    expect(codeOf('https://93.184.216.34/x')).toBe('ok')
    expect(codeOf('http://ntfy.sh/up')).toBe('endpoint_invalid')
    expect(codeOf('ftp://ntfy.sh/up')).toBe('endpoint_invalid')
    expect(codeOf('https://user:pw@ntfy.sh/up')).toBe('endpoint_invalid')
    expect(codeOf('https://ntfy.sh:22/up')).toBe('endpoint_invalid')
    expect(codeOf('https://ntfy.sh/up#frag')).toBe('endpoint_invalid')
    expect(codeOf('not a url')).toBe('endpoint_invalid')
    expect(codeOf(`https://ntfy.sh/${'a'.repeat(2100)}`)).toBe('endpoint_invalid')
  })

  it('blocks loopback, private, link-local, mapped and internal hosts', () => {
    for (const url of [
      'https://localhost/x', 'https://127.0.0.1/x', 'https://2130706433/x', 'https://0x7f.1/x', 'https://10.1.2.3/x',
      'https://192.168.0.10:8443/x', 'https://169.254.169.254/latest', 'https://100.64.0.1/x', 'https://[::1]/x',
      'https://[::ffff:127.0.0.1]/x', 'https://[fd00::1]/x', 'https://[fe80::1]/x', 'https://0.0.0.0/x',
      'https://intranet/x', 'https://printer.local/x', 'https://svc.internal/x', 'https://a.localhost/x',
      'https://trs-launcher.theredstonee.de/v1/me',
    ]) expect(codeOf(url), url).toBe('endpoint_not_allowed')
  })

  it('refuses to connect to private addresses at send time (no DNS rebinding)', async () => {
    const req = { headers: {}, body: Buffer.from('x'), timeoutMs: 2000 }
    await expect(httpsTransport({ ...req, url: 'https://localhost:4443/up' })).rejects.toMatchObject({ code: 'EPUSHBLOCKED' })
    await expect(httpsTransport({ ...req, url: 'https://127.0.0.1:4443/up' })).rejects.toThrow('blocked address')
  })

  it('checks DNS: every address must be public', async () => {
    const { env } = pushEnv()
    expect(await checkEndpoint(env.ctx, 'https://ntfy.example.org/up')).toBe('ntfy.example.org')
    expect(await codeAsync(() => checkEndpoint(env.ctx, 'https://rebind.evil.org/up'))).toBe('endpoint_not_allowed')
    env.ctx.push.resolve = async () => ['93.184.216.34', '127.0.0.1']
    expect(await codeAsync(() => checkEndpoint(env.ctx, 'https://mixed.example.org/up'))).toBe('endpoint_not_allowed')
    env.ctx.push.resolve = async () => {
      throw new Error('ENOTFOUND')
    }
    expect(await codeAsync(() => checkEndpoint(env.ctx, 'https://nope.example.org/up'))).toBe('endpoint_unresolvable')
  })
})

describe('push validation', () => {
  const p = phone()
  const ok = { platform: 'android', kind: 'unifiedpush', endpoint: 'https://ntfy.sh/up1', keys: p.keys, deviceName: 'Pixel', appVersion: '1.2.3', locale: 'de-AT' }
  const bad = (v: unknown) => {
    try {
      parseWith(pushDeviceBody, v)
      return 'ok'
    } catch (e) {
      return (e as { code: string }).code
    }
  }

  it('validates registration bodies strictly', () => {
    expect(bad(ok)).toBe('ok')
    expect(bad({ platform: 'ios', kind: 'poll', deviceName: 'iPhone', appVersion: '1.0', locale: 'en' })).toBe('ok')
    expect(bad({ ...ok, keys: undefined })).toBe('invalid_request')
    expect(bad({ ...ok, endpoint: 'http://ntfy.sh/up' })).toBe('invalid_request')
    expect(bad({ platform: 'ios', kind: 'poll', endpoint: 'https://ntfy.sh/x', deviceName: 'x', appVersion: '1', locale: 'en' })).toBe('invalid_request')
    expect(bad({ ...ok, platform: 'windows' })).toBe('invalid_request')
    expect(bad({ ...ok, locale: 'deutsch!' })).toBe('invalid_request')
    expect(bad({ ...ok, deviceName: 'a\u0000b' })).toBe('invalid_request')
    expect(bad({ ...ok, deviceName: 'x'.repeat(65) })).toBe('invalid_request')
    expect(bad({ ...ok, categories: { chat: false, spam: true } })).toBe('invalid_request')
    expect(bad({ ...ok, extra: 1 })).toBe('invalid_request')
    expect(bad({ ...ok, keys: { p256dh: 'x!', auth: p.keys.auth } })).toBe('invalid_request')
    expect(() => parseWith(pushDevicePatch, {})).toThrow()
    expect(() => parseWith(pushDevicePatch, { endpoint: 'https://ntfy.sh/x' })).toThrow()
    expect(parseWith(pushDevicePatch, { categories: { friend_online: true } }).categories).toEqual({ friend_online: true })
    expect(parseWith(pushPendingQuery, { device: 'd0123456789abcdef0123' })).toEqual({ device: 'd0123456789abcdef0123', since: 0, limit: 50 })
    expect(() => parseWith(pushPendingQuery, { device: 'd0123456789abcdef0123', since: '-1' })).toThrow()
    expect(pushLang('de-AT')).toBe('de')
    expect(pushLang('es_419')).toBe('es')
    expect(pushLang('fr')).toBe('en')
  })
})

describe('push devices', () => {
  it('registers, re-registers the same endpoint, takes it over, limits and deletes', async () => {
    const { env } = pushEnv()
    const a = await account(env, 'Alice')
    const b = await account(env, 'Bob')
    const p = phone()
    const r1 = await registerDevice(env.ctx, a.auth, upInput(p))
    expect(r1.created).toBe(true)
    expect(r1.device).toMatchObject({ kind: 'unifiedpush', endpointHost: 'ntfy.example.org', current: true, preview: false, pushWhilePlaying: false })
    expect(r1.device.categories).toMatchObject({ chat: true, friend_online: false })
    // Gleicher Endpunkt → dasselbe Gerät.
    const r2 = await registerDevice(env.ctx, a.auth, upInput(p, { deviceName: 'Pixel 9', categories: { chat: false } }))
    expect(r2).toMatchObject({ created: false, device: { id: r1.device.id, deviceName: 'Pixel 9', categories: { chat: false, friends: true } } })
    // Ein anderes Konto mit demselben Endpunkt übernimmt das Gerät.
    const r3 = await registerDevice(env.ctx, b.auth, upInput(p))
    expect(r3.created).toBe(true)
    expect(listDevices(env.ctx, a.auth.uuid, a.auth.tokenHash)).toHaveLength(0)
    // Schlüssel, die kein P-256-Punkt sind.
    const broken = { p256dh: b64u(Buffer.concat([Buffer.from([4]), Buffer.alloc(64, 1)])), auth: p.keys.auth }
    expect(await codeAsync(() => registerDevice(env.ctx, a.auth, upInput(p, { endpoint: 'https://ntfy.example.org/x2', keys: broken })))).toBe('invalid_keys')
    expect(await codeAsync(() => registerDevice(env.ctx, a.auth, upInput(p, { endpoint: 'https://ntfy.example.org/x2', keys: { ...p.keys, auth: b64u(Buffer.alloc(8)) } })))).toBe('invalid_keys')
    expect(await codeAsync(() => registerDevice(env.ctx, a.auth, upInput(p, { endpoint: 'https://x.evil.org/up' })))).toBe('endpoint_not_allowed')
    // Höchstens 10.
    for (let i = 0; i < 10; i++) await registerDevice(env.ctx, a.auth, pollInput())
    expect(await codeAsync(() => registerDevice(env.ctx, a.auth, pollInput()))).toBe('too_many_devices')
    const list = listDevices(env.ctx, a.auth.uuid, a.auth.tokenHash)
    expect(list).toHaveLength(10)
    expect(() => deleteDevice(env.ctx, b.auth.uuid, list[0]!.id)).toThrow()
    deleteDevice(env.ctx, a.auth.uuid, list[0]!.id)
    expect(listDevices(env.ctx, a.auth.uuid, a.auth.tokenHash)).toHaveLength(9)
  })

  it('refuses UnifiedPush without VAPID but allows poll devices', async () => {
    const { env } = pushEnv({ vapid: false })
    const a = await account(env, 'Alice')
    expect(await codeAsync(() => registerDevice(env.ctx, a.auth, upInput(phone())))).toBe('push_unavailable')
    expect((await registerDevice(env.ctx, a.auth, pollInput())).created).toBe(true)
  })

  it('patches categories and endpoint, only on own devices', async () => {
    const { env } = pushEnv()
    const a = await account(env, 'Alice')
    const b = await account(env, 'Bob')
    const p = phone()
    const { device } = await registerDevice(env.ctx, a.auth, upInput(p))
    const v = await updateDevice(env.ctx, a.auth, device.id, { categories: { friend_online: true, chat: false }, preview: true })
    expect(v).toMatchObject({ preview: true, categories: { friend_online: true, chat: false, friends: true } })
    const p2 = phone()
    const v2 = await updateDevice(env.ctx, a.auth, device.id, { endpoint: 'https://ntfy.example.org/new', keys: p2.keys })
    expect(v2.id).toBe(device.id)
    expect(await codeAsync(() => updateDevice(env.ctx, b.auth, device.id, { preview: false }))).toBe('device_not_found')
    const poll = await registerDevice(env.ctx, a.auth, pollInput())
    expect(await codeAsync(() => updateDevice(env.ctx, a.auth, poll.device.id, { endpoint: 'https://ntfy.example.org/z', keys: p2.keys }))).toBe('not_unifiedpush')
  })

  it('migration 23 is idempotent', () => {
    const { env } = pushEnv()
    expect(() => {
      migratePush(env.ctx.db)
      migratePush(env.ctx.db)
    }).not.toThrow()
  })

  it('ends with the session (logout)', async () => {
    const { env } = pushEnv()
    const a = await account(env, 'Alice')
    await registerDevice(env.ctx, a.auth, pollInput())
    expect(env.ctx.push.wants(a.auth.uuid)).toBe(true)
    logout(env.ctx, a.auth, false)
    expect(env.ctx.push.wants(a.auth.uuid)).toBe(false)
  })
})

describe('push delivery', () => {
  async function chatSetup(opts: { vapid?: boolean } = {}) {
    const ctx = pushEnv(opts)
    const a = await account(ctx.env, 'Alice')
    const b = await account(ctx.env, 'Bob')
    befriend(ctx.env, a.user, b.user)
    const dm = openDm(ctx.env.ctx, a.user.uuid, b.user.uuid)
    return { ...ctx, a, b, dm }
  }

  it('sends chat messages without content by default, with a preview when enabled', async () => {
    const { env, sent, a, b, dm } = await chatSetup()
    const p = phone()
    const { device } = await registerDevice(env.ctx, b.auth, upInput(p))
    sendMessage(env.ctx, a.user.uuid, dm.id, { text: 'Geheimes Passwort 1234' })
    await env.ctx.push.flush()
    expect(sent).toHaveLength(1)
    const req = sent[0]!
    expect(req.url).toBe('https://ntfy.example.org/upAbCdEf123?up=1')
    expect(req.headers).toMatchObject({ 'Content-Encoding': 'aes128gcm', TTL: '86400', Urgency: 'high' })
    expect(req.headers.Authorization).toMatch(/^vapid t=[\w-]+\.[\w-]+\.[\w-]+, k=/)
    expect(req.body.toString('latin1')).not.toContain('Geheim')
    const [n] = payloads(sent, p)
    expect(n).toMatchObject({ v: 1, type: 'chat_message', category: 'chat', title: 'Alice', body: 'Neue Nachricht', target: `/chat/${dm.id}`, collapse: `chat:${dm.id}` })
    expect(n!.id).toMatch(/^[a-z0-9]+\.\d+$/)
    expect(JSON.stringify(n)).not.toContain('Passwort')
    // Mit Vorschau: kurzer Text.
    await updateDevice(env.ctx, b.auth, device.id, { preview: true, locale: 'en' })
    sendMessage(env.ctx, a.user.uuid, dm.id, { text: 'Hello there' })
    await env.ctx.push.flush()
    expect(payloads(sent, p)[1]).toMatchObject({ title: 'Alice', body: 'Hello there' })
    // Eigene Nachrichten nie; stumm geschaltete Unterhaltungen nie.
    sendMessage(env.ctx, b.user.uuid, dm.id, { text: 'Antwort' })
    run(env.ctx.db, 'UPDATE chat_members SET muted_until = ? WHERE conversation_id = ? AND uuid = ?', env.ctx.now() + 3_600_000, dm.id, b.user.uuid)
    sendMessage(env.ctx, a.user.uuid, dm.id, { text: 'stumm' })
    await env.ctx.push.flush()
    expect(sent).toHaveLength(2)
  })

  it('filters by category and skips own-device syncs (friend_added after own accept)', async () => {
    const { env, sent } = pushEnv()
    const a = await account(env, 'Alice')
    const b = await account(env, 'Bob')
    const p = phone()
    const { device } = await registerDevice(env.ctx, b.auth, upInput(p, { locale: 'es-419' }))
    sendRequest(env.ctx, a.user, { uuid: b.user.uuid })
    await env.ctx.push.flush()
    expect(payloads(sent, p)).toMatchObject([{ type: 'friend_request', category: 'friends', title: 'Solicitud de amistad', body: 'Alice quiere ser tu amigo.', target: '/friends/requests' }])
    // Bob nimmt an: die Bestätigung an Bobs eigene Geräte (meOnly) ist keine Benachrichtigung.
    const { acceptRequest } = await import('../server/lib/friends')
    acceptRequest(env.ctx, b.user, a.user.uuid)
    await env.ctx.push.flush()
    // Nur der Erfolg „erster Freund“ kommt dazu, kein friend_added.
    expect(payloads(sent, p).map((n) => n.type)).toEqual(['friend_request', 'achievement_unlocked'])
    expect(payloads(sent, p)[1]).toMatchObject({ category: 'achievements', title: 'Logro desbloqueado' })
    // Kategorie aus → nichts.
    await updateDevice(env.ctx, b.auth, device.id, { categories: { friends: false } })
    const c = await account(env, 'Carol')
    sendRequest(env.ctx, c.user, { uuid: b.user.uuid })
    await env.ctx.push.flush()
    expect(sent).toHaveLength(2)
  })

  it('does not push while the app itself is open or the player is in-game on the PC', async () => {
    const { env, sent, a, b, dm } = await chatSetup()
    const p = phone()
    const { device } = await registerDevice(env.ctx, b.auth, upInput(p))
    const clock = env.clock
    // App offen (Stream mit pushDevice) → nichts; kurz nach dem Schließen auch nicht; danach wieder.
    const close = env.ctx.push.activity.open(b.user.uuid, device.id)
    sendMessage(env.ctx, a.user.uuid, dm.id, { text: '1' })
    close()
    clock.advance(30_000)
    sendMessage(env.ctx, a.user.uuid, dm.id, { text: '2' })
    await env.ctx.push.flush()
    expect(sent).toHaveLength(0)
    clock.advance(31_000)
    sendMessage(env.ctx, a.user.uuid, dm.id, { text: '3' })
    await env.ctx.push.flush()
    expect(sent).toHaveLength(1)
    // Launcher im Hintergrund offen (nicht im Spiel) → trotzdem Push.
    const desktop = env.ctx.push.activity.open(b.user.uuid, null)
    sendMessage(env.ctx, a.user.uuid, dm.id, { text: '4' })
    await env.ctx.push.flush()
    expect(sent).toHaveLength(2)
    // Im Spiel am PC → nichts (der TRS Client zeigt es), außer pushWhilePlaying.
    reportPresence(env.ctx, b.user, { state: 'in-game', via: 'client', game: { version: '1.21.1', loader: 'fabric' } })
    sendMessage(env.ctx, a.user.uuid, dm.id, { text: '5' })
    await env.ctx.push.flush()
    expect(sent).toHaveLength(2)
    await updateDevice(env.ctx, b.auth, device.id, { pushWhilePlaying: true })
    sendMessage(env.ctx, a.user.uuid, dm.id, { text: '6' })
    await env.ctx.push.flush()
    expect(sent).toHaveLength(3)
    desktop()
  })

  it('applies the skip rule per device', () => {
    const d = { id: 'd1', categories: JSON.stringify({ chat: true, friend_online: false }), push_while_playing: 0 }
    expect(skipReason(d, 'chat', { inApp: false, playing: false })).toBeNull()
    expect(skipReason(d, 'friend_online', { inApp: false, playing: false })).toBe('category_off')
    expect(skipReason(d, 'packs', { inApp: false, playing: false })).toBeNull()
    expect(skipReason(d, 'chat', { inApp: true, playing: false })).toBe('in_app')
    expect(skipReason(d, 'chat', { inApp: false, playing: true })).toBe('playing')
    expect(skipReason({ ...d, push_while_playing: 1 }, 'chat', { inApp: false, playing: true })).toBeNull()
  })

  it('retries with backoff, drops gone devices and counts failures', async () => {
    const { env, sent, statuses, a, b, dm } = await chatSetup()
    const p = phone()
    const { device } = await registerDevice(env.ctx, b.auth, upInput(p))
    statuses.push(503, 429, 201)
    sendMessage(env.ctx, a.user.uuid, dm.id, { text: 'x' })
    await env.ctx.push.flush()
    expect(sent).toHaveLength(3)
    expect(listDevices(env.ctx, b.auth.uuid, b.auth.tokenHash)[0]).toMatchObject({ failing: false })
    expect(listDevices(env.ctx, b.auth.uuid, b.auth.tokenHash)[0]!.lastSuccessAt).not.toBeNull()
    statuses.push(400)
    sendMessage(env.ctx, a.user.uuid, dm.id, { text: 'y' })
    await env.ctx.push.flush()
    expect(sent).toHaveLength(4)
    expect(listDevices(env.ctx, b.auth.uuid, b.auth.tokenHash)[0]).toMatchObject({ id: device.id, failing: true })
    statuses.push(410)
    sendMessage(env.ctx, a.user.uuid, dm.id, { text: 'z' })
    await env.ctx.push.flush()
    expect(listDevices(env.ctx, b.auth.uuid, b.auth.tokenHash)).toHaveLength(0)
    // Netzfehler zählen wie 5xx (Wiederholung).
    expect([null, 200, 201, 404, 410, 408, 429, 500, 503, 400, 401, 403, 413].map(classifyPushStatus)).toEqual(
      ['retry', 'ok', 'ok', 'gone', 'gone', 'retry', 'retry', 'retry', 'retry', 'fail', 'fail', 'fail', 'fail'],
    )
  })

  it('keeps notifications for poll devices (encrypted) until fetched', async () => {
    const { env, a, b, dm } = await chatSetup({ vapid: false })
    const { device } = await registerDevice(env.ctx, b.auth, pollInput())
    sendMessage(env.ctx, a.user.uuid, dm.id, { text: 'Hola Bob' })
    sendMessage(env.ctx, a.user.uuid, dm.id, { text: 'otra vez' })
    await env.ctx.push.flush()
    const raw = all<{ payload: Uint8Array }>(env.ctx.db, 'SELECT payload FROM push_pending')
    expect(raw).toHaveLength(2)
    expect(Buffer.from(raw[0]!.payload).toString('latin1')).not.toContain('Alice')
    const r1 = pendingFor(env.ctx, b.user.uuid, device.id, 0, 1)
    expect(r1.more).toBe(true)
    expect(r1.notifications).toMatchObject([{ type: 'chat_message', title: 'Alice', body: 'Nuevo mensaje', target: `/chat/${dm.id}` }])
    const r2 = pendingFor(env.ctx, b.user.uuid, device.id, Number(r1.cursor), 50)
    expect(r2.notifications).toHaveLength(1)
    expect(r2.more).toBe(false)
    // Bestätigtes (≤ since) ist gelöscht.
    expect(all(env.ctx.db, 'SELECT id FROM push_pending')).toHaveLength(1)
    const r3 = pendingFor(env.ctx, b.user.uuid, device.id, Number(r2.cursor), 50)
    expect(r3).toEqual({ notifications: [], cursor: r2.cursor, more: false })
    // Abgelaufen → nicht mehr geliefert.
    sendMessage(env.ctx, a.user.uuid, dm.id, { text: 'later' })
    await env.ctx.push.flush()
    env.clock.advance(25 * 3600_000)
    expect(pendingFor(env.ctx, b.user.uuid, device.id, Number(r3.cursor), 50).notifications).toHaveLength(0)
    // Fremdes Gerät / Push-Gerät.
    expect(await codeAsync(async () => pendingFor(env.ctx, a.user.uuid, device.id, 0, 50))).toBe('device_not_found')
  })

  it('event hub hands events for users without a stream to the push tap', () => {
    const got: string[] = []
    const hub = new EventHub(3, 100)
    hub.setTap({ wants: (u) => u === 'p', deliver: (u, e, id) => got.push(`${u}:${e.type}:${id}`) })
    expect(hub.wants('p')).toBe(true)
    expect(hub.wants('x')).toBe(false)
    hub.publish('p', { type: 'friends_changed' })
    hub.publish('x', { type: 'friends_changed' })
    hub.publish('p', { type: 'chat_typing', conversationId: 'c', uuid: 'q', typing: true, expiresInMs: 1 }, { ephemeral: true })
    expect(got).toHaveLength(1)
    expect(got[0]).toMatch(/^p:friends_changed:[a-z0-9]+\.\d+$/)
  })
})
