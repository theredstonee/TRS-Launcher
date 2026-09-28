import { randomBytes, randomInt } from 'node:crypto'
import type { AppContext } from './context'
import { one, run, tx } from './db'
import { ApiError, badRequest, conflict, notFound, unavailable } from './errors'
import { safeEqual, sha256Hex } from './ids'
import { activeSanction } from './sanctions'

/**
 * Anmeldung auf der Website per TRS Launcher (§29) – Code-Abgleich wie bei „Gerät koppeln“:
 *
 * 1. Die Website legt eine Anfrage an (`createLauncherLogin`). Der Browser bekommt einen langen, zufälligen
 *    **Link-Token** (für `trs-launcher://web-login/<token>`), einen kurzen **Bestätigungscode** zum Abgleichen und ein
 *    httpOnly-Cookie mit einem **Browser-Wert** – nur dieser Browser kann die Anfrage später einlösen.
 * 2. Der Launcher schlägt die Anfrage mit seinem Konto-Token nach (per Link-Token oder – anderer PC – per Code,
 *    streng begrenzt) und zeigt Code, Browser und Zeit. Erst ein Klick auf „Bestätigen“ gibt sie frei.
 * 3. Die Website fragt alle 2 s nach (`pollLauncherLogin`) und bekommt nach der Freigabe genau einmal die normale
 *    Website-Sitzung (wie nach der Microsoft-Anmeldung, mit Session-Rotation).
 *
 * Gespeichert werden nur SHA-256 von Link-Token und Browser-Wert, der Code (zum Nachschlagen), eine grobe
 * Browser-Angabe („Firefox · Windows“) und nach der Freigabe die UUID. Keine IP-Adresse, kein User-Agent.
 */

/** So lange gilt eine Anfrage (Website zeigt die Restzeit). */
export const LAUNCHER_LOGIN_TTL_MS = 2 * 60 * 1000
/** Nach der Freigabe bleibt mindestens so viel Zeit zum Einlösen. */
const APPROVED_GRACE_MS = 30 * 1000
/** Cookie mit dem Browser-Wert (httpOnly, Secure, SameSite=Strict, nur für die Anmelde-Routen). */
export const LAUNCHER_LOGIN_COOKIE = 'trs_llogin'
export const LAUNCHER_LOGIN_COOKIE_PATH = '/v1/web/launcher-login'
/** Höchstens so viele offene Anfragen insgesamt (Schutz vor Überflutung). */
export const MAX_PENDING_LAUNCHER_LOGINS = 2000
/** Code-Zeichen: ohne 0/O, 1/I/L und U (gut abzulesen und abzutippen). */
export const LOGIN_CODE_ALPHABET = '23456789ABCDEFGHJKMNPQRSTVWXYZ'
export const LOGIN_CODE_LENGTH = 6

export const LOGIN_TOKEN = /^[A-Za-z0-9_-]{43}$/
export const LOGIN_REQUEST_ID = /^[A-Za-z0-9_-]{22}$/
const CODE = new RegExp(`^[${LOGIN_CODE_ALPHABET}]{${LOGIN_CODE_LENGTH}}$`)

export type LauncherLoginStatus = 'pending' | 'approved' | 'denied'

interface LoginRow {
  id: string
  token_hash: string
  code: string
  browser_hash: string
  browser: string | null
  return_to: string
  status: LauncherLoginStatus
  uuid: string | null
  created_at: number
  expires_at: number
  decided_at: number | null
}

/** Eingabe → Code ohne Striche/Leerzeichen in Großbuchstaben, sonst `null`. */
export function normalizeLoginCode(input: string): string | null {
  if (typeof input !== 'string' || input.length > 20) return null
  const c = input.toUpperCase().replace(/[\s-]/g, '')
  return CODE.test(c) ? c : null
}

/** Anzeige „K7Q-2MX“. */
export function formatLoginCode(code: string): string {
  return `${code.slice(0, 3)}-${code.slice(3)}`
}

function newCode(): string {
  let c = ''
  for (let i = 0; i < LOGIN_CODE_LENGTH; i++) c += LOGIN_CODE_ALPHABET[randomInt(LOGIN_CODE_ALPHABET.length)]
  return c
}

/**
 * Grobe Browser-Angabe für den Bestätigungsdialog im Launcher („Firefox · Windows“) – nur aus einer festen Liste,
 * damit nichts aus dem User-Agent ungeprüft weitergeht. Unbekannt → `null`.
 */
export function browserLabel(ua: string | undefined): string | null {
  if (!ua) return null
  const u = ua.slice(0, 512)
  const browser = /Edg(?:e|A|iOS)?\//.test(u)
    ? 'Edge'
    : /OPR\/|Opera/.test(u)
      ? 'Opera'
      : /Vivaldi\//.test(u)
        ? 'Vivaldi'
        : /Firefox\/|FxiOS\//.test(u)
          ? 'Firefox'
          : /Chrome\/|CriOS\//.test(u)
            ? 'Chrome'
            : /Safari\//.test(u)
              ? 'Safari'
              : null
  const os = /Windows/.test(u)
    ? 'Windows'
    : /Android/.test(u)
      ? 'Android'
      : /iPhone|iPad|iPod/.test(u)
        ? 'iOS'
        : /Mac OS X|Macintosh/.test(u)
          ? 'macOS'
          : /CrOS/.test(u)
            ? 'ChromeOS'
            : /Linux|X11/.test(u)
              ? 'Linux'
              : null
  if (!browser && !os) return null
  return [browser, os].filter(Boolean).join(' · ')
}

export interface NewLauncherLogin {
  /** Link-Token (nur für `trs-launcher://web-login/<token>` und die Abfrage des Stands). */
  token: string
  /** Browser-Wert fürs Cookie. */
  browserSecret: string
  /** `K7Q-2MX` */
  code: string
  link: string
  expiresAt: string
}

/**
 * Neue Anfrage für diesen Browser. `previousBrowserSecret` = Cookie einer älteren Anfrage desselben Browsers: die
 * wird verworfen (je Browser höchstens eine offene Anfrage).
 */
export function createLauncherLogin(
  ctx: AppContext,
  opts: { returnTo: string, userAgent?: string, previousBrowserSecret?: string },
): NewLauncherLogin {
  const t = ctx.now()
  const pending = one<{ n: number }>(ctx.db, 'SELECT COUNT(*) AS n FROM launcher_logins WHERE expires_at > ?', t)!.n
  if (pending >= MAX_PENDING_LAUNCHER_LOGINS) {
    throw new ApiError(503, 'busy', 'Too many sign-in requests right now, try again in a minute', { retryAfter: 30 }, { 'Retry-After': '30' })
  }
  const token = randomBytes(32).toString('base64url')
  const browserSecret = randomBytes(32).toString('base64url')
  const id = randomBytes(16).toString('base64url')
  const expires = t + LAUNCHER_LOGIN_TTL_MS
  const browser = browserLabel(opts.userAgent)
  let code = ''
  tx(ctx.db, () => {
    if (opts.previousBrowserSecret && LOGIN_TOKEN.test(opts.previousBrowserSecret)) {
      run(ctx.db, 'DELETE FROM launcher_logins WHERE browser_hash = ?', sha256Hex(opts.previousBrowserSecret))
    }
    // Abgelaufene Anfragen halten ihren Code nicht länger fest.
    run(ctx.db, 'DELETE FROM launcher_logins WHERE expires_at <= ?', t)
    for (let attempt = 0; ; attempt++) {
      code = newCode()
      if (!one(ctx.db, 'SELECT 1 AS x FROM launcher_logins WHERE code = ?', code)) break
      if (attempt >= 20) throw unavailable('busy', 'Could not create a sign-in code, try again')
    }
    run(
      ctx.db,
      `INSERT INTO launcher_logins (id, token_hash, code, browser_hash, browser, return_to, status, created_at, expires_at)
       VALUES (?, ?, ?, ?, ?, ?, 'pending', ?, ?)`,
      id, sha256Hex(token), code, sha256Hex(browserSecret), browser, opts.returnTo, t, expires,
    )
  })
  return {
    token,
    browserSecret,
    code: formatLoginCode(code),
    link: `trs-launcher://web-login/${token}`,
    expiresAt: new Date(expires).toISOString(),
  }
}

// ---------------------------------------------------------------- Launcher-Seite

/** Was der Launcher im Bestätigungsdialog zeigt. */
export interface LauncherLoginView {
  /** Bezug für Bestätigen/Ablehnen (nicht der Link-Token). */
  id: string
  /** `K7Q-2MX` */
  code: string
  /** Host der Website, z. B. `trs-launcher.theredstonee.de`. */
  site: string
  /** Grobe Browser-Angabe oder `null`. */
  browser: string | null
  createdAt: string
  expiresAt: string
}

function viewOf(ctx: AppContext, r: LoginRow): LauncherLoginView {
  let site = ctx.config.siteUrl
  try {
    site = new URL(ctx.config.siteUrl).host
  } catch {
    // Adresse bleibt, wie sie ist.
  }
  return {
    id: r.id,
    code: formatLoginCode(r.code),
    site,
    browser: r.browser,
    createdAt: new Date(r.created_at).toISOString(),
    expiresAt: new Date(r.expires_at).toISOString(),
  }
}

const expired = () => notFound('login_request_expired', 'This sign-in request is unknown or expired – start again on the website')
const used = () => conflict('login_request_used', 'This sign-in request was already answered')

/** Offene Anfrage zum Link-Token. */
export function lookupByToken(ctx: AppContext, token: string): LauncherLoginView {
  if (!LOGIN_TOKEN.test(token)) throw expired()
  const r = one<LoginRow>(ctx.db, 'SELECT * FROM launcher_logins WHERE token_hash = ? AND expires_at > ?', sha256Hex(token), ctx.now())
  if (!r) throw expired()
  if (r.status !== 'pending') throw used()
  return viewOf(ctx, r)
}

/**
 * Offene Anfrage zum eingetippten Code (anderer PC). `null` = kein Treffer – die Route zählt das als Fehlversuch.
 * Ungültige Eingaben (falsche Zeichen/Länge) werfen `invalid_code`, ohne die Datenbank zu fragen.
 */
export function lookupByCode(ctx: AppContext, input: string): LauncherLoginView | null {
  const code = normalizeLoginCode(input)
  if (!code) throw badRequest('invalid_code', 'This is not a valid sign-in code')
  const r = one<LoginRow>(ctx.db, "SELECT * FROM launcher_logins WHERE code = ? AND expires_at > ? AND status = 'pending'", code, ctx.now())
  return r ? viewOf(ctx, r) : null
}

/**
 * Bestätigen (`approve`) oder ablehnen. Nur offene, nicht abgelaufene Anfragen, genau einmal; der Code muss zur
 * Anfrage passen (der Launcher schickt den angezeigten Code mit). Gesperrte Konten kommen hier nicht an
 * (Bearer-Anmeldung wirft vorher `banned`) – zur Sicherheit wird trotzdem geprüft.
 */
export function decideLauncherLogin(ctx: AppContext, uuid: string, id: string, codeInput: string, approve: boolean): void {
  if (!LOGIN_REQUEST_ID.test(id)) throw expired()
  const code = normalizeLoginCode(codeInput)
  const t = ctx.now()
  const r = one<LoginRow>(ctx.db, 'SELECT * FROM launcher_logins WHERE id = ? AND expires_at > ?', id, t)
  if (!r || !code || !safeEqual(code, r.code)) throw expired()
  if (r.status !== 'pending') throw used()
  if (approve && activeSanction(ctx, uuid, 'account_ban')) throw new ApiError(403, 'banned', 'This account is banned')
  const changed = approve
    ? run(
      ctx.db,
      "UPDATE launcher_logins SET status = 'approved', uuid = ?, decided_at = ?, expires_at = MAX(expires_at, ?) WHERE id = ? AND status = 'pending'",
      uuid, t, t + APPROVED_GRACE_MS, id,
    )
    : run(ctx.db, "UPDATE launcher_logins SET status = 'denied', decided_at = ? WHERE id = ? AND status = 'pending'", t, id)
  if (changed !== 1) throw used()
}

// ---------------------------------------------------------------- Website-Seite

export type PollResult =
  | { status: 'pending', expiresAt: string }
  | { status: 'expired' }
  | { status: 'denied', reason: 'denied' | 'banned' }
  | { status: 'approved', uuid: string, returnTo: string }

/**
 * Stand der Anfrage für den Browser, der sie angelegt hat (Link-Token + Browser-Wert aus dem Cookie müssen passen).
 * `approved` kommt genau einmal: die Anfrage wird dabei gelöscht, die Route legt die Sitzung an. Abgelehnte und
 * abgelaufene Anfragen werden ebenfalls gelöscht.
 */
export function pollLauncherLogin(ctx: AppContext, token: string, browserSecret: string | undefined): PollResult {
  if (!LOGIN_TOKEN.test(token) || !browserSecret || !LOGIN_TOKEN.test(browserSecret)) return { status: 'expired' }
  const r = one<LoginRow>(ctx.db, 'SELECT * FROM launcher_logins WHERE token_hash = ?', sha256Hex(token))
  if (!r || !safeEqual(sha256Hex(browserSecret), r.browser_hash)) return { status: 'expired' }
  const t = ctx.now()
  if (r.expires_at <= t) {
    run(ctx.db, 'DELETE FROM launcher_logins WHERE id = ?', r.id)
    return { status: 'expired' }
  }
  if (r.status === 'pending') return { status: 'pending', expiresAt: new Date(r.expires_at).toISOString() }
  // Einmalig: nur wer die Zeile tatsächlich löscht, bekommt das Ergebnis.
  if (run(ctx.db, 'DELETE FROM launcher_logins WHERE id = ?', r.id) !== 1) return { status: 'expired' }
  if (r.status === 'denied' || !r.uuid) return { status: 'denied', reason: 'denied' }
  if (activeSanction(ctx, r.uuid, 'account_ban')) return { status: 'denied', reason: 'banned' }
  return { status: 'approved', uuid: r.uuid, returnTo: r.return_to }
}

/** Anfrage dieses Browsers zurückziehen („Zurück“ auf der Website). */
export function cancelLauncherLogin(ctx: AppContext, token: string, browserSecret: string | undefined): void {
  if (!LOGIN_TOKEN.test(token) || !browserSecret || !LOGIN_TOKEN.test(browserSecret)) return
  run(ctx.db, 'DELETE FROM launcher_logins WHERE token_hash = ? AND browser_hash = ?', sha256Hex(token), sha256Hex(browserSecret))
}

export function sweepLauncherLogins(ctx: AppContext): void {
  run(ctx.db, 'DELETE FROM launcher_logins WHERE expires_at <= ?', ctx.now())
}
