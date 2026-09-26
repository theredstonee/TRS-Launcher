import { randomBytes } from 'node:crypto'
import {
  attachmentsOf,
  attachmentView,
  getAttachment,
  removeAttachmentFiles,
  type AttachmentView,
} from './attachments'
import type { AppContext } from './context'
import { ChatCipher } from './crypto'
import { all, one, placeholders, run, tx } from './db'
import { ApiError, badRequest, conflict, forbidden, notFound } from './errors'
import type { ApiEvent, PlayerRef } from './events'
import { areFriends, hasBlocked } from './friends'
import { inviteFromCard, worldCardBody, worldCardView, type WorldCardBody, type WorldCardView } from './hosting'
import { activeMute, applySpamStrike } from './moderation'
import { applyWordFilter, assertText, countLinks, sanitizeText } from './safety'
import { getUser } from './users'

/**
 * Chat: Direktnachrichten (nur zwischen Freunden) und Gruppen (Besitzer lädt eigene Freunde ein).
 *
 * Regeln:
 * - Schreiben dürfen Freunde (DM) bzw. Mitglieder (Gruppe). Entfreundet/blockiert → DM nur lesbar.
 *   Wer (per Moderation) stummgeschaltet ist, kann nirgends schreiben, reagieren oder tippen.
 * - Jeder Zugriff prüft die Mitgliedschaft; Fremde bekommen immer `404 conversation_not_found`
 *   (keine Auskunft, ob es die Unterhaltung gibt).
 * - Neue Gruppenmitglieder sehen Nachrichten erst ab ihrem Beitritt.
 * - Inhalte (Text, Einladung, Gruppenname) liegen AES-GCM-verschlüsselt in der Datenbank.
 * - Links/Server-Einladungen in Gruppen nur vom Besitzer oder von jemandem, der mit allen
 *   anderen Mitgliedern befreundet ist (Schutz vor Werbung/Phishing durch Fremde).
 */

export const CONVERSATION_ID = /^c[0-9a-f]{20}$/
export const MESSAGE_ID = /^m[0-9a-f]{20}$/

/** Feste Reaktionen (ID → Emoji). Clients zeigen das Emoji bzw. ein eigenes Bild dazu. */
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

/** `muted_until` für „unbefristet stumm“. */
export const MUTED_FOREVER = 253402300799000

export type SystemEvent = 'group_created' | 'member_added' | 'member_removed' | 'member_left' | 'renamed' | 'owner_changed'

export interface ConversationRow {
  id: string
  kind: 'dm' | 'group'
  dm_key: string | null
  name: Uint8Array | null
  owner_uuid: string | null
  created_at: number
  updated_at: number
  last_seq: number
}

export interface MemberRow {
  conversation_id: string
  uuid: string
  role: 'owner' | 'member'
  joined_at: number
  visible_from_seq: number
  read_seq: number
  receipt_seq: number
  receipt_at: number | null
  marked_unread: number
  muted_until: number | null
}

export interface MessageRow {
  id: string
  conversation_id: string
  seq: number
  sender_uuid: string | null
  kind: 'text' | 'system'
  body: Uint8Array | null
  reply_to: string | null
  created_at: number
  edited_at: number | null
  deleted_at: number | null
  deleted_by: 'sender' | 'owner' | 'admin' | null
  nonce: string | null
}

/** Verschlüsselter Inhalt einer Nachricht. */
interface Body {
  t?: string
  i?: { a: string, n?: string }
  /** Weltkarte (Welt-Hosting, §21). */
  w?: WorldCardBody
  s?: { e: SystemEvent, tg?: string, n?: string }
}

export interface InviteView {
  /** `host[:port]` wie im Spiel. Status (Icon, Spieler) über `GET /v1/servers/status`. */
  address: string
  /** Optionaler Anzeigename des Absenders. */
  name: string | null
}

export interface ReplyView {
  id: string
  seq: number
  sender: PlayerRef | null
  /** Anfang des Texts (≤ 120 Zeichen), `null` bei gelöschten/ausgeblendeten Nachrichten. */
  preview: string | null
  attachments: number
  invite: boolean
  /** Antwort auf eine Weltkarte. */
  world: boolean
  deleted: boolean
}

export interface ReactionView {
  emoji: ReactionId
  count: number
  /** Wer reagiert hat (Gruppen sind klein). */
  users: string[]
}

export interface SystemView {
  event: SystemEvent
  actor: PlayerRef | null
  target: PlayerRef | null
  /** Neuer Gruppenname bei `renamed`. */
  name: string | null
}

export interface MessageView {
  id: string
  conversationId: string
  seq: number
  kind: 'text' | 'system'
  sender: PlayerRef | null
  text: string | null
  invite: InviteView | null
  /** Einladung in eine gehostete Welt (§21). */
  world: WorldCardView | null
  attachments: AttachmentView[]
  replyTo: ReplyView | null
  system: SystemView | null
  reactions: ReactionView[]
  createdAt: string
  editedAt: string | null
  deleted: boolean
  deletedBy: 'sender' | 'owner' | 'admin' | null
  /** Du hast den Absender blockiert – Inhalt ausgeblendet. */
  hidden: boolean
  /** Idempotenz-Schlüssel des Absenders (nur für den Absender selbst gefüllt). */
  nonce: string | null
}

export interface MemberView {
  uuid: string
  name: string
  role: 'owner' | 'member'
  joinedAt: string
}

export interface ConversationView {
  id: string
  kind: 'dm' | 'group'
  /** Gruppenname (bei DMs `null` – dort gilt `peer`). */
  name: string | null
  owner: string | null
  members: MemberView[]
  peer: PlayerRef | null
  canWrite: boolean
  readOnlyReason: 'not_friends' | 'chat_muted' | null
  lastMessage: MessageView | null
  lastSeq: number
  unread: number
  markedUnread: boolean
  readSeq: number
  muted: boolean
  mutedUntil: string | null
  /** Lesestände der anderen (nur wenn beide Seiten Lesebestätigungen teilen). */
  reads: { uuid: string, seq: number, at: string | null }[]
  createdAt: string
  updatedAt: string
}

const iso = (t: number) => new Date(t).toISOString()
const hex = (n: number) => randomBytes(n).toString('hex')
const newConversationId = () => `c${hex(10)}`
const newMessageId = () => `m${hex(10)}`
const pairKey = (a: string, b: string) => (a < b ? `${a}:${b}` : `${b}:${a}`)
const TYPING_TTL_MS = 8000

const notFoundConv = () => notFound('conversation_not_found', 'Conversation not found')
const notFoundMsg = () => notFound('message_not_found', 'Message not found')

// ---------------------------------------------------------------- Verschlüsselung

function encBody(ctx: AppContext, id: string, body: Body): Uint8Array {
  return ctx.cipher.encrypt(JSON.stringify(body), `msg:${id}`)
}

function decBody(ctx: AppContext, row: Pick<MessageRow, 'id' | 'body'>): Body | null {
  if (!row.body) return null
  try {
    return JSON.parse(ctx.cipher.decryptText(row.body, `msg:${row.id}`)) as Body
  } catch (err) {
    console.error(`[trs-api] could not decrypt message ${row.id}`, (err as Error).message)
    return null
  }
}

function groupName(ctx: AppContext, c: Pick<ConversationRow, 'id' | 'name'>): string | null {
  if (!c.name) return null
  try {
    return ctx.cipher.decryptText(c.name, `grp:${c.id}`)
  } catch {
    return ''
  }
}

// ---------------------------------------------------------------- Lesen / Zugriff

export function getConversation(ctx: AppContext, id: string): ConversationRow | undefined {
  return one<ConversationRow>(ctx.db, 'SELECT * FROM chat_conversations WHERE id = ?', id)
}

export function getMember(ctx: AppContext, conversationId: string, uuid: string): MemberRow | undefined {
  return one<MemberRow>(ctx.db, 'SELECT * FROM chat_members WHERE conversation_id = ? AND uuid = ?', conversationId, uuid)
}

/** Unterhaltung + eigene Mitgliedschaft oder 404. */
export function access(ctx: AppContext, me: string, conversationId: string): { conv: ConversationRow, member: MemberRow } {
  const member = getMember(ctx, conversationId, me)
  const conv = member ? getConversation(ctx, conversationId) : undefined
  if (!member || !conv) throw notFoundConv()
  return { conv, member }
}

export function memberUuids(ctx: AppContext, conversationId: string): string[] {
  return all<{ uuid: string }>(ctx.db, 'SELECT uuid FROM chat_members WHERE conversation_id = ? ORDER BY joined_at, uuid', conversationId).map((r) => r.uuid)
}

function dmPeer(conv: ConversationRow, me: string): string | null {
  if (conv.kind !== 'dm' || !conv.dm_key) return null
  const [a, b] = conv.dm_key.split(':') as [string, string]
  return a === me ? b : a
}

/** Darf `me` hier schreiben? `null` = ja, sonst der Grund. */
export function writeBlock(ctx: AppContext, conv: ConversationRow, me: string): 'not_friends' | 'chat_muted' | null {
  if (activeMute(ctx, me)) return 'chat_muted'
  if (conv.kind === 'dm') {
    const peer = dmPeer(conv, me)
    if (!peer || !getUser(ctx, peer) || !areFriends(ctx, me, peer)) return 'not_friends'
  }
  return null
}

function assertCanWrite(ctx: AppContext, conv: ConversationRow, me: string): void {
  const reason = writeBlock(ctx, conv, me)
  if (reason === 'chat_muted') {
    const m = activeMute(ctx, me)!
    throw new ApiError(403, 'chat_muted', 'You are muted in chat', { until: m.expires_at ? iso(m.expires_at) : null })
  }
  if (reason === 'not_friends') throw forbidden('not_friends', 'You can only message friends')
}

function names(ctx: AppContext, uuids: Iterable<string>): Map<string, string> {
  const list = [...new Set(uuids)]
  const out = new Map<string, string>()
  for (let i = 0; i < list.length; i += 400) {
    const part = list.slice(i, i + 400)
    for (const r of all<{ uuid: string, name: string }>(ctx.db, `SELECT uuid, name FROM users WHERE uuid IN (${placeholders(part.length)})`, ...part)) {
      out.set(r.uuid, r.name)
    }
  }
  return out
}

function blockedBy(ctx: AppContext, viewer: string): Set<string> {
  return new Set(all<{ blocked: string }>(ctx.db, 'SELECT blocked FROM blocks WHERE blocker = ?', viewer).map((r) => r.blocked))
}

// ---------------------------------------------------------------- Ansichten

function reactionsOf(ctx: AppContext, ids: string[]): Map<string, ReactionView[]> {
  const out = new Map<string, ReactionView[]>()
  if (ids.length === 0) return out
  for (const r of all<{ message_id: string, uuid: string, emoji: ReactionId }>(
    ctx.db,
    `SELECT message_id, uuid, emoji FROM chat_reactions WHERE message_id IN (${placeholders(ids.length)}) ORDER BY created_at, rowid`,
    ...ids,
  )) {
    const list = out.get(r.message_id) ?? []
    let v = list.find((x) => x.emoji === r.emoji)
    if (!v) {
      v = { emoji: r.emoji, count: 0, users: [] }
      list.push(v)
    }
    v.count++
    v.users.push(r.uuid)
    out.set(r.message_id, list)
  }
  return out
}

/** Ansichten für eine Liste von Nachrichten. `hideFrom` = Absender, die der Betrachter blockiert hat. */
export function messageViews(ctx: AppContext, rows: MessageRow[], hideFrom: Set<string>, viewer: string | null): MessageView[] {
  if (rows.length === 0) return []
  const ids = rows.map((r) => r.id)
  const atts = attachmentsOf(ctx, ids)
  const reacts = reactionsOf(ctx, ids)
  const replyIds = [...new Set(rows.map((r) => r.reply_to).filter((x): x is string => !!x))]
  const replies = new Map<string, MessageRow>()
  if (replyIds.length) {
    for (const r of all<MessageRow>(ctx.db, `SELECT * FROM chat_messages WHERE id IN (${placeholders(replyIds.length)})`, ...replyIds)) replies.set(r.id, r)
  }
  const replyAtts = attachmentsOf(ctx, [...replies.keys()])
  const bodies = new Map(rows.map((r) => [r.id, decBody(ctx, r)]))
  const people = new Set<string>()
  for (const r of rows) {
    if (r.sender_uuid) people.add(r.sender_uuid)
    const tg = bodies.get(r.id)?.s?.tg
    if (tg) people.add(tg)
  }
  for (const r of replies.values()) if (r.sender_uuid) people.add(r.sender_uuid)
  const nm = names(ctx, people)
  const ref = (u: string | null | undefined): PlayerRef | null => (u ? { uuid: u, name: nm.get(u) ?? '' } : null)

  return rows.map((r) => {
    const body = bodies.get(r.id) ?? null
    const hidden = !!r.sender_uuid && hideFrom.has(r.sender_uuid) && r.kind === 'text'
    const deleted = r.deleted_at !== null
    const rep = r.reply_to ? replies.get(r.reply_to) : undefined
    let replyTo: ReplyView | null = null
    if (r.reply_to) {
      if (rep && rep.conversation_id === r.conversation_id) {
        const rb = rep.deleted_at === null ? decBody(ctx, rep) : null
        const repHidden = !!rep.sender_uuid && hideFrom.has(rep.sender_uuid)
        const text = rb?.t ?? null
        replyTo = {
          id: rep.id,
          seq: rep.seq,
          sender: ref(rep.sender_uuid),
          preview: repHidden || !text ? null : [...text].slice(0, 120).join(''),
          attachments: repHidden ? 0 : (replyAtts.get(rep.id)?.length ?? 0),
          invite: !repHidden && !!rb?.i,
          world: !repHidden && !!rb?.w,
          deleted: rep.deleted_at !== null,
        }
      } else {
        replyTo = { id: r.reply_to, seq: 0, sender: null, preview: null, attachments: 0, invite: false, world: false, deleted: true }
      }
    }
    const show = !deleted && !hidden
    return {
      id: r.id,
      conversationId: r.conversation_id,
      seq: r.seq,
      kind: r.kind,
      sender: ref(r.sender_uuid),
      text: show ? (body?.t ?? null) : null,
      invite: show && body?.i ? { address: body.i.a, name: body.i.n ?? null } : null,
      world: show && body?.w && r.sender_uuid ? worldCardView(body.w, ref(r.sender_uuid)!) : null,
      attachments: show ? (atts.get(r.id) ?? []).map(attachmentView) : [],
      replyTo: show ? replyTo : null,
      system: r.kind === 'system' && body?.s
        ? { event: body.s.e, actor: ref(r.sender_uuid), target: ref(body.s.tg), name: body.s.n ?? null }
        : null,
      reactions: show ? (reacts.get(r.id) ?? []) : [],
      createdAt: iso(r.created_at),
      editedAt: r.edited_at ? iso(r.edited_at) : null,
      deleted,
      deletedBy: r.deleted_by,
      hidden,
      nonce: viewer !== null && viewer === r.sender_uuid ? r.nonce : null,
    }
  })
}

function unreadCount(ctx: AppContext, m: MemberRow, me: string): number {
  return one<{ n: number }>(
    ctx.db,
    `SELECT COUNT(*) AS n FROM chat_messages
     WHERE conversation_id = ? AND seq > ? AND seq >= ? AND kind = 'text' AND deleted_at IS NULL
       AND sender_uuid IS NOT ? AND (sender_uuid IS NULL OR sender_uuid NOT IN (SELECT blocked FROM blocks WHERE blocker = ?))`,
    m.conversation_id, m.read_seq, m.visible_from_seq, me, me,
  )!.n
}

function mutedInfo(m: MemberRow, t: number): { muted: boolean, mutedUntil: string | null } {
  const on = m.muted_until !== null && m.muted_until > t
  return { muted: on, mutedUntil: on && m.muted_until !== MUTED_FOREVER ? iso(m.muted_until!) : null }
}

export function conversationView(ctx: AppContext, conv: ConversationRow, me: string, member?: MemberRow): ConversationView {
  const m = member ?? getMember(ctx, conv.id, me)
  if (!m) throw notFoundConv()
  const members = all<MemberRow & { name: string, share: number }>(
    ctx.db,
    `SELECT cm.*, u.name AS name, u.chat_read_receipts AS share FROM chat_members cm JOIN users u ON u.uuid = cm.uuid
     WHERE cm.conversation_id = ? ORDER BY cm.role = 'owner' DESC, cm.joined_at, u.name_lower`,
    conv.id,
  )
  const meRow = getUser(ctx, me)
  const iShare = meRow?.chat_read_receipts === 1
  const last = one<MessageRow>(
    ctx.db,
    'SELECT * FROM chat_messages WHERE conversation_id = ? AND seq >= ? ORDER BY seq DESC LIMIT 1',
    conv.id, m.visible_from_seq,
  )
  const peerUuid = dmPeer(conv, me)
  const peerName = peerUuid ? (members.find((x) => x.uuid === peerUuid)?.name ?? getUser(ctx, peerUuid)?.name ?? '') : null
  const reason = writeBlock(ctx, conv, me)
  const t = ctx.now()
  return {
    id: conv.id,
    kind: conv.kind,
    name: conv.kind === 'group' ? groupName(ctx, conv) : null,
    owner: conv.owner_uuid,
    members: members.map((x) => ({ uuid: x.uuid, name: x.name, role: x.role, joinedAt: iso(x.joined_at) })),
    peer: peerUuid ? { uuid: peerUuid, name: peerName ?? '' } : null,
    canWrite: reason === null,
    readOnlyReason: reason,
    lastMessage: last ? messageViews(ctx, [last], blockedBy(ctx, me), me)[0]! : null,
    lastSeq: conv.last_seq,
    unread: unreadCount(ctx, m, me),
    markedUnread: m.marked_unread === 1,
    readSeq: m.read_seq,
    ...mutedInfo(m, t),
    reads: iShare
      ? members.filter((x) => x.uuid !== me && x.share === 1 && x.receipt_seq > 0)
        .map((x) => ({ uuid: x.uuid, seq: x.receipt_seq, at: x.receipt_at ? iso(x.receipt_at) : null }))
      : [],
    createdAt: iso(conv.created_at),
    updatedAt: iso(conv.updated_at),
  }
}

export interface ConversationPage {
  conversations: ConversationView[]
  nextCursor: string | null
}

function encodeCursor(updatedAt: number, id: string): string {
  return Buffer.from(`${updatedAt}:${id}`).toString('base64url')
}

function decodeCursor(cursor: string): { t: number, id: string } {
  const m = /^(\d{1,15}):(c[0-9a-f]{20})$/.exec(Buffer.from(cursor, 'base64url').toString('utf8'))
  if (!m) throw badRequest('invalid_cursor', 'Invalid cursor')
  return { t: Number(m[1]), id: m[2]! }
}

/** Eigene Unterhaltungen, neueste Aktivität zuerst (Cursor-Seiten). */
export function listConversations(ctx: AppContext, me: string, opts: { cursor?: string, limit: number }): ConversationPage {
  const c = opts.cursor ? decodeCursor(opts.cursor) : null
  const rows = all<ConversationRow & { m_uuid: string }>(
    ctx.db,
    `SELECT c.*, cm.uuid AS m_uuid FROM chat_members cm JOIN chat_conversations c ON c.id = cm.conversation_id
     WHERE cm.uuid = ? ${c ? 'AND (c.updated_at < ? OR (c.updated_at = ? AND c.id < ?))' : ''}
     ORDER BY c.updated_at DESC, c.id DESC LIMIT ?`,
    ...(c ? [me, c.t, c.t, c.id, opts.limit + 1] : [me, opts.limit + 1]),
  )
  const page = rows.slice(0, opts.limit)
  const lastRow = page[page.length - 1]
  return {
    conversations: page.map((r) => conversationView(ctx, r, me)),
    nextCursor: rows.length > opts.limit && lastRow ? encodeCursor(lastRow.updated_at, lastRow.id) : null,
  }
}

export interface UnreadSummary {
  /** Summe ungelesener Nachrichten in nicht stummgeschalteten Unterhaltungen (+1 je „ungelesen markiert“ ohne Nachrichten). */
  total: number
  conversations: { id: string, unread: number, markedUnread: boolean, muted: boolean }[]
}

export function unreadSummary(ctx: AppContext, me: string): UnreadSummary {
  const t = ctx.now()
  const rows = all<MemberRow>(ctx.db, 'SELECT * FROM chat_members WHERE uuid = ?', me)
  let total = 0
  const conversations = rows.map((m) => {
    const unread = unreadCount(ctx, m, me)
    const { muted } = mutedInfo(m, t)
    if (!muted) total += unread > 0 ? unread : m.marked_unread
    return { id: m.conversation_id, unread, markedUnread: m.marked_unread === 1, muted }
  }).filter((c) => c.unread > 0 || c.markedUnread)
  return { total, conversations }
}

export interface MessagePage {
  messages: MessageView[]
  /** Gibt es ältere (bei `before`/Standard) bzw. neuere (bei `after`) Nachrichten? */
  hasMore: boolean
}

/** Nachrichten seitenweise: Standard = neueste; `before` = ältere; `after` = neuere (zum Nachholen). Immer aufsteigend sortiert. */
export function listMessages(ctx: AppContext, me: string, conversationId: string, opts: { before?: number, after?: number, limit: number }): MessagePage {
  const { member } = access(ctx, me, conversationId)
  const from = member.visible_from_seq
  let rows: MessageRow[]
  if (opts.after !== undefined) {
    rows = all<MessageRow>(
      ctx.db,
      'SELECT * FROM chat_messages WHERE conversation_id = ? AND seq > ? AND seq >= ? ORDER BY seq ASC LIMIT ?',
      conversationId, opts.after, from, opts.limit + 1,
    )
    const more = rows.length > opts.limit
    return { messages: messageViews(ctx, rows.slice(0, opts.limit), blockedBy(ctx, me), me), hasMore: more }
  }
  rows = all<MessageRow>(
    ctx.db,
    `SELECT * FROM chat_messages WHERE conversation_id = ? AND seq >= ? ${opts.before !== undefined ? 'AND seq < ?' : ''}
     ORDER BY seq DESC LIMIT ?`,
    ...(opts.before !== undefined ? [conversationId, from, opts.before, opts.limit + 1] : [conversationId, from, opts.limit + 1]),
  )
  const more = rows.length > opts.limit
  const page = rows.slice(0, opts.limit).reverse()
  return { messages: messageViews(ctx, page, blockedBy(ctx, me), me), hasMore: more }
}

export function getMessage(ctx: AppContext, id: string): MessageRow | undefined {
  return one<MessageRow>(ctx.db, 'SELECT * FROM chat_messages WHERE id = ?', id)
}

/** Nachricht, die `me` sehen darf (Mitglied + ab Beitritt), sonst 404. */
export function accessMessage(ctx: AppContext, me: string, messageId: string): { msg: MessageRow, conv: ConversationRow, member: MemberRow } {
  const msg = getMessage(ctx, messageId)
  if (!msg) throw notFoundMsg()
  const member = getMember(ctx, msg.conversation_id, me)
  if (!member || msg.seq < member.visible_from_seq) throw notFoundMsg()
  return { msg, conv: getConversation(ctx, msg.conversation_id)!, member }
}

// ---------------------------------------------------------------- Ereignisse

const publish = (ctx: AppContext, uuid: string, e: ApiEvent, ephemeral = false) => ctx.events.publish(uuid, e, { ephemeral })

/** Nachricht an alle Mitglieder (wer den Absender blockiert hat, bekommt sie ausgeblendet). */
function publishMessage(ctx: AppContext, type: 'chat_message' | 'chat_message_edited' | 'chat_message_deleted', msg: MessageRow): void {
  const members = memberUuids(ctx, msg.conversation_id)
  const targets = members.filter((u) => ctx.events.wants(u))
  if (targets.length === 0) return
  const plain = messageViews(ctx, [msg], new Set(), null)[0]!
  const hiddenFor = msg.sender_uuid
    ? new Set(all<{ blocker: string }>(ctx.db, 'SELECT blocker FROM blocks WHERE blocked = ?', msg.sender_uuid).map((r) => r.blocker))
    : new Set<string>()
  let hidden: MessageView | undefined
  for (const u of targets) {
    let view = plain
    if (hiddenFor.has(u)) view = hidden ??= messageViews(ctx, [msg], new Set([msg.sender_uuid!]), null)[0]!
    if (u === msg.sender_uuid && msg.nonce) view = { ...view, nonce: msg.nonce }
    publish(ctx, u, { type, conversationId: msg.conversation_id, message: view })
  }
}

/** Unterhaltung (neu/geändert) an die angegebenen Mitglieder – jeder bekommt seine eigene Sicht. */
export function publishConversation(ctx: AppContext, conversationId: string, to?: string[]): void {
  const conv = getConversation(ctx, conversationId)
  if (!conv) return
  for (const u of to ?? memberUuids(ctx, conversationId)) {
    if (!ctx.events.wants(u)) continue
    const m = getMember(ctx, conversationId, u)
    if (m) publish(ctx, u, { type: 'chat_conversation', conversation: conversationView(ctx, conv, u, m) })
  }
}

function publishState(ctx: AppContext, me: string, conversationId: string): void {
  if (!ctx.events.wants(me)) return
  const m = getMember(ctx, conversationId, me)
  if (!m) return
  publish(ctx, me, {
    type: 'chat_state',
    conversationId,
    unread: unreadCount(ctx, m, me),
    markedUnread: m.marked_unread === 1,
    readSeq: m.read_seq,
    ...mutedInfo(m, ctx.now()),
  })
}

// ---------------------------------------------------------------- Unterhaltungen anlegen

/** DM mit einem Freund öffnen (oder die bestehende holen). Ohne Freundschaft: nur bestehende, lesend. */
export function openDm(ctx: AppContext, me: string, other: string): ConversationView {
  if (other === me) throw badRequest('cannot_target_self', 'You cannot do this with yourself')
  const key = pairKey(me, other)
  const existing = one<ConversationRow>(ctx.db, 'SELECT * FROM chat_conversations WHERE dm_key = ?', key)
  if (existing) {
    if (getMember(ctx, existing.id, me)) return conversationView(ctx, existing, me)
  }
  if (!areFriends(ctx, me, other)) throw forbidden('not_friends', 'You can only message friends')
  const t = ctx.now()
  const id = existing?.id ?? newConversationId()
  tx(ctx.db, () => {
    if (!existing) {
      run(
        ctx.db,
        "INSERT INTO chat_conversations (id, kind, dm_key, name, owner_uuid, created_at, updated_at, last_seq) VALUES (?, 'dm', ?, NULL, NULL, ?, ?, 0)",
        id, key, t, t,
      )
    }
    for (const u of [me, other]) {
      run(
        ctx.db,
        `INSERT INTO chat_members (conversation_id, uuid, role, joined_at, visible_from_seq) VALUES (?, ?, 'member', ?, 1)
         ON CONFLICT DO NOTHING`,
        id, u, t,
      )
    }
  })
  const conv = getConversation(ctx, id)!
  publishConversation(ctx, id, [other])
  return conversationView(ctx, conv, me)
}

function groupCount(ctx: AppContext, uuid: string): { owned: number, joined: number } {
  const r = one<{ owned: number, joined: number }>(
    ctx.db,
    `SELECT COALESCE(SUM(cm.role = 'owner'), 0) AS owned, COUNT(*) AS joined FROM chat_members cm
     JOIN chat_conversations c ON c.id = cm.conversation_id WHERE cm.uuid = ? AND c.kind = 'group'`,
    uuid,
  )!
  return r
}

function cleanGroupName(raw: string): string {
  const name = sanitizeText(raw).replace(/\n/g, ' ')
  if (name.length === 0) throw badRequest('invalid_name', 'The group name must not be empty')
  assertText(name, 32)
  return name
}

/** Neue Gruppe mit eigenen Freunden. */
export function createGroup(ctx: AppContext, me: string, rawName: string, memberList: string[]): ConversationView {
  const lim = ctx.config.limits
  if (activeMute(ctx, me)) assertCanWrite(ctx, { kind: 'group' } as ConversationRow, me)
  const name = applyWordFilter(ctx, cleanGroupName(rawName))
  const others = [...new Set(memberList)].filter((u) => u !== me)
  if (others.length + 1 > lim.maxGroupMembers) throw conflict('group_full', `A group can have at most ${lim.maxGroupMembers} members`)
  const notFriends = others.filter((u) => !areFriends(ctx, me, u))
  if (notFriends.length) throw new ApiError(403, 'not_friends', 'You can only add your friends', { uuids: notFriends })
  const mine = groupCount(ctx, me)
  if (mine.owned >= lim.maxGroupsOwned) throw conflict('group_limit', `You can own at most ${lim.maxGroupsOwned} groups`)
  if (mine.joined >= lim.maxGroupsJoined) throw conflict('group_limit', `You can be in at most ${lim.maxGroupsJoined} groups`)
  const full = others.filter((u) => groupCount(ctx, u).joined >= lim.maxGroupsJoined)
  if (full.length) throw new ApiError(409, 'target_group_limit', 'Some players are in too many groups', { uuids: full })

  const t = ctx.now()
  const id = newConversationId()
  tx(ctx.db, () => {
    run(
      ctx.db,
      "INSERT INTO chat_conversations (id, kind, dm_key, name, owner_uuid, created_at, updated_at, last_seq) VALUES (?, 'group', NULL, ?, ?, ?, ?, 0)",
      id, ctx.cipher.encrypt(name, `grp:${id}`), me, t, t,
    )
    run(ctx.db, "INSERT INTO chat_members (conversation_id, uuid, role, joined_at, visible_from_seq) VALUES (?, ?, 'owner', ?, 1)", id, me, t)
    for (const u of others) {
      run(ctx.db, "INSERT INTO chat_members (conversation_id, uuid, role, joined_at, visible_from_seq) VALUES (?, ?, 'member', ?, 1)", id, u, t)
    }
    insertSystem(ctx, id, me, { e: 'group_created', n: name })
  })
  publishConversation(ctx, id, others)
  return conversationView(ctx, getConversation(ctx, id)!, me)
}

/** Systemnachricht anlegen (in einer laufenden Transaktion). Rückgabe: die Zeile. */
function insertSystem(ctx: AppContext, conversationId: string, actor: string | null, s: Body['s']): MessageRow {
  const t = ctx.now()
  const conv = getConversation(ctx, conversationId)!
  const seq = conv.last_seq + 1
  const id = newMessageId()
  run(
    ctx.db,
    `INSERT INTO chat_messages (id, conversation_id, seq, sender_uuid, kind, body, reply_to, created_at)
     VALUES (?, ?, ?, ?, 'system', ?, NULL, ?)`,
    id, conversationId, seq, actor, encBody(ctx, id, { s }), t,
  )
  run(ctx.db, 'UPDATE chat_conversations SET last_seq = ?, updated_at = ? WHERE id = ?', seq, t, conversationId)
  return getMessage(ctx, id)!
}

function ownedGroup(ctx: AppContext, me: string, conversationId: string): ConversationRow {
  const { conv, member } = access(ctx, me, conversationId)
  if (conv.kind !== 'group') throw notFoundConv()
  if (member.role !== 'owner') throw forbidden('not_owner', 'Only the group owner can do this')
  return conv
}

export function renameGroup(ctx: AppContext, me: string, conversationId: string, rawName: string): ConversationView {
  const conv = ownedGroup(ctx, me, conversationId)
  assertCanWrite(ctx, conv, me)
  const name = applyWordFilter(ctx, cleanGroupName(rawName))
  const sys = tx(ctx.db, () => {
    run(ctx.db, 'UPDATE chat_conversations SET name = ? WHERE id = ?', ctx.cipher.encrypt(name, `grp:${conv.id}`), conv.id)
    return insertSystem(ctx, conv.id, me, { e: 'renamed', n: name })
  })
  publishMessage(ctx, 'chat_message', sys)
  publishConversation(ctx, conv.id)
  return conversationView(ctx, getConversation(ctx, conv.id)!, me)
}

export function addMembers(ctx: AppContext, me: string, conversationId: string, list: string[]): ConversationView {
  const lim = ctx.config.limits
  const conv = ownedGroup(ctx, me, conversationId)
  assertCanWrite(ctx, conv, me)
  const current = new Set(memberUuids(ctx, conv.id))
  const add = [...new Set(list)].filter((u) => !current.has(u) && u !== me)
  if (add.length === 0) return conversationView(ctx, conv, me)
  const notFriends = add.filter((u) => !areFriends(ctx, me, u))
  if (notFriends.length) throw new ApiError(403, 'not_friends', 'You can only add your friends', { uuids: notFriends })
  if (current.size + add.length > lim.maxGroupMembers) throw conflict('group_full', `A group can have at most ${lim.maxGroupMembers} members`)
  const full = add.filter((u) => groupCount(ctx, u).joined >= lim.maxGroupsJoined)
  if (full.length) throw new ApiError(409, 'target_group_limit', 'Some players are in too many groups', { uuids: full })
  const t = ctx.now()
  const sys = tx(ctx.db, () => add.map((u) => {
    const m = insertSystem(ctx, conv.id, me, { e: 'member_added', tg: u })
    run(ctx.db, "INSERT INTO chat_members (conversation_id, uuid, role, joined_at, visible_from_seq) VALUES (?, ?, 'member', ?, ?)", conv.id, u, t, m.seq)
    return m
  }))
  for (const m of sys) publishMessage(ctx, 'chat_message', m)
  publishConversation(ctx, conv.id)
  return conversationView(ctx, getConversation(ctx, conv.id)!, me)
}

/** Mitglied entfernen (Besitzer) oder selbst austreten (`target === me`). */
export function removeMember(ctx: AppContext, me: string, conversationId: string, target: string): void {
  if (target === me) return leaveGroup(ctx, me, conversationId)
  const conv = ownedGroup(ctx, me, conversationId)
  if (!getMember(ctx, conv.id, target)) throw notFound('member_not_found', 'This player is not in the group')
  const sys = tx(ctx.db, () => {
    run(ctx.db, 'DELETE FROM chat_members WHERE conversation_id = ? AND uuid = ?', conv.id, target)
    return insertSystem(ctx, conv.id, me, { e: 'member_removed', tg: target })
  })
  clearTyping(ctx, conv.id, target)
  publish(ctx, target, { type: 'chat_conversation_removed', conversationId: conv.id, reason: 'removed' })
  publishMessage(ctx, 'chat_message', sys)
  publishConversation(ctx, conv.id)
}

/** Austreten. Besitzer: Besitz geht ans dienstälteste Mitglied; letzter geht → Gruppe weg. */
export function leaveGroup(ctx: AppContext, me: string, conversationId: string): void {
  const { conv, member } = access(ctx, me, conversationId)
  if (conv.kind !== 'group') throw notFound('conversation_not_found', 'Conversation not found')
  const others = all<{ uuid: string }>(
    ctx.db, 'SELECT uuid FROM chat_members WHERE conversation_id = ? AND uuid <> ? ORDER BY joined_at, uuid', conv.id, me,
  ).map((r) => r.uuid)
  if (others.length === 0) {
    deleteConversationRows(ctx, [conv.id])
    publish(ctx, me, { type: 'chat_conversation_removed', conversationId: conv.id, reason: 'left' })
    return
  }
  const sys = tx(ctx.db, () => {
    run(ctx.db, 'DELETE FROM chat_members WHERE conversation_id = ? AND uuid = ?', conv.id, me)
    const out = [insertSystem(ctx, conv.id, me, { e: 'member_left' })]
    if (member.role === 'owner') {
      const next = others[0]!
      run(ctx.db, "UPDATE chat_members SET role = 'owner' WHERE conversation_id = ? AND uuid = ?", conv.id, next)
      run(ctx.db, 'UPDATE chat_conversations SET owner_uuid = ? WHERE id = ?', next, conv.id)
      out.push(insertSystem(ctx, conv.id, null, { e: 'owner_changed', tg: next }))
    }
    return out
  })
  clearTyping(ctx, conv.id, me)
  publish(ctx, me, { type: 'chat_conversation_removed', conversationId: conv.id, reason: 'left' })
  for (const m of sys) publishMessage(ctx, 'chat_message', m)
  publishConversation(ctx, conv.id)
}

export function transferOwner(ctx: AppContext, me: string, conversationId: string, target: string): ConversationView {
  const conv = ownedGroup(ctx, me, conversationId)
  if (target === me) return conversationView(ctx, conv, me)
  if (!getMember(ctx, conv.id, target)) throw notFound('member_not_found', 'This player is not in the group')
  const sys = tx(ctx.db, () => {
    run(ctx.db, "UPDATE chat_members SET role = 'member' WHERE conversation_id = ? AND uuid = ?", conv.id, me)
    run(ctx.db, "UPDATE chat_members SET role = 'owner' WHERE conversation_id = ? AND uuid = ?", conv.id, target)
    run(ctx.db, 'UPDATE chat_conversations SET owner_uuid = ? WHERE id = ?', target, conv.id)
    return insertSystem(ctx, conv.id, me, { e: 'owner_changed', tg: target })
  })
  publishMessage(ctx, 'chat_message', sys)
  publishConversation(ctx, conv.id)
  return conversationView(ctx, getConversation(ctx, conv.id)!, me)
}

export function deleteGroup(ctx: AppContext, me: string, conversationId: string): void {
  const conv = ownedGroup(ctx, me, conversationId)
  const members = memberUuids(ctx, conv.id)
  deleteConversationRows(ctx, [conv.id])
  for (const u of members) publish(ctx, u, { type: 'chat_conversation_removed', conversationId: conv.id, reason: 'deleted' })
}

/** Löscht Unterhaltungen samt Nachrichten, Reaktionen und Bild-Dateien. */
export function deleteConversationRows(ctx: AppContext, ids: string[]): void {
  if (ids.length === 0) return
  const atts = all<{ id: string }>(
    ctx.db,
    `SELECT a.id FROM chat_attachments a JOIN chat_messages m ON m.id = a.message_id WHERE m.conversation_id IN (${placeholders(ids.length)})`,
    ...ids,
  ).map((r) => r.id)
  run(ctx.db, `DELETE FROM chat_conversations WHERE id IN (${placeholders(ids.length)})`, ...ids)
  removeAttachmentFiles(ctx, atts)
  for (const key of [...ctx.typing.keys()]) if (ids.includes(key.split(':')[0]!)) ctx.typing.delete(key)
}

// ---------------------------------------------------------------- Nachrichten

export interface SendInput {
  text?: string
  replyTo?: string
  attachments?: string[]
  invite?: { address: string, name?: string }
  /** Weltkarte: nur der Host des Raums (§21). Empfänger, die mit ihm befreundet sind, werden eingeladen. */
  world?: { roomId: string }
  nonce?: string
}

/** Gruppen: Links/Einladungen nur vom Besitzer oder von jemandem, der mit allen anderen befreundet ist. */
function assertLinksAllowed(ctx: AppContext, conv: ConversationRow, me: string, text: string, invite: boolean): void {
  if (conv.kind !== 'group') return
  if (!invite && countLinks(text) === 0) return
  if (conv.owner_uuid === me) return
  const strangers = memberUuids(ctx, conv.id).filter((u) => u !== me && !areFriends(ctx, me, u))
  if (strangers.length > 0) {
    throw new ApiError(422, 'links_not_allowed', 'In groups, only the owner or players who are friends with every member can send links and server invites')
  }
}

function checkContent(ctx: AppContext, conv: ConversationRow, me: string, rawText: string | undefined, invite: boolean): string {
  const text = sanitizeText(rawText ?? '')
  assertText(text, ctx.config.limits.maxMessageLength)
  const filtered = applyWordFilter(ctx, text)
  assertLinksAllowed(ctx, conv, me, filtered, invite)
  return filtered
}

function spamCheck(ctx: AppContext, me: string, text: string): void {
  if (text && ctx.spam.isSpam(me, text)) {
    applySpamStrike(ctx, me)
    throw new ApiError(422, 'spam_detected', 'This looks like spam – slow down')
  }
}

/** Nachricht senden. Rückgabe `created: false`, wenn die nonce schon benutzt wurde (gleiche Nachricht). */
export function sendMessage(ctx: AppContext, me: string, conversationId: string, input: SendInput): { message: MessageView, created: boolean } {
  const lim = ctx.config.limits
  const { conv, member } = access(ctx, me, conversationId)
  if (input.nonce) {
    const dup = one<MessageRow>(ctx.db, 'SELECT * FROM chat_messages WHERE sender_uuid = ? AND nonce = ?', me, input.nonce)
    if (dup) {
      if (dup.conversation_id !== conv.id) throw conflict('nonce_reused', 'This nonce was already used in another conversation')
      return { message: messageViews(ctx, [dup], new Set(), me)[0]!, created: false }
    }
  }
  assertCanWrite(ctx, conv, me)
  if (input.invite && input.world) throw badRequest('invite_conflict', 'A message can carry a server invite or a world, not both')
  const world = input.world ? worldCardBody(ctx, me, input.world.roomId) : undefined
  const text = checkContent(ctx, conv, me, input.text, !!input.invite || !!world)
  const attIds = [...new Set(input.attachments ?? [])]
  if (attIds.length > lim.maxAttachmentsPerMessage) throw badRequest('too_many_attachments', `At most ${lim.maxAttachmentsPerMessage} images per message`)
  if (!text && attIds.length === 0 && !input.invite && !world) throw badRequest('empty_message', 'A message needs text, an image or an invite')
  for (const id of attIds) {
    const a = getAttachment(ctx, id)
    if (!a || a.uploader_uuid !== me || a.message_id !== null) throw notFound('attachment_not_found', 'Unknown or already used image')
  }
  let replyTo: string | null = null
  if (input.replyTo) {
    const r = getMessage(ctx, input.replyTo)
    if (!r || r.conversation_id !== conv.id || r.seq < member.visible_from_seq || r.deleted_at !== null) {
      throw notFound('message_not_found', 'The message you reply to does not exist')
    }
    replyTo = r.id
  }
  spamCheck(ctx, me, text)
  const inviteName = input.invite?.name ? applyWordFilter(ctx, sanitizeText(input.invite.name).replace(/\n/g, ' ')) : undefined
  const body: Body = {
    ...(text ? { t: text } : {}),
    ...(input.invite ? { i: { a: input.invite.address.toLowerCase(), ...(inviteName ? { n: inviteName } : {}) } } : {}),
    ...(world ? { w: world } : {}),
  }
  const t = ctx.now()
  const id = newMessageId()
  const row = tx(ctx.db, () => {
    const c = getConversation(ctx, conv.id)!
    const seq = c.last_seq + 1
    run(
      ctx.db,
      `INSERT INTO chat_messages (id, conversation_id, seq, sender_uuid, kind, body, reply_to, created_at, nonce)
       VALUES (?, ?, ?, ?, 'text', ?, ?, ?, ?)`,
      id, conv.id, seq, me, encBody(ctx, id, body), replyTo, t, input.nonce ?? null,
    )
    attIds.forEach((a, i) => run(ctx.db, 'UPDATE chat_attachments SET message_id = ?, position = ? WHERE id = ?', id, i, a))
    run(ctx.db, 'UPDATE chat_conversations SET last_seq = ?, updated_at = ? WHERE id = ?', seq, t, conv.id)
    run(
      ctx.db,
      'UPDATE chat_members SET read_seq = ?, receipt_seq = MAX(receipt_seq, ?), receipt_at = ?, marked_unread = 0 WHERE conversation_id = ? AND uuid = ?',
      seq, seq, t, conv.id, me,
    )
    return getMessage(ctx, id)!
  })
  stopTyping(ctx, conv.id, me)
  publishMessage(ctx, 'chat_message', row)
  // Eigene andere Geräte: Zähler aktualisieren.
  publishState(ctx, me, conv.id)
  if (world) inviteFromCard(ctx, getUser(ctx, me)!, world.r, memberUuids(ctx, conv.id))
  return { message: messageViews(ctx, [row], new Set(), me)[0]!, created: true }
}

export function editMessage(ctx: AppContext, me: string, messageId: string, rawText: string): MessageView {
  const { msg, conv } = accessMessage(ctx, me, messageId)
  if (msg.sender_uuid !== me || msg.kind !== 'text') throw forbidden('not_sender', 'You can only edit your own messages')
  if (msg.deleted_at !== null) throw conflict('message_deleted', 'This message was deleted')
  assertCanWrite(ctx, conv, me)
  const old = decBody(ctx, msg) ?? {}
  const text = checkContent(ctx, conv, me, rawText, false)
  const atts = attachmentsOf(ctx, [msg.id]).get(msg.id)?.length ?? 0
  if (!text && atts === 0 && !old.i && !old.w) throw badRequest('empty_message', 'A message needs text, an image or an invite')
  if (text === (old.t ?? '')) return messageViews(ctx, [msg], blockedBy(ctx, me), me)[0]!
  spamCheck(ctx, me, text)
  const body: Body = { ...old }
  if (text) body.t = text
  else delete body.t
  run(ctx.db, 'UPDATE chat_messages SET body = ?, edited_at = ? WHERE id = ?', encBody(ctx, msg.id, body), ctx.now(), msg.id)
  const row = getMessage(ctx, msg.id)!
  publishMessage(ctx, 'chat_message_edited', row)
  return messageViews(ctx, [row], blockedBy(ctx, me), me)[0]!
}

/**
 * Für alle löschen (Grabstein). Eigene Nachrichten; in Gruppen darf der Besitzer auch fremde
 * löschen; Admins über die Moderation (`by = 'admin'`).
 */
export function deleteMessage(ctx: AppContext, me: string | null, messageId: string, by: 'sender' | 'owner' | 'admin' = 'sender'): MessageView {
  let msg: MessageRow
  let deletedBy = by
  if (by === 'admin') {
    const m = getMessage(ctx, messageId)
    if (!m) throw notFoundMsg()
    msg = m
  } else {
    const a = accessMessage(ctx, me!, messageId)
    msg = a.msg
    if (msg.kind !== 'text') throw forbidden('not_sender', 'System messages cannot be deleted')
    if (msg.sender_uuid === me) deletedBy = 'sender'
    else if (a.conv.kind === 'group' && a.member.role === 'owner') deletedBy = 'owner'
    else throw forbidden('not_sender', 'You can only delete your own messages')
  }
  if (msg.deleted_at !== null) return messageViews(ctx, [msg], new Set(), me)[0]!
  const atts = (attachmentsOf(ctx, [msg.id]).get(msg.id) ?? []).map((a) => a.id)
  tx(ctx.db, () => {
    run(ctx.db, 'UPDATE chat_messages SET body = NULL, deleted_at = ?, deleted_by = ? WHERE id = ?', ctx.now(), deletedBy, msg.id)
    run(ctx.db, 'DELETE FROM chat_reactions WHERE message_id = ?', msg.id)
    run(ctx.db, 'DELETE FROM chat_attachments WHERE message_id = ?', msg.id)
  })
  removeAttachmentFiles(ctx, atts)
  const row = getMessage(ctx, msg.id)!
  publishMessage(ctx, 'chat_message_deleted', row)
  return messageViews(ctx, [row], new Set(), me)[0]!
}

export function setReaction(ctx: AppContext, me: string, messageId: string, emoji: ReactionId, on: boolean): ReactionView[] {
  const { msg, conv } = accessMessage(ctx, me, messageId)
  if (msg.kind !== 'text' || msg.deleted_at !== null) throw conflict('message_deleted', 'You cannot react to this message')
  assertCanWrite(ctx, conv, me)
  const changed = on
    ? run(ctx.db, 'INSERT INTO chat_reactions (message_id, uuid, emoji, created_at) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING', msg.id, me, emoji, ctx.now())
    : run(ctx.db, 'DELETE FROM chat_reactions WHERE message_id = ? AND uuid = ? AND emoji = ?', msg.id, me, emoji)
  const reactions = reactionsOf(ctx, [msg.id]).get(msg.id) ?? []
  if (changed > 0) {
    for (const u of memberUuids(ctx, conv.id)) {
      publish(ctx, u, { type: 'chat_reactions', conversationId: conv.id, messageId: msg.id, reactions })
    }
  }
  return reactions
}

// ---------------------------------------------------------------- Lesen, ungelesen, stumm, tippen

export function markRead(ctx: AppContext, me: string, conversationId: string, seq: number): ConversationView {
  const { conv, member } = access(ctx, me, conversationId)
  const s = Math.min(seq, conv.last_seq)
  const t = ctx.now()
  const receipt = s > member.receipt_seq
  run(
    ctx.db,
    `UPDATE chat_members SET read_seq = MAX(read_seq, ?), marked_unread = 0,
       receipt_seq = MAX(receipt_seq, ?), receipt_at = CASE WHEN ? > receipt_seq THEN ? ELSE receipt_at END
     WHERE conversation_id = ? AND uuid = ?`,
    s, s, s, t, conv.id, me,
  )
  if (receipt) {
    const at = iso(t)
    const meRow = getUser(ctx, me)
    // Eigene Geräte immer; andere nur, wenn beide Lesebestätigungen teilen.
    publish(ctx, me, { type: 'chat_read', conversationId: conv.id, uuid: me, seq: s, at })
    if (meRow?.chat_read_receipts === 1) {
      for (const r of all<{ uuid: string }>(
        ctx.db,
        `SELECT cm.uuid FROM chat_members cm JOIN users u ON u.uuid = cm.uuid
         WHERE cm.conversation_id = ? AND cm.uuid <> ? AND u.chat_read_receipts = 1`,
        conv.id, me,
      )) publish(ctx, r.uuid, { type: 'chat_read', conversationId: conv.id, uuid: me, seq: s, at })
    }
  }
  publishState(ctx, me, conv.id)
  return conversationView(ctx, getConversation(ctx, conv.id)!, me)
}

/** Als ungelesen markieren; mit `seq` ab dieser Nachricht (eigener Lesestand geht zurück, Lesebestätigungen nicht). */
export function markUnread(ctx: AppContext, me: string, conversationId: string, seq?: number): ConversationView {
  const { conv, member } = access(ctx, me, conversationId)
  if (seq !== undefined) {
    const back = Math.max(member.visible_from_seq - 1, Math.min(member.read_seq, seq - 1))
    run(ctx.db, 'UPDATE chat_members SET read_seq = ?, marked_unread = 1 WHERE conversation_id = ? AND uuid = ?', back, conv.id, me)
  } else {
    run(ctx.db, 'UPDATE chat_members SET marked_unread = 1 WHERE conversation_id = ? AND uuid = ?', conv.id, me)
  }
  publishState(ctx, me, conv.id)
  return conversationView(ctx, conv, me)
}

/** Stummschalten: `until` = Ende (ms), `MUTED_FOREVER` = unbefristet, `null` = aus. */
export function setConversationMute(ctx: AppContext, me: string, conversationId: string, until: number | null): ConversationView {
  const { conv } = access(ctx, me, conversationId)
  run(ctx.db, 'UPDATE chat_members SET muted_until = ? WHERE conversation_id = ? AND uuid = ?', until, conv.id, me)
  publishState(ctx, me, conv.id)
  return conversationView(ctx, conv, me)
}

function typingKey(conversationId: string, uuid: string): string {
  return `${conversationId}:${uuid}`
}

function clearTyping(ctx: AppContext, conversationId: string, uuid: string): void {
  ctx.typing.delete(typingKey(conversationId, uuid))
}

function emitTyping(ctx: AppContext, conversationId: string, me: string, typing: boolean): void {
  const rows = all<{ uuid: string }>(
    ctx.db,
    `SELECT cm.uuid FROM chat_members cm JOIN users u ON u.uuid = cm.uuid
     WHERE cm.conversation_id = ? AND cm.uuid <> ? AND u.chat_typing = 1
       AND cm.uuid NOT IN (SELECT blocker FROM blocks WHERE blocked = ?)`,
    conversationId, me, me,
  )
  for (const r of rows) {
    publish(ctx, r.uuid, { type: 'chat_typing', conversationId, uuid: me, typing, expiresInMs: TYPING_TTL_MS }, true)
  }
}

function stopTyping(ctx: AppContext, conversationId: string, me: string): void {
  const key = typingKey(conversationId, me)
  const until = ctx.typing.get(key)
  if (until === undefined) return
  ctx.typing.delete(key)
  if (until > ctx.now()) emitTyping(ctx, conversationId, me, false)
}

/**
 * „Tippt gerade“: Client sendet `typing: true` höchstens alle 3 s, solange getippt wird, und
 * `false` beim Leeren des Felds. Beim Senden endet es automatisch. Wer es abgeschaltet hat,
 * sendet und sieht nichts (gegenseitig).
 */
export function setTyping(ctx: AppContext, me: string, conversationId: string, typing: boolean): void {
  const { conv } = access(ctx, me, conversationId)
  if (getUser(ctx, me)?.chat_typing !== 1) return
  if (!typing) return stopTyping(ctx, conv.id, me)
  if (writeBlock(ctx, conv, me)) return
  const key = typingKey(conv.id, me)
  const t = ctx.now()
  const prev = ctx.typing.get(key)
  ctx.typing.set(key, t + TYPING_TTL_MS)
  // Wiederholte Meldungen nur weitergeben, wenn der Empfänger sie sonst bald ablaufen ließe.
  if (prev !== undefined && prev - t > TYPING_TTL_MS - 2500) return
  emitTyping(ctx, conv.id, me, true)
}

export function sweepTyping(ctx: AppContext): void {
  const t = ctx.now()
  for (const [k, until] of ctx.typing) if (until <= t) ctx.typing.delete(k)
}

// ---------------------------------------------------------------- Freundschaft/Blockade → Sichten

/** Nach Freundschafts-Änderung: DM (falls vorhanden) für beide neu schicken (Schreibrecht). */
export function refreshDm(ctx: AppContext, a: string, b: string): void {
  const conv = one<{ id: string }>(ctx.db, 'SELECT id FROM chat_conversations WHERE dm_key = ?', pairKey(a, b))
  if (conv) publishConversation(ctx, conv.id)
}

// ---------------------------------------------------------------- Kontolöschung

export interface ChatPurgePlan {
  uuid: string
  dmPeers: { conversationId: string, peer: string }[]
  groups: string[]
  files: string[]
}

/**
 * Vor dem Löschen des Kontos: DMs ganz löschen (beide Seiten), leere Gruppen löschen, bei eigenen
 * Gruppen den Besitz weitergeben. Nachrichten/Reaktionen/Bilder des Kontos verschwinden danach per
 * FK mit der Nutzerzeile; die Dateien räumt {@link finishChatPurge} weg.
 */
export function prepareChatPurge(ctx: AppContext, uuid: string): ChatPurgePlan {
  const files = all<{ id: string }>(ctx.db, 'SELECT id FROM chat_attachments WHERE uploader_uuid = ?', uuid).map((r) => r.id)
  const dms = all<ConversationRow>(
    ctx.db,
    "SELECT c.* FROM chat_members cm JOIN chat_conversations c ON c.id = cm.conversation_id WHERE cm.uuid = ? AND c.kind = 'dm'",
    uuid,
  )
  const dmPeers = dms.map((c) => ({ conversationId: c.id, peer: dmPeer(c, uuid)! }))
  const groups = all<ConversationRow & { role: string }>(
    ctx.db,
    "SELECT c.*, cm.role FROM chat_members cm JOIN chat_conversations c ON c.id = cm.conversation_id WHERE cm.uuid = ? AND c.kind = 'group'",
    uuid,
  )
  const drop: string[] = dms.map((c) => c.id)
  const keep: string[] = []
  tx(ctx.db, () => {
    for (const g of groups) {
      const others = all<{ uuid: string }>(
        ctx.db, 'SELECT uuid FROM chat_members WHERE conversation_id = ? AND uuid <> ? ORDER BY joined_at, uuid', g.id, uuid,
      ).map((r) => r.uuid)
      if (others.length === 0) {
        drop.push(g.id)
        continue
      }
      keep.push(g.id)
      if (g.role === 'owner') {
        run(ctx.db, "UPDATE chat_members SET role = 'owner' WHERE conversation_id = ? AND uuid = ?", g.id, others[0]!)
        run(ctx.db, 'UPDATE chat_conversations SET owner_uuid = ? WHERE id = ?', others[0]!, g.id)
      }
    }
    deleteConversationRows(ctx, drop)
  })
  for (const key of [...ctx.typing.keys()]) if (key.endsWith(`:${uuid}`)) ctx.typing.delete(key)
  ctx.spam.forget(uuid)
  return { uuid, dmPeers, groups: keep, files }
}

export function finishChatPurge(ctx: AppContext, plan: ChatPurgePlan): void {
  removeAttachmentFiles(ctx, plan.files)
  for (const d of plan.dmPeers) publish(ctx, d.peer, { type: 'chat_conversation_removed', conversationId: d.conversationId, reason: 'deleted' })
  for (const g of plan.groups) {
    for (const u of memberUuids(ctx, g)) publish(ctx, u, { type: 'chat_reload', conversationId: g })
    publishConversation(ctx, g)
  }
}

// ---------------------------------------------------------------- Schlüsseltausch

/** Verschlüsselt bis zu `max` Nachrichten/Gruppennamen mit altem Schlüssel neu. Rückgabe: Anzahl. */
export function rotateMessageKeys(ctx: AppContext, max: number): number {
  const prefix = Buffer.concat([Buffer.from([1, ctx.cipher.activeId.length]), Buffer.from(ctx.cipher.activeId, 'ascii')])
  let n = 0
  for (const r of all<{ id: string, body: Uint8Array }>(
    ctx.db,
    'SELECT id, body FROM chat_messages WHERE body IS NOT NULL AND substr(body, 1, ?) <> ? LIMIT ?',
    prefix.length, prefix, max,
  )) {
    try {
      run(ctx.db, 'UPDATE chat_messages SET body = ? WHERE id = ?', ctx.cipher.encrypt(ctx.cipher.decrypt(r.body, `msg:${r.id}`), `msg:${r.id}`), r.id)
      n++
    } catch (err) {
      console.error(`[trs-api] could not re-encrypt message ${r.id} (key ${ChatCipher.keyIdOf(r.body)})`, (err as Error).message)
    }
  }
  for (const r of all<{ id: string, name: Uint8Array }>(
    ctx.db,
    'SELECT id, name FROM chat_conversations WHERE name IS NOT NULL AND substr(name, 1, ?) <> ? LIMIT ?',
    prefix.length, prefix, max,
  )) {
    try {
      run(ctx.db, 'UPDATE chat_conversations SET name = ? WHERE id = ?', ctx.cipher.encrypt(ctx.cipher.decrypt(r.name, `grp:${r.id}`), `grp:${r.id}`), r.id)
      n++
    } catch (err) {
      console.error(`[trs-api] could not re-encrypt group name ${r.id}`, (err as Error).message)
    }
  }
  return n
}

/** Für Meldungen: Klartext einer Nachricht (Text + Einladung), ohne Sichtbarkeitsregeln. */
export function decryptForEvidence(ctx: AppContext, row: MessageRow): { text: string | null, invite: InviteView | null, world: { roomId: string, name: string } | null, system: Body['s'] | null } {
  const b = decBody(ctx, row)
  return {
    text: b?.t ?? null,
    invite: b?.i ? { address: b.i.a, name: b.i.n ?? null } : null,
    world: b?.w ? { roomId: b.w.r, name: b.w.n } : null,
    system: b?.s ?? null,
  }
}

export function groupNameOf(ctx: AppContext, conv: ConversationRow): string | null {
  return groupName(ctx, conv)
}

/** Blockiert `a` `b` oder umgekehrt? */
export function eitherBlocked(ctx: AppContext, a: string, b: string): boolean {
  return hasBlocked(ctx, a, b) || hasBlocked(ctx, b, a)
}
