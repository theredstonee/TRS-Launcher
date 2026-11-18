import { createHash, createHmac } from 'node:crypto'
import { createServer, type Server } from 'node:http'
import { afterEach, describe, expect, it } from 'vitest'
import { createApp, createRouter, send, setResponseStatus, toNodeListener } from 'h3'
import { setContext } from '../server/lib/context'
import { isApiError } from '../server/lib/errors'
import resolveRoute from '../server/routes/v1/internal/hosting/resolve.get'
import wakeRoute from '../server/routes/v1/internal/hosting/wake.post'
import { makeEnv } from './helpers'

const SECRET = 'r'.repeat(40)
const closers: (() => Promise<void>)[] = []
afterEach(async () => {
  for (const c of closers.splice(0)) await c()
  setContext(undefined)
})

async function harness() {
  const app = createApp({
    onError: async (error, event) => {
      const cause = (error as { cause?: unknown }).cause
      const api = isApiError(error) ? error : isApiError(cause) ? cause : null
      setResponseStatus(event, api?.status ?? 500)
      await send(event, JSON.stringify({ error: { code: api?.code ?? 'internal_error' } }), 'application/json')
    },
  })
  const router = createRouter()
  router.get('/v1/internal/hosting/resolve', resolveRoute)
  router.post('/v1/internal/hosting/wake', wakeRoute)
  app.use(router)
  const server: Server = createServer(toNodeListener(app))
  await new Promise<void>((r) => server.listen(0, '127.0.0.1', r))
  const env = makeEnv({ env: { RELAY_SECRET: SECRET, RELAY_HOST: 'relay.example.test', CRAFTINGHOSTING_API_KEY: 'k',
    CLOUDFLARE_DNS_TOKEN: 'cf' } })
  setContext(env.ctx)
  closers.push(() => new Promise<void>((r) => server.close(() => r())))
  return `http://127.0.0.1:${(server.address() as { port: number }).port}`
}

function signed(method: string, path: string, body = '', secret = SECRET, time = Math.floor(Date.now() / 1000)) {
  const sig = createHmac('sha256', secret)
    .update(`${time}\n${method}\n${path}\n${createHash('sha256').update(body).digest('hex')}`).digest('hex')
  return { 'x-relay-time': String(time), 'x-relay-sig': sig }
}

describe('TRS Wake internal routes', () => {
  it('need a valid relay signature', async () => {
    const base = await harness()
    const path = '/v1/internal/hosting/resolve?host=nope.trs-launcher.de'
    expect((await fetch(base + path)).status).toBe(403)
    expect((await fetch(base + path, { headers: signed('GET', path, '', 'x'.repeat(40)) })).status).toBe(403)
    expect((await fetch(base + path, { headers: signed('GET', path, '', SECRET, Math.floor(Date.now() / 1000) - 300) })).status).toBe(403)
    expect((await fetch(base + path, { headers: signed('GET', '/v1/internal/hosting/resolve?host=x.trs-launcher.de') })).status).toBe(403)
    const ok = await fetch(base + path, { headers: signed('GET', path) })
    expect(ok.status).toBe(404)
    expect((await ok.json()).error.code).toBe('unknown_host')

    const body = JSON.stringify({ host: 'nope.trs-launcher.de', player: { name: 'Steve' } })
    const wake = '/v1/internal/hosting/wake'
    expect((await fetch(base + wake, { method: 'POST', body, headers: { ...signed('POST', wake, '{}'), 'content-type': 'application/json' } })).status).toBe(403)
    const w = await fetch(base + wake, { method: 'POST', body, headers: { ...signed('POST', wake, body), 'content-type': 'application/json' } })
    expect(w.status).toBe(404)
  })
})
