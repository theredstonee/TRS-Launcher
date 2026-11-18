/**
 * Server-Listen-Ping gegen TRS Wake (oder jeden Minecraft-Server):
 *   node scripts/wake-probe.ts <host> [port=25565] [protocol=774] [connectTo]
 * `connectTo` = andere IP/Adresse für die TCP-Verbindung, `host` steht dann nur im Handshake
 * (z. B. das Relay direkt prüfen, bevor DNS umgestellt ist). Gibt das Status-JSON und die Pingzeit aus.
 */
import net from 'node:net'
import { PacketReader, packet, writeLong, writeString, writeVarInt } from '../src/wake/mc.ts'

const [host, portArg = '25565', protoArg = '774', connectTo] = process.argv.slice(2)
if (!host) {
  console.error('usage: node scripts/wake-probe.ts <host> [port=25565] [protocol=774] [connectTo]')
  process.exit(2)
}
const port = Number(portArg)
const protocol = Number(protoArg)

const s = net.connect(port, connectTo ?? host)
const reader = new PacketReader(1 << 20)
const timer = setTimeout(() => {
  console.error('timeout (5 s)')
  s.destroy()
  process.exit(1)
}, 5000)
let sentAt = 0n

s.on('connect', () => {
  const p = Buffer.alloc(2)
  p.writeUInt16BE(port)
  s.write(Buffer.concat([packet(0x00, writeVarInt(protocol), writeString(host), p, writeVarInt(1)), packet(0x00)]))
})
s.on('data', (c: Buffer) => {
  reader.push(c)
  for (let pk = reader.next(); pk; pk = reader.next()) {
    if (pk === 'error') {
      console.error('bad packet')
      process.exit(1)
    }
    if (pk.id === 0x00) {
      console.log(JSON.stringify(JSON.parse(pk.data.string(32767)), null, 2))
      sentAt = process.hrtime.bigint()
      s.write(packet(0x01, writeLong(sentAt)))
    } else if (pk.id === 0x01) {
      console.log(`ping ${Number(process.hrtime.bigint() - sentAt) / 1e6} ms`)
      clearTimeout(timer)
      s.end()
    }
  }
})
s.on('error', (err) => {
  console.error(`error: ${err.message}`)
  process.exit(1)
})
