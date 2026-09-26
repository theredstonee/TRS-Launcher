import { ConfigError, loadConfig } from './config.ts'
import { startRelay } from './relay.ts'

/** Einstieg für den Betrieb (start.sh → `node src/main.ts`). */
async function main(): Promise<void> {
  let config
  try {
    config = loadConfig(process.env)
  } catch (err) {
    if (err instanceof ConfigError) {
      console.error(`[trs-relay] ${err.message}`)
      process.exit(1)
    }
    throw err
  }
  const relay = await startRelay(config)
  console.info(
    `[trs-relay] listening tcp=${config.bindHost}:${relay.tcpPort} udp=${config.bindHost}:${relay.udpPort}` +
    `${config.publicHost ? ` public=${config.publicHost}` : ''} secrets=${config.secrets.length}`,
  )
  let stopping = false
  const stop = (signal: string) => {
    if (stopping) return
    stopping = true
    console.info(`[trs-relay] ${signal} – shutting down`)
    const force = setTimeout(() => process.exit(0), config.shutdownGraceMs + 2000)
    force.unref()
    relay.close().then(
      () => process.exit(0),
      () => process.exit(0),
    )
  }
  process.on('SIGTERM', () => stop('SIGTERM'))
  process.on('SIGINT', () => stop('SIGINT'))
  process.on('uncaughtException', (err) => {
    console.error('[trs-relay] uncaught exception', err)
    process.exit(1)
  })
}

void main()
