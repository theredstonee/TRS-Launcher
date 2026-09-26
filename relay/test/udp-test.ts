import assert from 'node:assert/strict'
import { randomBytes } from 'node:crypto'
import { after, afterEach, describe, it } from 'node:test'
import { T, frame } from '../src/protocol.ts'
import { bindingRequest, parseBindingResponse } from '../src/stun.ts'
import { U, udpPacket } from '../src/udp.ts'
import { Client, GUEST, GUEST2, HOST, closeAll, hostToken, relay, sleep, token, UdpClient } from './helpers.ts'

const sockets: UdpClient[] = []
async function udp(): Promise<UdpClient> {
  const c = await UdpClient.open()
  sockets.push(c)
  return c
}
afterEach(async () => {
  for (const s of sockets.splice(0)) s.close()
  await closeAll()
})
after(closeAll)

async function bind(c: UdpClient, port: number, tok: string): Promise<Buffer> {
  c.send(udpPacket(U.BIND, Buffer.from(tok)), port)
  const m = await c.next()
  assert.equal(m.subarray(0, 4).toString(), 'TRSU')
  assert.equal(m[4], U.BOUND, m[4] === U.ERROR ? m.subarray(5).toString() : '')
  assert.equal(m.length, 13)
  return m.subarray(5)
}

describe('STUN binding responder', () => {
  it('answers a binding request with XOR-MAPPED-ADDRESS, MAPPED-ADDRESS and FINGERPRINT', async () => {
    const r = await relay()
    const c = await udp()
    const txid = randomBytes(12)
    c.send(bindingRequest(txid), r.udpPort)
    const res = await c.next()
    assert.equal(res.length, 52)
    const p = parseBindingResponse(res)!
    assert.ok(p.txid.equals(txid))
    assert.deepEqual(p.xorMapped, { address: '127.0.0.1', port: c.port })
    assert.deepEqual(p.mapped, { address: '127.0.0.1', port: c.port })
    assert.equal(p.fingerprintOk, true)
  })

  it('ignores malformed requests and rate-limits per IP', async () => {
    const r = await relay({ stunPerIpPerSec: 3 })
    const c = await udp()
    const bad = bindingRequest(randomBytes(12))
    bad.writeUInt16BE(8, 2) // Länge passt nicht
    c.send(bad, r.udpPort)
    const indication = bindingRequest(randomBytes(12))
    indication.writeUInt16BE(0x0011, 0)
    c.send(indication, r.udpPort)
    assert.ok(await c.silent(150))
    for (let i = 0; i < 6; i++) c.send(bindingRequest(randomBytes(12)), r.udpPort)
    await sleep(200)
    assert.equal(c.inbox.length, 3)
  })
})

describe('TRS UDP relay', () => {
  it('forwards datagrams between host and guest, answers pings and unbinds', async () => {
    const r = await relay()
    const h = await udp()
    const g = await udp()
    // Ohne Host kein Gast.
    g.send(udpPacket(U.BIND, Buffer.from(token())), r.udpPort)
    const err = await g.next()
    assert.equal(err[4], U.ERROR)
    assert.equal(err.subarray(5).toString(), 'host_offline')

    const hk = await bind(h, r.udpPort, hostToken())
    const gk = await bind(g, r.udpPort, token())

    g.send(udpPacket(U.DATA, gk, Buffer.from('hi host')), r.udpPort)
    const toHost = await h.next()
    assert.equal(toHost[4], U.DATA_FROM)
    assert.equal(toHost.subarray(5, 21).toString('hex'), GUEST)
    assert.equal(toHost.subarray(21).toString(), 'hi host')

    h.send(udpPacket(U.DATA, hk, Buffer.from(GUEST, 'hex'), Buffer.from('hi guest')), r.udpPort)
    const toGuest = await g.next()
    assert.equal(toGuest[4], U.DATA_FROM)
    assert.equal(toGuest.subarray(5, 21).toString('hex'), HOST)
    assert.equal(toGuest.subarray(21).toString(), 'hi guest')

    g.send(udpPacket(U.PING, gk), r.udpPort)
    const pong = await g.next()
    assert.equal(pong[4], U.PONG)
    assert.ok(pong.subarray(5).equals(gk))

    // Fremde Adresse mit gestohlenem Schlüssel → still verworfen.
    const thief = await udp()
    thief.send(udpPacket(U.DATA, gk, Buffer.from('spoof')), r.udpPort)
    thief.send(udpPacket(U.PING, gk), r.udpPort)
    assert.ok(await h.silent(150))
    assert.ok(await thief.silent(0))

    // Zu große Nutzlast → verworfen.
    g.send(udpPacket(U.DATA, gk, Buffer.alloc(1201)), r.udpPort)
    assert.ok(await h.silent(150))

    g.send(udpPacket(U.UNBIND, gk), r.udpPort)
    await sleep(50)
    g.send(udpPacket(U.DATA, gk, Buffer.from('gone')), r.udpPort)
    assert.ok(await h.silent(150))
    assert.equal(r.registry.udpByKey.size, 1)
  })

  it('shares the room size with TCP guests and removes kicked guests', async () => {
    const r = await relay()
    const control = await Client.connect(r.tcpPort)
    control.hello(T.HOST_HELLO, hostToken({ m: 2 }))
    assert.equal((await control.frame()).type, T.WELCOME)
    const h = await udp()
    const g = await udp()
    const g2 = await udp()
    await bind(h, r.udpPort, hostToken({ m: 2 }))
    const gk = await bind(g, r.udpPort, token({ m: 2 }))
    g2.send(udpPacket(U.BIND, Buffer.from(token({ u: GUEST2, m: 2 }))), r.udpPort)
    assert.equal((await g2.next()).subarray(5).toString(), 'room_full')

    control.write(frame(T.KICK, Buffer.from(GUEST, 'hex')))
    await sleep(100)
    g.send(udpPacket(U.DATA, gk, Buffer.from('after kick')), r.udpPort)
    assert.ok(await h.silent(150))
    g.send(udpPacket(U.BIND, Buffer.from(token({ m: 2 }))), r.udpPort)
    assert.equal((await g.next()).subarray(5).toString(), 'kicked')
  })

  it('drops datagrams over the room bandwidth budget and rejects bad tokens', async () => {
    const r = await relay({ roomBandwidthMbit: 0.1 }) // Puffer 16 KiB
    const h = await udp()
    const g = await udp()
    await bind(h, r.udpPort, hostToken())
    const gk = await bind(g, r.udpPort, token())
    for (let i = 0; i < 40; i++) g.send(udpPacket(U.DATA, gk, Buffer.alloc(1000)), r.udpPort)
    await sleep(300)
    assert.ok(h.inbox.length > 5 && h.inbox.length < 30, `received ${h.inbox.length}`)

    const x = await udp()
    x.send(udpPacket(U.BIND, Buffer.from('trsr1.bogus.token')), r.udpPort)
    assert.equal((await x.next()).subarray(5).toString(), 'bad_token')
  })
})
