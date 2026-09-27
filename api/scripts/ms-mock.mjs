/**
 * Attrappe der Microsoft-, Xbox- und Minecraft-Endpunkte für Tests und die lokale Vorschau (§24.1).
 * NIE in Produktion verwenden – sie prüft nur, was die echte Kette auch prüft (Client-Secret, Redirect-URI,
 * PKCE S256, einmalige Codes) und liefert erfundene Tokens.
 *
 *   import { startMsMock } from './ms-mock.mjs'
 *   const mock = await startMsMock({ clientId, clientSecret, accounts: [{ name: 'Steve', uuid: '…' }] })
 *   // MS_AUTHORITY_URL=mock.authority, XBOX_USER_AUTH_URL=mock.xbl, XBOX_XSTS_URL=mock.xsts, MINECRAFT_SERVICES_URL=mock.minecraft
 *
 * Konto-Auswahl: `/authorize` zeigt eine kleine Seite mit allen Konten (Klick = Anmeldung). Automatisch geht es mit
 * `mock.next = 'Steve'` (einmalig) oder `?login_hint=Steve`. Fehlerfälle je Konto: `xerr` (XSTS-Fehler, z. B.
 * 2148916238 = Kinderkonto), `noGame` (Profil 404), `denied` (Nutzer bricht ab → `error=access_denied`).
 */
import { createHash, randomBytes } from 'node:crypto'
import { createServer } from 'node:http'
import { URLSearchParams } from 'node:url'

export async function startMsMock({ clientId, clientSecret, accounts, port = 0 }) {
  const codes = new Map()
  const state = { next: null, calls: [] }
  const byName = (n) => accounts.find((a) => a.name.toLowerCase() === String(n ?? '').toLowerCase())

  const server = createServer(async (req, res) => {
    const u = new URL(req.url, 'http://x')
    const chunks = []
    for await (const c of req) chunks.push(c)
    const raw = Buffer.concat(chunks).toString('utf8')
    state.calls.push(`${req.method} ${u.pathname}`)
    const json = (status, body) => {
      res.writeHead(status, { 'content-type': 'application/json' })
      res.end(JSON.stringify(body))
    }

    if (req.method === 'GET' && u.pathname === '/consumers/oauth2/v2.0/authorize') {
      const q = u.searchParams
      if (q.get('client_id') !== clientId || q.get('code_challenge_method') !== 'S256' || !q.get('code_challenge') || q.get('scope') !== 'XboxLive.signin') {
        res.writeHead(400, { 'content-type': 'text/plain' })
        return res.end('bad authorize request')
      }
      const redirect = q.get('redirect_uri')
      const pick = u.searchParams.get('pick') ?? state.next ?? q.get('login_hint')
      state.next = null
      const account = byName(pick)
      if (account) {
        const target = new URL(redirect)
        if (account.denied) {
          target.searchParams.set('error', 'access_denied')
        } else {
          const code = `mc_${randomBytes(12).toString('hex')}`
          codes.set(code, { account, challenge: q.get('code_challenge'), redirect })
          target.searchParams.set('code', code)
        }
        target.searchParams.set('state', q.get('state'))
        res.writeHead(302, { location: target.toString() })
        return res.end()
      }
      // Auswahlseite (für die Vorschau im Browser).
      const links = accounts
        .map((a) => {
          const l = new URL(u.toString())
          l.searchParams.set('pick', a.name)
          return `<li><a href="${l.pathname}${l.search}">${a.name}</a> <small>${a.xerr ? `XSTS ${a.xerr}` : a.noGame ? 'no Minecraft' : a.denied ? 'cancels' : ''}</small></li>`
        })
        .join('')
      res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' })
      return res.end(`<!doctype html><meta charset="utf-8"><title>Microsoft (mock)</title><body style="font:16px system-ui;background:#f3f3f3;display:grid;place-items:center;min-height:90vh"><div style="background:#fff;padding:32px 40px;box-shadow:0 2px 8px #0002;min-width:320px"><h1 style="font-size:22px;margin:0 0 4px">Microsoft</h1><p style="color:#666;margin:0 0 16px">Local test double – pick an account</p><ul style="line-height:2">${links}</ul></div>`)
    }

    if (req.method === 'POST' && u.pathname === '/consumers/oauth2/v2.0/token') {
      const f = new URLSearchParams(raw)
      const entry = codes.get(f.get('code'))
      codes.delete(f.get('code'))
      if (f.get('client_id') !== clientId || f.get('client_secret') !== clientSecret) return json(401, { error: 'invalid_client' })
      if (!entry || f.get('grant_type') !== 'authorization_code' || f.get('redirect_uri') !== entry.redirect) return json(400, { error: 'invalid_grant' })
      const challenge = createHash('sha256').update(f.get('code_verifier') ?? '').digest('base64url')
      if (challenge !== entry.challenge) return json(400, { error: 'invalid_grant', error_description: 'PKCE' })
      return json(200, { token_type: 'Bearer', scope: 'XboxLive.signin', expires_in: 3600, access_token: `ms.${entry.account.name}` })
    }

    if (req.method === 'POST' && u.pathname === '/xbl') {
      const body = JSON.parse(raw || '{}')
      const ticket = body?.Properties?.RpsTicket ?? ''
      const account = ticket.startsWith('d=ms.') ? byName(ticket.slice(5)) : null
      if (!account || body.RelyingParty !== 'http://auth.xboxlive.com') return json(400, {})
      return json(200, { Token: `xbl.${account.name}`, DisplayClaims: { xui: [{ uhs: `uhs${account.name.length}` }] } })
    }

    if (req.method === 'POST' && u.pathname === '/xsts') {
      const body = JSON.parse(raw || '{}')
      const tok = body?.Properties?.UserTokens?.[0] ?? ''
      const account = tok.startsWith('xbl.') ? byName(tok.slice(4)) : null
      if (!account || body.RelyingParty !== 'rp://api.minecraftservices.com/') return json(400, {})
      if (account.xerr) return json(401, { Identity: '0', XErr: account.xerr, Message: '', Redirect: 'https://start.ui.xboxlive.com/' })
      return json(200, { Token: `xsts.${account.name}`, DisplayClaims: { xui: [{ uhs: `uhs${account.name.length}` }] } })
    }

    if (req.method === 'POST' && u.pathname === '/mc/authentication/login_with_xbox') {
      const body = JSON.parse(raw || '{}')
      const m = /^XBL3\.0 x=uhs(\d+);xsts\.(.+)$/.exec(body?.identityToken ?? '')
      const account = m ? byName(m[2]) : null
      if (!account || Number(m[1]) !== account.name.length) return json(401, { error: 'UNAUTHORIZED' })
      return json(200, { username: 'x', access_token: `mc.${account.name}`, token_type: 'Bearer', expires_in: 86400 })
    }

    if (req.method === 'GET' && u.pathname === '/mc/minecraft/profile') {
      const auth = req.headers.authorization ?? ''
      const account = auth.startsWith('Bearer mc.') ? byName(auth.slice(10)) : null
      if (!account) return json(401, {})
      if (account.noGame) return json(404, { path: '/minecraft/profile', errorType: 'NOT_FOUND', error: 'NOT_FOUND' })
      return json(200, { id: account.uuid, name: account.name, skins: [], capes: [] })
    }

    json(404, { error: 'not_found' })
  })
  await new Promise((r) => server.listen(port, '127.0.0.1', r))
  const base = `http://127.0.0.1:${server.address().port}`
  return {
    base,
    authority: `${base}/consumers/oauth2/v2.0`,
    xbl: `${base}/xbl`,
    xsts: `${base}/xsts`,
    minecraft: `${base}/mc`,
    get calls() {
      return state.calls
    },
    set next(name) {
      state.next = name
    },
    /** Nutzt einen erfundenen Code direkt (für Tests ohne Browser): wie ein Klick auf das Konto. */
    issueCode(name, challenge, redirect) {
      const account = byName(name)
      const code = `mc_${randomBytes(12).toString('hex')}`
      codes.set(code, { account, challenge, redirect })
      return code
    },
    close: () => new Promise((r) => server.close(r)),
  }
}
