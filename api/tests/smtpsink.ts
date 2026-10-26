import { createServer, type Server, type Socket } from 'node:net'

/**
 * Kleiner SMTP-Empfänger für Tests und den lokalen Smoke: nur 127.0.0.1, kein TLS, keine Anmeldung. Nimmt jede Mail an
 * und merkt sich Umschlag + Rohdaten. `failNext` lässt den nächsten Versand mit einem Fehlercode scheitern.
 */
export interface SinkMail {
  from: string
  to: string[]
  data: string
}

export class SmtpSink {
  readonly mails: SinkMail[] = []
  failNext: number | null = null
  private server: Server | null = null
  private sockets = new Set<Socket>()
  port = 0

  async start(port = 0): Promise<number> {
    this.server = createServer((s) => this.session(s))
    await new Promise<void>((resolve) => this.server!.listen(port, '127.0.0.1', resolve))
    this.port = (this.server.address() as { port: number }).port
    return this.port
  }

  async stop(): Promise<void> {
    for (const s of this.sockets) s.destroy()
    await new Promise<void>((resolve) => (this.server ? this.server.close(() => resolve()) : resolve()))
  }

  private session(sock: Socket): void {
    let buf = ''
    let inData = false
    let cur: SinkMail = { from: '', to: [], data: '' }
    this.sockets.add(sock)
    sock.on('close', () => this.sockets.delete(sock))
    const say = (line: string) => sock.write(`${line}\r\n`)
    say('220 trs-sink ESMTP')
    sock.on('error', () => {})
    sock.on('data', (chunk) => {
      buf += chunk.toString('utf8')
      for (;;) {
        if (inData) {
          const end = buf.indexOf('\r\n.\r\n')
          if (end < 0) return
          cur.data = buf.slice(0, end).replace(/^\.\./gm, '.')
          buf = buf.slice(end + 5)
          inData = false
          if (this.failNext !== null) {
            say(`${this.failNext} rejected`)
            this.failNext = null
          } else {
            this.mails.push(cur)
            say('250 OK queued')
          }
          cur = { from: '', to: [], data: '' }
          continue
        }
        const nl = buf.indexOf('\r\n')
        if (nl < 0) return
        const line = buf.slice(0, nl)
        buf = buf.slice(nl + 2)
        const cmd = line.slice(0, 4).toUpperCase()
        if (cmd === 'EHLO') {
          say('250-trs-sink')
          say('250-8BITMIME')
          say('250-SMTPUTF8')
          say('250 SIZE 10485760')
        } else if (cmd === 'HELO') say('250 trs-sink')
        else if (cmd === 'MAIL') {
          cur.from = /<([^>]*)>/.exec(line)?.[1] ?? ''
          say('250 OK')
        } else if (cmd === 'RCPT') {
          cur.to.push(/<([^>]*)>/.exec(line)?.[1] ?? '')
          say('250 OK')
        } else if (cmd === 'DATA') {
          inData = true
          say('354 go ahead')
        } else if (cmd === 'RSET') {
          cur = { from: '', to: [], data: '' }
          say('250 OK')
        } else if (cmd === 'NOOP') say('250 OK')
        else if (cmd === 'QUIT') {
          say('221 bye')
          sock.end()
          return
        } else say('502 not implemented')
      }
    })
  }
}
