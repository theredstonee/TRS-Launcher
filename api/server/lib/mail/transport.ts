import nodemailer from 'nodemailer'
import type { MailAccount, MailConfig } from '../config'

/**
 * SMTP-Versand über nodemailer. Zugangsdaten nur aus der Konfiguration (.env); Fehlermeldungen werden vor dem
 * Speichern gekürzt und von Adressen/Zugangsdaten befreit. Tests setzen eine eigene `Sender`-Attrappe.
 */

export interface OutgoingMail {
  to: string
  subject: string
  html: string
  text: string
  headers?: Record<string, string>
}

export interface SendResult {
  messageId: string | null
}

/** Fehler beim Versand: `permanent` = nicht wiederholen (z. B. 550 Empfänger unbekannt). */
export class SendError extends Error {
  constructor(message: string, readonly permanent: boolean) {
    super(message)
    this.name = 'SendError'
  }
}

export interface Sender {
  send(mail: OutgoingMail): Promise<SendResult>
  close?(): void
}

/** `system` = no-reply (Bestätigungen, Team), `newsletter` = Kampagnen + Testmails. */
export type MailChannel = 'system' | 'newsletter'
export const MAIL_CHANNELS: readonly MailChannel[] = ['system', 'newsletter']

/**
 * Zwei SMTP-Konten auf demselben Server, je ein nodemailer-Pool. Ohne eigenes Newsletter-Konto teilen sich beide
 * Kanäle einen Pool (und damit auch die Drosselung).
 */
export class MailService {
  private senders: Record<MailChannel, Sender | null>

  constructor(readonly config: MailConfig | null, sender?: Sender | null) {
    if (sender !== undefined || !config) this.senders = { system: sender ?? null, newsletter: sender ?? null }
    else {
      const system = smtpSender(config, config.system)
      this.senders = { system, newsletter: config.newsletter.shared ? system : smtpSender(config, config.newsletter) }
    }
  }

  /** Mail ist eingerichtet (SMTP oder Test-Attrappe). */
  get available(): boolean {
    return this.senders.system !== null
  }

  /** Teilen sich beide Kanäle einen Versand (eine gemeinsame Drosselung)? */
  get shared(): boolean {
    return this.senders.system === this.senders.newsletter
  }

  /** Für Tests und den lokalen Smoke: eigenen Versand setzen (`null` = aus); ohne `channel` für beide. */
  setSender(sender: Sender | null, channel?: MailChannel): void {
    if (channel) {
      if (this.senders[channel] !== this.senders[channel === 'system' ? 'newsletter' : 'system']) this.senders[channel]?.close?.()
      this.senders[channel] = sender
      return
    }
    this.close()
    this.senders = { system: sender, newsletter: sender }
  }

  async send(mail: OutgoingMail, channel: MailChannel = 'system'): Promise<SendResult> {
    const s = this.senders[channel]
    if (!s) throw new SendError('mail not configured', false)
    return s.send(mail)
  }

  close(): void {
    this.senders.system?.close?.()
    if (this.senders.newsletter !== this.senders.system) this.senders.newsletter?.close?.()
  }
}

/** Fehlertext ohne Adressen/Zugangsdaten, höchstens 300 Zeichen. */
export function cleanError(message: string): string {
  return message
    .replace(/[^\s<>()"']+@[^\s<>()"']+/g, '<address>')
    .replace(/(auth|pass(word)?|user(name)?)\S*\s*[:=]\s*\S+/gi, '$1=<hidden>')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 300)
}

function smtpSender(c: MailConfig, account: MailAccount): Sender {
  const local = c.host === '127.0.0.1' || c.host === 'localhost'
  const transport = nodemailer.createTransport({
    host: c.host,
    port: c.port,
    secure: c.secure,
    // Ohne TLS nur an localhost (lokaler Test-Sink); sonst STARTTLS Pflicht.
    requireTLS: !c.secure && !local,
    ignoreTLS: local && !c.secure,
    auth: account.user && account.pass ? { user: account.user, pass: account.pass } : undefined,
    pool: true,
    maxConnections: 2,
    maxMessages: 200,
    connectionTimeout: 20_000,
    greetingTimeout: 15_000,
    socketTimeout: 60_000,
    tls: { minVersion: 'TLSv1.2' },
    logger: false,
    debug: false,
  })
  return {
    async send(mail) {
      try {
        const info = await transport.sendMail({
          from: account.from,
          ...(c.replyTo ? { replyTo: c.replyTo } : {}),
          to: mail.to,
          subject: mail.subject,
          html: mail.html,
          text: mail.text,
          headers: mail.headers,
          disableFileAccess: true,
          disableUrlAccess: true,
        })
        return { messageId: typeof info.messageId === 'string' ? info.messageId : null }
      } catch (err) {
        const e = err as { responseCode?: number, message?: string, code?: string }
        const permanent = typeof e.responseCode === 'number' && e.responseCode >= 500 && e.responseCode < 600 && e.code !== 'EAUTH'
        throw new SendError(cleanError(`${e.code ?? 'ERROR'} ${e.responseCode ?? ''} ${e.message ?? ''}`), permanent)
      }
    },
    close() {
      transport.close()
    },
  }
}
