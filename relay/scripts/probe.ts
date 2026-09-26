/**
 * Prüft ein laufendes Relay von außen:
 *   node scripts/probe.ts <host> [tcpPort=25503] [udpPort=25504]
 * 1. STUN Binding Request → zeigt die eigene öffentliche Adresse.
 * 2. TCP: Präambel + HOST_HELLO mit Unsinn → erwartet ERROR bad_token (Protokoll spricht).
 * 3. Mit RELAY_SECRET in der Umgebung zusätzlich ein kompletter Durchlauf
 *    (Host-Steuerung, Gast, Kopplung, Bytes hin und zurück) mit einem Test-Raum.
 */
import { randomBytes } from 'node:crypto'
import dgram from 'node:dgram'
import net from 'node:net'
import { PREAMBLE, T, frame } from '../src/protocol.ts'
import { bindingRequest, parseBindingResponse } from '../src/stun.ts'
import { signToken } from '../src/token.ts'

const [host = '127.0.0.1', tcpArg = '25503', udpArg = '25504'] = process.argv.slice(2)
const tcpPort = Number(tcpArg)
const udpPort = Number(udpArg)

function stun(): Promise<string> {
  return new Promise((resolve, reject) => {
    const s = dgram.createSocket('udp4')
    const txid = randomBytes(12)
    const timer = setTimeout(() => {
      s.close()
      reject(new Error('no STUN answer within 3 s'))
    }, 3000)
    s.on('message', (m) => {
      const p = parseBindingResponse(m)
      if (!p || !p.txid.equals(txid)) return
      clearTimeout(timer)
      s.close()
      resolve(`${p.xorMapped?.address}:${p.xorMapped?.port} (fingerprint ${p.fingerprintOk ? 'ok' : 'BAD'})`)
    })
    s.send(bindingRequest(txid), udpPort, host)
  })
}

/** Verbindung mit einfachem Frame-Leser. */
function connect(): Promise<{ s: net.Socket, next: () => Promise<{ type: number, payload: Buffer }>, raw: (n: number) => Promise<Buffer> }> {
  return new Promise((resolve, reject) => {
    const s = net.connect(tcpPort, host, () => {
      let buf = Buffer.alloc(0)
      let wake: (() => void) | null = null
      s.on('data', (c: Buffer) => {
        buf = Buffer.concat([buf, c])
        wake?.()
      })
      const wait = async (ok: () => boolean) => {
        const deadline = Date.now() + 5000
        while (!ok()) {
          if (Date.now() > deadline) throw new Error('timeout')
          await new Promise<void>((r) => {
            wake = r
            setTimeout(r, 100)
          })
        }
      }
      resolve({
        s,
        next: async () => {
          await wait(() => buf.length >= 3 && buf.length >= 3 + buf.readUInt16BE(1))
          const len = buf.readUInt16BE(1)
          const f = { type: buf[0]!, payload: buf.subarray(3, 3 + len) }
          buf = buf.subarray(3 + len)
          return f
        },
        raw: async (n) => {
          await wait(() => buf.length >= n)
          const out = buf.subarray(0, n)
          buf = buf.subarray(n)
          return out
        },
      })
    })
    s.once('error', reject)
    s.setTimeout(10_000, () => s.destroy())
  })
}

async function main(): Promise<void> {
  console.log(`STUN  ${host}:${udpPort} → ${await stun()}`)
  const a = await connect()
  a.s.write(Buffer.concat([PREAMBLE, frame(T.HOST_HELLO, 'probe')]))
  const e = await a.next()
  console.log(`TCP   ${host}:${tcpPort} → ${e.type === T.ERROR ? `ERROR ${e.payload.toString()}` : `unexpected 0x${e.type.toString(16)}`}`)
  a.s.destroy()

  const secret = process.env.RELAY_SECRET?.split(',')[0]?.trim()
  if (!secret) {
    console.log('(set RELAY_SECRET for a full host/guest round trip)')
    return
  }
  const now = Math.floor(Date.now() / 1000)
  const room = `h${randomBytes(10).toString('hex')}`
  const hostUuid = randomBytes(16).toString('hex')
  const guestUuid = randomBytes(16).toString('hex')
  const tok = (role: 'host' | 'guest') => signToken({
    v: 1, r: room, u: role === 'host' ? hostUuid : guestUuid, h: hostUuid, role, m: 2, iat: now, exp: now + 60, n: randomBytes(8).toString('hex'),
  }, secret)
  const control = await connect()
  control.s.write(Buffer.concat([PREAMBLE, frame(T.HOST_HELLO, tok('host'))]))
  const w = await control.next()
  if (w.type !== T.WELCOME) throw new Error(`host hello failed: ${w.payload.toString()}`)
  const guest = await connect()
  guest.s.write(Buffer.concat([PREAMBLE, frame(T.GUEST_HELLO, tok('guest')), Buffer.from('ping')]))
  const open = await control.next()
  if (open.type !== T.GUEST_OPEN) throw new Error(`expected GUEST_OPEN, got 0x${open.type.toString(16)}`)
  const data = await connect()
  data.s.write(Buffer.concat([PREAMBLE, frame(T.PAIR, open.payload.subarray(0, 16))]))
  await data.next()
  await guest.next()
  const t0 = Date.now()
  if ((await data.raw(4)).toString() !== 'ping') throw new Error('guest bytes did not arrive')
  data.s.write('pong')
  if ((await guest.raw(4)).toString() !== 'pong') throw new Error('host bytes did not arrive')
  console.log(`PAIR  round trip ok (${Date.now() - t0} ms)`)
  for (const c of [guest, data, control]) c.s.destroy()
}

main().catch((err: Error) => {
  console.error(`probe failed: ${err.message}`)
  process.exit(1)
})
