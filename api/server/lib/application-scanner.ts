import { createConnection } from 'node:net'

const STREAM_COMMAND = Buffer.from('zINSTREAM\0', 'ascii')
const CHUNK_BYTES = 1024 * 1024
const MAX_REPLY_BYTES = 4096

export type ScanResult = { status: 'clean' } | { status: 'infected', signature: string }

/** Send bytes to clamd's INSTREAM command; files never leave the configured private scanner network. */
export async function scanApplicationFile(host: string, port: number, data: Buffer, timeoutMs = 60_000): Promise<ScanResult> {
  return await new Promise<ScanResult>((resolve, reject) => {
    const socket = createConnection({ host, port })
    let reply = Buffer.alloc(0)
    let settled = false
    const finish = (error?: Error, result?: ScanResult) => {
      if (settled) return
      settled = true
      socket.destroy()
      if (error) reject(error)
      else resolve(result!)
    }
    socket.setTimeout(timeoutMs, () => finish(new Error('ClamAV scan timed out')))
    socket.once('error', (error) => finish(new Error(`ClamAV connection failed: ${error.message}`)))
    socket.on('data', (part) => {
      reply = Buffer.concat([reply, part])
      if (reply.length > MAX_REPLY_BYTES) return finish(new Error('ClamAV reply exceeded the limit'))
      const end = reply.indexOf(0)
      if (end < 0) return
      const text = reply.subarray(0, end).toString('utf8')
      if (/ FOUND$/.test(text)) return finish(undefined, { status: 'infected', signature: text.replace(/^stream: /, '').replace(/ FOUND$/, '') })
      if (/ OK$/.test(text)) return finish(undefined, { status: 'clean' })
      finish(new Error(`ClamAV scan failed: ${text.slice(0, 200)}`))
    })
    socket.once('connect', () => {
      socket.write(STREAM_COMMAND)
      for (let offset = 0; offset < data.length; offset += CHUNK_BYTES) {
        const chunk = data.subarray(offset, Math.min(offset + CHUNK_BYTES, data.length))
        const length = Buffer.allocUnsafe(4)
        length.writeUInt32BE(chunk.length)
        socket.write(length)
        socket.write(chunk)
      }
      socket.end(Buffer.alloc(4))
    })
  })
}