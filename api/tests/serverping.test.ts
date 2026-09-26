import { createServer, Socket, type AddressInfo, type Server } from 'node:net'
import { afterEach, describe, expect, it } from 'vitest'
import { flattenMotd, isPublicIp, ServerStatusService, splitAddress, type PingDeps } from '../server/lib/serverping'
import { solidPng } from './helpers'

const servers: Server[] = []
afterEach(() => {
  for (const s of servers.splice(0)) s.close()
})

function varint(n: number): Buffer {
  const out: number[] = []
  do {
    let b = n & 0x7f
    n >>>= 7
    if (n) b |= 0x80
    out.push(b)
  } while (n)
  return Buffer.from(out)
}

/** Minimaler Minecraft-Server: beantwortet die Status-Anfrage mit `json` (oder `raw`). */
async function fakeMc(json: unknown, raw?: Buffer): Promise<{ port: number, handshakes: Buffer[] }> {
  const handshakes: Buffer[] = []
  const server = createServer((sock) => {
    sock.once('data', (d) => {
      handshakes.push(d)
      if (raw) return void sock.end(raw)
      const s = Buffer.from(JSON.stringify(json), 'utf8')
      const body = Buffer.concat([varint(0), varint(s.length), s])
      sock.end(Buffer.concat([varint(body.length), body]))
    })
  })
  servers.push(server)
  await new Promise<void>((r) => server.listen(0, '127.0.0.1', r))
  return { port: (server.address() as AddressInfo).port, handshakes }
}

function deps(over: Partial<PingDeps> = {}): PingDeps & { lookups: string[], srv: string[] } {
  const lookups: string[] = []
  const srv: string[] = []
  return {
    lookups,
    srv,
    resolveSrv: async (name) => {
      srv.push(name)
      return null
    },
    lookup: async (host) => {
      lookups.push(host)
      return ['127.0.0.1']
    },
    connect: (ip, port) => new Socket().connect({ host: ip, port }),
    allowPrivate: true,
    ...over,
  }
}

describe('server status (invites)', () => {
  it('classifies public and private addresses', () => {
    for (const ip of ['1.1.1.1', '8.8.8.8', '2606:4700:4700::1111', '185.199.108.153']) expect(isPublicIp(ip)).toBe(true)
    for (const ip of [
      '127.0.0.1', '10.1.2.3', '172.16.0.1', '192.168.1.1', '169.254.169.254', '100.64.0.1', '0.0.0.0', '224.0.0.1',
      '::1', 'fe80::1', 'fd00::1', '::ffff:127.0.0.1', '::ffff:192.168.0.1', '2001:db8::1', 'not-an-ip',
    ]) expect(isPublicIp(ip)).toBe(false)
    expect(splitAddress('Play.Example.NET:25566')).toEqual({ host: 'play.example.net', port: 25566 })
    expect(splitAddress('mc.example.org')).toEqual({ host: 'mc.example.org', port: null })
  })

  it('pings a server, flattens the MOTD, re-encodes the icon and caches', async () => {
    let t = 0
    const mc = await fakeMc({
      version: { name: 'Paper §a1.21.4', protocol: 769 },
      players: { online: 12, max: 100 },
      description: { text: '§6Willkommen', extra: [{ text: ' auf ' }, 'Survival'] },
      favicon: `data:image/png;base64,${solidPng(64, 64).toString('base64')}`,
    })
    const d = deps()
    const svc = new ServerStatusService(true, () => t, d)
    const s = await svc.status(`play.example.net:${mc.port}`)
    expect(s).toMatchObject({
      online: true,
      reason: null,
      version: { name: 'Paper 1.21.4', protocol: 769 },
      players: { online: 12, max: 100 },
      motd: 'Willkommen auf Survival',
    })
    expect(s.icon).toMatch(/^data:image\/png;base64,/)
    // Hostname geht im Handshake mit, SRV nur ohne Port.
    expect(mc.handshakes[0]!.includes(Buffer.from('play.example.net'))).toBe(true)
    expect(d.srv).toEqual([])
    await svc.status(`play.example.net:${mc.port}`)
    expect(mc.handshakes).toHaveLength(1)
    t += 61_000
    await svc.status(`play.example.net:${mc.port}`)
    expect(mc.handshakes).toHaveLength(2)
    expect(flattenMotd([{ text: 'a' }, { translate: 'b', extra: 'c' }])).toBe('abc')
  })

  it('follows SRV records without a port and refuses private targets (SSRF)', async () => {
    const mc = await fakeMc({ version: { name: '1.21', protocol: 767 }, players: { online: 0, max: 5 }, description: 'hi' })
    const d = deps({ resolveSrv: async (name) => (name === '_minecraft._tcp.example.net' ? { name: 'mc.example.net.', port: mc.port } : null) })
    const s = await new ServerStatusService(true, () => 0, d).status('example.net')
    expect(s.online).toBe(true)
    expect(d.lookups).toEqual(['mc.example.net'])

    const strict = new ServerStatusService(true, () => 0, deps({ allowPrivate: false }))
    expect((await strict.status(`internal.example.net:${mc.port}`)).reason).toBe('private_address')
    expect((await strict.status('127.0.0.1:25565')).reason).toBe('private_address')
    expect((await strict.status('8.8.8.8:22')).reason).toBe('private_address') // Port < 1024
    const unresolvable = new ServerStatusService(true, () => 0, deps({ lookup: async () => { throw new Error('ENOTFOUND') } }))
    expect((await unresolvable.status('nope.invalid')).reason).toBe('unresolvable')
    expect((await new ServerStatusService(false, () => 0, deps()).status('x.de')).reason).toBe('disabled')
  })

  it('handles garbage, refused connections and timeouts', async () => {
    const junk = await fakeMc(null, Buffer.from('HTTP/1.1 400 Bad Request\r\n\r\n'))
    expect((await new ServerStatusService(true, () => 0, deps()).status(`a.de:${junk.port}`)).reason).toBe('invalid_response')
    const closed = await fakeMc({})
    servers.pop()!.close()
    await new Promise((r) => setTimeout(r, 20))
    expect((await new ServerStatusService(true, () => 0, deps()).status(`b.de:${closed.port}`)).reason).toBe('refused')
    const silent = createServer(() => {})
    servers.push(silent)
    await new Promise<void>((r) => silent.listen(0, '127.0.0.1', r))
    const port = (silent.address() as AddressInfo).port
    const s = await new ServerStatusService(true, () => 0, deps(), { timeoutMs: 150 }).status(`c.de:${port}`)
    expect(s).toMatchObject({ online: false, reason: 'timeout' })
  })
})
