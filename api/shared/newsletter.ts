/**
 * Newsletter (API.md §40) – Datenformen für Server und Website-Editor (reine Typen/Daten, keine Importe).
 * Texte liegen je Baustein und Sprache vor (`{ de, en, es }`); der Versand nimmt die Sprache des Empfängers,
 * sonst Englisch, sonst Deutsch.
 */

export const NEWSLETTER_LANGS = ['de', 'en', 'es'] as const
export type NewsletterLang = (typeof NEWSLETTER_LANGS)[number]
export type Localized = Partial<Record<NewsletterLang, string>>

export const CAMPAIGN_LOOKS = ['auto', 'redstone', 'halloween', 'winter', 'fox'] as const
export type CampaignLook = (typeof CAMPAIGN_LOOKS)[number]
export const CAMPAIGN_STATUSES = ['draft', 'scheduled', 'sending', 'sent', 'cancelled'] as const
export type CampaignStatus = (typeof CAMPAIGN_STATUSES)[number]
export const SUBSCRIBER_STATUSES = ['pending', 'confirmed', 'unsubscribed'] as const
export type SubscriberStatus = (typeof SUBSCRIBER_STATUSES)[number]
export const TEAM_NOTIFY_KINDS = ['application', 'report', 'appeal', 'pet_request'] as const
export type TeamNotifyKind = (typeof TEAM_NOTIFY_KINDS)[number]
/** Recht, das ein Team-Mitglied für die jeweilige Benachrichtigung braucht. */
export const TEAM_NOTIFY_PERMISSION: Record<TeamNotifyKind, string> = {
  application: 'applications.view',
  report: 'reports.view',
  appeal: 'appeals.handle',
  pet_request: 'creators.manage',
}

export const BLOCK_TYPES = ['title', 'text', 'button', 'divider', 'update', 'images', 'cosmetic', 'creator'] as const
export type BlockType = (typeof BLOCK_TYPES)[number]

export const NEWSLETTER_LIMITS = {
  blocks: 40,
  subject: 150,
  preheader: 200,
  title: 140,
  text: 5000,
  label: 60,
  url: 800,
  caption: 160,
  updateItems: 12,
  name: 100,
} as const

export interface UpdateItem {
  title: string
  text: string
}

export type Block =
  | { id: string, type: 'title', size: 'h1' | 'h2', kicker?: Localized, text: Localized }
  | { id: string, type: 'text', text: Localized, muted?: boolean }
  | { id: string, type: 'button', label: Localized, url: string }
  | { id: string, type: 'divider' }
  | {
    id: string
    type: 'update'
    version: string
    /** Update-Name („The Heist“) je Sprache – aus dem Changelog. */
    title?: Localized
    /** Banner aus dem Changelog: Akzentfarbe + Motiv (Bildadresse). */
    accent?: string | null
    motif?: string | null
    /** Punkte je Sprache (Schlagzeile + Satz) – aus dem Changelog, im Editor änderbar. */
    items: Partial<Record<NewsletterLang, UpdateItem[]>>
    button?: Localized
    url?: string
  }
  | { id: string, type: 'images', images: { src: string, alt?: Localized, caption?: Localized }[] }
  | {
    id: string
    type: 'cosmetic'
    item?: { kind: 'cape' | 'cosmetic', id: string } | null
    /** Eigenes Bild statt der Karte des Teils. */
    image?: string | null
    kicker?: Localized
    title: Localized
    text?: Localized
    /** Klartext-Code (erscheint in der Mail); per „Code erzeugen“ über das Code-System. */
    code?: string | null
    codeId?: number | null
    codeExpiresAt?: string | null
  }
  | { id: string, type: 'creator', uuid: string, kicker?: Localized, text: Localized, url?: string | null }

export interface CampaignContent {
  subject: Localized
  preheader: Localized
  blocks: Block[]
}

export interface ActorRefView {
  kind: 'staff' | 'key' | 'system'
  uuid: string | null
  name: string
}

export interface CampaignView {
  id: string
  name: string
  look: CampaignLook
  /** Look, der beim Versand jetzt gelten würde bzw. gegolten hat. */
  resolvedLook: 'redstone' | 'halloween' | 'winter' | 'fox'
  content: CampaignContent
  status: CampaignStatus
  langs: NewsletterLang[] | null
  scheduledAt: string | null
  startedAt: string | null
  sentAt: string | null
  total: number
  sent: number
  failed: number
  queued: number
  rev: number
  createdAt: string
  createdBy: ActorRefView
  updatedAt: string
  updatedBy: ActorRefView
}

export interface CampaignListItem extends Omit<CampaignView, 'content'> {
  subject: string
}

export interface SubscriberStats {
  total: number
  byStatus: Record<SubscriberStatus, number>
  /** Nur bestätigte. */
  byLang: Record<NewsletterLang, number>
  bySource: Record<'website' | 'launcher', number>
  /** Bestätigte der letzten 30 Tage. */
  confirmedLast30d: number
}

export interface MailKeyView {
  id: number
  name: string
  hint: string
  permissions: ('newsletter.manage' | 'newsletter.send')[]
  testEmail: string | null
  createdAt: string
  createdBy: ActorRefView
  lastUsedAt: string | null
  revokedAt: string | null
}

export interface TeamNotifyView {
  email: string | null
  pendingEmail: string | null
  kinds: TeamNotifyKind[]
  /** Arten, die du mit deinen Rechten bekommen kannst. */
  available: TeamNotifyKind[]
  lang: NewsletterLang
  /** Fallback-Adresse aus der Server-Konfiguration ist gesetzt. */
  fallbackConfigured: boolean
}

export interface MyNewsletterView {
  status: 'none' | SubscriberStatus
  email: string | null
  lang: NewsletterLang | null
  source: 'website' | 'launcher' | null
  confirmedAt: string | null
  updatedAt: string | null
}

/** Text in der Sprache des Empfängers, sonst Englisch, sonst Deutsch, sonst Spanisch. */
export function pickLang(l: Localized | null | undefined, lang: NewsletterLang): string {
  if (!l) return ''
  return (l[lang] || l.en || l.de || l.es || '').trim()
}
