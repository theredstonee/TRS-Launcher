import assert from 'node:assert/strict'
import net from 'node:net'
import { after, afterEach, describe, it } from 'node:test'
import { PREAMBLE, T, frame } from '../src/protocol.ts'
import type { Relay } from '../src/relay.ts'
import { Client, GUEST, GUEST2, OTHER_SECRET, ROOM, closeAll, hostToken, relay, sleep, token } from './helpers.ts'

afterEach(closeAll)
after(closeAll)

async function host(r: Relay, tok = hostToken()): Promise<{ control: Client, welcome: Record<string, unknown> }> {
  const control = await Client.connect(r.tcpPort)
  control.hello(T.HOST_HELLO, tok)
  const f = await control.frame()
  assert.equal(f.type, T.WELCOME, f.type === T.ERROR ? f.payload.toString() : '')
  return { control, welcome: JSON.parse(f.payload.toString()) as Record<string, unknown> }
}

/** Gast verbinden, GUEST_OPEN abwarten, Host-Datenverbindung koppeln. */
async function paired(r: Relay, control: Client, guestUuid = GUEST) {
  const guest = await Client.connect(r.tcpPort)
  guest.hello(T.GUEST_HELLO, token({ u: guestUuid }))
  const open = await control.frame()
  assert.equal(open.type, T.GUEST_OPEN)
  const pairId = open.payload.subarray(0, 16)
  assert.equal(open.payload.subarray(16).toString('hex'), guestUuid)
  const data = await Client.connect(r.tcpPort)
  data.hello(T.PAIR, pairId)
  assert.equal((await data.frame()).type, T.WELCOME)
  assert.equal((await guest.frame()).type, T.WELCOME)
  return { guest, data, pairId }
}

describe('tcp handshake and piping', () => {
  it('pairs a guest with a host data connection and pipes raw bytes both ways', async () => {
    const r = await relay()
    const { control, welcome } = await host(r, hostToken({ m: 4 }))
    assert.deepEqual(welcome, { room: ROOM, maxGuests: 3, pairTimeoutMs: 10_000, idleTimeoutMs: 45_000 })

    const guest = await Client.connect(r.tcpPort)
    // Hello und erste Spiel-Bytes in einem Stück: werden bis zur Kopplung gepuffert.
    guest.write(Buffer.concat([PREAMBLE, frame(T.GUEST_HELLO, token()), Buffer.from('early-bytes')]))
    const open = await control.frame()
    assert.equal(open.type, T.GUEST_OPEN)
    assert.equal(open.payload.length, 32)
    assert.equal(open.payload.subarray(16).toString('hex'), GUEST)
    const pairId = open.payload.subarray(0, 16)

    const data = await Client.connect(r.tcpPort)
    data.write(Buffer.concat([PREAMBLE, frame(T.PAIR, pairId), Buffer.from('from-host')]))
    const w = await data.frame()
    assert.equal(w.type, T.WELCOME)
    assert.deepEqual(JSON.parse(w.payload.toString()), { room: ROOM })
    assert.equal((await guest.frame()).type, T.WELCOME)
    assert.equal((await data.bytes(11)).toString(), 'early-bytes')
    assert.equal((await guest.bytes(9)).toString(), 'from-host')

    const big = Buffer.alloc(200_000, 7)
    guest.write(big)
    assert.ok((await data.bytes(big.length)).equals(big))
    data.write('pong')
    assert.equal((await guest.bytes(4)).toString(), 'pong')

    guest.destroy()
    const closed = await control.frame()
    assert.equal(closed.type, T.GUEST_CLOSED)
    assert.ok(closed.payload.equals(pairId))
    await data.closed()
    assert.equal(r.stats().pairs, 0)
  })

  it('answers PING with PONG on the control connection', async () => {
    const r = await relay()
    const { control } = await host(r)
    control.write(frame(T.PING, Buffer.from([1, 2, 3])))
    const f = await control.frame()
    assert.equal(f.type, T.PONG)
    assert.deepEqual([...f.payload], [1, 2, 3])
    control.write(frame(T.PING, Buffer.alloc(9)))
    assert.equal(await control.error(), 'bad_frame')
  })

  it('rejects bad preambles, oversized frames and unknown hello types', async () => {
    const r = await relay()
    const a = await Client.connect(r.tcpPort)
    a.write('GET / HTTP/1.1\r\n')
    assert.equal(await a.error(), 'bad_preamble')
    const b = await Client.connect(r.tcpPort)
    const h = Buffer.from([T.HOST_HELLO, 0x10, 0x00]) // 4096 > 1024
    b.write(Buffer.concat([PREAMBLE, h]))
    assert.equal(await b.error(), 'bad_frame')
    const c = await Client.connect(r.tcpPort)
    c.hello(0x55, 'x')
    assert.equal(await c.error(), 'bad_frame')
    const d = await Client.connect(r.tcpPort)
    d.hello(T.PAIR, Buffer.alloc(16))
    assert.equal(await d.error(), 'unknown_pair')
  })

  it('rejects invalid tokens: signature, expiry, role, garbage; accepts rotated secrets', async () => {
    const r = await relay({ secrets: [OTHER_SECRET, 'test-relay-secret-0123456789abcdef-0123456789'] })
    const now = Math.floor(Date.now() / 1000)
    const cases: [number, string, string][] = [
      [T.HOST_HELLO, hostToken().replace(/.$/, (c) => (c === 'A' ? 'Q' : 'A')), 'bad_token'],
      [T.HOST_HELLO, hostToken({ iat: now - 200, exp: now - 100 }), 'expired'],
      [T.HOST_HELLO, token(), 'bad_token'], // Gast-Token als Host
      [T.GUEST_HELLO, hostToken(), 'bad_token'], // Host-Token als Gast
      [T.HOST_HELLO, 'garbage', 'bad_token'],
      [T.HOST_HELLO, 'trä', 'bad_token'],
    ]
    for (const [type, tok, code] of cases) {
      const c = await Client.connect(r.tcpPort)
      c.hello(type, tok)
      assert.equal(await c.error(), code, tok)
    }
    // Signiert mit dem zweiten Schlüssel (Rotation) → gültig.
    await host(r)
  })

  it('refuses guests without a live host (host_offline) and times out unpaired guests', async () => {
    const r = await relay({ pairTimeoutMs: 200 })
    const lonely = await Client.connect(r.tcpPort)
    lonely.hello(T.GUEST_HELLO, token())
    assert.equal(await lonely.error(), 'host_offline')

    // Token eines anderen Hosts für denselben Raum.
    const { control } = await host(r)
    const wrong = await Client.connect(r.tcpPort)
    wrong.hello(T.GUEST_HELLO, token({ h: 'dd'.repeat(16) }))
    assert.equal(await wrong.error(), 'host_offline')

    const g = await Client.connect(r.tcpPort)
    g.hello(T.GUEST_HELLO, token())
    const open = await control.frame()
    assert.equal(open.type, T.GUEST_OPEN)
    assert.equal(await g.error(1000), 'host_timeout')
    const closed = await control.frame()
    assert.equal(closed.type, T.GUEST_CLOSED)
    assert.ok(closed.payload.equals(open.payload.subarray(0, 16)))
  })

  it('enforces room size and connections per guest', async () => {
    const r = await relay({ maxConnsPerGuest: 2 })
    const { control } = await host(r, hostToken({ m: 2 })) // 1 Gast
    await paired(r, control, GUEST)
    const second = await Client.connect(r.tcpPort)
    second.hello(T.GUEST_HELLO, token({ u: GUEST2, m: 2 }))
    assert.equal(await second.error(), 'room_full')
    // Derselbe Gast darf eine zweite Verbindung öffnen (z. B. Ping + Login) …
    await paired(r, control, GUEST)
    // … aber nicht mehr als maxConnsPerGuest.
    const third = await Client.connect(r.tcpPort)
    third.hello(T.GUEST_HELLO, token())
    assert.equal(await third.error(), 'too_many_connections')
  })

  it('caps players at MAX_ROOM_PLAYERS even if the token allows more', async () => {
    const r = await relay({ maxRoomPlayers: 2 })
    const { control, welcome } = await host(r, hostToken({ m: 10 }))
    assert.equal(welcome.maxGuests, 1)
    await paired(r, control, GUEST)
    const c = await Client.connect(r.tcpPort)
    c.hello(T.GUEST_HELLO, token({ u: GUEST2 }))
    assert.equal(await c.error(), 'room_full')
  })

  it('replaces an old control connection and closes its guests', async () => {
    const r = await relay()
    const first = await host(r)
    const { guest, data } = await paired(r, first.control)
    const second = await host(r)
    assert.equal(await first.control.error(), 'replaced')
    await guest.closed()
    await data.closed()
    // Der neue Host bekommt neue Gäste.
    await paired(r, second.control)
  })

  it('kicks by uuid (and refuses the uuid afterwards) and closes single guests', async () => {
    const r = await relay()
    const { control } = await host(r)
    const a = await paired(r, control, GUEST)
    control.write(frame(T.KICK, Buffer.from(GUEST, 'hex')))
    await a.guest.closed()
    await a.data.closed()
    assert.equal((await control.frame()).type, T.GUEST_CLOSED)
    const again = await Client.connect(r.tcpPort)
    again.hello(T.GUEST_HELLO, token())
    assert.equal(await again.error(), 'kicked')

    // CLOSE_GUEST auf eine wartende Verbindung.
    const b = await Client.connect(r.tcpPort)
    b.hello(T.GUEST_HELLO, token({ u: GUEST2 }))
    const open = await control.frame()
    control.write(frame(T.CLOSE_GUEST, open.payload.subarray(0, 16)))
    assert.equal(await b.error(), 'kicked')
  })

  it('closes all guests when the host control connection goes away', async () => {
    const r = await relay()
    const { control } = await host(r)
    const { guest, data } = await paired(r, control)
    control.destroy()
    await guest.closed()
    await data.closed()
    await sleep(50)
    assert.equal(r.stats().rooms, 0)
  })
})

describe('tcp limits', () => {
  it('limits concurrent connections per IP and the total', async () => {
    const r = await relay({ maxConnsPerIp: 2 })
    await Client.connect(r.tcpPort)
    await Client.connect(r.tcpPort)
    const c = await Client.connect(r.tcpPort)
    assert.equal(await c.error(), 'too_many_connections')
    const r2 = await relay({ maxConnections: 1 })
    await Client.connect(r2.tcpPort)
    const d = await Client.connect(r2.tcpPort)
    assert.equal(await d.error(), 'server_full')
  })

  it('rate-limits new connections and repeated handshake failures per IP', async () => {
    const r = await relay({ newConnsPerIpPerMin: 3 })
    for (let i = 0; i < 3; i++) (await Client.connect(r.tcpPort)).destroy()
    const c = await Client.connect(r.tcpPort)
    assert.equal(await c.error(), 'rate_limited')

    const r2 = await relay({ handshakeFailsPerIp: 2 })
    for (let i = 0; i < 2; i++) {
      const x = await Client.connect(r2.tcpPort)
      x.hello(T.HOST_HELLO, 'nope')
      assert.equal(await x.error(), 'bad_token')
    }
    const y = await Client.connect(r2.tcpPort)
    assert.equal(await y.error(), 'rate_limited')
  })

  it('throttles a room to its bandwidth budget', async () => {
    // 0,8 Mbit/s = 100 000 B/s, Puffer 25 000 B → 150 000 B brauchen ≥ 1,25 s.
    const r = await relay({ roomBandwidthMbit: 0.8 })
    const { control } = await host(r)
    const { guest, data } = await paired(r, control)
    const payload = Buffer.alloc(150_000, 1)
    const t0 = Date.now()
    guest.write(payload)
    await data.bytes(payload.length, 5000)
    const elapsed = Date.now() - t0
    assert.ok(elapsed >= 1000, `too fast: ${elapsed} ms`)
    assert.ok(elapsed < 4000, `too slow: ${elapsed} ms`)
  })

  it('closes idle handshakes, silent control connections and idle pipes', async () => {
    const r = await relay({ handshakeTimeoutMs: 150, controlIdleMs: 300, pipeIdleMs: 300 })
    const idle = await Client.connect(r.tcpPort)
    assert.equal(await idle.error(1000), 'idle_timeout')

    const { control } = await host(r)
    const { guest, data } = await paired(r, control)
    // Steuerung mit Pings bleibt offen, die stille Leitung wird geschlossen.
    const pinger = setInterval(() => control.write(frame(T.PING)), 100)
    await guest.closed(2000)
    await data.closed(2000)
    clearInterval(pinger)
    assert.equal(control.ended, false)
    // Ohne Pings: idle_timeout (vorher kommen noch PONG/GUEST_CLOSED-Frames).
    let code = ''
    for (let i = 0; i < 20 && code === ''; i++) {
      const f = await control.frame(2000)
      if (f.type === T.ERROR) code = f.payload.toString()
    }
    assert.equal(code, 'idle_timeout')
  })
})

describe('shutdown', () => {
  it('tells hosts, closes everything and stops accepting', async () => {
    const r = await relay({ shutdownGraceMs: 1000 })
    const { control } = await host(r)
    const { guest } = await paired(r, control)
    const t0 = Date.now()
    const closing = r.close()
    assert.equal(await control.error(), 'shutting_down')
    await guest.closed()
    await closing
    assert.ok(Date.now() - t0 < 2000)
    await assert.rejects(new Promise((resolve, reject) => {
      const s = net.connect(r.tcpPort, '127.0.0.1', () => resolve(s))
      s.once('error', reject)
    }))
  })
})
