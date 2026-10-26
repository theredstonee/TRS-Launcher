import type { AppContext } from '../context'
import { all, one, run, tx } from '../db'
import { unavailable } from '../errors'
import type { CampaignContent, NewsletterLang } from '../../../shared/newsletter'
import { renderCampaign, UNSUB_PLACEHOLDER, type RenderedMail } from './render'
import { isLook, resolveLook, type Look } from './theme'
import { unsubscribeUrl } from './tokens'
import { SendError, type MailChannel, type OutgoingMail } from './transport'

/**
 * Ausgangs-Warteschlange (SQLite): jede Mail ist eine Zeile mit Status. Der Hintergrund-Takt verschickt gedrosselt
 * (`MAIL_RATE_PER_MIN`), wiederholt vorübergehende Fehler mit Backoff (1, 2, 4 … min, höchstens 5 Versuche) und
 * markiert Mails, die bei einem Neustart gerade „sending“ waren, als fehlgeschlagen – lieber eine Mail zu wenig als
 * doppelt. Kampagnen-Mails werden erst beim Versand je Empfänger gerendert (Sprache + Abmelde-Link).
 */

export type OutboxKind = 'confirm' | 'campaign' | 'test' | 'team' | 'team_confirm'
export const MAX_ATTEMPTS = 5
/** Erledigte Zeilen (mit Adresse/Inhalt) werden nach so langer Zeit gelöscht. */
export const OUTBOX_RETENTION_MS = 30 * 24 * 60 * 60 * 1000

/** Kampagnen und Testmails gehen über das Newsletter-Konto, alles andere über das System-Konto (no-reply). */
export function channelOf(kind: OutboxKind): MailChannel {
  return kind === 'campaign' || kind === 'test' ? 'newsletter' : 'system'
}

export function requireMail(ctx: AppContext): void {
  if (!ctx.mail.available) throw unavailable('mail_unavailable', 'Sending e-mail is not configured on this server')
}

export function enqueue(ctx: AppContext, m: {
  kind: Exclude<OutboxKind, 'campaign'>
  to: string
  mail: RenderedMail
  headers?: Record<string, string>
  subscriberId?: number | null
  campaignId?: string | null
}): number {
  const t = ctx.now()
  return one<{ id: number }>(
    ctx.db,
    `INSERT INTO mail_outbox (kind, campaign_id, subscriber_id, to_addr, subject, html, text, headers, status, attempts, next_at, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'queued', 0, ?, ?) RETURNING id`,
    m.kind, m.campaignId ?? null, m.subscriberId ?? null, m.to, m.mail.subject, m.mail.html, m.mail.text,
    m.headers ? JSON.stringify(m.headers) : null, t, t,
  )!.id
}

/** Neustart: Mails, deren Versand unterbrochen wurde, nicht erneut senden (kein Doppelversand). */
export function recoverOutbox(ctx: AppContext): number {
  const rows = all<{ id: number, campaign_id: string | null }>(ctx.db, "SELECT id, campaign_id FROM mail_outbox WHERE status = 'sending'")
  if (rows.length === 0) return 0
  tx(ctx.db, () => {
    for (const r of rows) {
      run(ctx.db, "UPDATE mail_outbox SET status = 'failed', last_error = 'interrupted' WHERE id = ?", r.id)
      if (r.campaign_id) run(ctx.db, 'UPDATE newsletter_campaigns SET failed_count = failed_count + 1 WHERE id = ?', r.campaign_id)
    }
  })
  return rows.length
}

interface OutboxRow {
  id: number
  kind: OutboxKind
  campaign_id: string | null
  subscriber_id: number | null
  to_addr: string
  subject: string | null
  html: string | null
  text: string | null
  headers: string | null
  attempts: number
}

// Gerenderte Kampagnen je Sprache (mit Platzhalter für den Abmelde-Link) – nur während des Versands.
const rendered = new Map<string, RenderedMail>()

function campaignMail(ctx: AppContext, c: { id: string, rev: number, content: string, sent_look: string | null, look: string }, lang: NewsletterLang): RenderedMail {
  const look: Look = isLook(c.sent_look) ? c.sent_look : resolveLook(ctx, isLook(c.look) ? c.look : 'auto')
  const key = `${c.id}:${c.rev}:${lang}:${look}`
  const hit = rendered.get(key)
  if (hit) return hit
  if (rendered.size > 50) rendered.clear()
  const mail = renderCampaign(ctx, JSON.parse(c.content) as CampaignContent, look, lang)
  rendered.set(key, mail)
  return mail
}

/** Mail einer Zeile zusammensetzen; `null` = überspringen (z. B. Abonnent inzwischen abgemeldet). */
function build(ctx: AppContext, r: OutboxRow): OutgoingMail | null {
  if (r.kind !== 'campaign') {
    return { to: r.to_addr, subject: r.subject ?? '', html: r.html ?? '', text: r.text ?? '', headers: r.headers ? JSON.parse(r.headers) as Record<string, string> : undefined }
  }
  const s = one<{ id: number, email: string, lang: NewsletterLang, status: string, unsub_nonce: string }>(
    ctx.db, 'SELECT id, email, lang, status, unsub_nonce FROM newsletter_subscribers WHERE id = ?', r.subscriber_id,
  )
  const c = one<{ id: string, rev: number, content: string, sent_look: string | null, look: string, status: string }>(
    ctx.db, 'SELECT id, rev, content, sent_look, look, status FROM newsletter_campaigns WHERE id = ?', r.campaign_id,
  )
  if (!s || s.status !== 'confirmed' || !c || c.status !== 'sending') return null
  const mail = campaignMail(ctx, c, s.lang)
  const unsub = unsubscribeUrl(ctx, s.id, s.unsub_nonce)
  return {
    to: s.email,
    subject: mail.subject,
    html: mail.html.split(UNSUB_PLACEHOLDER).join(unsub.replace(/&/g, '&amp;')),
    text: mail.text.split(UNSUB_PLACEHOLDER).join(unsub),
    headers: listHeaders(ctx, unsub),
  }
}

/** RFC 2369 / RFC 8058: Abmelden mit einem Klick (POST an dieselbe Adresse, ohne Anmeldung/CSRF). */
export function listHeaders(ctx: AppContext, unsub: string): Record<string, string> {
  return {
    'List-Unsubscribe': `<${unsub}>`,
    'List-Unsubscribe-Post': 'List-Unsubscribe=One-Click',
    'List-Id': `TRS Launcher Newsletter <newsletter.${new URL(ctx.config.siteUrl).hostname}>`,
  }
}

function backoff(attempts: number): number {
  return Math.min(60 * 60_000, 60_000 * 2 ** Math.max(0, attempts - 1))
}

function finishCampaigns(ctx: AppContext, ids: Iterable<string>): void {
  for (const id of ids) {
    const open = one<{ n: number }>(ctx.db, "SELECT COUNT(*) AS n FROM mail_outbox WHERE campaign_id = ? AND kind = 'campaign' AND status IN ('queued', 'sending')", id)!.n
    if (open === 0) run(ctx.db, "UPDATE newsletter_campaigns SET status = 'sent', sent_at = ? WHERE id = ? AND status = 'sending'", ctx.now(), id)
  }
}

let running: Promise<{ sent: number, failed: number }> | null = null

/**
 * Bis zu `budget` fällige Mails je SMTP-Konto versenden (nacheinander). Läuft nie doppelt (ein laufender Durchgang wird geteilt).
 * Startet vorher fällige geplante Kampagnen.
 */
export function processOutbox(ctx: AppContext, budget: number): Promise<{ sent: number, failed: number }> {
  if (running) return running
  running = (async () => {
    let sent = 0
    let failed = 0
    startDueCampaigns(ctx)
    if (!ctx.mail.available || budget <= 0) {
      finishCampaigns(ctx, all<{ id: string }>(ctx.db, "SELECT id FROM newsletter_campaigns WHERE status = 'sending'").map((c) => c.id))
      return { sent, failed }
    }
    // Budget je SMTP-Konto (eigene Drosselung); teilen sich beide Kanäle ein Konto, gilt ein gemeinsames Budget.
    const groups: OutboxKind[][] = ctx.mail.shared
      ? [['confirm', 'team', 'team_confirm', 'test', 'campaign']]
      : [['confirm', 'team', 'team_confirm'], ['test', 'campaign']]
    const rows = groups.flatMap((kinds) => all<OutboxRow>(
      ctx.db,
      `SELECT id, kind, campaign_id, subscriber_id, to_addr, subject, html, text, headers, attempts FROM mail_outbox
       WHERE status = 'queued' AND next_at <= ? AND kind IN (${kinds.map(() => '?').join(', ')})
       ORDER BY CASE kind WHEN 'campaign' THEN 1 ELSE 0 END, id LIMIT ?`,
      ctx.now(), ...kinds, budget,
    ))
    const touched = new Set<string>()
    for (const r of rows) {
      if (r.campaign_id) touched.add(r.campaign_id)
      let mail: OutgoingMail | null
      try {
        mail = build(ctx, r)
      } catch (err) {
        console.error('[mail] render failed', err)
        mail = null
      }
      if (!mail) {
        run(ctx.db, "UPDATE mail_outbox SET status = 'skipped' WHERE id = ? AND status = 'queued'", r.id)
        continue
      }
      // Erst als „sending“ markieren, dann senden: stirbt der Prozess dazwischen, gibt es keinen zweiten Versuch.
      if (run(ctx.db, "UPDATE mail_outbox SET status = 'sending', attempts = attempts + 1 WHERE id = ? AND status = 'queued'", r.id) === 0) continue
      try {
        await ctx.mail.send(mail, channelOf(r.kind))
        tx(ctx.db, () => {
          run(ctx.db, "UPDATE mail_outbox SET status = 'sent', sent_at = ?, last_error = NULL, html = CASE WHEN kind = 'campaign' THEN NULL ELSE html END WHERE id = ?", ctx.now(), r.id)
          if (r.campaign_id && r.kind === 'campaign') run(ctx.db, 'UPDATE newsletter_campaigns SET sent_count = sent_count + 1 WHERE id = ?', r.campaign_id)
        })
        sent++
      } catch (err) {
        const permanent = err instanceof SendError && err.permanent
        const msg = err instanceof Error ? err.message : 'send failed'
        const attempts = r.attempts + 1
        if (permanent || attempts >= MAX_ATTEMPTS) {
          tx(ctx.db, () => {
            run(ctx.db, "UPDATE mail_outbox SET status = 'failed', last_error = ? WHERE id = ?", msg.slice(0, 300), r.id)
            if (r.campaign_id && r.kind === 'campaign') run(ctx.db, 'UPDATE newsletter_campaigns SET failed_count = failed_count + 1 WHERE id = ?', r.campaign_id)
          })
          failed++
        } else {
          run(ctx.db, "UPDATE mail_outbox SET status = 'queued', next_at = ?, last_error = ? WHERE id = ?", ctx.now() + backoff(attempts), msg.slice(0, 300), r.id)
        }
        console.warn(`[mail] send failed (${permanent ? 'permanent' : `attempt ${attempts}`}): ${msg}`)
      }
    }
    // Auch Kampagnen ohne offene Zeilen abschließen (z. B. nach einem Neustart, der den Rest als fehlgeschlagen markiert hat).
    for (const c of all<{ id: string }>(ctx.db, "SELECT id FROM newsletter_campaigns WHERE status = 'sending'")) touched.add(c.id)
    finishCampaigns(ctx, touched)
    return { sent, failed }
  })().finally(() => {
    running = null
  })
  return running
}

/** Geplante Kampagnen, deren Zeit gekommen ist, in die Warteschlange legen. */
export function startDueCampaigns(ctx: AppContext): string[] {
  const due = all<{ id: string }>(ctx.db, "SELECT id FROM newsletter_campaigns WHERE status = 'scheduled' AND scheduled_at <= ?", ctx.now())
  for (const d of due) {
    try {
      startCampaign(ctx, d.id)
    } catch (err) {
      console.error('[mail] starting scheduled campaign failed', err)
    }
  }
  return due.map((d) => d.id)
}

/** Kampagne starten: Look festhalten, alle bestätigten Empfänger (ggf. nur bestimmte Sprachen) einreihen. */
export function startCampaign(ctx: AppContext, id: string): number {
  return tx(ctx.db, () => {
    const c = one<{ look: string, langs: string | null, status: string }>(ctx.db, 'SELECT look, langs, status FROM newsletter_campaigns WHERE id = ?', id)
    if (!c || (c.status !== 'scheduled' && c.status !== 'draft')) return 0
    const langs = c.langs ? JSON.parse(c.langs) as string[] : null
    const look = resolveLook(ctx, isLook(c.look) ? c.look : 'auto')
    const t = ctx.now()
    const where = langs && langs.length ? `AND lang IN (${langs.map(() => '?').join(', ')})` : ''
    const n = run(
      ctx.db,
      `INSERT OR IGNORE INTO mail_outbox (kind, campaign_id, subscriber_id, to_addr, status, attempts, next_at, created_at)
       SELECT 'campaign', ?, id, email, 'queued', 0, ?, ? FROM newsletter_subscribers WHERE status = 'confirmed' ${where}`,
      id, t, t, ...(langs ?? []),
    )
    run(
      ctx.db,
      "UPDATE newsletter_campaigns SET status = 'sending', started_at = ?, sent_look = ?, total = total + ?, scheduled_at = COALESCE(scheduled_at, ?) WHERE id = ?",
      t, look, n, t, id,
    )
    if (n === 0) run(ctx.db, "UPDATE newsletter_campaigns SET status = 'sent', sent_at = ? WHERE id = ?", t, id)
    return n
  })
}

/** Laufenden Versand anhalten: wartende Mails verwerfen. */
export function cancelQueued(ctx: AppContext, campaignId: string): number {
  return run(ctx.db, "UPDATE mail_outbox SET status = 'cancelled' WHERE campaign_id = ? AND kind = 'campaign' AND status = 'queued'", campaignId)
}

export function queuedCount(ctx: AppContext, campaignId: string): number {
  return one<{ n: number }>(ctx.db, "SELECT COUNT(*) AS n FROM mail_outbox WHERE campaign_id = ? AND kind = 'campaign' AND status IN ('queued', 'sending')", campaignId)!.n
}

/** Alte erledigte Zeilen löschen (Adressen und Inhalte nicht länger als nötig aufheben). */
export function sweepOutbox(ctx: AppContext): void {
  run(ctx.db, "DELETE FROM mail_outbox WHERE status IN ('sent', 'failed', 'skipped', 'cancelled') AND created_at < ?", ctx.now() - OUTBOX_RETENTION_MS)
}

/** Takt für den Hintergrund: Budget je Durchgang aus `MAIL_RATE_PER_MIN` (Durchgang alle `intervalMs`). */
export function budgetPerTick(ctx: AppContext, intervalMs: number): number {
  const rate = ctx.mail.config?.ratePerMin ?? 60
  return Math.max(1, Math.floor((rate * intervalMs) / 60_000))
}
