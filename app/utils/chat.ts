import { z } from 'zod'
// Relativ importiert, damit Tests die Datei ohne Nuxt laden können.
import { intlLocale, t } from './i18n'
import { trsPresenceSchema, trsUserRefSchema } from './trs'
import { chatWorldSchema, hostingEventSchemas } from './hosting'

// Chat (Sozial): Schemas für alles, was der Kern liefert (wird beim Empfang
// geprüft), und reine Funktionen für Zeitleiste, Vorschauen, Reaktionen,
// Lesestatus und Links. Netzwerk gibt es hier nicht – alles läuft im Kern.

const uuid = z.string().regex(/^[0-9a-f]{32}$/)
const conversationId = z.string().regex(/^c[0-9a-f]{20}$/)
const messageId = z.string().regex(/^m[0-9a-f]{20}$/)
const attachmentId = z.string().regex(/^a[0-9a-f]{24}$/)
const reportId = z.string().regex(/^r[0-9a-f]{16}$/)
const time = z.string().max(40).nullable()
const user = trsUserRefSchema

/** Feste Reaktionen der API (Reihenfolge = Auswahlfeld). */
export const REACTIONS = {
  thumbs_up: '👍',
  heart: '❤️',
  laugh: '😂',
  wow: '😮',
  sad: '😢',
  angry: '😡',
  party: '🎉',
  fire: '🔥',
  eyes: '👀',
  check: '✅',
} as const
export type ReactionId = keyof typeof REACTIONS
export const REACTION_IDS = Object.keys(REACTIONS) as ReactionId[]

export const MAX_TEXT = 2000
export const MAX_IMAGES = 10
export const MAX_GROUP_MEMBERS = 25
/** Nachrichten desselben Absenders innerhalb dieser Zeit bilden eine Gruppe. */
export const CLUSTER_MS = 5 * 60_000

export const chatReactionSchema = z.object({
  emoji: z.enum(REACTION_IDS as [ReactionId, ...ReactionId[]]),
  count: z.number().int().min(0),
  users: z.array(uuid),
})

export const chatAttachmentSchema = z.object({
  id: attachmentId,
  mime: z.enum(['image/jpeg', 'image/png', 'image/webp']),
  width: z.number().int().min(0),
  height: z.number().int().min(0),
  bytes: z.number().int().min(0),
  thumbWidth: z.number().int().min(0),
  thumbHeight: z.number().int().min(0),
})

export const chatInviteSchema = z.object({ address: z.string().min(1).max(261), name: z.string().max(32).nullable() })

export const chatReplySchema = z.object({
  id: messageId,
  seq: z.number().int().min(0),
  sender: user.nullable(),
  preview: z.string().max(200).nullable(),
  attachments: z.number().int().min(0),
  invite: z.boolean(),
  /** Antwort auf eine Weltkarte (ältere Kerne schicken das Feld nicht). */
  world: z.boolean().default(false),
  deleted: z.boolean(),
})

export const chatSystemSchema = z.object({
  event: z.enum(['group_created', 'member_added', 'member_removed', 'member_left', 'renamed', 'owner_changed']),
  actor: user.nullable(),
  target: user.nullable(),
  name: z.string().max(32).nullable(),
})

export const chatMessageSchema = z.object({
  id: messageId,
  conversationId,
  seq: z.number().int().min(0),
  kind: z.enum(['text', 'system']),
  sender: user.nullable(),
  text: z.string().max(MAX_TEXT).nullable(),
  invite: chatInviteSchema.nullable(),
  /** Weltkarte einer gehosteten Welt (API §21.8). */
  world: chatWorldSchema.nullable().default(null),
  attachments: z.array(chatAttachmentSchema).max(MAX_IMAGES),
  replyTo: chatReplySchema.nullable(),
  system: chatSystemSchema.nullable(),
  reactions: z.array(chatReactionSchema),
  createdAt: time,
  editedAt: time,
  deleted: z.boolean(),
  deletedBy: z.enum(['sender', 'owner', 'admin']).nullable(),
  hidden: z.boolean(),
  nonce: z.string().max(64).nullable(),
})

export const chatMemberSchema = z.object({ uuid, name: z.string().max(16), role: z.enum(['owner', 'member']), joinedAt: time })
export const chatReadMarkSchema = z.object({ uuid, seq: z.number().int().min(0), at: time })

export const chatConversationSchema = z.object({
  id: conversationId,
  kind: z.enum(['dm', 'group']),
  name: z.string().max(32).nullable(),
  owner: uuid.nullable(),
  members: z.array(chatMemberSchema),
  peer: user.nullable(),
  canWrite: z.boolean(),
  readOnlyReason: z.enum(['not_friends', 'chat_muted', 'blocked']).nullable(),
  lastMessage: chatMessageSchema.nullable(),
  lastSeq: z.number().int().min(0),
  unread: z.number().int().min(0),
  markedUnread: z.boolean(),
  readSeq: z.number().int().min(0),
  muted: z.boolean(),
  mutedUntil: time,
  reads: z.array(chatReadMarkSchema),
  createdAt: time,
  updatedAt: time,
})

export const conversationPageSchema = z.object({
  conversations: z.array(chatConversationSchema),
  nextCursor: z.string().max(256).nullable(),
})
export const messagePageSchema = z.object({ messages: z.array(chatMessageSchema), hasMore: z.boolean() })
export const unreadSummarySchema = z.object({
  total: z.number().int().min(0),
  conversations: z.array(
    z.object({ id: conversationId, unread: z.number().int().min(0), markedUnread: z.boolean(), muted: z.boolean() }),
  ),
})

export const inviteStatusSchema = z.object({
  address: z.string().max(261),
  online: z.boolean(),
  reason: z.enum(['private_address', 'unresolvable', 'timeout', 'refused', 'invalid_response', 'busy', 'disabled']).nullable(),
  version: z.string().max(64).nullable(),
  playersOnline: z.number().int().min(0).nullable(),
  playersMax: z.number().int().min(0).nullable(),
  motd: z.string().max(256).nullable(),
  icon: z.string().startsWith('data:image/png;base64,').max(200_000).nullable(),
  latencyMs: z.number().int().min(0).nullable(),
})

export const localImageSchema = z.object({
  id: z.string().regex(/^l[0-9a-f]{20}$/),
  name: z.string().max(80),
  width: z.number().int().min(1),
  height: z.number().int().min(1),
  bytes: z.number().int().min(0),
  addedAt: z.string().max(40),
})
export const stagedImagesSchema = z.object({ images: z.array(localImageSchema), rejected: z.number().int().min(0) })

export const reportReasonIds = ['insult_hate', 'spam', 'inappropriate', 'scam_phishing', 'harassment', 'other'] as const
export type ReportReason = (typeof reportReasonIds)[number]
export const reportKinds = ['message', 'image', 'player', 'group'] as const
export type ReportKind = (typeof reportKinds)[number]
export type ReportStatus = 'open' | 'in_review' | 'resolved'

export const myReportSchema = z.object({
  id: reportId,
  kind: z.enum(reportKinds),
  reason: z.enum(reportReasonIds),
  status: z.enum(['open', 'in_review', 'resolved']),
  outcome: z.enum(['actioned', 'dismissed']).nullable(),
  createdAt: time,
  updatedAt: time,
})

export const myModerationSchema = z.object({
  mute: z
    .object({ until: time, reason: z.string().max(200).nullable(), auto: z.enum(['reports', 'spam']).nullable() })
    .nullable(),
  warnings: z.array(z.object({ reason: z.string().max(200).nullable(), at: time })),
})

export const liveStatusSchema = z.object({
  state: z.enum(['off', 'connecting', 'live', 'down']),
  account: uuid.nullable(),
  retryInMs: z.number().int().min(0).nullable(),
})

const presence = trsPresenceSchema.nullable()

/** Ereignisse aus dem Echtzeit-Kanal (Tauri-Event `trs-live`). */
export const liveEventSchema = z.discriminatedUnion('type', [
  z.object({ type: z.literal('hello'), resumed: z.boolean(), first: z.boolean() }),
  z.object({ type: z.literal('resync'), reason: z.string() }),
  z.object({ type: z.literal('chat_message'), conversationId, message: chatMessageSchema }),
  z.object({ type: z.literal('chat_message_edited'), conversationId, message: chatMessageSchema }),
  z.object({ type: z.literal('chat_message_deleted'), conversationId, message: chatMessageSchema }),
  z.object({ type: z.literal('chat_reactions'), conversationId, messageId, reactions: z.array(chatReactionSchema) }),
  z.object({ type: z.literal('chat_typing'), conversationId, uuid, typing: z.boolean(), expiresInMs: z.number().int().min(0) }),
  z.object({ type: z.literal('chat_read'), conversationId, uuid, seq: z.number().int().min(0), at: time }),
  z.object({
    type: z.literal('chat_state'),
    conversationId,
    unread: z.number().int().min(0),
    markedUnread: z.boolean(),
    readSeq: z.number().int().min(0),
    muted: z.boolean(),
    mutedUntil: time,
  }),
  z.object({ type: z.literal('chat_conversation'), conversation: chatConversationSchema }),
  z.object({ type: z.literal('chat_conversation_removed'), conversationId, reason: z.enum(['left', 'removed', 'deleted']) }),
  z.object({ type: z.literal('chat_reload'), conversationId }),
  z.object({ type: z.literal('friend_request'), from: user }),
  z.object({ type: z.literal('friend_request_cancelled'), uuid }),
  z.object({ type: z.literal('friend_added'), friend: user }),
  z.object({ type: z.literal('friend_removed'), uuid }),
  z.object({ type: z.literal('friends_changed') }),
  z.object({ type: z.literal('presence'), uuid, presence }),
  z.object({ type: z.literal('friend_online'), friend: user, presence }),
  z.object({ type: z.literal('cape_offer'), from: user.nullable(), cape: z.string().max(48).nullable() }),
  z.object({ type: z.literal('cape_offer_accepted'), capeId: z.string().max(40), by: user.nullable() }),
  z.object({ type: z.literal('cape_share_removed'), capeId: z.string().max(40) }),
  z.object({ type: z.literal('report_update'), report: myReportSchema }),
  z.object({
    type: z.literal('moderation'),
    action: z.enum(['warn', 'mute', 'unmute']),
    reason: z.string().max(200).nullable(),
    until: time,
    auto: z.enum(['reports', 'spam']).nullable(),
  }),
  z.object({
    type: z.literal('settings'),
    settings: z.object({
      showBadge: z.boolean(),
      showCapeToOthers: z.boolean(),
      presenceVisibility: z.enum(['friends', 'nobody']),
      shareServer: z.boolean(),
      chatReadReceipts: z.boolean(),
      chatTypingIndicator: z.boolean(),
    }),
  }),
  ...hostingEventSchemas,
])

export type ChatReaction = z.infer<typeof chatReactionSchema>
export type ChatAttachment = z.infer<typeof chatAttachmentSchema>
export type ChatInvite = z.infer<typeof chatInviteSchema>
export type ChatReply = z.infer<typeof chatReplySchema>
export type ChatSystem = z.infer<typeof chatSystemSchema>
export type ChatMessage = z.infer<typeof chatMessageSchema>
export type ChatMember = z.infer<typeof chatMemberSchema>
export type ChatConversation = z.infer<typeof chatConversationSchema>
export type ConversationPage = z.infer<typeof conversationPageSchema>
export type MessagePage = z.infer<typeof messagePageSchema>
export type UnreadSummary = z.infer<typeof unreadSummarySchema>
export type InviteStatus = z.infer<typeof inviteStatusSchema>
export type LocalImage = z.infer<typeof localImageSchema>
export type StagedImages = z.infer<typeof stagedImagesSchema>
export type MyReport = z.infer<typeof myReportSchema>
export type MyModeration = z.infer<typeof myModerationSchema>
export type LiveStatus = z.infer<typeof liveStatusSchema>
export type LiveEvent = z.infer<typeof liveEventSchema>

/** Was beim Senden rausgeht (der Kern prüft alles noch einmal). */
export interface OutgoingMessage {
  text?: string
  replyTo?: string
  attachments?: string[]
  invite?: { address: string; name: string | null }
  nonce?: string
}

/** Woher ein Bild zum Hochladen kommt. */
export type UploadSource =
  | { kind: 'screenshot'; instanceId: string; fileName: string }
  | { kind: 'local'; id: string }

/** Was gemeldet wird. */
export type ReportTarget =
  | { kind: 'message'; messageId: string }
  | { kind: 'image'; attachmentId: string }
  | { kind: 'player'; uuid: string; conversationId?: string }
  | { kind: 'group'; conversationId: string }

/** Eine Nachricht in der Oberfläche – auch noch nicht bestätigte eigene. */
export type LocalMessage = ChatMessage & {
  /** Nur bei eigenen, noch nicht bestätigten Nachrichten. */
  local?: {
    state: 'sending' | 'failed'
    error?: string
    /** Zum erneuten Senden. */
    draft: OutgoingMessage
    /** Vorschauen der Bilder, während sie hochladen (URLs für `<img>`). */
    previews: string[]
    /** Quellen der Bilder (für erneutes Senden, falls das Hochladen scheiterte). */
    sources: UploadSource[]
  }
}

// --- Bild-URLs ----------------------------------------------------------------------

let chatBase: string | null = null

/** Basis des Protokolls `trschat:` (Windows: `http://trschat.localhost/`). Tests setzen sie selbst. */
export function setChatBase(base: string) {
  chatBase = base.endsWith('/') ? base : `${base}/`
}

function base(): string {
  if (chatBase) return chatBase
  const windows = typeof navigator !== 'undefined' && /windows/i.test(navigator.userAgent)
  return windows ? 'http://trschat.localhost/' : 'trschat://localhost/'
}

/** Bild einer Nachricht (`thumb` = Vorschau ≤ 400 px). Ohne Token – der Kern holt es. */
export function attachmentUrl(id: string, thumb = false): string {
  if (!/^a[0-9a-f]{24}$/.test(id)) return ''
  return `${base()}${thumb ? 't' : 'a'}/${id}`
}

/** Vorschau einer eigenen Datei im Auswahlfeld. */
export function localImageUrl(id: string): string {
  if (!/^l[0-9a-f]{20}$/.test(id)) return ''
  return `${base()}l/${id}`
}

/** Beweisbild einer Meldung (nur Admins). */
export function evidenceUrl(report: string, id: string): string {
  if (!/^r[0-9a-f]{16}$/.test(report) || !/^a[0-9a-f]{24}$/.test(id)) return ''
  return `${base()}e/${report}/${id}`
}

// --- Nachrichtenlisten --------------------------------------------------------------

function isPending(m: LocalMessage): boolean {
  return m.local !== undefined
}

/** Reihenfolge: bestätigte nach `seq`, eigene unbestätigte am Ende (in Sendereihenfolge). */
function compare(a: LocalMessage, b: LocalMessage): number {
  const pa = isPending(a)
  const pb = isPending(b)
  if (pa !== pb) return pa ? 1 : -1
  if (pa && pb) return (a.createdAt ?? '').localeCompare(b.createdAt ?? '')
  return a.seq - b.seq
}

/**
 * Nachricht einfügen oder ersetzen: gleiche ID → ersetzt; gleiche Nonce wie
 * eine eigene unbestätigte → die bestätigte ersetzt sie (auch vom Echtzeit-Kanal).
 * Liefert eine neue, sortierte Liste.
 */
export function upsertMessage(list: readonly LocalMessage[], message: LocalMessage): LocalMessage[] {
  const out = list.filter((m) => {
    if (m.id === message.id) return false
    if (message.nonce && m.local && m.nonce === message.nonce) return false
    return true
  })
  out.push(message)
  out.sort(compare)
  return out
}

/** Mehrere Nachrichten (z. B. eine nachgeladene Seite) einfügen. */
export function mergeMessages(list: readonly LocalMessage[], incoming: readonly LocalMessage[]): LocalMessage[] {
  let out = [...list]
  for (const m of incoming) out = upsertMessage(out, m)
  return out
}

/** Höchste bestätigte Nachrichtennummer (für „nachholen“). */
export function lastSeq(list: readonly LocalMessage[]): number {
  let seq = 0
  for (const m of list) if (!m.local && m.seq > seq) seq = m.seq
  return seq
}

/** Reaktion lokal umschalten (sofort sichtbar, der Server bestätigt). */
export function toggleReaction(reactions: readonly ChatReaction[], emoji: ReactionId, me: string, on: boolean): ChatReaction[] {
  const out = reactions.map((r) => ({ ...r, users: [...r.users] }))
  const existing = out.find((r) => r.emoji === emoji)
  if (on) {
    if (existing) {
      if (!existing.users.includes(me)) {
        existing.users.push(me)
        existing.count += 1
      }
    } else {
      out.push({ emoji, count: 1, users: [me] })
    }
  } else if (existing && existing.users.includes(me)) {
    existing.users = existing.users.filter((u) => u !== me)
    existing.count = Math.max(0, existing.count - 1)
  }
  return out.filter((r) => r.count > 0).sort((a, b) => REACTION_IDS.indexOf(a.emoji) - REACTION_IDS.indexOf(b.emoji))
}

/** Zufälliger Idempotenz-Schlüssel je Nachricht (`^[A-Za-z0-9_-]{8,64}$`). */
export function newNonce(): string {
  const bytes = new Uint8Array(12)
  globalThis.crypto.getRandomValues(bytes)
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
}

// --- Zeitleiste ---------------------------------------------------------------------

export type TimelineItem =
  | { type: 'day'; key: string; label: string }
  | {
      type: 'message'
      key: string
      message: LocalMessage
      mine: boolean
      /** Beginn einer Gruppe (Name/Zeit zeigen). */
      first: boolean
      /** Ende einer Gruppe (Abstand danach). */
      last: boolean
    }

function dateOf(m: LocalMessage, now: Date): Date {
  const d = m.createdAt ? new Date(m.createdAt) : now
  return Number.isNaN(d.getTime()) ? now : d
}

/** Kalendertag in der lokalen Zeitzone (`2026-09-26`). */
export function dayKey(d: Date): string {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function startOfDay(d: Date): number {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime()
}

/** Abstand in ganzen Kalendertagen (heute = 0, gestern = 1). */
export function daysAgo(d: Date, now: Date): number {
  return Math.round((startOfDay(now) - startOfDay(d)) / 86_400_000)
}

/** Trenner-Beschriftung: „Heute“, „Gestern“, Wochentag, sonst Datum. */
export function dayLabel(d: Date, now = new Date()): string {
  const ago = daysAgo(d, now)
  if (ago === 0) return t('social.chat.today')
  if (ago === 1) return t('social.chat.yesterday')
  if (ago > 1 && ago < 7) return new Intl.DateTimeFormat(intlLocale(), { weekday: 'long' }).format(d)
  const sameYear = d.getFullYear() === now.getFullYear()
  return new Intl.DateTimeFormat(intlLocale(), sameYear ? { day: 'numeric', month: 'long' } : { dateStyle: 'medium' }).format(d)
}

/** Uhrzeit einer Nachricht („17:52“ / „5:52 PM“). */
export function messageTime(iso: string | null, now = new Date()): string {
  const d = iso ? new Date(iso) : now
  if (Number.isNaN(d.getTime())) return ''
  return new Intl.DateTimeFormat(intlLocale(), { hour: 'numeric', minute: '2-digit' }).format(d)
}

/** Datum in der Unterhaltungsliste: Uhrzeit heute, „Gestern“, Wochentag, Datum. */
export function listTime(iso: string | null, now = new Date()): string {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  const ago = daysAgo(d, now)
  if (ago <= 0) return messageTime(iso, now)
  if (ago === 1) return t('social.chat.yesterday')
  if (ago < 7) return new Intl.DateTimeFormat(intlLocale(), { weekday: 'short' }).format(d)
  const sameYear = d.getFullYear() === now.getFullYear()
  return new Intl.DateTimeFormat(intlLocale(), sameYear ? { day: 'numeric', month: 'short' } : { dateStyle: 'medium' }).format(d)
}

/**
 * Zeitleiste mit Tagestrennern und Gruppen: Nachrichten desselben Absenders
 * im Abstand von höchstens 5 Minuten gehören zusammen (Name/Uhrzeit nur einmal).
 */
export function buildTimeline(messages: readonly LocalMessage[], me: string | null, now = new Date()): TimelineItem[] {
  const items: TimelineItem[] = []
  let lastDay = ''
  let prev: { sender: string | null; at: number; system: boolean } | null = null
  let lastMessageItem: Extract<TimelineItem, { type: 'message' }> | null = null
  for (const m of messages) {
    const d = dateOf(m, now)
    const day = dayKey(d)
    if (day !== lastDay) {
      items.push({ type: 'day', key: `day:${day}`, label: dayLabel(d, now) })
      lastDay = day
      prev = null
      if (lastMessageItem) lastMessageItem.last = true
      lastMessageItem = null
    }
    const sender = m.sender?.uuid ?? null
    const system = m.kind === 'system'
    const at = d.getTime()
    const first = !prev || prev.system || system || prev.sender !== sender || at - prev.at > CLUSTER_MS
    if (first && lastMessageItem) lastMessageItem.last = true
    const item = { type: 'message' as const, key: m.id, message: m, mine: !!me && sender === me && !system, first, last: false }
    items.push(item)
    lastMessageItem = item
    prev = { sender, at, system }
  }
  if (lastMessageItem) lastMessageItem.last = true
  return items
}

// --- Texte --------------------------------------------------------------------------

/** Name einer Unterhaltung: Freund bzw. Gruppenname (oder die Mitglieder). */
export function conversationTitle(c: Pick<ChatConversation, 'kind' | 'name' | 'peer' | 'members'>, me: string | null): string {
  if (c.kind === 'dm') return c.peer?.name ?? '?'
  if (c.name) return c.name
  const names = c.members.filter((m) => m.uuid !== me).map((m) => m.name)
  return names.slice(0, 3).join(', ') || t('social.group.unnamed')
}

/** Satz für eine Systemnachricht („Bob hat Carl hinzugefügt“). */
export function systemText(system: ChatSystem | null): string {
  if (!system) return ''
  const actor = system.actor?.name ?? t('social.system.someone')
  const target = system.target?.name ?? t('social.system.someone')
  switch (system.event) {
    case 'group_created':
      return t('social.system.groupCreated', { actor })
    case 'member_added':
      return t('social.system.memberAdded', { actor, target })
    case 'member_removed':
      return t('social.system.memberRemoved', { actor, target })
    case 'member_left':
      return t('social.system.memberLeft', { name: system.target?.name ?? actor })
    case 'renamed':
      return t('social.system.renamed', { actor, name: system.name ?? '' })
    case 'owner_changed':
      return t('social.system.ownerChanged', { name: target })
  }
}

/** Kurzfassung einer Nachricht (Liste, Antwort, Benachrichtigung). */
export function messageSummary(
  m: Pick<ChatMessage, 'kind' | 'text' | 'attachments' | 'invite' | 'deleted' | 'hidden' | 'system'> & { world?: ChatMessage['world'] },
): string {
  if (m.deleted) return t('social.chat.deleted')
  if (m.hidden) return t('social.chat.hidden')
  if (m.kind === 'system') return systemText(m.system)
  const text = m.text?.split('\n').find((l) => l.trim())?.trim()
  if (text) return text
  if (m.attachments.length) return t('social.chat.images', m.attachments.length)
  if (m.invite) return t('social.chat.inviteTo', { address: m.invite.address })
  if (m.world) return t('social.hosting.cardSummary', { name: m.world.name })
  return ''
}

/** Vorschau in der Unterhaltungsliste (eigene mit „Du: “). */
export function conversationPreview(c: Pick<ChatConversation, 'lastMessage'>, me: string | null): string {
  const m = c.lastMessage
  if (!m) return t('social.chat.clickToWrite')
  const summary = messageSummary(m)
  if (m.kind !== 'system' && !m.deleted && m.sender?.uuid === me) return t('social.chat.you', { text: summary })
  return summary
}

/** „Bob schreibt …“, „Bob und Alex schreiben …“, „3 Leute schreiben …“. */
export function typingText(names: readonly string[]): string {
  if (!names.length) return ''
  if (names.length === 1) return t('social.chat.typingOne', { name: names[0]! })
  if (names.length === 2) return t('social.chat.typingTwo', { a: names[0]!, b: names[1]! })
  return t('social.chat.typingMany', { n: names.length })
}

/** Wer tippt gerade (abgelaufene Einträge zählen nicht, ich nie). */
export function typingNames(
  typing: Readonly<Record<string, number>> | undefined,
  conversation: Pick<ChatConversation, 'members' | 'peer'>,
  me: string | null,
  now = Date.now(),
): string[] {
  if (!typing) return []
  const names: string[] = []
  for (const [uuid, until] of Object.entries(typing)) {
    if (until <= now || uuid === me) continue
    const name = conversation.members.find((m) => m.uuid === uuid)?.name ?? (conversation.peer?.uuid === uuid ? conversation.peer.name : null)
    if (name) names.push(name)
  }
  return names.sort()
}

/**
 * Lesestatus unter meiner letzten Nachricht: DM „Gelesen“/„Gesendet“,
 * Gruppe „Gelesen von n“. `null` = nichts zeigen (keine Lesebestätigungen).
 */
export function readState(
  c: Pick<ChatConversation, 'kind' | 'reads' | 'members'>,
  message: Pick<LocalMessage, 'seq' | 'local'>,
  me: string | null,
): { read: boolean; count: number } | null {
  if (message.local) return null
  const others = c.reads.filter((r) => r.uuid !== me)
  const readers = others.filter((r) => r.seq >= message.seq).length
  if (c.kind === 'dm') return { read: readers > 0, count: readers }
  return readers > 0 ? { read: true, count: readers } : { read: false, count: 0 }
}

// --- Links --------------------------------------------------------------------------

export type TextPart = { type: 'text'; value: string } | { type: 'link'; value: string; href: string }

const LINK = /https:\/\/[^\s<>"'\x60]+/g
const TRAILING = /[.,;:!?)\]}'"»”]+$/

/**
 * Text in Teile zerlegen: nur `https://`-Links werden klickbar (höchstens 10),
 * alles andere bleibt reiner Text (Vue escaped es beim Einsetzen).
 */
export function splitLinks(text: string): TextPart[] {
  const parts: TextPart[] = []
  let at = 0
  let links = 0
  for (const match of text.matchAll(LINK)) {
    if (links >= 10) break
    let url = match[0]
    const trail = TRAILING.exec(url)
    if (trail) url = url.slice(0, url.length - trail[0].length)
    const start = match.index ?? 0
    let parsed: URL | null = null
    try {
      parsed = new URL(url)
    } catch {
      parsed = null
    }
    if (!parsed || parsed.protocol !== 'https:' || !parsed.hostname.includes('.') || parsed.username || parsed.password) continue
    if (start > at) parts.push({ type: 'text', value: text.slice(at, start) })
    parts.push({ type: 'link', value: url, href: parsed.href })
    at = start + url.length
    links++
  }
  if (at < text.length) parts.push({ type: 'text', value: text.slice(at) })
  return parts
}

/** Nur Emojis (1–3), dann größer darstellen. */
export function onlyEmoji(text: string | null): boolean {
  if (!text) return false
  const trimmed = text.trim()
  if (!trimmed || trimmed.length > 24) return false
  const noEmoji = trimmed.replace(/\p{Extended_Pictographic}|\p{Emoji_Modifier}|‍|️|\s/gu, '')
  if (noEmoji.length) return false
  const count = [...trimmed.matchAll(/\p{Extended_Pictographic}/gu)].length
  return count >= 1 && count <= 3
}

/** Zeichen zählen wie die API (Unicode-Codepunkte). */
export function codePoints(text: string): number {
  return [...text].length
}

// --- Unterhaltungen -----------------------------------------------------------------

/** Neueste Aktivität zuerst. */
export function sortConversations<T extends Pick<ChatConversation, 'updatedAt' | 'lastMessage' | 'id'>>(list: readonly T[]): T[] {
  const at = (c: T) => c.lastMessage?.createdAt ?? c.updatedAt ?? ''
  return [...list].sort((a, b) => at(b).localeCompare(at(a)) || a.id.localeCompare(b.id))
}

/** Zählt für das Abzeichen (stummgeschaltete nicht). */
export function unreadCount(c: Pick<ChatConversation, 'unread' | 'markedUnread' | 'muted'>): number {
  if (c.muted) return 0
  return c.unread > 0 ? c.unread : c.markedUnread ? 1 : 0
}

/** Stummschaltung abgelaufen? (`mutedUntil` in der Vergangenheit) */
export function isMuted(c: Pick<ChatConversation, 'muted' | 'mutedUntil'>, now = Date.now()): boolean {
  if (!c.muted) return false
  if (!c.mutedUntil) return true
  const until = Date.parse(c.mutedUntil)
  return Number.isNaN(until) || until > now
}

/** Suche in der Liste: Name/Gruppenname/Mitglieder, ohne Groß-/Kleinschreibung. */
export function matchesSearch(c: Pick<ChatConversation, 'kind' | 'name' | 'peer' | 'members'>, query: string, me: string | null): boolean {
  const q = query.trim().toLocaleLowerCase()
  if (!q) return true
  if (conversationTitle(c, me).toLocaleLowerCase().includes(q)) return true
  return c.members.some((m) => m.name.toLocaleLowerCase().includes(q))
}
