import { createHmac } from 'node:crypto'
import { DatabaseSync } from 'node:sqlite'
import { createServer, type Server } from 'node:http'
import { createApp, createRouter, send, setResponseHeaders, setResponseStatus, toNodeListener, type EventHandler } from 'h3'
import { afterEach, describe, expect, it } from 'vitest'
import { setContext } from '../server/lib/context'
import { isApiError } from '../server/lib/errors'
import type { ApiEvent } from '../server/lib/events'
import { migrateRemote } from '../server/lib/migrations'
import {
  REMOTE_COMMAND_TTL_MS,
  REMOTE_ONLINE_MS,
  REMOTE_PAIR_TTL_MS,
  cleanLabel,
  formatPairCode,
  normalizePairCode,
  secretKeyHex,
  sweepRemote,
} from '../server/lib/remote'
import registerRoute from '../server/routes/v1/remote/devices/index.post'
import removeMeRoute from '../server/routes/v1/remote/devices/me.delete'
import pairCreateRoute from '../server/routes/v1/remote/pair/index.post'
import pairCancelRoute from '../server/routes/v1/remote/pair/index.delete'
import pairConfirmRoute from '../server/routes/v1/remote/pair/confirm.post'
import pairingsRoute from '../server/routes/v1/remote/pairings/index.get'
import unpairRoute from '../server/routes/v1/remote/pairings/[peerId].delete'
import statusRoute from '../server/routes/v1/remote/status.post'
import commandRoute from '../server/routes/v1/remote/[deviceId]/commands.post'
import claimRoute from '../server/routes/v1/remote/commands/[id]/claim.post'
import resultRoute from '../server/routes/v1/remote/commands/[id]/result.post'
import { login, makeEnv, type TestEnv } from './helpers'

interface Harness {
  env: TestEnv
  base: string
  close: () => Promise<void>
}
const open: Harness[] = []
afterEach(async () => {
  for (const h of open.splice(0)) await h.close()
  setContext(undefined)
})

/** Echter HTTP-Server mit den Routen von §33 + JSON-Fehlern wie in server/error-handler.ts. */
async function harness(): Promise<Harness> {
  const app = createApp({
    onError: async (error, event) => {
      const cause = (error as { cause?: unknown }).cause
      const api = isApiError(error) ? error : isApiError(cause) ? cause : null
      if (!api) console.error(error)
      setResponseStatus(event, api?.status ?? 500)
      if (api?.headers) setResponseHeaders(event, api.headers)
      await send(event, JSON.stringify({ error: api ? { code: api.code, message: api.message, ...api.details } : { code: 'internal_error' } }), 'application/json')
    },
  })
  const router = createRouter()
  const routes: [string, 'post' | 'get' | 'delete', EventHandler][] = [
    ['/v1/remote/devices', 'post', registerRoute],
    ['/v1/remote/devices/me', 'delete', removeMeRoute],
    ['/v1/remote/pair', 'post', pairCreateRoute],
    ['/v1/remote/pair', 'delete', pairCancelRoute],
    ['/v1/remote/pair/confirm', 'post', pairConfirmRoute],
    ['/v1/remote/pairings', 'get', pairingsRoute],
    ['/v1/remote/pairings/:peerId', 'delete', unpairRoute],
    ['/v1/remote/status', 'post', statusRoute],
    ['/v1/remote/:deviceId/commands', 'post', commandRoute],
    ['/v1/remote/commands/:id/claim', 'post', claimRoute],
    ['/v1/remote/commands/:id/result', 'post', resultRoute],
  ]
  for (const [p, m, h] of routes) router[m](p, h)
  app.use(router)
  const server: Server = createServer(toNodeListener(app))
  await new Promise<void>((r) => server.listen(0, '127.0.0.1', r))
  const base = `http://127.0.0.1:${(server.address() as { port: number }).port}`
  const env = makeEnv({ env: { TRUST_PROXY: 'none' } })
  setContext(env.ctx)
  const h = { env, base, close: () => new Promise<void>((r) => server.close(() => r())) }
  open.push(h)
  return h
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any -- Test: beliebige JSON-Antworten
type Json = Record<string, any> | null

async function req(h: Harness, method: string, path: string, opts: { token?: string, device?: Device, body?: unknown } = {}) {
  const headers: Record<string, string> = {}
  if (opts.token) headers.authorization = `Bearer ${opts.token}`
  if (opts.device) headers['x-trs-device'] = `${opts.device.id}.${opts.device.secret}`
  if (opts.body !== undefined) headers['content-type'] = 'application/json'
  const res = await fetch(`${h.base}${path}`, { method, headers, body: opts.body === undefined ? undefined : JSON.stringify(opts.body) })
  const text = await res.text()
  return { status: res.status, json: (text ? JSON.parse(text) : null) as Json }
}

interface Device {
  id: string
  secret: string
  token: string
}

async function register(h: Harness, token: string, kind: 'desktop' | 'phone', name = kind === 'desktop' ? 'Gaming-PC' : 'Pixel 9'): Promise<Device> {
  const r = await req(h, 'POST', '/v1/remote/devices', { token, body: { kind, name } })
  expect(r.status).toBe(201)
  expect(r.json!.device.kind).toBe(kind)
  return { id: r.json!.device.id, secret: r.json!.secret, token }
}

/** Ein Konto mit PC + Handy, gekoppelt; PC meldet „online“. */
async function paired(h: Harness, allow = { launch: true, install: true }) {
  const me = await login(h.env, 'Steve')
  const pc = await register(h, me.token, 'desktop')
  const phone = await register(h, me.token, 'phone')
  const code = await req(h, 'POST', '/v1/remote/pair', { token: me.token, device: pc })
  expect(code.status).toBe(201)
  const ok = await req(h, 'POST', '/v1/remote/pair/confirm', { token: me.token, device: phone, body: { code: code.json!.code } })
  expect(ok.status).toBe(200)
  await status(h, pc, { allow })
  return { me, pc, phone, uuid: me.user.uuid as string }
}

function status(h: Harness, pc: Device, extra: Record<string, unknown> = {}) {
  return req(h, 'POST', '/v1/remote/status', {
    token: pc.token,
    device: pc,
    body: {
      online: true,
      allow: { launch: true, install: true },
      instances: [{ id: 'fabric-1-21', name: 'Fabric 1.21', version: '1.21.11', loader: 'fabric', iconHash: 'ab12cd34', running: false }],
      tasks: [],
      ...extra,
    },
  })
}

function events(h: Harness, uuid: string): ApiEvent[] {
  const got: ApiEvent[] = []
  h.env.ctx.events.subscribe(uuid, (e) => got.push(e), () => {}, 'me')
  return got
}

const command = (h: Harness, phone: Device, pcId: string, body: Record<string, unknown>) =>
  req(h, 'POST', `/v1/remote/${pcId}/commands`, { token: phone.token, device: phone, body })

let keySeq = 0
const key = () => `key-${(++keySeq).toString().padStart(6, '0')}`

describe('remote control: building blocks', () => {
  it('pairing codes are six easy characters and accept the QR link', () => {
    expect(normalizePairCode('k7q-2mx')).toBe('K7Q2MX')
    expect(normalizePairCode('trs-launcher://remote-pair/K7Q2MX')).toBe('K7Q2MX')
    for (const bad of ['K7Q2M', 'K0Q2MX', 'KIQ2MX', '', 'trs-launcher://other/K7Q2MX', 'x'.repeat(100)]) expect(normalizePairCode(bad)).toBeNull()
    expect(formatPairCode('K7Q2MX')).toBe('K7Q-2MX')
  })

  it('labels lose control and bidi characters and are cut', () => {
    expect(cleanLabel('  Gaming‮  PC\u0000 ', 48)).toBe('Gaming PC')
    expect(cleanLabel('x'.repeat(100), 10)).toBe('x'.repeat(10))
    expect(cleanLabel(42, 10)).toBe('')
  })

  it('migration is idempotent', () => {
    const db = new DatabaseSync(':memory:')
    db.exec('CREATE TABLE users (uuid TEXT PRIMARY KEY)')
    migrateRemote(db)
    migrateRemote(db)
    for (const t of ['remote_devices', 'remote_pairings', 'remote_pair_codes', 'remote_commands']) {
      expect(db.prepare("SELECT 1 AS x FROM sqlite_master WHERE type = 'table' AND name = ?").get(t)).toBeTruthy()
    }
  })
})

describe('remote control: devices and auth', () => {
  it('needs a session, and the device header must belong to the same account', async () => {
    const h = await harness()
    expect((await req(h, 'POST', '/v1/remote/devices', { body: { kind: 'desktop', name: 'PC' } })).status).toBe(401)
    const a = await login(h.env, 'Alex')
    const b = await login(h.env, 'Bob')
    const pc = await register(h, a.token, 'desktop')
    // Ohne/mit falscher Kopfzeile: 403 (nicht 401 – das hieße „Token abgelaufen“).
    expect((await req(h, 'GET', '/v1/remote/pairings', { token: a.token })).json!.error.code).toBe('remote_device_invalid')
    const wrong = await req(h, 'GET', '/v1/remote/pairings', { token: a.token, device: { ...pc, secret: 'A'.repeat(43) } })
    expect([wrong.status, wrong.json!.error.code]).toEqual([403, 'remote_device_invalid'])
    // Gerät von Alex mit Bobs Token.
    const foreign = await req(h, 'GET', '/v1/remote/pairings', { token: b.token, device: pc })
    expect([foreign.status, foreign.json!.error.code]).toEqual([403, 'remote_wrong_account'])
    // Ein PC kann keinen Code einlösen, ein Handy keinen erzeugen.
    const phone = await register(h, a.token, 'phone')
    expect((await req(h, 'POST', '/v1/remote/pair/confirm', { token: a.token, device: pc, body: { code: 'K7Q2MX' } })).json!.error.code).toBe('remote_wrong_device')
    expect((await req(h, 'POST', '/v1/remote/pair', { token: a.token, device: phone })).json!.error.code).toBe('remote_wrong_device')
    // Name wird gesäubert, leer → 400.
    expect((await req(h, 'POST', '/v1/remote/devices', { token: a.token, body: { kind: 'phone', name: '‮ ' } })).json!.error.code).toBe('invalid_name')
    // Nur der Hash liegt in der DB.
    const row = h.env.ctx.db.prepare('SELECT secret_hash FROM remote_devices WHERE id = ?').get(pc.id) as { secret_hash: string }
    expect(row.secret_hash).toBe(secretKeyHex(pc.secret))
  })

  it('keeps at most 10 devices per kind (the quietest one goes)', async () => {
    const h = await harness()
    const a = await login(h.env, 'Alex')
    const first = await register(h, a.token, 'phone', 'Alt')
    h.env.clock.advance(1000)
    for (let i = 0; i < 10; i++) {
      // Anmelde-Grenze (10 je Stunde) ist hier nicht das Thema.
      if (i === 5) h.env.clock.advance(60 * 60_000)
      await register(h, a.token, 'phone', `Phone ${i}`)
      h.env.clock.advance(1000)
    }
    const n = h.env.ctx.db.prepare("SELECT COUNT(*) AS n FROM remote_devices WHERE kind = 'phone'").get() as { n: number }
    expect(n.n).toBe(10)
    expect((await req(h, 'GET', '/v1/remote/pairings', { token: a.token, device: first })).json!.error.code).toBe('remote_device_invalid')
  })
})

describe('remote control: pairing', () => {
  it('pairs phone and PC of the same account once, both sides see each other', async () => {
    const h = await harness()
    const me = await login(h.env, 'Steve')
    const got = events(h, me.user.uuid)
    const pc = await register(h, me.token, 'desktop')
    const phone = await register(h, me.token, 'phone')
    const code = await req(h, 'POST', '/v1/remote/pair', { token: me.token, device: pc })
    expect(code.json!.code).toMatch(/^[2-9A-Z]{3}-[2-9A-Z]{3}$/)
    expect(code.json!.link).toBe(`trs-launcher://remote-pair/${code.json!.code.replace('-', '')}`)
    expect(code.json!.expiresIn).toBe(120)
    const ok = await req(h, 'POST', '/v1/remote/pair/confirm', { token: me.token, device: phone, body: { code: code.json!.link } })
    expect(ok.json!.desktop).toMatchObject({ id: pc.id, kind: 'desktop', name: 'Gaming-PC', online: false, status: null })
    expect(got.find((e) => e.type === 'remote_pairing')).toMatchObject({ action: 'added', desktopId: pc.id, phoneId: phone.id })
    // Einmalig.
    const again = await req(h, 'POST', '/v1/remote/pair/confirm', { token: me.token, device: phone, body: { code: code.json!.code } })
    expect([again.status, again.json!.error.code]).toEqual([404, 'remote_code_expired'])
    const pcSide = await req(h, 'GET', '/v1/remote/pairings', { token: me.token, device: pc })
    expect(pcSide.json!.peers.map((p: { id: string }) => p.id)).toEqual([phone.id])
    expect(pcSide.json!.peers[0].status).toBeUndefined()
    const phoneSide = await req(h, 'GET', '/v1/remote/pairings', { token: me.token, device: phone })
    expect(phoneSide.json!.peers.map((p: { id: string }) => p.id)).toEqual([pc.id])
  })

  it('refuses a code of another account and counts it as a failure', async () => {
    const h = await harness()
    const a = await login(h.env, 'Alex')
    const b = await login(h.env, 'Bob')
    const pc = await register(h, a.token, 'desktop')
    const phone = await register(h, b.token, 'phone')
    const code = await req(h, 'POST', '/v1/remote/pair', { token: a.token, device: pc })
    const r = await req(h, 'POST', '/v1/remote/pair/confirm', { token: b.token, device: phone, body: { code: code.json!.code } })
    expect([r.status, r.json!.error.code]).toEqual([403, 'remote_wrong_account'])
    expect(h.env.ctx.db.prepare('SELECT COUNT(*) AS n FROM remote_pairings').get()).toEqual({ n: 0 })
  })

  it('codes expire after 2 minutes, a new code replaces the old one, cancel removes it', async () => {
    const h = await harness()
    const me = await login(h.env, 'Steve')
    const pc = await register(h, me.token, 'desktop')
    const phone = await register(h, me.token, 'phone')
    const first = await req(h, 'POST', '/v1/remote/pair', { token: me.token, device: pc })
    const second = await req(h, 'POST', '/v1/remote/pair', { token: me.token, device: pc })
    expect((await req(h, 'POST', '/v1/remote/pair/confirm', { token: me.token, device: phone, body: { code: first.json!.code } })).status).toBe(404)
    h.env.clock.advance(REMOTE_PAIR_TTL_MS + 1)
    expect((await req(h, 'POST', '/v1/remote/pair/confirm', { token: me.token, device: phone, body: { code: second.json!.code } })).status).toBe(404)
    const third = await req(h, 'POST', '/v1/remote/pair', { token: me.token, device: pc })
    expect((await req(h, 'DELETE', '/v1/remote/pair', { token: me.token, device: pc })).status).toBe(204)
    expect((await req(h, 'POST', '/v1/remote/pair/confirm', { token: me.token, device: phone, body: { code: third.json!.code } })).status).toBe(404)
  })

  it('wrong codes are rate limited per account', async () => {
    const h = await harness()
    const me = await login(h.env, 'Steve')
    const phone = await register(h, me.token, 'phone')
    const codes = ['AAAAAA', 'BBBBBB', 'CCCCCC', 'DDDDDD', 'EEEEEE', 'FFFFFF']
    const statuses: number[] = []
    for (const c of codes) statuses.push((await req(h, 'POST', '/v1/remote/pair/confirm', { token: me.token, device: phone, body: { code: c } })).status)
    expect(statuses).toEqual([404, 404, 404, 404, 404, 429])
    expect((await req(h, 'POST', '/v1/remote/pair/confirm', { token: me.token, device: phone, body: { code: 'nope!' } })).status).toBe(429)
  })

  it('unpairing and removing a device ends the remote control', async () => {
    const h = await harness()
    const { me, pc, phone } = await paired(h)
    const got = events(h, me.user.uuid)
    expect((await req(h, 'DELETE', `/v1/remote/pairings/${phone.id}`, { token: me.token, device: pc })).status).toBe(204)
    expect(got.at(-1)).toMatchObject({ type: 'remote_pairing', action: 'removed', desktopId: pc.id, phoneId: phone.id })
    expect((await command(h, phone, pc.id, { type: 'ping', idempotencyKey: key() })).json!.error.code).toBe('remote_peer_not_found')
    expect((await req(h, 'DELETE', `/v1/remote/pairings/${phone.id}`, { token: me.token, device: pc })).status).toBe(404)
    expect((await req(h, 'DELETE', '/v1/remote/devices/me', { token: me.token, device: pc })).status).toBe(204)
    expect((await req(h, 'GET', '/v1/remote/pairings', { token: me.token, device: pc })).json!.error.code).toBe('remote_device_invalid')
  })
})

describe('remote control: status', () => {
  it('relays the PC status to the phone, cleaned, and goes offline without heartbeat', async () => {
    const h = await harness()
    const { me, pc, phone } = await paired(h)
    const got = events(h, me.user.uuid)
    const r = await status(h, pc, {
      instances: [
        { id: 'fabric-1-21', name: 'My‮ Pack', version: '1.21.11', loader: 'Fabric', iconHash: 'nothex!', running: true },
        { id: '../evil', name: 'x', version: '1', loader: 'x', running: false },
        { id: 'fabric-1-21', name: 'dup', version: '1', loader: 'x', running: false },
      ],
      tasks: [{ title: 'Pack laden', progress: 0.5, instanceId: 'fabric-1-21' }],
    })
    expect(r.status).toBe(204)
    const ev = got.find((e) => e.type === 'remote_status')
    expect(ev).toMatchObject({ desktopId: pc.id, online: true })
    if (ev?.type !== 'remote_status') throw new Error('no status')
    expect(ev.status.instances).toEqual([{ id: 'fabric-1-21', name: 'My Pack', version: '1.21.11', loader: 'fabric', iconHash: null, running: true }])
    expect(ev.status.tasks).toEqual([{ title: 'Pack laden', progress: 0.5, instanceId: 'fabric-1-21' }])
    // Status ist flüchtig (nicht im Wiederaufnahme-Puffer).
    expect(h.env.ctx.events.replay(me.user.uuid, h.env.ctx.events.currentId()).ok).toBe(true)
    let list = await req(h, 'GET', '/v1/remote/pairings', { token: me.token, device: phone })
    expect(list.json!.peers[0]).toMatchObject({ online: true })
    h.env.clock.advance(REMOTE_ONLINE_MS + 1)
    list = await req(h, 'GET', '/v1/remote/pairings', { token: me.token, device: phone })
    expect(list.json!.peers[0].online).toBe(false)
    // Phones cannot report a status, bad bodies are refused.
    expect((await status(h, phone)).json!.error.code).toBe('remote_wrong_device')
    expect((await status(h, pc, { instances: 'x' })).status).toBe(400)
  })
})

describe('remote control: commands', () => {
  it('delivers a signed command only the PC can verify, claim once, report the result', async () => {
    const h = await harness()
    const { me, pc, phone } = await paired(h)
    const got = events(h, me.user.uuid)
    const r = await command(h, phone, pc.id, { type: 'launch_instance', args: { instanceId: 'fabric-1-21' }, idempotencyKey: key() })
    expect(r.status).toBe(202)
    expect(r.json!.command).toMatchObject({ desktopId: pc.id, type: 'launch_instance', state: 'pending' })
    const ev = got.find((e) => e.type === 'remote_command')
    if (ev?.type !== 'remote_command') throw new Error('no command')
    expect(ev.desktopId).toBe(pc.id)
    // Der PC rechnet den Schlüssel aus seinem Geheimnis aus.
    const sig = createHmac('sha256', Buffer.from(secretKeyHex(pc.secret), 'hex')).update(ev.payload).digest('base64url')
    expect(ev.sig).toBe(sig)
    const payload = JSON.parse(ev.payload)
    expect(payload).toMatchObject({ v: 1, id: r.json!.command.id, desktopId: pc.id, phoneId: phone.id, phoneName: 'Pixel 9', type: 'launch_instance', args: { instanceId: 'fabric-1-21' } })
    expect(payload.expiresAt - payload.issuedAt).toBe(REMOTE_COMMAND_TTL_MS)

    const id = r.json!.command.id
    // Nur der Ziel-PC darf abholen.
    const other = await register(h, me.token, 'desktop', 'Laptop')
    expect((await req(h, 'POST', `/v1/remote/commands/${id}/claim`, { token: me.token, device: other })).status).toBe(404)
    const claim = await req(h, 'POST', `/v1/remote/commands/${id}/claim`, { token: me.token, device: pc })
    expect(claim.json!.command).toEqual({ type: 'launch_instance', args: { instanceId: 'fabric-1-21' } })
    expect((await req(h, 'POST', `/v1/remote/commands/${id}/claim`, { token: me.token, device: pc })).json!.error.code).toBe('remote_command_claimed')
    expect(got.at(-1)).toMatchObject({ type: 'remote_command_update', commandId: id, state: 'running', commandType: 'launch_instance' })
    expect((await req(h, 'POST', `/v1/remote/commands/${id}/result`, { token: me.token, device: pc, body: { ok: false, error: 'instance_not_found' } })).status).toBe(204)
    expect(got.at(-1)).toMatchObject({ type: 'remote_command_update', commandId: id, state: 'failed', error: 'instance_not_found' })
    expect((await req(h, 'POST', `/v1/remote/commands/${id}/result`, { token: me.token, device: pc, body: { ok: true } })).json!.error.code).toBe('remote_command_not_running')
  })

  it('same idempotency key returns the same command without a second delivery', async () => {
    const h = await harness()
    const { me, pc, phone } = await paired(h)
    const got = events(h, me.user.uuid)
    const k = key()
    const a = await command(h, phone, pc.id, { type: 'stop_instance', args: { instanceId: 'fabric-1-21' }, idempotencyKey: k })
    const b = await command(h, phone, pc.id, { type: 'stop_instance', args: { instanceId: 'fabric-1-21' }, idempotencyKey: k })
    expect([a.status, b.status]).toEqual([202, 200])
    expect(b.json!.duplicate).toBe(true)
    expect(b.json!.command.id).toBe(a.json!.command.id)
    expect(got.filter((e) => e.type === 'remote_command')).toHaveLength(1)
    const c = await command(h, phone, pc.id, { type: 'ping', idempotencyKey: k })
    expect([c.status, c.json!.error.code]).toEqual([409, 'idempotency_conflict'])
  })

  it('commands expire after 60 s – the server decides, not the PC clock', async () => {
    const h = await harness()
    const { me, pc, phone } = await paired(h)
    const r = await command(h, phone, pc.id, { type: 'ping', idempotencyKey: key() })
    h.env.clock.advance(REMOTE_COMMAND_TTL_MS + 1)
    const claim = await req(h, 'POST', `/v1/remote/commands/${r.json!.command.id}/claim`, { token: me.token, device: pc })
    expect([claim.status, claim.json!.error.code]).toEqual([410, 'remote_command_expired'])
    // Aufräumen: alte Befehle verschwinden.
    h.env.clock.advance(4 * 60 * 60_000)
    sweepRemote(h.env.ctx)
    expect(h.env.ctx.db.prepare('SELECT COUNT(*) AS n FROM remote_commands').get()).toEqual({ n: 0 })
  })

  it('checks arguments, pairing, online state and the permissions of the PC', async () => {
    const h = await harness()
    const { me, pc, phone } = await paired(h, { launch: false, install: true })
    const bad = await command(h, phone, pc.id, { type: 'launch_instance', args: { instanceId: '../../evil' }, idempotencyKey: key() })
    expect([bad.status, bad.json!.error.code]).toEqual([400, 'invalid_args'])
    expect((await command(h, phone, pc.id, { type: 'install_pack_code', args: { code: 'nope' }, idempotencyKey: key() })).json!.error.code).toBe('invalid_args')
    expect((await command(h, phone, pc.id, { type: 'format_disk', idempotencyKey: key() })).json!.error.code).toBe('invalid_request')
    const off = await command(h, phone, pc.id, { type: 'launch_instance', args: { instanceId: 'fabric-1-21' }, idempotencyKey: key() })
    expect([off.status, off.json!.error.code]).toEqual([403, 'remote_command_disabled'])
    const pack = await command(h, phone, pc.id, { type: 'install_pack_code', args: { code: 'trs-7k2m-q9xa' }, idempotencyKey: key() })
    expect(pack.status).toBe(202)
    const claim = await req(h, 'POST', `/v1/remote/commands/${pack.json!.command.id}/claim`, { token: me.token, device: pc })
    expect(claim.json!.command.args).toEqual({ code: 'TRS-7K2M-Q9XA' })
    // PC meldet „aus“ → offline (Ping geht trotzdem). Kurz warten: höchstens 5 Befehle in 5 s.
    h.env.clock.advance(5000)
    await status(h, pc, { online: false })
    expect((await command(h, phone, pc.id, { type: 'install_pack_code', args: { code: 'TRS-7K2M-Q9XA' }, idempotencyKey: key() })).json!.error.code).toBe('remote_offline')
    expect((await command(h, phone, pc.id, { type: 'ping', idempotencyKey: key() })).status).toBe(202)
    // PC ohne Kopplung / anderes Konto.
    const stranger = await login(h.env, 'Eve')
    const evePhone = await register(h, stranger.token, 'phone')
    expect((await command(h, evePhone, pc.id, { type: 'ping', idempotencyKey: key() })).json!.error.code).toBe('remote_peer_not_found')
    // Nur Handys schicken Befehle.
    expect((await req(h, 'POST', `/v1/remote/${pc.id}/commands`, { token: me.token, device: pc, body: { type: 'ping', idempotencyKey: key() } })).json!.error.code).toBe('remote_wrong_device')
  })

  it('rate limits commands per phone', async () => {
    const h = await harness()
    const { pc, phone } = await paired(h)
    const statuses: number[] = []
    for (let i = 0; i < 6; i++) statuses.push((await command(h, phone, pc.id, { type: 'ping', idempotencyKey: key() })).status)
    expect(statuses).toEqual([202, 202, 202, 202, 202, 429])
  })
})
