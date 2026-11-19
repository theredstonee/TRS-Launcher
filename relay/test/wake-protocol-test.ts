import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import { ConfigError } from '../src/config.ts'
import { loadWakeConfig, parseApiUrl } from '../src/wake/config.ts'
import {
  PacketReader, Reader, legacyPingResponse, modifiedUtf8, nbtString, packet, varIntSize, writeLong, writeString,
  writeUuid, writeVarInt,
} from '../src/wake/mc.ts'
import {
  NEWEST_KNOWN_PROTOCOL, configDisconnect, configKeepAlive, hostingHost, loginDisconnect, loginSuccess, normalizeHost,
  parseHandshake, parseLoginStart, transfer, transferProto,
} from '../src/wake/packets.ts'
import { berlinTime, statusJson } from '../src/wake/texts.ts'

const hex = (s: string) => Buffer.from(s.replace(/\s+/g, ''), 'hex')
const UUID = '0123456789abcdef0123456789abcdef'

describe('wake: VarInt and strings', () => {
  // Werte aus der Protokoll-Doku (wiki.vg „VarInt and VarLong“)
  const cases: [number, string][] = [
    [0, '00'], [1, '01'], [2, '02'], [127, '7f'], [128, '8001'], [255, 'ff01'], [25565, 'ddc701'],
    [2097151, 'ffff7f'], [2147483647, 'ffffffff07'], [-1, 'ffffffff0f'], [-2147483648, '8080808008'],
  ]
  for (const [n, h] of cases) {
    it(`varint ${n} ⇄ ${h}`, () => {
      assert.equal(writeVarInt(n).toString('hex'), h)
      assert.equal(varIntSize(n), h.length / 2)
      assert.equal(new Reader(hex(h)).varInt(), n)
    })
  }

  it('rejects a varint longer than 5 bytes', () => {
    assert.throws(() => new Reader(hex('ffffffffff01')).varInt())
  })

  it('strings: length prefix in bytes, limit in characters', () => {
    assert.equal(writeString('§a').toString('hex'), '03c2a761')
    assert.equal(new Reader(hex('03c2a761')).string(2), '§a')
    assert.throws(() => new Reader(writeString('abcde')).string(4))
    assert.throws(() => new Reader(hex('05616263')).string(16))
  })

  it('frames packets and splits a stream at any byte boundary', () => {
    const a = packet(0x00, writeString('hi'))
    assert.equal(a.toString('hex'), '0400026869')
    const b = packet(0x01, writeLong(42n))
    const all = Buffer.concat([a, b])
    for (let cut = 0; cut <= all.length; cut++) {
      const r = new PacketReader(1024)
      r.push(all.subarray(0, cut))
      const got: number[] = []
      for (let p = r.next(); p && p !== 'error'; p = r.next()) got.push(p.id)
      r.push(all.subarray(cut))
      for (let p = r.next(); p && p !== 'error'; p = r.next()) got.push(p.id)
      assert.deepEqual(got, [0, 1], `cut ${cut}`)
    }
  })

  it('rejects oversized, empty and over-long length prefixes', () => {
    const r = new PacketReader(16)
    r.push(packet(0x00, Buffer.alloc(16)))
    assert.equal(r.next(), 'error')
    const z = new PacketReader(16)
    z.push(hex('00'))
    assert.equal(z.next(), 'error')
    const long = new PacketReader(1 << 30)
    long.push(hex('80808001'))
    assert.equal(long.next(), 'error')
  })
})

describe('wake: NBT and legacy ping', () => {
  it('writes a nameless TAG_String in modified UTF-8', () => {
    assert.equal(nbtString('hi').toString('hex'), '0800026869')
    assert.equal(nbtString('§').toString('hex'), '080002c2a7')
    assert.equal(modifiedUtf8('\0').toString('hex'), 'c080')
    // U+1F600 als zwei Surrogate je 3 Bytes, nicht 4 Bytes wie in UTF-8
    assert.equal(modifiedUtf8('😀').toString('hex'), 'eda0bdedb880')
    assert.equal(modifiedUtf8('–').toString('hex'), 'e28093')
  })

  it('answers the legacy ping with a 0xFF kick in the 1.4+ format', () => {
    const b = legacyPingResponse('M', 0, 5)
    const text = '§1\u0000127\u0000TRS Wake\u0000M\u00000\u00005'
    assert.equal(b[0], 0xff)
    assert.equal(b.readUInt16BE(1), text.length)
    assert.equal(b.subarray(0, 9).toString('hex'), 'ff001500a700310000')
    assert.equal(Buffer.from(b.subarray(3)).swap16().toString('utf16le'), text)
  })
})

describe('wake: handshake and hosts', () => {
  it('parses a 1.21.4 login handshake (byte fixture)', () => {
    const fixture = Buffer.concat([hex('1a 00 8106 13'), Buffer.from('abc.trs-launcher.de'), hex('63dd 02')])
    const r = new PacketReader(1024)
    r.push(fixture)
    const p = r.next()
    assert.ok(p && p !== 'error')
    assert.equal(p.id, 0)
    assert.deepEqual(parseHandshake(p.data), { protocol: 769, address: 'abc.trs-launcher.de', port: 25565, intent: 2 })
  })

  it('rejects unknown intents', () => {
    const p = Buffer.concat([writeVarInt(47), writeString('x'), hex('63dd'), writeVarInt(4)])
    assert.throws(() => parseHandshake(new Reader(p)))
  })

  it('normalises the address', () => {
    assert.equal(normalizeHost('ABC.TRS-Launcher.de.'), 'abc.trs-launcher.de')
    assert.equal(normalizeHost('abc.trs-launcher.de\0FML3\0'), 'abc.trs-launcher.de')
    assert.equal(normalizeHost('abc.trs-launcher.de.\0FML\0'), 'abc.trs-launcher.de')
  })

  it('accepts only <name>.<domain>', () => {
    const d = 'trs-launcher.de'
    assert.equal(hostingHost('My-Server.trs-launcher.de.', d), 'my-server.trs-launcher.de')
    assert.equal(hostingHost('abc.trs-launcher.de\0FML2\0', d), 'abc.trs-launcher.de')
    for (const bad of ['trs-launcher.de', '.trs-launcher.de', 'a.b.trs-launcher.de', '-x.trs-launcher.de', 'x-.trs-launcher.de',
      'abc.trs-launcher.de.evil.com', 'abctrs-launcher.de', '135.125.185.232', 'a_b.trs-launcher.de', `${'a'.repeat(64)}.trs-launcher.de`]) {
      assert.equal(hostingHost(bad, d), null, bad)
    }
  })
})

describe('wake: login packets per protocol', () => {
  const name = writeString('Steve')
  const uuid = writeUuid(UUID)

  it('login start: name only up to 1.18.2', () => {
    for (const v of [4, 5, 47, 340, 758]) assert.deepEqual(parseLoginStart(new Reader(name), v), { name: 'Steve', uuid: null })
  })

  it('login start 1.19 (759): skips signature data', () => {
    const sig = Buffer.concat([hex('01'), writeLong(1n), writeVarInt(3), hex('aabbcc'), writeVarInt(2), hex('ddee')])
    assert.deepEqual(parseLoginStart(new Reader(Buffer.concat([name, sig])), 759), { name: 'Steve', uuid: null })
    assert.deepEqual(parseLoginStart(new Reader(Buffer.concat([name, hex('00')])), 759), { name: 'Steve', uuid: null })
  })

  it('login start 1.19.1 (760): signature data, then optional uuid', () => {
    assert.deepEqual(parseLoginStart(new Reader(Buffer.concat([name, hex('00 01'), uuid])), 760), { name: 'Steve', uuid: UUID })
    assert.deepEqual(parseLoginStart(new Reader(Buffer.concat([name, hex('00 00')])), 760), { name: 'Steve', uuid: null })
  })

  it('login start 1.19.3–1.20.1 (761–763): optional uuid', () => {
    assert.deepEqual(parseLoginStart(new Reader(Buffer.concat([name, hex('01'), uuid])), 763), { name: 'Steve', uuid: UUID })
    assert.deepEqual(parseLoginStart(new Reader(Buffer.concat([name, hex('00')])), 761), { name: 'Steve', uuid: null })
  })

  it('login start 1.20.2+ (764+): uuid required', () => {
    assert.deepEqual(parseLoginStart(new Reader(Buffer.concat([name, uuid])), 769), { name: 'Steve', uuid: UUID })
    assert.throws(() => parseLoginStart(new Reader(name), 769))
  })

  it('rejects names the API would reject', () => {
    assert.throws(() => parseLoginStart(new Reader(writeString('a b')), 47))
    assert.throws(() => parseLoginStart(new Reader(writeString('')), 47))
    assert.throws(() => parseLoginStart(new Reader(writeString('x'.repeat(17))), 47))
  })

  it('protocol table: transfer from 1.20.5 (766), strict flag only 766–767', () => {
    assert.equal(transferProto(5), null)
    assert.equal(transferProto(765), null)
    assert.equal(transferProto(766)?.strictErrorHandling, true)
    assert.equal(transferProto(767)?.strictErrorHandling, true)
    assert.equal(transferProto(768)?.strictErrorHandling, false)
    assert.equal(transferProto(774)?.cfgTransfer, 0x0b)
    assert.equal(transferProto(NEWEST_KNOWN_PROTOCOL + 50)?.cfgTransfer, 0x0b)
    assert.equal(transferProto(0x4000_0100), null)
  })

  it('login success 1.20.5/1.21 (766/767) with strict error handling byte', () => {
    const want = Buffer.concat([hex('19 02'), uuid, hex('05'), Buffer.from('Steve'), hex('00 00')])
    assert.ok(loginSuccess(transferProto(766)!, UUID, 'Steve').equals(want))
  })

  it('login success 1.21.2+ (768+) without it', () => {
    const want = Buffer.concat([hex('18 02'), uuid, hex('05'), Buffer.from('Steve'), hex('00')])
    assert.ok(loginSuccess(transferProto(768)!, UUID, 'Steve').equals(want))
    assert.ok(loginSuccess(transferProto(775)!, UUID, 'Steve').equals(want))
  })

  it('login disconnect = JSON string', () => {
    const p = loginDisconnect('§aHi\nx')
    const r = new Reader(p)
    r.varInt()
    assert.equal(r.varInt(), 0x00)
    assert.deepEqual(JSON.parse(r.string(32767)), { text: '§aHi\nx' })
  })

  it('configuration packets: disconnect (NBT), keep-alive, transfer', () => {
    const t = transferProto(769)!
    assert.equal(configDisconnect(t, 769, 'hi').toString('hex'), '06020800026869')
    assert.equal(configKeepAlive(t, 1n).toString('hex'), '09040000000000000001')
    assert.equal(transfer(t, 'a.b', 25565).toString('hex'), '080b03612e62ddc701')
  })
})

describe('wake: status texts and config', () => {
  it('formats cooldown times in Europe/Berlin', () => {
    assert.equal(berlinTime('2026-11-18T19:30:00Z'), '20:30')
    assert.equal(berlinTime('2026-07-01T19:30:00Z'), '21:30')
    assert.equal(berlinTime('nope'), null)
  })

  it('status json echoes the protocol and shows the state', () => {
    const s = statusJson(769, { ok: true, info: { name: 'abc', motd: 'Meine Welt', state: 'offline', target: null, maxPlayers: 8, cooldownUntil: null } }) as {
      version: { protocol: number }, players: { max: number, online: number }, description: { text: string }
    }
    assert.equal(s.version.protocol, 769)
    assert.deepEqual(s.players, { max: 8, online: 0 })
    assert.equal(s.description.text, '§fMeine Welt §8– §a▶ Click to join, it starts now\n§7Free by §bcraftinghosting.free §8× §ctrs-launcher.de')
    const named = statusJson(769, { ok: true, info: { name: 'abc', motd: '', state: 'starting', target: null, maxPlayers: 8, cooldownUntil: null } }) as { description: { text: string } }
    assert.equal(named.description.text.split('\n')[0], '§fabc §8– §e● Starting …')
  })

  it('TRS_API_URL must be https (http only for 127.0.0.1)', () => {
    assert.equal(parseApiUrl(undefined), 'https://trs-launcher.theredstonee.de')
    assert.equal(parseApiUrl('https://api.example.de/'), 'https://api.example.de')
    assert.equal(parseApiUrl('http://127.0.0.1:3000'), 'http://127.0.0.1:3000')
    for (const bad of ['http://example.de', 'http://localhost:3000', 'ftp://x', 'nope', 'https://u:p@x.de', 'https://x.de/?a=1']) {
      assert.throws(() => parseApiUrl(bad), ConfigError, bad)
    }
  })

  it('WAKE_PORT and HOSTING_DOMAIN', () => {
    const s = ['x'.repeat(32), 'y'.repeat(32)]
    const c = loadWakeConfig({}, s, '0.0.0.0')
    assert.equal(c.port, 25565)
    assert.equal(c.hostingDomain, 'trs-launcher.de')
    assert.equal(c.secret, s[0])
    assert.equal(loadWakeConfig({ WAKE_PORT: '0' }, s, '0.0.0.0').port, 0)
    assert.equal(loadWakeConfig({ HOSTING_DOMAIN: 'Example.ORG.' }, s, '0.0.0.0').hostingDomain, 'example.org')
    assert.throws(() => loadWakeConfig({ WAKE_PORT: '70000' }, s, '0.0.0.0'), ConfigError)
    assert.throws(() => loadWakeConfig({ HOSTING_DOMAIN: 'nodot' }, s, '0.0.0.0'), ConfigError)
  })
})
