import { randomBytes } from 'node:crypto'
import dgram from 'node:dgram'
import net from 'node:net'
import { testConfig, type RelayConfig } from '../src/config.ts'
import { FRAME_HEADER, PREAMBLE, frame, type Frame } from '../src/protocol.ts'
import { startRelay, type Relay } from '../src/relay.ts'
import { signToken, type RelayToken } from '../src/token.ts'

export const SECRET = 'test-relay-secret-0123456789abcdef-0123456789'
export const OTHER_SECRET = 'other-relay-secret-0123456789abcdef-012345678'
export const ROOM = 'h0123456789abcdef0123'
export const HOST = 'aa'.repeat(16)
export const GUEST = 'bb'.repeat(16)
export const GUEST2 = 'cc'.repeat(16)

const running: Relay[] = []

export async function relay(over: Partial<RelayConfig> = {}): Promise<Relay> {
  const r = await startRelay(testConfig({ secrets: [SECRET], ...over }), { log: () => {} })
  running.push(r)
  return r
}

export async function closeAll(): Promise<void> {
  await Promise.all(running.splice(0).map((r) => r.close()))
}

export function token(over: Partial<RelayToken> = {}, secret = SECRET): string {
  const now = Math.floor(Date.now() / 1000)
  const role = over.role ?? 'guest'
  return signToken({
    v: 1,
    r: ROOM,
    u: role === 'host' ? HOST : GUEST,
    h: HOST,
    role,
    m: 10,
    iat: now,
    exp: now + 120,
    n: randomBytes(8).toString('hex'),
    ...over,
  }, secret)
}

export const hostToken = (over: Partial<RelayToken> = {}) => token({ role: 'host', u: HOST, ...over })

/** Test-Client mit Puffer: liest Frames oder rohe Bytes. */
export class Client {
  readonly socket: net.Socket
  private buf: Buffer = Buffer.alloc(0)
  private waiters: (() => void)[] = []
  ended = false
  readonly closedP: Promise<void>

  private constructor(socket: net.Socket) {
    this.socket = socket
    socket.on('data', (c: Buffer) => {
      this.buf = Buffer.concat([this.buf, c])
      this.wake()
    })
    socket.on('error', () => {})
    this.closedP = new Promise((resolve) => socket.on('close', () => {
      this.ended = true
      this.wake()
      resolve()
    }))
  }

  private wake(): void {
    for (const w of this.waiters.splice(0)) w()
  }

  static connect(port: number): Promise<Client> {
    return new Promise((resolve, reject) => {
      const s = net.connect(port, '127.0.0.1', () => resolve(new Client(s)))
      s.once('error', reject)
    })
  }

  write(b: Buffer | string): void {
    this.socket.write(b)
  }

  hello(type: number, tok: string | Buffer): void {
    this.write(Buffer.concat([PREAMBLE, frame(type, tok)]))
  }

  private async until(ok: () => boolean, ms: number): Promise<void> {
    const deadline = Date.now() + ms
    while (!ok()) {
      if (this.ended) throw new Error('connection closed')
      const left = deadline - Date.now()
      if (left <= 0) throw new Error('timeout')
      await new Promise<void>((resolve) => {
        const t = setTimeout(resolve, left)
        this.waiters.push(() => {
          clearTimeout(t)
          resolve()
        })
      })
    }
  }

  async frame(ms = 2000): Promise<Frame> {
    const full = () => this.buf.length >= FRAME_HEADER && this.buf.length >= FRAME_HEADER + this.buf.readUInt16BE(1)
    try {
      await this.until(full, ms)
    } catch (e) {
      if (!full()) throw e
    }
    const len = this.buf.readUInt16BE(1)
    const f = { type: this.buf[0]!, payload: Buffer.from(this.buf.subarray(FRAME_HEADER, FRAME_HEADER + len)) }
    this.buf = this.buf.subarray(FRAME_HEADER + len)
    return f
  }

  /** ERROR-Code des nächsten Frames (wirft, wenn es kein ERROR ist). */
  async error(ms = 2000): Promise<string> {
    const f = await this.frame(ms)
    if (f.type !== 0x8f) throw new Error(`expected ERROR, got 0x${f.type.toString(16)}`)
    return f.payload.toString('latin1')
  }

  async bytes(n: number, ms = 2000): Promise<Buffer> {
    try {
      await this.until(() => this.buf.length >= n, ms)
    } catch (e) {
      if (this.buf.length < n) throw e
    }
    const out = this.buf.subarray(0, n)
    this.buf = this.buf.subarray(n)
    return out
  }

  async closed(ms = 2000): Promise<void> {
    await Promise.race([this.closedP, new Promise((_, rej) => setTimeout(() => rej(new Error('not closed')), ms))])
  }

  destroy(): void {
    this.socket.destroy()
  }
}

export class UdpClient {
  readonly socket = dgram.createSocket('udp4')
  readonly inbox: Buffer[] = []
  private waiters: (() => void)[] = []

  static async open(): Promise<UdpClient> {
    const c = new UdpClient()
    c.socket.on('message', (m) => {
      c.inbox.push(m)
      for (const w of c.waiters.splice(0)) w()
    })
    await new Promise<void>((r) => c.socket.bind(0, '127.0.0.1', () => r()))
    return c
  }

  get port(): number {
    return this.socket.address().port
  }

  send(b: Buffer, port: number): void {
    this.socket.send(b, port, '127.0.0.1')
  }

  async next(ms = 1000): Promise<Buffer> {
    const deadline = Date.now() + ms
    while (this.inbox.length === 0) {
      const left = deadline - Date.now()
      if (left <= 0) throw new Error('timeout')
      await new Promise<void>((resolve) => {
        const t = setTimeout(resolve, left)
        this.waiters.push(() => {
          clearTimeout(t)
          resolve()
        })
      })
    }
    return this.inbox.shift()!
  }

  /** Nichts in `ms` angekommen? */
  async silent(ms = 200): Promise<boolean> {
    await sleep(ms)
    return this.inbox.length === 0
  }

  close(): void {
    this.socket.close()
  }
}

export const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))
