import type { AppContext } from '../context'
import { all, one, run, tx } from '../db'
import { ApiError, badRequest } from '../errors'
import { can, teamOf, type Permission, type Staff } from '../team'
import {
  TEAM_NOTIFY_KINDS,
  TEAM_NOTIFY_PERMISSION,
  type NewsletterLang,
  type TeamNotifyKind,
  type TeamNotifyView,
} from '../../../shared/newsletter'
import { enqueue, requireMail } from './outbox'
import { renderConfirm, renderTeam, type TeamItem } from './render'
import { TEAM_KIND_NAMES, isMailLang } from './texts'
import { autoLook } from './theme'
import { CONFIRM_TTL_MS, hashToken, newConfirmToken } from './tokens'

/**
 * Team-Benachrichtigungen per Mail (§40.6): neue Bewerbung, Meldung, Einspruch, Creator-Haustier-Antrag. Empfänger =
 * Team-Mitglieder mit dem nötigen Recht und eingeschalteter Art (eigene, bestätigte Adresse), sonst `MAIL_TEAM_TO`.
 * Gebündelt: höchstens eine Mail je Art alle 5 Minuten – was dazwischen kommt, steht in der nächsten Mail.
 * Inhalte bleiben knapp (keine Meldungs-Texte, keine Einspruch-Begründungen) – Details nur im Admin.
 */

export const TEAM_BUNDLE_MS = 5 * 60 * 1000

interface PrefsRow {
  uuid: string
  email: string | null
  pending_email: string | null
  confirm_hash: string | null
  confirm_expires_at: number | null
  kinds: string
  lang: NewsletterLang
  updated_at: number
}

function parseKinds(raw: string): TeamNotifyKind[] {
  try {
    const v = JSON.parse(raw) as unknown
    return Array.isArray(v) ? TEAM_NOTIFY_KINDS.filter((k) => v.includes(k)) : []
  } catch {
    return []
  }
}

export function availableKinds(staff: Staff): TeamNotifyKind[] {
  return TEAM_NOTIFY_KINDS.filter((k) => can(staff, TEAM_NOTIFY_PERMISSION[k] as Permission))
}

export function teamPrefsView(ctx: AppContext, staff: Staff): TeamNotifyView {
  const r = one<PrefsRow>(ctx.db, 'SELECT * FROM mail_team_prefs WHERE uuid = ?', staff.uuid)
  const available = availableKinds(staff)
  const pendingValid = r?.pending_email && r.confirm_expires_at && r.confirm_expires_at > ctx.now()
  return {
    email: r?.email ?? null,
    pendingEmail: pendingValid ? r!.pending_email : null,
    kinds: r ? parseKinds(r.kinds).filter((k) => available.includes(k)) : [],
    available,
    lang: r?.lang ?? 'de',
    fallbackConfigured: (ctx.config.mail?.teamTo.length ?? 0) > 0,
  }
}

/**
 * Eigene Einstellungen speichern. Neue Adresse → Bestätigungs-Mail (gilt erst nach dem Klick); `email: null` löscht
 * die Adresse. Arten nur aus den eigenen Rechten.
 */
export function setTeamPrefs(ctx: AppContext, staff: Staff, input: { email?: string | null, kinds: TeamNotifyKind[], lang: NewsletterLang }, normalize: (s: string) => string | null): { view: TeamNotifyView, confirmationSent: boolean } {
  if (!/^[0-9a-f]{32}$/.test(staff.uuid)) throw badRequest('not_a_person', 'Only team members with an account can set notifications')
  const available = availableKinds(staff)
  const kinds = TEAM_NOTIFY_KINDS.filter((k) => input.kinds.includes(k) && available.includes(k))
  const t = ctx.now()
  let sent = false
  tx(ctx.db, () => {
    const cur = one<PrefsRow>(ctx.db, 'SELECT * FROM mail_team_prefs WHERE uuid = ?', staff.uuid)
    if (!cur) {
      run(ctx.db, 'INSERT INTO mail_team_prefs (uuid, kinds, lang, updated_at) VALUES (?, ?, ?, ?)', staff.uuid, JSON.stringify(kinds), input.lang, t)
    } else {
      run(ctx.db, 'UPDATE mail_team_prefs SET kinds = ?, lang = ?, updated_at = ? WHERE uuid = ?', JSON.stringify(kinds), input.lang, t, staff.uuid)
    }
    if (input.email === null) {
      run(ctx.db, 'UPDATE mail_team_prefs SET email = NULL, pending_email = NULL, confirm_hash = NULL, confirm_expires_at = NULL WHERE uuid = ?', staff.uuid)
    } else if (input.email !== undefined) {
      const email = normalize(input.email)
      if (!email) throw badRequest('invalid_email', 'This e-mail address is not valid')
      const now = one<PrefsRow>(ctx.db, 'SELECT * FROM mail_team_prefs WHERE uuid = ?', staff.uuid)!
      if (email !== now.email && !(email === now.pending_email && now.confirm_expires_at && now.confirm_expires_at > t && t - (now.confirm_expires_at - CONFIRM_TTL_MS) < 2 * 60_000)) {
        requireMail(ctx)
        const { token, hash } = newConfirmToken('t')
        run(ctx.db, 'UPDATE mail_team_prefs SET pending_email = ?, confirm_hash = ?, confirm_expires_at = ? WHERE uuid = ?', email, hash, t + CONFIRM_TTL_MS, staff.uuid)
        const url = `${ctx.config.siteUrl}/newsletter/confirm?token=${token}&lang=${input.lang}`
        enqueue(ctx, { kind: 'team_confirm', to: email, mail: renderConfirm(ctx, 'team', autoLook(ctx), input.lang, url, email) })
        sent = true
      }
    }
  })
  return { view: teamPrefsView(ctx, staff), confirmationSent: sent }
}

/** Team-Adresse bestätigen (Link `t…`) → bestätigte Adresse. */
export function confirmTeamEmail(ctx: AppContext, token: string): string {
  const r = one<PrefsRow>(ctx.db, 'SELECT * FROM mail_team_prefs WHERE confirm_hash = ?', hashToken(token))
  if (!r || !r.pending_email) throw new ApiError(404, 'invalid_token', 'This link is not valid or was already used')
  if (!r.confirm_expires_at || r.confirm_expires_at <= ctx.now()) throw new ApiError(410, 'token_expired', 'This link has expired')
  run(ctx.db, 'UPDATE mail_team_prefs SET email = pending_email, pending_email = NULL, confirm_hash = NULL, confirm_expires_at = NULL, updated_at = ? WHERE uuid = ?', ctx.now(), r.uuid)
  return r.pending_email
}

// ---------------------------------------------------------------- Ereignisse

/**
 * Ereignis für das Team vormerken und (wenn die 5 Minuten seit der letzten Mail dieser Art um sind) gleich senden.
 * Wirft nie – eine fehlende Mail darf die eigentliche Aktion (Bewerbung, Meldung …) nicht stören.
 */
export function notifyTeam(ctx: AppContext, kind: TeamNotifyKind, item: TeamItem): void {
  try {
    if (!ctx.mail.available) return
    run(ctx.db, 'INSERT INTO mail_team_pending (kind, data, created_at) VALUES (?, ?, ?)', kind, JSON.stringify(item), ctx.now())
    flushTeam(ctx, kind)
  } catch (err) {
    console.error('[mail] team notification failed', err)
  }
}

interface Recipient {
  email: string
  lang: NewsletterLang
}

/** Empfänger einer Art: Team-Mitglieder mit Recht + eingeschalteter Art + bestätigter Adresse, sonst MAIL_TEAM_TO. */
export function teamRecipients(ctx: AppContext, kind: TeamNotifyKind): Recipient[] {
  const out = new Map<string, Recipient>()
  for (const p of all<PrefsRow>(ctx.db, 'SELECT * FROM mail_team_prefs WHERE email IS NOT NULL')) {
    if (!parseKinds(p.kinds).includes(kind)) continue
    const staff = teamOf(ctx, p.uuid)
    if (!staff || !can(staff, TEAM_NOTIFY_PERMISSION[kind] as Permission)) continue
    out.set(p.email!, { email: p.email!, lang: isMailLang(p.lang) ? p.lang : 'de' })
  }
  if (out.size === 0) for (const a of ctx.config.mail?.teamTo ?? []) out.set(a, { email: a, lang: 'de' })
  return [...out.values()]
}

/** Gesammelte Ereignisse versenden (je Art höchstens eine Mail je 5 Minuten). `only` = nur diese Art prüfen. */
export function flushTeam(ctx: AppContext, only?: TeamNotifyKind): number {
  if (!ctx.mail.available) return 0
  let mails = 0
  const t = ctx.now()
  for (const kind of only ? [only] : TEAM_NOTIFY_KINDS) {
    tx(ctx.db, () => {
      const last = one<{ last_sent_at: number }>(ctx.db, 'SELECT last_sent_at FROM mail_team_state WHERE kind = ?', kind)?.last_sent_at ?? 0
      if (t - last < TEAM_BUNDLE_MS) return
      const rows = all<{ id: number, data: string }>(ctx.db, 'SELECT id, data FROM mail_team_pending WHERE kind = ? ORDER BY id LIMIT 200', kind)
      if (rows.length === 0) return
      run(ctx.db, `DELETE FROM mail_team_pending WHERE kind = ? AND id <= ?`, kind, rows[rows.length - 1]!.id)
      run(ctx.db, 'INSERT INTO mail_team_state (kind, last_sent_at) VALUES (?, ?) ON CONFLICT(kind) DO UPDATE SET last_sent_at = excluded.last_sent_at', kind, t)
      const items = rows.map((r) => JSON.parse(r.data) as TeamItem)
      const look = autoLook(ctx)
      for (const rcp of teamRecipients(ctx, kind)) {
        const mail = renderTeam(ctx, look, rcp.lang, kind, TEAM_KIND_NAMES[rcp.lang][kind], items)
        enqueue(ctx, { kind: 'team', to: rcp.email, mail })
        mails++
      }
    })
  }
  return mails
}

/** Für die Hooks: Zeitstempel lesbar (Berlin, 24 h). */
export function stamp(t: number): string {
  return new Intl.DateTimeFormat('de-DE', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'Europe/Berlin' }).format(new Date(t))
}
