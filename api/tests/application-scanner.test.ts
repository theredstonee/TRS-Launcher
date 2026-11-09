import { createServer } from 'node:net'
import { afterEach, describe, expect, it } from 'vitest'
import { scanApplicationFile } from '../server/lib/application-scanner'

const servers: ReturnType<typeof createServer>[] = []

async function fakeClamd(answer: string, onRequest: (data: Buffer) => void = () => {}): Promise<{ host: string, port: number }> {
  const server = createServer((socket) => {
    const incoming: Buffer[] = []
    socket.on('data', (part) => incoming.push(part))
    socket.on('end', () => {
      onRequest(Buffer.concat(incoming))
      socket.end(`${answer}\0`)
    })
  })
  servers.push(server)
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve))
  const address = server.address()
  if (!address || typeof address === 'string') throw new Error('Fake clamd did not bind')
  return { host: '127.0.0.1', port: address.port }
}

afterEach(async () => {
  await Promise.all(servers.splice(0).map((server) => new Promise<void>((resolve) => server.close(() => resolve()))))
})

describe('application attachment scanner', () => {
  it('streams chunk-framed bytes and returns a clean result', async () => {
    let received = Buffer.alloc(0)
    const daemon = await fakeClamd('stream: OK', (data) => { received = data })
    const payload = Buffer.from('private application attachment')
    await expect(scanApplicationFile(daemon.host, daemon.port, payload)).resolves.toEqual({ status: 'clean' })
    expect(received.subarray(0, 10)).toEqual(Buffer.from('zINSTREAM\0'))
    expect(received.subarray(10, 14).readUInt32BE()).toBe(payload.length)
    expect(received.subarray(14, 14 + payload.length)).toEqual(payload)
    expect(received.subarray(-4)).toEqual(Buffer.alloc(4))
  })

  it('returns the signature for an infected file', async () => {
    const daemon = await fakeClamd('stream: Eicar-Test-Signature FOUND')
    await expect(scanApplicationFile(daemon.host, daemon.port, Buffer.from('test')))
      .resolves.toEqual({ status: 'infected', signature: 'Eicar-Test-Signature' })
  })

  it('fails closed on an unexpected scanner reply', async () => {
    const daemon = await fakeClamd('stream: ERROR')
    await expect(scanApplicationFile(daemon.host, daemon.port, Buffer.from('test'))).rejects.toThrow('ClamAV scan failed')
  })
})