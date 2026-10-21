import { randomInt } from 'node:crypto'
import { z } from 'zod'
import { audit } from './audit'
import type { AppContext } from './context'
import { all, one, run } from './db'
import { badRequest, conflict, notFound } from './errors'
import { sha256Hex } from './ids'
import { sanitizeText } from './safety'
import { assertCan, type Staff } from './team'

/**
 * Pitch-Links (§38): Die Pitch-Präsentation (statisches Bundle in assets/pitch, Server-Asset `pitch`) ist nur über
 * geheime Links erreichbar, die das Team im Admin-Bereich erzeugt: `/pitch/<token>`.
 *
 * - Token: 32 Zeichen Base62 (≈ 190 Bit Zufall). Gespeichert wird nur der SHA-256 (Nachschlagen per Hash – kein
 *   Vergleich von Klartext) und die letzten 4 Zeichen für die Anzeige. Den vollen Link gibt es genau einmal, beim
 *   Anlegen.
 * - Je Link: Bezeichnung, Standard-Modus (loop/manual) und -Sprache (de/en) – beides hängt die kopierbare Adresse als
 *   Query an –, optionales Ablaufdatum, Widerruf, Aufrufzähler (nur die Seite selbst, nicht die Dateien).
 * - Ungültig/abgelaufen/widerrufen → überall dieselbe 404-Seite (server/lib/pitch-http.ts).
 */

export const PITCH_MODES = ['loop', 'manual'] as const
export type PitchMode = (typeof PITCH_MODES)[number]
export const PITCH_LANGS = ['de', 'en'] as const
export type PitchLang = (typeof PITCH_LANGS)[number]
/** Wählbare Laufzeiten in Tagen; `null` = unbegrenzt. */
export const PITCH_EXPIRY_DAYS = [1, 7, 30, 90] as const
export const PITCH_TOKEN_LENGTH = 32
export const PITCH_TOKEN = /^[A-Za-z0-9]{32}$/
export const MAX_LABEL = 80
/** Höchstens so viele Links insgesamt (auch widerrufene/abgelaufene) – alte zuerst löschen. */
export const MAX_PITCH_LINKS = 500

const BASE62 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'
const DAY = 24 * 60 * 60 * 1000

/** Neuer Token: 32 Zeichen Base62, jedes Zeichen gleichverteilt (crypto.randomInt, ohne Modulo-Verzerrung). */
export function newPitchToken(): string {
  let out = ''
  for (let i = 0; i < PITCH_TOKEN_LENGTH; i++) out += BASE62[randomInt(BASE62.length)]
  return out
}

export function pitchTokenHash(token: string): string {
  return sha256Hex(token)
}

// ---------------------------------------------------------------- Ansichten

interface PitchRow {
  id: number
  token_hash: string
  token_hint: string
  label: string
  mode: PitchMode
  lang: PitchLang
  created_by: string
  created_at: number
  expires_at: number | null
  revoked_at: number | null
  views: number
  last_view_at: number | null
}

export type PitchStatus = 'active' | 'expired' | 'revoked'

/** Ansicht im Team-Bereich (Vertrag API.md §38.2). */
export interface PitchLink {
  id: number
  label: string
  mode: PitchMode
  lang: PitchLang
  /** Letzte 4 Zeichen des Tokens (Anzeige „…abcd“). */
  tokenHint: string
  status: PitchStatus
  createdBy: string
  createdByName: string | null
  createdAt: string
  expiresAt: string | null
  revokedAt: string | null
  views: number
  lastViewAt: string | null
}

const iso = (t: number | null) => (t === null ? null : new Date(t).toISOString())

export function pitchStatus(r: Pick<PitchRow, 'expires_at' | 'revoked_at'>, now: number): PitchStatus {
  if (r.revoked_at !== null) return 'revoked'
  if (r.expires_at !== null && r.expires_at <= now) return 'expired'
  return 'active'
}

function view(ctx: AppContext, r: PitchRow & { creator_name?: string | null }): PitchLink {
  return {
    id: r.id,
    label: r.label,
    mode: r.mode,
    lang: r.lang,
    tokenHint: r.token_hint,
    status: pitchStatus(r, ctx.now()),
    createdBy: r.created_by,
    createdByName: r.creator_name ?? null,
    createdAt: iso(r.created_at)!,
    expiresAt: iso(r.expires_at),
    revokedAt: iso(r.revoked_at),
    views: r.views,
    lastViewAt: iso(r.last_view_at),
  }
}

const SELECT = 'SELECT p.*, u.name AS creator_name FROM pitch_links p LEFT JOIN users u ON u.uuid = p.created_by'

function rowOf(ctx: AppContext, id: number): PitchRow & { creator_name: string | null } {
  const r = one<PitchRow & { creator_name: string | null }>(ctx.db, `${SELECT} WHERE p.id = ?`, id)
  if (!r) throw notFound('not_found', 'No such pitch link')
  return r
}

/** Kopierbare Adresse: `<site>/pitch/<token>?mode=…&lang=…`. */
export function pitchUrl(ctx: AppContext, token: string, mode: PitchMode, lang: PitchLang): string {
  const q = new URLSearchParams({ mode, lang })
  return `${ctx.config.siteUrl.replace(/\/+$/, '')}/pitch/${token}?${q.toString()}`
}

// ---------------------------------------------------------------- Team

export const pitchCreateBody = z.strictObject({
  label: z.string().trim().min(1).max(MAX_LABEL),
  mode: z.enum(PITCH_MODES).default('loop'),
  lang: z.enum(PITCH_LANGS).default('de'),
  expiresInDays: z.union([z.literal(1), z.literal(7), z.literal(30), z.literal(90), z.null()]).optional(),
})
export type PitchCreate = z.output<typeof pitchCreateBody>

/** Alle Links (neueste zuerst), auch widerrufene und abgelaufene. */
export function listPitchLinks(ctx: AppContext, staff: Staff): PitchLink[] {
  assertCan(staff, 'pitch.manage')
  return all<PitchRow & { creator_name: string | null }>(ctx.db, `${SELECT} ORDER BY p.created_at DESC, p.id DESC`).map((r) => view(ctx, r))
}

/** Neuer Link. Der Klartext-Token steckt nur in dieser Antwort (`token`, `url`). */
export function createPitchLink(ctx: AppContext, staff: Staff, body: PitchCreate): { link: PitchLink, token: string, url: string } {
  assertCan(staff, 'pitch.manage')
  const count = one<{ n: number }>(ctx.db, 'SELECT COUNT(*) AS n FROM pitch_links')!.n
  if (count >= MAX_PITCH_LINKS) throw conflict('pitch_limit', `At most ${MAX_PITCH_LINKS} pitch links – delete old ones first`)
  // Steuer-/unsichtbare Zeichen raus, eine Zeile.
  const label = sanitizeText(body.label).replace(/\s+/g, ' ').trim()
  if (!label) throw badRequest('invalid_request', 'Label is empty')
  const t = ctx.now()
  const expires = body.expiresInDays ? t + body.expiresInDays * DAY : null
  const token = newPitchToken()
  run(
    ctx.db,
    `INSERT INTO pitch_links (token_hash, token_hint, label, mode, lang, created_by, created_at, expires_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
    pitchTokenHash(token), token.slice(-4), label, body.mode, body.lang, staff.uuid, t, expires,
  )
  const id = one<{ id: number }>(ctx.db, 'SELECT last_insert_rowid() AS id')!.id
  audit(ctx, staff.uuid, 'pitch.create', String(id), `${label} (${body.mode}, ${body.lang}, ${body.expiresInDays ? `${body.expiresInDays}d` : 'no expiry'})`)
  return { link: view(ctx, rowOf(ctx, id)), token, url: pitchUrl(ctx, token, body.mode, body.lang) }
}

/** Widerrufen: der Link liefert sofort 404. Zweiter Aufruf ändert nichts. */
export function revokePitchLink(ctx: AppContext, staff: Staff, id: number): PitchLink {
  assertCan(staff, 'pitch.manage')
  const r = rowOf(ctx, id)
  if (r.revoked_at === null) {
    run(ctx.db, 'UPDATE pitch_links SET revoked_at = ? WHERE id = ?', ctx.now(), id)
    audit(ctx, staff.uuid, 'pitch.revoke', String(id), r.label)
  }
  return view(ctx, rowOf(ctx, id))
}

export function deletePitchLink(ctx: AppContext, staff: Staff, id: number): void {
  assertCan(staff, 'pitch.manage')
  const r = rowOf(ctx, id)
  run(ctx.db, 'DELETE FROM pitch_links WHERE id = ?', id)
  audit(ctx, staff.uuid, 'pitch.delete', String(id), r.label)
}

// ---------------------------------------------------------------- Öffentlich

/** Gültiger Link zum Token (Form, Hash, nicht widerrufen, nicht abgelaufen) – sonst `null`. */
export function resolvePitchToken(ctx: AppContext, token: string): { id: number } | null {
  if (!PITCH_TOKEN.test(token)) return null
  const r = one<PitchRow>(ctx.db, 'SELECT * FROM pitch_links WHERE token_hash = ?', pitchTokenHash(token))
  if (!r || pitchStatus(r, ctx.now()) !== 'active') return null
  return { id: r.id }
}

/** Aufruf der Seite zählen (nur index.html, nie die Dateien). */
export function recordPitchView(ctx: AppContext, id: number): void {
  run(ctx.db, 'UPDATE pitch_links SET views = views + 1, last_view_at = ? WHERE id = ?', ctx.now(), id)
}
