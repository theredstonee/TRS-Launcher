import type { AppContext } from '../context'
import { all, one, run, tx } from '../db'
import { ApiError, badRequest } from '../errors'
import {
  NEWSLETTER_LANGS,
  SUBSCRIBER_STATUSES,
  type MyNewsletterView,
  type NewsletterLang,
  type SubscriberStats,
  type SubscriberStatus,
} from '../../../shared/newsletter'
import { enqueue, requireMail } from './outbox'
import { renderConfirm } from './render'
import { autoLook } from './theme'
import { CONFIRM_TOKEN, CONFIRM_TTL_MS, hashToken, maskEmail, newConfirmToken, newNonce, verifyUnsubscribe } from './tokens'
import { confirmTeamEmail } from './team-notify'

/**
 * Newsletter-Empfänger (§40.2): Double-Opt-in über Website-Formular oder Launcher (TRS-Konto), Abmelden mit einem Klick.
 * Die öffentliche Anmeldung antwortet immer gleich (kein Hinweis, ob eine Adresse schon eingetragen ist).
 */

/** Version des Einwilligungstexts (Formular/Launcher/Datenschutz) – bei Änderungen am Text hochzählen. */
export const CONSENT_VERSION = '2026-10-26'
/** Bestätigungs-Mails je Adresse höchstens so oft (sonst still nichts). */
export const RESEND_GAP_MS = 2 * 60 * 1000
/** Unbestätigte Anmeldungen werden nach … gelöscht. */
export const PENDING_RETENTION_MS = 7 * 24 * 60 * 60 * 1000
/** Abgemeldete Adressen werden nach … gelöscht. */
export const UNSUBSCRIBED_RETENTION_MS = 30 * 24 * 60 * 60 * 1000

const EMAIL = /^[^\s@<>()[\]\\,;:"]{1,64}@(?=.{4,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$/

/** Adresse normalisieren (trim, NFC, klein) und prüfen; ungültig → `null`. */
export function normalizeEmail(raw: string): string | null {
  const s = raw.normalize('NFC').trim().toLowerCase()
  if (s.length > 254 || !EMAIL.test(s)) return null
  return s
}

export interface SubscriberRow {
  id: number
  email: string
  lang: NewsletterLang
  status: SubscriberStatus
  source: 'website' | 'launcher'
  uuid: string | null
  link_uuid: string | null
  created_at: number
  updated_at: number
  requested_at: number | null
  confirmed_at: number | null
  consent_source: string | null
  consent_version: string | null
  unsubscribed_at: number | null
  confirm_hash: string | null
  confirm_expires_at: number | null
  unsub_nonce: string
}

function byEmail(ctx: AppContext, email: string): SubscriberRow | undefined {
  return one<SubscriberRow>(ctx.db, 'SELECT * FROM newsletter_subscribers WHERE email = ?', email)
}

function confirmUrl(ctx: AppContext, token: string, lang: NewsletterLang): string {
  return `${ctx.config.siteUrl}/newsletter/confirm?token=${token}&lang=${lang}`
}

/** Neuen Bestätigungs-Token setzen und die Mail einreihen. */
function sendConfirmation(ctx: AppContext, row: Pick<SubscriberRow, 'id' | 'email' | 'lang'>): void {
  const { token, hash } = newConfirmToken('c')
  const t = ctx.now()
  run(ctx.db, 'UPDATE newsletter_subscribers SET confirm_hash = ?, confirm_expires_at = ?, requested_at = ? WHERE id = ?', hash, t + CONFIRM_TTL_MS, t, row.id)
  const mail = renderConfirm(ctx, 'newsletter', autoLook(ctx), row.lang, confirmUrl(ctx, token, row.lang), row.email)
  enqueue(ctx, { kind: 'confirm', to: row.email, mail, subscriberId: row.id })
}

export interface SubscribeInput {
  email: string
  lang: NewsletterLang
  source: 'website' | 'launcher'
  /** Konto aus dem Launcher – wird erst mit der Bestätigung verknüpft. */
  uuid?: string
}

/**
 * Anmelden (Website oder Launcher). Ergebnis nur für interne Zwecke/Tests – die Website-Route antwortet immer gleich.
 * `mail` = eine Bestätigungs-Mail wurde eingereiht.
 */
export function subscribe(ctx: AppContext, input: SubscribeInput): { mail: boolean, state: 'new' | 'pending' | 'confirmed' | 'resubscribed' | 'linked' | 'throttled' } {
  requireMail(ctx)
  const email = normalizeEmail(input.email)
  if (!email) throw badRequest('invalid_email', 'This e-mail address is not valid')
  const t = ctx.now()
  return tx(ctx.db, () => {
    const row = byEmail(ctx, email)
    const link = input.uuid ?? null
    if (link) {
      // Andere offene Verknüpfungen dieses Kontos verwerfen (nur die neueste Adresse zählt).
      run(ctx.db, 'UPDATE newsletter_subscribers SET link_uuid = NULL WHERE link_uuid = ? AND email <> ?', link, email)
    }
    if (!row) {
      const created = one<SubscriberRow>(
        ctx.db,
        `INSERT INTO newsletter_subscribers (email, lang, status, source, uuid, link_uuid, created_at, updated_at, unsub_nonce)
         VALUES (?, ?, 'pending', ?, NULL, ?, ?, ?, ?) RETURNING *`,
        email, input.lang, input.source, link, t, t, newNonce(),
      )!
      sendConfirmation(ctx, created)
      return { mail: true, state: 'new' }
    }
    if (row.status === 'confirmed') {
      if (!link || row.uuid === link) {
        // Schon bestätigt: Website still, Launcher ändert nur die Sprache.
        if (link) run(ctx.db, 'UPDATE newsletter_subscribers SET lang = ?, updated_at = ? WHERE id = ?', input.lang, t, row.id)
        return { mail: false, state: 'confirmed' }
      }
      // Bestätigte Adresse mit einem (anderen) Konto verknüpfen – nur nach Klick in der Mail.
      if (row.requested_at !== null && t - row.requested_at < RESEND_GAP_MS && row.link_uuid === link) return { mail: false, state: 'throttled' }
      run(ctx.db, 'UPDATE newsletter_subscribers SET link_uuid = ?, updated_at = ? WHERE id = ?', link, t, row.id)
      sendConfirmation(ctx, { ...row, lang: input.lang })
      return { mail: true, state: 'linked' }
    }
    if (row.status === 'pending') {
      run(ctx.db, 'UPDATE newsletter_subscribers SET lang = ?, link_uuid = COALESCE(?, link_uuid), updated_at = ? WHERE id = ?', input.lang, link, t, row.id)
      if (row.requested_at !== null && t - row.requested_at < RESEND_GAP_MS) return { mail: false, state: 'throttled' }
      sendConfirmation(ctx, { ...row, lang: input.lang })
      return { mail: true, state: 'pending' }
    }
    // Abgemeldet → erneut anmelden (wieder mit Bestätigung, neuer Abmelde-Schlüssel).
    run(
      ctx.db,
      `UPDATE newsletter_subscribers SET status = 'pending', lang = ?, source = ?, link_uuid = COALESCE(?, link_uuid), unsub_nonce = ?,
       unsubscribed_at = NULL, updated_at = ? WHERE id = ?`,
      input.lang, input.source, link, newNonce(), t, row.id,
    )
    sendConfirmation(ctx, { ...row, lang: input.lang })
    return { mail: true, state: 'resubscribed' }
  })
}

export interface ConfirmResult {
  kind: 'newsletter' | 'team'
  email: string
}

/** Bestätigungs-Link einlösen (Newsletter `c…` oder Team-Adresse `t…`). */
export function confirmToken(ctx: AppContext, token: string): ConfirmResult {
  if (!CONFIRM_TOKEN.test(token)) throw new ApiError(404, 'invalid_token', 'This link is not valid')
  if (token.startsWith('t')) return { kind: 'team', email: maskEmail(confirmTeamEmail(ctx, token)) }
  const t = ctx.now()
  return tx(ctx.db, () => {
    const row = one<SubscriberRow>(ctx.db, 'SELECT * FROM newsletter_subscribers WHERE confirm_hash = ?', hashToken(token))
    if (!row) throw new ApiError(404, 'invalid_token', 'This link is not valid or was already used')
    if (row.confirm_expires_at === null || row.confirm_expires_at <= t) throw new ApiError(410, 'token_expired', 'This link has expired – please sign up again')
    if (row.link_uuid) {
      // Konto übernimmt die Adresse (eine Adresse je Konto: alte Verknüpfung lösen).
      run(ctx.db, 'UPDATE newsletter_subscribers SET uuid = NULL WHERE uuid = ? AND id <> ?', row.link_uuid, row.id)
    }
    const already = row.status === 'confirmed'
    run(
      ctx.db,
      `UPDATE newsletter_subscribers SET status = 'confirmed', confirm_hash = NULL, confirm_expires_at = NULL,
       uuid = COALESCE(link_uuid, uuid), link_uuid = NULL, updated_at = ?,
       confirmed_at = CASE WHEN ? THEN confirmed_at ELSE ? END,
       consent_source = CASE WHEN ? THEN consent_source ELSE ? END,
       consent_version = CASE WHEN ? THEN consent_version ELSE ? END
       WHERE id = ?`,
      t, already ? 1 : 0, t, already ? 1 : 0, row.link_uuid ? 'launcher' : row.source, already ? 1 : 0, CONSENT_VERSION, row.id,
    )
    return { kind: 'newsletter', email: maskEmail(row.email) }
  })
}

/** Abmelden per Token (Link in der Mail, auch RFC-8058-One-Click). Schon abgemeldet → gleiche Antwort. */
export function unsubscribeByToken(ctx: AppContext, token: string): { email: string } {
  const id = verifyUnsubscribe(ctx, token, (i) => one<{ unsub_nonce: string }>(ctx.db, 'SELECT unsub_nonce FROM newsletter_subscribers WHERE id = ?', i)?.unsub_nonce ?? null)
  if (id === null) throw new ApiError(404, 'invalid_token', 'This link is not valid')
  const row = one<SubscriberRow>(ctx.db, 'SELECT * FROM newsletter_subscribers WHERE id = ?', id)!
  unsubscribeRow(ctx, row)
  return { email: maskEmail(row.email) }
}

function unsubscribeRow(ctx: AppContext, row: SubscriberRow): void {
  const t = ctx.now()
  tx(ctx.db, () => {
    if (row.status !== 'unsubscribed') {
      run(
        ctx.db,
        "UPDATE newsletter_subscribers SET status = 'unsubscribed', unsubscribed_at = ?, confirm_hash = NULL, confirm_expires_at = NULL, link_uuid = NULL, updated_at = ? WHERE id = ?",
        t, t, row.id,
      )
    }
    // Wartende Kampagnen-Mails an diese Adresse nicht mehr senden.
    run(ctx.db, "UPDATE mail_outbox SET status = 'skipped' WHERE subscriber_id = ? AND kind = 'campaign' AND status = 'queued'", row.id)
  })
}

// ---------------------------------------------------------------- Launcher (/v1/me/newsletter)

function myRow(ctx: AppContext, uuid: string): SubscriberRow | undefined {
  return one<SubscriberRow>(ctx.db, 'SELECT * FROM newsletter_subscribers WHERE uuid = ? OR link_uuid = ? ORDER BY (link_uuid = ?) DESC, updated_at DESC LIMIT 1', uuid, uuid, uuid)
}

export function myNewsletter(ctx: AppContext, uuid: string): MyNewsletterView {
  const r = myRow(ctx, uuid)
  if (!r) return { status: 'none', email: null, lang: null, source: null, confirmedAt: null, updatedAt: null }
  // Eine noch nicht bestätigte Verknüpfung gilt fürs Konto als „ausstehend“, auch wenn die Adresse woanders bestätigt ist.
  const pendingLink = r.link_uuid === uuid && r.uuid !== uuid
  return {
    status: pendingLink ? 'pending' : r.status,
    email: r.email,
    lang: r.lang,
    source: r.source,
    confirmedAt: !pendingLink && r.confirmed_at ? new Date(r.confirmed_at).toISOString() : null,
    updatedAt: new Date(r.updated_at).toISOString(),
  }
}

/** Launcher: Adresse eintragen/ändern (immer mit Bestätigungs-Mail, außer Adresse + Konto sind schon bestätigt). */
export function setMyNewsletter(ctx: AppContext, uuid: string, input: { email: string, lang: NewsletterLang }): MyNewsletterView {
  const email = normalizeEmail(input.email)
  if (!email) throw badRequest('invalid_email', 'This e-mail address is not valid')
  const current = one<SubscriberRow>(ctx.db, 'SELECT * FROM newsletter_subscribers WHERE uuid = ?', uuid)
  if (current && current.email !== email && current.status !== 'unsubscribed') {
    // Neue Adresse: die alte bekommt nichts mehr (sie war an dieses Konto gebunden).
    unsubscribeRow(ctx, current)
  }
  subscribe(ctx, { email, lang: input.lang, source: 'launcher', uuid })
  return myNewsletter(ctx, uuid)
}

/** Launcher: abmelden (verknüpfte Adresse) bzw. offene Verknüpfung verwerfen. */
export function deleteMyNewsletter(ctx: AppContext, uuid: string): MyNewsletterView {
  tx(ctx.db, () => {
    for (const r of all<SubscriberRow>(ctx.db, 'SELECT * FROM newsletter_subscribers WHERE link_uuid = ? AND (uuid IS NULL OR uuid <> ?)', uuid, uuid)) {
      if (r.status === 'pending' && r.uuid === null) run(ctx.db, 'DELETE FROM newsletter_subscribers WHERE id = ?', r.id)
      else run(ctx.db, 'UPDATE newsletter_subscribers SET link_uuid = NULL, confirm_hash = NULL, confirm_expires_at = NULL WHERE id = ?', r.id)
    }
  })
  const own = one<SubscriberRow>(ctx.db, 'SELECT * FROM newsletter_subscribers WHERE uuid = ?', uuid)
  if (own) unsubscribeRow(ctx, own)
  return myNewsletter(ctx, uuid)
}

// ---------------------------------------------------------------- Team: Zahlen

export function subscriberStats(ctx: AppContext): SubscriberStats {
  const byStatus = Object.fromEntries(SUBSCRIBER_STATUSES.map((s) => [s, 0])) as Record<SubscriberStatus, number>
  for (const r of all<{ status: SubscriberStatus, n: number }>(ctx.db, 'SELECT status, COUNT(*) AS n FROM newsletter_subscribers GROUP BY status')) byStatus[r.status] = r.n
  const byLang = Object.fromEntries(NEWSLETTER_LANGS.map((l) => [l, 0])) as Record<NewsletterLang, number>
  for (const r of all<{ lang: NewsletterLang, n: number }>(ctx.db, "SELECT lang, COUNT(*) AS n FROM newsletter_subscribers WHERE status = 'confirmed' GROUP BY lang")) byLang[r.lang] = r.n
  const bySource = { website: 0, launcher: 0 }
  for (const r of all<{ source: 'website' | 'launcher', n: number }>(ctx.db, "SELECT source, COUNT(*) AS n FROM newsletter_subscribers WHERE status = 'confirmed' GROUP BY source")) bySource[r.source] = r.n
  const recent = one<{ n: number }>(ctx.db, "SELECT COUNT(*) AS n FROM newsletter_subscribers WHERE status = 'confirmed' AND confirmed_at >= ?", ctx.now() - 30 * 24 * 60 * 60 * 1000)!.n
  return { total: byStatus.pending + byStatus.confirmed + byStatus.unsubscribed, byStatus, byLang, bySource, confirmedLast30d: recent }
}

/** Bestätigte Empfänger (optional nur bestimmte Sprachen) – für den Bestätigungsdialog vor dem Versand. */
export function audienceCount(ctx: AppContext, langs: NewsletterLang[] | null): number {
  if (!langs || langs.length === 0) return one<{ n: number }>(ctx.db, "SELECT COUNT(*) AS n FROM newsletter_subscribers WHERE status = 'confirmed'")!.n
  return one<{ n: number }>(ctx.db, `SELECT COUNT(*) AS n FROM newsletter_subscribers WHERE status = 'confirmed' AND lang IN (${langs.map(() => '?').join(', ')})`, ...langs)!.n
}

/** Unbestätigte nach 7 Tagen, Abgemeldete nach 30 Tagen löschen. */
export function sweepSubscribers(ctx: AppContext): number {
  const t = ctx.now()
  return run(ctx.db, "DELETE FROM newsletter_subscribers WHERE status = 'pending' AND updated_at < ?", t - PENDING_RETENTION_MS)
    + run(ctx.db, "DELETE FROM newsletter_subscribers WHERE status = 'unsubscribed' AND updated_at < ?", t - UNSUBSCRIBED_RETENTION_MS)
}
