import assert from 'node:assert/strict'
import { after, afterEach, describe, it } from 'node:test'
import { WakeApi } from '../src/wake/api.ts'
import { type Packet, packet, writeLong } from '../src/wake/mc.ts'
import { MSG } from '../src/wake/texts.ts'
import { sleep } from './helpers.ts'
import { DOMAIN, FakeApi, HOSTNAME, McClient, UUID, WAKE_SECRET, closeWake, handshake, hostInfo, loginStart, wakeSetup } from './wake-helpers.ts'

afterEach(closeWake)
after(closeWake)

const FOOTER = '§7Free server: §ctrs-launcher.de §8× §bcraftinghosting.free'

async function status(port: number, address = HOSTNAME, protocol = 769) {
  const c = await McClient.connect(port)
  c.write(handshake(protocol, address, 1), packet(0x00))
  const p = await c.packet()
  assert.equal(p.id, 0x00)
  const json = JSON.parse(p.data.string(32767)) as {
    version: { name: string, protocol: number }, players: { max: number, online: number }, description: { text: string }
  }
  return { c, json }
}

const line1 = (text: string) => text.split('\n')[0]

/** Login-Disconnect lesen (JSON-Text). */
async function loginKickText(c: McClient): Promise<string> {
  const p = await c.packet()
  assert.equal(p.id, 0x00)
  return (JSON.parse(p.data.string(262144)) as { text: string }).text
}

/** Konfigurations-Disconnect lesen (NBT-String). */
function nbtText(p: Packet): string {
  assert.equal(p.data.u8(), 0x08)
  const len = p.data.u16()
  return p.data.bytes(len).toString('utf8')
}

describe('wake: server list', () => {
  it('shows each state and answers ping', async () => {
    const { api, port } = await wakeSetup()
    const cases: [unknown, number, string][] = [
      [hostInfo('offline'), 200, '§a● §fSchläft – Beitreten startet ihn'],
      [hostInfo('stopping'), 200, '§a● §fSchläft – Beitreten startet ihn'],
      [hostInfo('starting'), 200, '§e● §fStartet …'],
      [hostInfo('cooldown', { cooldownUntil: '2026-11-18T19:30:00Z' }), 200, '§c● §fPause bis 20:30'],
      [hostInfo('unavailable'), 200, '§c● §fNicht verfügbar'],
      [hostInfo('running', { target: { host: 'node.example', port: 25570 } }), 200, '§a● §fLäuft'],
      [{ error: { code: 'unknown_host' } }, 404, '§c● §fUnbekannter Server'],
      [{ error: { code: 'internal' } }, 500, '§c● §fNicht verfügbar'],
    ]
    let i = 0
    for (const [body, code, want] of cases) {
      const host = `s${i++}.${DOMAIN}`
      api.setHost(host, body, code)
      const { c, json } = await status(port, host, 767)
      assert.equal(line1(json.description.text), want, host)
      assert.equal(json.description.text.split('\n')[1], FOOTER)
      assert.deepEqual(json.version, { name: 'TRS Wake', protocol: 767 })
      assert.equal(json.players.online, 0)
      assert.equal(json.players.max, code === 200 ? 20 : 0)
      c.write(packet(0x01, writeLong(1234567n)))
      const pong = await c.packet()
      assert.equal(pong.id, 0x01)
      assert.equal(pong.data.long(), 1234567n)
      await c.closed()
    }
  })

  it('foreign addresses never reach the API', async () => {
    const { api, port } = await wakeSetup()
    for (const addr of ['135.125.185.232', 'trs-launcher.de', 'x.example.com']) {
      const { c, json } = await status(port, addr, 47)
      assert.equal(line1(json.description.text), '§c● §fUnbekannter Server')
      assert.equal(json.version.protocol, 47)
      c.destroy()
    }
    assert.equal(api.resolves, 0)
  })

  it('caches resolve for ~10 s and asks once for parallel pings', async () => {
    const { api, port } = await wakeSetup()
    api.setHost(HOSTNAME, hostInfo('offline'))
    api.delayMs = 100
    const all = await Promise.all([1, 2, 3, 4, 5].map(() => status(port)))
    for (const { c } of all) c.destroy()
    const again = await status(port)
    again.c.destroy()
    assert.equal(api.resolves, 1)
  })

  it('answers the legacy 0xFE ping', async () => {
    const { port } = await wakeSetup()
    const c = await McClient.connect(port)
    c.write(Buffer.from([0xfe, 0x01, 0xfa]))
    await c.closed()
    assert.equal(c.raw[0], 0xff)
    const text = Buffer.from(c.raw.subarray(3)).swap16().toString('utf16le')
    assert.ok(text.startsWith('§1\u0000127\u0000TRS Wake'))
  })
})

describe('wake: login before 1.20.5 (disconnect message)', () => {
  it('wakes a sleeping server and tells the player to reconnect', async () => {
    const { api, listener, port } = await wakeSetup()
    api.setHost(HOSTNAME, hostInfo('offline'))
    const c = await McClient.connect(port)
    c.write(handshake(47, 'ABC.trs-launcher.de.\0FML\0', 2), loginStart(47, 'Steve'))
    assert.equal(await loginKickText(c), MSG.starting)
    await c.closed()
    assert.deepEqual(api.wakes, [{ host: HOSTNAME, player: { name: 'Steve' } }])
    assert.equal(listener.wakes, 1)
  })

  it('stale DNS: running server → reconnect, no wake', async () => {
    const { api, port } = await wakeSetup()
    api.setHost(HOSTNAME, hostInfo('running', { target: { host: 'node.example', port: 25570 } }))
    const c = await McClient.connect(port)
    c.write(handshake(340, HOSTNAME, 2), loginStart(340, 'Steve'))
    assert.equal(await loginKickText(c), MSG.running)
    assert.equal(api.wakes.length, 0)
  })

  it('maps wake errors to messages', async () => {
    const { api, port } = await wakeSetup()
    api.setHost(HOSTNAME, hostInfo('offline'))
    const cases: [number, unknown, string][] = [
      [409, { error: { code: 'not_allowed' } }, '§cNur Freunde des Besitzers können diesen Server starten.'],
      [409, { error: { code: 'cooldown', details: { cooldownUntil: '2026-11-18T19:30:00Z' } } }, '§cPflichtpause bis 20:30.'],
      [409, { error: { code: 'eula_required' } }, '§cDer Besitzer muss den Server einmal im TRS Launcher starten.'],
      [409, { error: { code: 'unavailable' } }, '§cServer gerade nicht verfügbar.'],
      [500, { error: { code: 'internal' } }, '§cServer gerade nicht verfügbar.'],
      [429, { error: { code: 'rate_limited' } }, '§cBitte kurz warten.'],
    ]
    for (const [code, body, want] of cases) {
      api.wake = () => ({ status: code, body })
      const c = await McClient.connect(port)
      c.write(handshake(5, HOSTNAME, 2), loginStart(5, 'Alex'))
      assert.equal(await loginKickText(c), want)
      c.destroy()
    }
  })

  it('unknown host → disconnect, also for foreign addresses', async () => {
    const { api, port } = await wakeSetup()
    for (const addr of [HOSTNAME, 'example.com']) {
      const c = await McClient.connect(port)
      c.write(handshake(47, addr, 2), loginStart(47, 'Steve'))
      assert.equal(await loginKickText(c), MSG.unknown)
      c.destroy()
    }
    assert.equal(api.wakes.length, 0)
  })
})

describe('wake: login 1.20.5+ (wait and transfer)', () => {
  it('login success → acknowledged → keep-alive → transfer once running', async () => {
    const { api, listener, port } = await wakeSetup()
    api.setHost(HOSTNAME, hostInfo('offline'))
    const c = await McClient.connect(port)
    c.write(handshake(769, HOSTNAME, 2), loginStart(769, 'Steve'))
    const ok = await c.packet()
    assert.equal(ok.id, 0x02)
    assert.equal(ok.data.uuid(), UUID)
    assert.equal(ok.data.string(16), 'Steve')
    assert.equal(ok.data.varInt(), 0)
    assert.equal(ok.data.left, 0)
    assert.deepEqual(api.wakes, [{ host: HOSTNAME, player: { name: 'Steve', uuid: '01234567-89ab-cdef-0123-456789abcdef' } }])

    // Login Acknowledged, danach Client Information/Brand wie ein echter Client
    c.write(packet(0x03), packet(0x00, Buffer.alloc(10)), packet(0x02, Buffer.alloc(20)))
    const ka = await c.packet()
    assert.equal(ka.id, 0x04)
    c.write(packet(0x04, writeLong(ka.data.long())))
    assert.equal(listener.waiting.size, 1)

    api.setHost(HOSTNAME, hostInfo('running', { target: { host: 'node1.craftinghosting.free', port: 25570 } }))
    const t = await c.packetId(0x0b, 3000)
    assert.equal(t.data.string(32767), 'node1.craftinghosting.free')
    assert.equal(t.data.varInt(), 25570)
    assert.equal(listener.transfers, 1)
    assert.equal(listener.waiting.size, 0)
    c.destroy()
  })

  it('1.20.5/1.21 (766/767): login success carries the strict flag', async () => {
    const { api, port } = await wakeSetup()
    api.setHost(HOSTNAME, hostInfo('offline'))
    const c = await McClient.connect(port)
    c.write(handshake(767, HOSTNAME, 2), loginStart(767, 'Steve'))
    const ok = await c.packet()
    assert.equal(ok.id, 0x02)
    ok.data.uuid()
    ok.data.string(16)
    assert.equal(ok.data.varInt(), 0)
    assert.equal(ok.data.bool(), false)
    assert.equal(ok.data.left, 0)
    c.destroy()
  })

  it('already running (stale DNS) → transfer right after acknowledged', async () => {
    const { api, port } = await wakeSetup()
    api.setHost(HOSTNAME, hostInfo('running', { target: { host: 'node.example', port: 25599 } }))
    const c = await McClient.connect(port)
    c.write(handshake(774, HOSTNAME, 2), loginStart(774, 'Steve'), packet(0x03))
    assert.equal((await c.packet()).id, 0x02)
    const t = await c.packet()
    assert.equal(t.id, 0x0b)
    assert.equal(t.data.string(255), 'node.example')
    assert.equal(t.data.varInt(), 25599)
    assert.equal(api.wakes.length, 0)
  })

  it('gives up after the timeout with an NBT disconnect', async () => {
    const { api, port } = await wakeSetup({ giveUpMs: 300 })
    api.setHost(HOSTNAME, hostInfo('starting'))
    const c = await McClient.connect(port)
    c.write(handshake(769, HOSTNAME, 2), loginStart(769, 'Steve'), packet(0x03))
    assert.equal((await c.packet()).id, 0x02)
    const d = await c.packetId(0x02, 2000)
    assert.equal(nbtText(d), MSG.giveUp)
    await c.closed()
  })

  it('stops waiting when the server goes into cooldown', async () => {
    const { api, port } = await wakeSetup()
    api.setHost(HOSTNAME, hostInfo('offline'))
    const c = await McClient.connect(port)
    c.write(handshake(769, HOSTNAME, 2), loginStart(769, 'Steve'), packet(0x03))
    assert.equal((await c.packet()).id, 0x02)
    api.setHost(HOSTNAME, hostInfo('cooldown', { cooldownUntil: '2026-11-18T19:30:00Z' }))
    const d = await c.packetId(0x02, 2000)
    assert.equal(nbtText(d), '§cPflichtpause bis 20:30.')
  })

  it('friends joining together: rate-limited wake while starting → still waits', async () => {
    const { api, port } = await wakeSetup()
    api.setHost(HOSTNAME, hostInfo('offline'))
    api.wake = () => {
      api.setHost(HOSTNAME, hostInfo('starting'))
      return { status: 429, body: { error: { code: 'rate_limited' } } }
    }
    const c = await McClient.connect(port)
    c.write(handshake(769, HOSTNAME, 2), loginStart(769, 'Alex'))
    assert.equal((await c.packet()).id, 0x02)
    c.destroy()
  })

  it('wake refused → login disconnect, no login success', async () => {
    const { api, port } = await wakeSetup()
    api.setHost(HOSTNAME, hostInfo('offline'))
    api.wake = () => ({ status: 409, body: { error: { code: 'not_allowed' } } })
    const c = await McClient.connect(port)
    c.write(handshake(769, HOSTNAME, 2), loginStart(769, 'Steve'))
    assert.equal(await loginKickText(c), MSG.notAllowed)
  })

  it('too many waiting players → falls back to the reconnect message', async () => {
    const { api, port } = await wakeSetup({ maxWaiting: 0 })
    api.setHost(HOSTNAME, hostInfo('offline'))
    const c = await McClient.connect(port)
    c.write(handshake(769, HOSTNAME, 2), loginStart(769, 'Steve'))
    assert.equal(await loginKickText(c), MSG.starting)
    assert.equal(api.wakes.length, 1)
  })

  it('no acknowledged → closed after the ack timeout', async () => {
    const { api, listener, port } = await wakeSetup({ ackTimeoutMs: 150 })
    api.setHost(HOSTNAME, hostInfo('offline'))
    const c = await McClient.connect(port)
    c.write(handshake(769, HOSTNAME, 2), loginStart(769, 'Steve'))
    assert.equal((await c.packet()).id, 0x02)
    await c.closed(1000)
    await sleep(20)
    assert.equal(listener.waiting.size, 0)
  })

  it('shutdown sends waiting players a message', async () => {
    const { api, listener, port } = await wakeSetup()
    api.setHost(HOSTNAME, hostInfo('starting'))
    const c = await McClient.connect(port)
    c.write(handshake(769, HOSTNAME, 2), loginStart(769, 'Steve'), packet(0x03))
    assert.equal((await c.packet()).id, 0x02)
    for (let i = 0; i < 100 && listener.waiting.size === 0; i++) await sleep(10)
    await sleep(30)
    const closing = listener.close(500)
    const d = await c.packetId(0x02, 2000)
    assert.equal(nbtText(d), MSG.shutdown)
    await closing
    assert.equal(listener.conns.size, 0)
  })
})

describe('wake: limits', () => {
  it('closes silent connections after the handshake timeout', async () => {
    const { port, listener } = await wakeSetup({ handshakeTimeoutMs: 100 })
    const c = await McClient.connect(port)
    await c.closed(1000)
    await sleep(10)
    assert.equal(listener.conns.size, 0)
  })

  it('closes on oversized or broken packets', async () => {
    const { port } = await wakeSetup({ maxPacket: 64 })
    const big = await McClient.connect(port)
    big.write(packet(0x00, Buffer.alloc(100)))
    await big.closed()
    const junk = await McClient.connect(port)
    junk.write(Buffer.from([0x05, 0x07, 0, 0, 0, 0]))
    await junk.closed()
  })

  it('limits new connections per IP and minute', async () => {
    const { port, listener } = await wakeSetup({ newConnsPerIpPerMin: 3 })
    const cs = await Promise.all([1, 2, 3].map(() => McClient.connect(port)))
    const fourth = await McClient.connect(port)
    await fourth.closed()
    assert.equal(listener.rejected, 1)
    for (const c of cs) assert.equal(c.ended, false)
    for (const c of cs) c.destroy()
  })

  it('limits concurrent connections per IP', async () => {
    const { port, listener } = await wakeSetup({ maxConnsPerIp: 2 })
    const a = await McClient.connect(port)
    const b = await McClient.connect(port)
    const c = await McClient.connect(port)
    await c.closed()
    a.destroy()
    await a.closed()
    await sleep(20)
    const d = await McClient.connect(port)
    await sleep(50)
    assert.equal(d.ended, false)
    assert.equal(listener.rejected, 1)
    b.destroy()
    d.destroy()
  })
})

describe('wake: API client', () => {
  it('signs requests; a wrong secret is rejected by the API', async () => {
    const api = new FakeApi()
    const baseUrl = await api.start()
    try {
      api.setHost(HOSTNAME, hostInfo('offline'))
      const opts = { baseUrl, cacheMs: 10_000, timeoutMs: 1000, now: Date.now, log: () => {} }
      const good = new WakeApi({ ...opts, secret: WAKE_SECRET })
      const r = await good.resolve(HOSTNAME)
      assert.ok(r.ok && r.info.state === 'offline')
      assert.deepEqual(await good.wake(HOSTNAME, { name: 'Steve', uuid: null }), { ok: true, state: 'starting' })
      const bad = new WakeApi({ ...opts, secret: 'wrong-secret-0123456789abcdef-0123456789' })
      assert.deepEqual(await bad.resolve(HOSTNAME), { ok: false, code: 'error' })
      assert.equal(api.badSigs, 1)
    } finally {
      await api.close()
    }
  })

  it('cache expires; bad API answers count as errors', async () => {
    const api = new FakeApi()
    const baseUrl = await api.start()
    try {
      let t = 0
      api.setHost(HOSTNAME, hostInfo('offline'))
      api.setHost(`bad.${DOMAIN}`, { name: 'bad', state: 'exploded' })
      const w = new WakeApi({ baseUrl, secret: WAKE_SECRET, cacheMs: 10_000, timeoutMs: 1000, now: () => Date.now() + t, log: () => {} })
      await w.resolve(HOSTNAME)
      t += 9000
      await w.resolve(HOSTNAME)
      assert.equal(api.resolves, 1)
      t += 2000
      await w.resolve(HOSTNAME)
      assert.equal(api.resolves, 2)
      assert.deepEqual(await w.resolve(`bad.${DOMAIN}`), { ok: false, code: 'error' })
    } finally {
      await api.close()
    }
  })

  it('times out a hanging API', async () => {
    const api = new FakeApi()
    const baseUrl = await api.start()
    try {
      api.setHost(HOSTNAME, hostInfo('offline'))
      api.delayMs = 500
      const w = new WakeApi({ baseUrl, secret: WAKE_SECRET, cacheMs: 10_000, timeoutMs: 100, now: Date.now, log: () => {} })
      const started = Date.now()
      assert.deepEqual(await w.resolve(HOSTNAME), { ok: false, code: 'error' })
      assert.ok(Date.now() - started < 400)
    } finally {
      await api.close()
    }
  })
})
