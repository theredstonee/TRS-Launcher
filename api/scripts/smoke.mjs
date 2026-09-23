#!/usr/bin/env node
/**
 * End-to-End-Smoke-Test gegen den GEBAUTEN Server (`pnpm build` vorher).
 * Startet einen Mojang-Session-Server-Mock auf 127.0.0.1 und die API mit
 * einem Wegwerf-Datenordner, spielt die wichtigsten Abläufe per HTTP durch.
 *
 *   node scripts/smoke.mjs            (Node-Binary = aktuelles `node`)
 *   NODE_BIN=/pfad/zu/node24 node scripts/smoke.mjs
 */
import { spawn } from 'node:child_process'
import { mkdtempSync, rmSync } from 'node:fs'
import { createServer } from 'node:http'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { deflateSync, crc32 } from 'node:zlib'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const PORT = 3000 + Math.floor(Math.random() * 1000) + 3000
const BASE = `http://127.0.0.1:${PORT}`
const ADMIN_KEY = 'smoke-admin-key-0123456789abcdef-0123456789'
const ADMIN_UUID = '75c1a6f3112240abbdb57b9d21c64232'
const ORIGIN = 'https://allowed.example'

// ------------------------------------------------------------ Mojang-Mock
const joins = new Map()
const mojang = createServer((req, res) => {
  const u = new URL(req.url, 'http://x')
  const p = joins.get(u.searchParams.get('serverId'))
  if (u.pathname === '/session/minecraft/hasJoined' && p && p.name.toLowerCase() === u.searchParams.get('username')?.toLowerCase()) {
    res.writeHead(200, { 'content-type': 'application/json' })
    res.end(JSON.stringify({ id: p.uuid, name: p.name, properties: [] }))
  } else {
    res.writeHead(204)
    res.end()
  }
})
await new Promise((r) => mojang.listen(0, '127.0.0.1', r))
const MOJANG = `http://127.0.0.1:${mojang.address().port}`

// ------------------------------------------------------------ API starten
const data = mkdtempSync(join(tmpdir(), 'trs-api-smoke-'))
const api = spawn(process.env.NODE_BIN ?? process.execPath, [join(ROOT, '.output/server/index.mjs')], {
  env: {
    ...process.env,
    PORT: String(PORT),
    HOST: '127.0.0.1',
    DATA_DIR: data,
    SECRET_KEY: 'smoke-secret-0123456789abcdef-0123456789abcdef',
    ADMIN_API_KEY: ADMIN_KEY,
    ADMIN_UUIDS: ADMIN_UUID,
    CORS_ORIGINS: ORIGIN,
    MOJANG_SESSIONSERVER_URL: MOJANG,
    ALLOW_INSECURE_MOJANG_URL: 'true',
    PUBLIC_BASE_URL: BASE,
    TRUST_PROXY: 'cloudflare',
  },
  stdio: ['ignore', 'pipe', 'pipe'],
})
let serverLog = ''
api.stdout.on('data', (d) => (serverLog += d))
api.stderr.on('data', (d) => (serverLog += d))

let failures = 0
let passed = 0
function check(name, cond, extra = '') {
  if (cond) passed++
  else {
    failures++
    console.log(`  ✗ ${name} ${extra}`)
  }
}
async function http(method, path, { token, body, headers = {}, raw, ip = '203.0.113.7' } = {}) {
  const h = { 'cf-connecting-ip': ip, ...headers }
  if (token) h.authorization = `Bearer ${token}`
  let payload
  if (raw !== undefined) payload = raw
  else if (body !== undefined) {
    h['content-type'] = 'application/json'
    payload = JSON.stringify(body)
  }
  const res = await fetch(BASE + path, { method, headers: h, body: payload })
  const text = await res.text()
  let json = null
  try {
    json = JSON.parse(text)
  } catch {
    // kein JSON
  }
  return { status: res.status, headers: res.headers, json, text }
}
async function loginAs(name, uuid) {
  const c = await http('POST', '/v1/auth/challenge', { ip: `198.51.100.${Math.floor(Math.random() * 200)}` })
  joins.set(c.json.serverId, { name, uuid })
  const v = await http('POST', '/v1/auth/verify', { body: { username: name, serverId: c.json.serverId } })
  return v
}
function png(w, h, rgba = [200, 30, 20, 255]) {
  const row = Buffer.alloc(1 + w * 4)
  for (let x = 0; x < w; x++) row.set(rgba, 1 + x * 4)
  const raw = Buffer.concat(Array.from({ length: h }, () => row))
  const chunk = (t, d) => {
    const l = Buffer.alloc(4)
    l.writeUInt32BE(d.length)
    const td = Buffer.concat([Buffer.from(t), d])
    const c = Buffer.alloc(4)
    c.writeUInt32BE(crc32(td) >>> 0)
    return Buffer.concat([l, td, c])
  }
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(w, 0)
  ihdr.writeUInt32BE(h, 4)
  ihdr[8] = 8
  ihdr[9] = 6
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw)),
    chunk('IEND', Buffer.alloc(0)),
  ])
}

try {
  for (let i = 0; i < 100; i++) {
    try {
      if ((await fetch(`${BASE}/v1/health`)).ok) break
    } catch {
      // startet noch
    }
    await new Promise((r) => setTimeout(r, 100))
  }

  console.log('health, headers, errors')
  const h = await http('GET', '/v1/health')
  check('health 200', h.status === 200 && h.json.status === 'ok')
  check('HSTS', h.headers.get('strict-transport-security')?.includes('max-age=63072000'))
  check('nosniff', h.headers.get('x-content-type-options') === 'nosniff')
  check('frame DENY', h.headers.get('x-frame-options') === 'DENY')
  check('CSP', h.headers.get('content-security-policy')?.startsWith("default-src 'none'"))
  check('no-store', h.headers.get('cache-control') === 'no-store')
  const nf = await http('GET', '/v1/does-not-exist')
  check('404 json', nf.status === 404 && nf.json.error.code === 'not_found')
  const page = await http('GET', '/')
  check('status page', page.status === 200 && page.headers.get('content-type')?.startsWith('text/html') && page.headers.get('content-security-policy')?.includes("style-src 'unsafe-inline'"))

  console.log('CORS')
  const pf = await http('OPTIONS', '/v1/me', { headers: { origin: 'https://evil.example', 'access-control-request-method': 'GET' } })
  check('preflight foreign origin 403', pf.status === 403 && !pf.headers.get('access-control-allow-origin'))
  const pf2 = await http('OPTIONS', '/v1/me', { headers: { origin: ORIGIN, 'access-control-request-method': 'GET' } })
  check('preflight allowed origin 204', pf2.status === 204 && pf2.headers.get('access-control-allow-origin') === ORIGIN)
  const cors = await http('GET', '/v1/health', { headers: { origin: 'https://evil.example' } })
  check('no ACAO for foreign origin', cors.headers.get('access-control-allow-origin') === null)

  console.log('auth')
  const unauth = await http('GET', '/v1/me')
  check('401 without token', unauth.status === 401 && unauth.headers.get('www-authenticate') === 'Bearer')
  const a = await loginAs('Theredstonee', ADMIN_UUID)
  check('verify 200', a.status === 200 && /^trs_/.test(a.json.token) && a.json.user.admin === true, JSON.stringify(a.json))
  const A = a.json.token
  const b = await loginAs('Bob', 'b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0')
  const B = b.json.token
  const c0 = await http('POST', '/v1/auth/challenge')
  const bad = await http('POST', '/v1/auth/verify', { body: { username: 'Bob', serverId: c0.json.serverId } })
  check('not joined → 401', bad.status === 401 && bad.json.error.code === 'not_joined')
  const reuse = await http('POST', '/v1/auth/verify', { body: { username: 'Bob', serverId: c0.json.serverId } })
  check('challenge single-use', reuse.json?.error.code === 'invalid_challenge')
  const inval = await http('POST', '/v1/auth/verify', { body: { username: 'Bob', serverId: 'nope', extra: 1 } })
  check('validation 400 with fields', inval.status === 400 && inval.json.error.code === 'invalid_request' && inval.json.error.fields.length >= 1)
  const ctype = await http('POST', '/v1/auth/verify', { raw: '{}', headers: { 'content-type': 'text/plain' } })
  check('415 wrong content type', ctype.status === 415)
  const big = await http('PATCH', '/v1/me', { token: A, raw: JSON.stringify({ showBadge: true, pad: 'x'.repeat(20000) }), headers: { 'content-type': 'application/json' } })
  check('413 body too large', big.status === 413)
  const broken = await http('PATCH', '/v1/me', { token: A, raw: '{"showBadge":', headers: { 'content-type': 'application/json' } })
  check('400 invalid json', broken.status === 400 && broken.json.error.code === 'invalid_json')

  console.log('profile')
  const me = await http('GET', '/v1/me', { token: B })
  check('me defaults', me.json.settings.showBadge === true && me.json.settings.shareServer === false && me.json.settings.presenceVisibility === 'friends')
  const patched = await http('PATCH', '/v1/me', { token: B, body: { shareServer: true } })
  check('patch settings', patched.json.settings.shareServer === true)

  console.log('capes')
  const cat = await http('GET', '/v1/capes', { token: B })
  check('catalog', cat.status === 200 && Array.isArray(cat.json.capes))
  const up = await http('POST', '/v1/capes/upload?name=Bobs%20Umhang', { token: B, raw: png(64, 32), headers: { 'content-type': 'image/png' } })
  check('upload 201 pending', up.status === 201 && up.json.cape.status === 'pending' && up.json.cape.scale === 1, JSON.stringify(up.json))
  const capeId = up.json.cape.id
  const fake = await http('POST', '/v1/capes/upload', { token: B, raw: Buffer.from('GIF89a not a png at all.............................'), headers: { 'content-type': 'image/png' } })
  check('fake png 400', fake.status === 400 && fake.json.error.code === 'invalid_png')
  const poly = await http('POST', '/v1/capes/upload', { token: B, raw: Buffer.concat([png(64, 32), Buffer.from('PK\x03\x04<html>')]), headers: { 'content-type': 'image/png' } })
  check('polyglot 400', poly.status === 400)
  const dims = await http('POST', '/v1/capes/upload', { token: B, raw: png(100, 50), headers: { 'content-type': 'image/png' } })
  check('wrong dims 400', dims.json?.error.code === 'invalid_dimensions')
  const huge = await http('POST', '/v1/capes/upload', { token: B, raw: Buffer.alloc(300 * 1024, 1), headers: { 'content-type': 'image/png' } })
  check('oversized 413', huge.status === 413)
  const hidden = await http('GET', `/v1/capes/${capeId}.png`)
  check('pending texture hidden', hidden.status === 404)
  const own = await fetch(`${BASE}/v1/capes/${capeId}.png`, { headers: { authorization: `Bearer ${B}` } })
  check('owner sees pending texture (private)', own.status === 200 && own.headers.get('cache-control') === 'private, no-store')
  await own.arrayBuffer()
  const pend = await http('GET', '/v1/admin/capes?status=pending', { headers: { 'x-admin-key': ADMIN_KEY } })
  check('admin list pending', pend.json.capes.some((c) => c.id === capeId))
  const noadmin = await http('GET', '/v1/admin/stats', { token: B })
  check('non-admin 403', noadmin.status === 403)
  const wrongkey = await http('GET', '/v1/admin/stats', { headers: { 'x-admin-key': 'x'.repeat(40) } })
  check('wrong admin key 401', wrongkey.status === 401)
  const appr = await http('POST', `/v1/admin/capes/${capeId}/approve`, { headers: { 'x-admin-key': ADMIN_KEY } })
  check('approve', appr.json.cape.status === 'approved')
  const tex = await fetch(appr.json.cape.url.replace(BASE, BASE))
  const etag = tex.headers.get('etag')
  check('public texture', tex.status === 200 && tex.headers.get('content-type') === 'image/png' && tex.headers.get('cache-control') === 'public, max-age=31536000, immutable' && tex.headers.get('cross-origin-resource-policy') === 'cross-origin')
  await tex.arrayBuffer()
  const stale = await fetch(`${BASE}/v1/capes/${capeId}.png`, { headers: { authorization: `Bearer trs_${'x'.repeat(43)}` } })
  check('invalid token still loads public texture', stale.status === 200)
  await stale.arrayBuffer()
  const t304 = await fetch(`${BASE}/v1/capes/${capeId}.png`, { headers: { 'if-none-match': etag } })
  check('304 with ETag', t304.status === 304)
  const meta = await http('GET', `/v1/capes/${capeId}`)
  check('cape metadata', meta.json.cape.animated === false && meta.json.cape.frames === 1 && meta.json.cape.scale === 1)
  const setc = await http('PUT', '/v1/me/cape', { token: B, body: { capeId } })
  check('set active cape', setc.json.activeCape.id === capeId)

  console.log('lookup + presence + friends + events')
  const lk = await http('POST', '/v1/players/lookup', { token: A, body: { uuids: ['b0b0b0b0-b0b0-b0b0-b0b0-b0b0b0b0b0b0', 'ffffffffffffffffffffffffffffffff'] } })
  check('lookup', lk.json.players.length === 1 && lk.json.players[0].cape.id === capeId && lk.json.players[0].badge === true, JSON.stringify(lk.json))

  // SSE-Stream von A öffnen, dann schickt B eine Anfrage.
  const ac = new AbortController()
  const events = []
  const sse = fetch(`${BASE}/v1/events`, { headers: { authorization: `Bearer ${A}` }, signal: ac.signal }).then(async (res) => {
    check('sse content-type', res.headers.get('content-type') === 'text/event-stream')
    const reader = res.body.getReader()
    const dec = new TextDecoder()
    let buf = ''
    for (;;) {
      const { value, done } = await reader.read()
      if (done) break
      buf += dec.decode(value)
      for (const m of buf.matchAll(/event: (\S+)\ndata: (.*)\n\n/g)) events.push(m[1])
      buf = buf.slice(buf.lastIndexOf('\n\n') + 2)
    }
  }).catch(() => {})
  await new Promise((r) => setTimeout(r, 300))
  const fr = await http('POST', '/v1/friends/requests', { token: B, body: { target: 'theredstonee' } })
  check('friend request 201', fr.status === 201 && fr.json.status === 'sent')
  const acc = await http('POST', '/v1/friends/requests/b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0/accept', { token: A })
  check('accept', acc.status === 200 && acc.json.friend.name === 'Bob')
  const pr = await http('POST', '/v1/presence', { token: B, body: { state: 'in-game', game: { version: '1.21.1', loader: 'fabric', server: 'Play.Example.net' } } })
  check('presence', pr.json.expiresInSec === 180)
  await new Promise((r) => setTimeout(r, 300))
  const fl = await http('GET', '/v1/friends', { token: A })
  const bob = fl.json.friends.find((f) => f.name === 'Bob')
  check('friend presence with server', bob?.presence?.state === 'in-game' && bob.presence.game.server === 'play.example.net', JSON.stringify(fl.json))
  check('sse events', events.includes('hello') && events.includes('friend_request') && events.includes('presence'), events.join(','))
  ac.abort()
  await sse

  console.log('redeem brute force + rate limits')
  const statuses = []
  for (let i = 0; i < 7; i++) {
    statuses.push((await http('POST', '/v1/capes/redeem', { token: B, body: { code: 'ABCDE-FGHJK-MNPQR-STVWX' } })).status)
  }
  check('redeem: 5× 404 then 429', statuses.slice(0, 5).every((s) => s === 404) && statuses[5] === 429, statuses.join(','))
  const rl = []
  for (let i = 0; i < 22; i++) rl.push(await http('POST', '/v1/auth/challenge', { ip: '192.0.2.99' }))
  const last = rl.at(-1)
  check('challenge rate limit 429 + Retry-After', last.status === 429 && Number(last.headers.get('retry-after')) > 0)

  console.log('admin + deletion')
  const stats = await http('GET', '/v1/admin/stats', { token: A })
  check('stats', stats.json.users.total === 2 && stats.json.capes.approved === 1, JSON.stringify(stats.json))
  const ban = await http('POST', '/v1/admin/users/b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0/ban', { headers: { 'x-admin-key': ADMIN_KEY }, body: { reason: 'smoke' } })
  check('ban', ban.json.user.banned?.reason === 'smoke')
  const banned = await http('GET', '/v1/me', { token: B })
  check('banned token rejected', banned.status === 401 || banned.status === 403)
  await http('DELETE', '/v1/admin/users/b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0/ban', { headers: { 'x-admin-key': ADMIN_KEY } })
  const del = await http('DELETE', '/v1/me', { token: A })
  check('delete account 204', del.status === 204)
  const after = await http('GET', '/v1/me', { token: A })
  check('token gone after deletion', after.status === 401)
} catch (err) {
  failures++
  console.error(err)
} finally {
  api.kill()
  mojang.close()
  await new Promise((r) => setTimeout(r, 300))
  rmSync(data, { recursive: true, force: true })
}

if (/at .*\.mjs:\d+/.test(serverLog)) console.log('server log contains stack traces (server side only):\n', serverLog.slice(0, 2000))
console.log(`\n${passed} passed, ${failures} failed`)
process.exit(failures === 0 ? 0 : 1)
