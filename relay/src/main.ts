import { ConfigError, loadConfig } from './config.ts'
import { startRelay } from './relay.ts'
import { loadWakeConfig } from './wake/config.ts'
import { startWake } from './wake/listener.ts'

/** Einstieg für den Betrieb (start.sh → `node src/main.ts`). */
async function main(): Promise<void> {
  let config
  let wakeConfig
  try {
    config = loadConfig(process.env)
    wakeConfig = loadWakeConfig(process.env, config.secrets, config.bindHost)
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
  // TRS Wake läuft daneben; fällt er beim Start aus, bleibt das Relay trotzdem oben.
  let wake: Awaited<ReturnType<typeof startWake>> | null = null
  if (wakeConfig.port !== 0) {
    try {
      wake = await startWake(wakeConfig)
      console.info(`[trs-wake] listening tcp=${wakeConfig.bindHost}:${wake.port} domain=${wakeConfig.hostingDomain} api=${new URL(wakeConfig.apiUrl).host}`)
    } catch (err) {
      console.error(`[trs-wake] not started: ${(err as Error).message}`)
    }
  }
  let stopping = false
  const stop = (signal: string) => {
    if (stopping) return
    stopping = true
    console.info(`[trs-relay] ${signal} – shutting down`)
    const force = setTimeout(() => process.exit(0), config.shutdownGraceMs + 2000)
    force.unref()
    Promise.allSettled([relay.close(), wake?.listener.close()]).then(() => process.exit(0))
  }
  process.on('SIGTERM', () => stop('SIGTERM'))
  process.on('SIGINT', () => stop('SIGINT'))
  process.on('uncaughtException', (err) => {
    console.error('[trs-relay] uncaught exception', err)
    process.exit(1)
  })
}

void main()
