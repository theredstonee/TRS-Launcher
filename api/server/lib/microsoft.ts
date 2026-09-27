import { createHash, randomBytes } from 'node:crypto'
import { z } from 'zod'
import type { AppContext } from './context'
import { safeEqual } from './ids'
import { normalizeUuid } from './ids'

/**
 * Website-Anmeldung mit Microsoft (§24.1): Authorization Code + PKCE als vertraulicher Client (Client-Secret nur
 * auf dem Server), danach Xbox Live → XSTS (Minecraft) → `login_with_xbox` → `minecraft/profile`.
 * Heraus kommen nur UUID und Name. Alle Tokens leben nur während dieser Kette im Speicher und werden danach
 * verworfen – gespeichert wird allein die Website-Sitzung.
 */

export interface MicrosoftConfig {
  clientId: string
  clientSecret: string
  redirectUri: string
  /** `…/oauth2/v2.0` des Tenants `consumers` (nur private Microsoft-Konten, wie im Launcher). */
  authorityUrl: string
  xboxUserAuthUrl: string
  xboxXstsUrl: string
  minecraftServicesUrl: string
}

export const MS_SCOPE = 'XboxLive.signin'
/** Anmeldung (Weg zu Microsoft und zurück) muss so schnell abgeschlossen sein. */
export const MS_STATE_TTL_MS = 10 * 60 * 1000
export const MS_STATE_COOKIE = 'trs_oauth'
const MAX_PENDING = 5000
const UPSTREAM_TIMEOUT_MS = 10_000
const MAX_BODY = 64 * 1024

/** Fehler der Anmeldung → Code für die Website (`/login?error=…`). Nie Details aus fremden Antworten. */
export type MsLoginErrorCode =
  | 'ms_disabled'
  | 'ms_cancelled'
  | 'ms_state'
  | 'ms_failed'
  | 'no_xbox'
  | 'child_account'
  | 'xbox_region'
  | 'xbox_verification'
  | 'xbox_banned'
  | 'no_minecraft'
  | 'banned'
  | 'rate_limited'

export class MsLoginError extends Error {
  constructor(readonly code: MsLoginErrorCode, detail?: string) {
    super(detail ?? code)
    this.name = 'MsLoginError'
  }
}

// ---------------------------------------------------------------- state + PKCE (nur RAM, einmalig)

interface Pending {
  state: string
  verifier: string
  returnTo: string
  expires: number
}

/**
 * Offene Anmeldungen: Schlüssel = zufälliger Wert im kurzlebigen Cookie `trs_oauth` (httpOnly, Secure,
 * SameSite=Lax, Pfad /auth/microsoft). `state` geht zusätzlich über Microsoft und muss zum Cookie passen; der
 * PKCE-Verifier verlässt den Server nur Richtung Token-Endpunkt. Jeder Eintrag gilt genau einmal.
 */
export class OAuthStateStore {
  private pending = new Map<string, Pending>()
  constructor(private readonly now: () => number) {}

  create(returnTo: string): { handle: string, state: string, challenge: string } {
    this.sweep()
    if (this.pending.size >= MAX_PENDING) {
      // Überlauf (Angriff): die ältesten verwerfen.
      let drop = Math.ceil(MAX_PENDING / 5)
      for (const k of this.pending.keys()) {
        if (drop-- <= 0) break
        this.pending.delete(k)
      }
    }
    const handle = randomBytes(32).toString('base64url')
    const state = randomBytes(32).toString('base64url')
    const verifier = randomBytes(48).toString('base64url')
    const challenge = createHash('sha256').update(verifier).digest('base64url')
    this.pending.set(handle, { state, verifier, returnTo, expires: this.now() + MS_STATE_TTL_MS })
    return { handle, state, challenge }
  }

  /** Einmalig einlösen: passt `state` zum Cookie-Eintrag? Sonst `null`. */
  take(handle: string | undefined, state: string | undefined): Pending | null {
    if (!handle || !/^[A-Za-z0-9_-]{43}$/.test(handle)) return null
    const p = this.pending.get(handle)
    if (!p) return null
    this.pending.delete(handle)
    if (p.expires <= this.now()) return null
    if (!state || !safeEqual(state, p.state)) return null
    return p
  }

  sweep(): void {
    const t = this.now()
    for (const [k, p] of this.pending) if (p.expires <= t) this.pending.delete(k)
  }

  get size(): number {
    return this.pending.size
  }
}

/** Nur eigene, relative Pfade als Rücksprung (kein offener Redirect). */
export function safeReturnTo(raw: unknown): string {
  if (typeof raw !== 'string' || raw.length > 200) return '/applications'
  if (!/^\/(?![/\\])[A-Za-z0-9/_\-.~%?=&]*$/.test(raw)) return '/applications'
  if (raw.startsWith('/auth/') || raw.startsWith('/v1/')) return '/applications'
  return raw
}

export function authorizeUrl(cfg: MicrosoftConfig, state: string, challenge: string): string {
  const q = new URLSearchParams({
    client_id: cfg.clientId,
    response_type: 'code',
    redirect_uri: cfg.redirectUri,
    response_mode: 'query',
    scope: MS_SCOPE,
    state,
    code_challenge: challenge,
    code_challenge_method: 'S256',
    prompt: 'select_account',
  })
  return `${cfg.authorityUrl}/authorize?${q.toString()}`
}

// ---------------------------------------------------------------- Kette Microsoft → Minecraft

type Fetch = typeof fetch

async function readJson(res: Response): Promise<unknown> {
  const text = await res.text()
  if (text.length > MAX_BODY) throw new MsLoginError('ms_failed', 'upstream body too large')
  if (!text) return null
  try {
    return JSON.parse(text) as unknown
  } catch {
    return null
  }
}

async function call(f: Fetch, url: string, init: RequestInit, step: string): Promise<{ status: number, body: unknown }> {
  let res: Response
  try {
    res = await f(url, { ...init, redirect: 'error', signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS) })
  } catch (err) {
    throw new MsLoginError('ms_failed', `${step}: ${(err as Error).message}`)
  }
  return { status: res.status, body: await readJson(res) }
}

const tokenResponse = z.object({ access_token: z.string().min(1).max(8192) })
const xblResponse = z.object({
  Token: z.string().min(1).max(8192),
  DisplayClaims: z.object({ xui: z.array(z.object({ uhs: z.string().min(1).max(64) })).min(1) }),
})
const xstsError = z.object({ XErr: z.union([z.number(), z.string()]) })
const mcLogin = z.object({ access_token: z.string().min(1).max(8192) })
const mcProfile = z.object({ id: z.string(), name: z.string().regex(/^[A-Za-z0-9_]{1,16}$/) })

/** Bekannte XSTS-Fehler (XErr) → Code für die Website. */
export function xstsErrorCode(xerr: number): MsLoginErrorCode {
  switch (xerr) {
    case 2148916233: return 'no_xbox'
    case 2148916235: return 'xbox_region'
    case 2148916236:
    case 2148916237: return 'xbox_verification'
    case 2148916238: return 'child_account'
    case 2148916227: return 'xbox_banned'
    default: return 'ms_failed'
  }
}

export interface MinecraftIdentity {
  uuid: string
  name: string
}

/** Code gegen Tokens tauschen und bis zum Minecraft-Profil durchgehen. Wirft {@link MsLoginError}. */
export async function exchangeCode(cfg: MicrosoftConfig, code: string, verifier: string, f: Fetch = fetch): Promise<MinecraftIdentity> {
  // 1) Microsoft: Code → Access-Token (vertraulicher Client: mit Secret, dazu PKCE).
  const token = await call(f, `${cfg.authorityUrl}/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded', Accept: 'application/json' },
    body: new URLSearchParams({
      client_id: cfg.clientId,
      client_secret: cfg.clientSecret,
      grant_type: 'authorization_code',
      code,
      redirect_uri: cfg.redirectUri,
      code_verifier: verifier,
      scope: MS_SCOPE,
    }).toString(),
  }, 'token')
  const msToken = tokenResponse.safeParse(token.body)
  if (token.status !== 200 || !msToken.success) {
    const err = (token.body as { error?: unknown } | null)?.error
    throw new MsLoginError('ms_failed', `token ${token.status} ${typeof err === 'string' ? err.slice(0, 40) : ''}`)
  }

  // 2) Xbox Live: Nutzer-Token (RpsTicket „d=“ = Token einer eigenen Azure-App).
  const xbl = await call(f, cfg.xboxUserAuthUrl, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json', 'x-xbl-contract-version': '1' },
    body: JSON.stringify({
      Properties: { AuthMethod: 'RPS', SiteName: 'user.auth.xboxlive.com', RpsTicket: `d=${msToken.data.access_token}` },
      RelyingParty: 'http://auth.xboxlive.com',
      TokenType: 'JWT',
    }),
  }, 'xbl')
  const xblData = xblResponse.safeParse(xbl.body)
  if (xbl.status === 401 || xbl.status === 400) throw new MsLoginError('no_xbox', `xbl ${xbl.status}`)
  if (xbl.status !== 200 || !xblData.success) throw new MsLoginError('ms_failed', `xbl ${xbl.status}`)
  const uhs = xblData.data.DisplayClaims.xui[0]!.uhs

  // 3) XSTS für Minecraft.
  const xsts = await call(f, cfg.xboxXstsUrl, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json', 'x-xbl-contract-version': '1' },
    body: JSON.stringify({
      Properties: { SandboxId: 'RETAIL', UserTokens: [xblData.data.Token] },
      RelyingParty: 'rp://api.minecraftservices.com/',
      TokenType: 'JWT',
    }),
  }, 'xsts')
  if (xsts.status === 401 || xsts.status === 403) {
    const e = xstsError.safeParse(xsts.body)
    throw new MsLoginError(e.success ? xstsErrorCode(Number(e.data.XErr)) : 'ms_failed', `xsts ${xsts.status}`)
  }
  const xstsData = xblResponse.safeParse(xsts.body)
  if (xsts.status !== 200 || !xstsData.success) throw new MsLoginError('ms_failed', `xsts ${xsts.status}`)

  // 4) Minecraft: login_with_xbox.
  const mc = await call(f, `${cfg.minecraftServicesUrl}/authentication/login_with_xbox`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify({ identityToken: `XBL3.0 x=${uhs};${xstsData.data.Token}` }),
  }, 'minecraft')
  const mcData = mcLogin.safeParse(mc.body)
  if (mc.status !== 200 || !mcData.success) throw new MsLoginError('ms_failed', `login_with_xbox ${mc.status}`)

  // 5) Profil: 404 = kein Minecraft (Java) gekauft bzw. noch kein Profil angelegt.
  const profile = await call(f, `${cfg.minecraftServicesUrl}/minecraft/profile`, {
    method: 'GET',
    headers: { Authorization: `Bearer ${mcData.data.access_token}`, Accept: 'application/json' },
  }, 'profile')
  if (profile.status === 404) throw new MsLoginError('no_minecraft', 'profile 404')
  const p = mcProfile.safeParse(profile.body)
  const uuid = p.success ? normalizeUuid(p.data.id) : null
  if (profile.status !== 200 || !p.success || !uuid) throw new MsLoginError('ms_failed', `profile ${profile.status}`)
  return { uuid, name: p.data.name }
}

export function msConfig(ctx: AppContext): MicrosoftConfig | null {
  return ctx.config.microsoft
}
