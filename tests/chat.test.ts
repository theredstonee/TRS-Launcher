import { beforeEach, describe, expect, it } from 'vitest'
import { setLocale } from '../app/utils/i18n'
import {
  buildTimeline,
  conversationPreview,
  conversationTitle,
  dayLabel,
  isMuted,
  liveEventSchema,
  listTime,
  mergeMessages,
  messageSummary,
  onlyEmoji,
  readState,
  sortConversations,
  splitLinks,
  systemText,
  toggleReaction,
  typingNames,
  typingText,
  unreadCount,
  upsertMessage,
  attachmentUrl,
  setChatBase,
  newNonce,
  type ChatConversation,
  type LocalMessage,
} from '../app/utils/chat'

const ME = '75c1a6f3112240abbdb57b9d21c64232'
const BOB = 'b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0'
const CARL = 'c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0c0'
const CONV = 'c1f0e2d3c4b5a6978899a'

function msg(seq: number, sender: string, at: string, extra: Partial<LocalMessage> = {}): LocalMessage {
  return {
    id: `m${seq.toString(16).padStart(20, '0')}`,
    conversationId: CONV,
    seq,
    kind: 'text',
    sender: { uuid: sender, name: sender === ME ? 'Theredstonee' : sender === BOB ? 'Bob' : 'Carl' },
    text: `Nachricht ${seq}`,
    invite: null,
    attachments: [],
    replyTo: null,
    system: null,
    reactions: [],
    createdAt: at,
    editedAt: null,
    deleted: false,
    deletedBy: null,
    hidden: false,
    nonce: null,
    ...extra,
  }
}

function conv(extra: Partial<ChatConversation> = {}): ChatConversation {
  return {
    id: CONV,
    kind: 'dm',
    name: null,
    owner: null,
    members: [
      { uuid: ME, name: 'Theredstonee', role: 'member', joinedAt: null },
      { uuid: BOB, name: 'Bob', role: 'member', joinedAt: null },
    ],
    peer: { uuid: BOB, name: 'Bob' },
    canWrite: true,
    readOnlyReason: null,
    lastMessage: null,
    lastSeq: 0,
    unread: 0,
    markedUnread: false,
    readSeq: 0,
    muted: false,
    mutedUntil: null,
    reads: [],
    createdAt: null,
    updatedAt: null,
    ...extra,
  }
}

beforeEach(async () => {
  await setLocale('de')
})

describe('Nachrichtenlisten', () => {
  it('sortiert nach seq, ersetzt gleiche IDs und hängt Unbestätigte ans Ende', () => {
    const local = msg(0, ME, '2026-09-26T10:05:00Z', {
      id: 'local-abc',
      nonce: 'nonce-1234',
      seq: Number.MAX_SAFE_INTEGER,
      local: { state: 'sending', draft: { text: 'hi', nonce: 'nonce-1234' }, previews: [], sources: [] },
    })
    let list = mergeMessages([], [msg(3, BOB, '2026-09-26T10:03:00Z'), msg(1, BOB, '2026-09-26T10:01:00Z')])
    list = upsertMessage(list, local)
    list = upsertMessage(list, msg(2, BOB, '2026-09-26T10:02:00Z'))
    expect(list.map((m) => m.id)).toEqual([msg(1, BOB, '').id, msg(2, BOB, '').id, msg(3, BOB, '').id, 'local-abc'])
    const edited = { ...msg(2, BOB, '2026-09-26T10:02:00Z'), text: 'geändert', editedAt: '2026-09-26T10:04:00Z' }
    list = upsertMessage(list, edited)
    expect(list.filter((m) => m.seq === 2)).toHaveLength(1)
    expect(list.find((m) => m.seq === 2)?.text).toBe('geändert')
  })

  it('die bestätigte Nachricht ersetzt die eigene mit gleicher Nonce (auch vom Echtzeit-Kanal)', () => {
    const local = msg(0, ME, '2026-09-26T10:05:00Z', {
      id: 'local-x',
      nonce: 'nonce-5678',
      local: { state: 'sending', draft: { nonce: 'nonce-5678' }, previews: [], sources: [] },
    })
    const confirmed = msg(4, ME, '2026-09-26T10:05:01Z', { nonce: 'nonce-5678' })
    const list = upsertMessage([msg(3, BOB, '2026-09-26T10:03:00Z'), local], confirmed)
    expect(list.map((m) => m.id)).toEqual([msg(3, BOB, '').id, confirmed.id])
    // Eine fremde Nachricht mit zufällig gleicher Nonce ersetzt nichts Bestätigtes.
    const other = msg(5, BOB, '2026-09-26T10:06:00Z', { nonce: 'nonce-5678' })
    expect(upsertMessage(list, other)).toHaveLength(3)
  })

  it('Nonces passen zum API-Format', () => {
    const n = newNonce()
    expect(n).toMatch(/^[A-Za-z0-9_-]{8,64}$/)
    expect(newNonce()).not.toBe(n)
  })
})

describe('Zeitleiste', () => {
  it('setzt Tagestrenner und fasst Nachrichten desselben Absenders zusammen', () => {
    const now = new Date('2026-09-26T18:00:00')
    const messages = [
      msg(1, BOB, new Date('2026-09-25T20:00:00').toISOString()),
      msg(2, BOB, new Date('2026-09-26T09:00:00').toISOString()),
      msg(3, BOB, new Date('2026-09-26T09:02:00').toISOString()),
      msg(4, ME, new Date('2026-09-26T09:03:00').toISOString()),
      msg(5, ME, new Date('2026-09-26T09:30:00').toISOString()),
      msg(6, ME, new Date('2026-09-26T09:31:00').toISOString(), { kind: 'system', system: { event: 'renamed', actor: { uuid: ME, name: 'T' }, target: null, name: 'Crew' } }),
    ]
    const items = buildTimeline(messages, ME, now)
    expect(items.filter((i) => i.type === 'day').map((i) => (i as { label: string }).label)).toEqual(['Gestern', 'Heute'])
    const flags = items.filter((i) => i.type === 'message').map((i) => (i.type === 'message' ? [i.message.seq, i.first, i.last, i.mine] : null))
    expect(flags).toEqual([
      [1, true, true, false],
      [2, true, false, false],
      [3, false, true, false],
      [4, true, true, true],
      [5, true, true, true], // mehr als 5 Minuten später → neue Gruppe
      [6, true, true, false], // Systemnachricht steht allein und ist nie „meine“
    ])
  })

  it('beschriftet ältere Tage mit Wochentag bzw. Datum', () => {
    const now = new Date('2026-09-26T12:00:00')
    expect(dayLabel(new Date('2026-09-26T01:00:00'), now)).toBe('Heute')
    expect(dayLabel(new Date('2026-09-23T12:00:00'), now)).toBe('Mittwoch')
    expect(dayLabel(new Date('2026-08-01T12:00:00'), now)).toBe('1. August')
    expect(dayLabel(new Date('2025-11-29T12:00:00'), now)).toContain('2025')
    expect(listTime(null)).toBe('')
    expect(listTime(new Date('2026-09-25T12:00:00').toISOString(), now)).toBe('Gestern')
  })
})

describe('Texte', () => {
  it('Vorschau und Titel der Unterhaltung', () => {
    expect(conversationPreview(conv(), ME)).toBe('Klicke, um zu schreiben')
    expect(conversationPreview(conv({ lastMessage: msg(1, ME, '2026-09-26T10:00:00Z', { text: 'Hi\nzweite Zeile' }) }), ME)).toBe('Du: Hi')
    expect(conversationPreview(conv({ lastMessage: msg(1, BOB, '2026-09-26T10:00:00Z', { text: null, attachments: [
      { id: 'a0123456789abcdef01234567', mime: 'image/png', width: 1, height: 1, bytes: 1, thumbWidth: 1, thumbHeight: 1 },
      { id: 'a0123456789abcdef01234568', mime: 'image/png', width: 1, height: 1, bytes: 1, thumbWidth: 1, thumbHeight: 1 },
    ] }) }), ME)).toBe('2 Bilder')
    expect(messageSummary(msg(1, BOB, '', { deleted: true, text: null }))).toBe('Nachricht gelöscht')
    expect(messageSummary(msg(1, BOB, '', { text: null, invite: { address: 'play.example.net', name: null } }))).toBe('Server-Einladung: play.example.net')
    expect(conversationTitle(conv(), ME)).toBe('Bob')
    const group = conv({ kind: 'group', name: null, peer: null, members: [...conv().members, { uuid: CARL, name: 'Carl', role: 'member', joinedAt: null }] })
    expect(conversationTitle(group, ME)).toBe('Bob, Carl')
    expect(conversationTitle({ ...group, name: 'Bau-Crew' }, ME)).toBe('Bau-Crew')
  })

  it('Systemnachrichten und „schreibt …“', () => {
    expect(systemText({ event: 'member_added', actor: { uuid: ME, name: 'Theredstonee' }, target: { uuid: CARL, name: 'Carl' }, name: null })).toBe(
      'Theredstonee hat Carl hinzugefügt',
    )
    expect(typingText(['Bob'])).toBe('Bob schreibt')
    expect(typingText(['Bob', 'Carl'])).toBe('Bob und Carl schreiben')
    expect(typingText(['A', 'B', 'C'])).toBe('3 Leute schreiben')
    const now = 1_000_000
    const names = typingNames({ [BOB]: now + 5000, [CARL]: now - 1, [ME]: now + 5000 }, conv(), ME, now)
    expect(names).toEqual(['Bob'])
  })

  it('Links: nur https, ohne Satzzeichen am Ende, alles andere bleibt Text', () => {
    const parts = splitLinks('Schau: https://example.com/a?b=1. Und javascript:alert(1) oder http://x.de und https://user:pw@evil.com')
    expect(parts.filter((p) => p.type === 'link').map((p) => p.type === 'link' && p.href)).toEqual(['https://example.com/a?b=1'])
    expect(parts.map((p) => p.value).join('')).toBe('Schau: https://example.com/a?b=1. Und javascript:alert(1) oder http://x.de und https://user:pw@evil.com')
    expect(splitLinks('ohne Link')).toEqual([{ type: 'text', value: 'ohne Link' }])
    expect(splitLinks('<img src=x onerror=alert(1)>')).toEqual([{ type: 'text', value: '<img src=x onerror=alert(1)>' }])
  })

  it('erkennt reine Emoji-Nachrichten', () => {
    expect(onlyEmoji('🔥')).toBe(true)
    expect(onlyEmoji('👍🏽 🎉')).toBe(true)
    expect(onlyEmoji('🔥🔥🔥🔥')).toBe(false)
    expect(onlyEmoji('ok 🔥')).toBe(false)
    expect(onlyEmoji(null)).toBe(false)
  })
})

describe('Reaktionen, Lesestatus, Zähler', () => {
  it('schaltet Reaktionen um und hält die feste Reihenfolge', () => {
    let r = toggleReaction([], 'fire', ME, true)
    r = toggleReaction(r, 'thumbs_up', BOB, true)
    r = toggleReaction(r, 'fire', BOB, true)
    r = toggleReaction(r, 'fire', BOB, true)
    expect(r).toEqual([
      { emoji: 'thumbs_up', count: 1, users: [BOB] },
      { emoji: 'fire', count: 2, users: [ME, BOB] },
    ])
    r = toggleReaction(r, 'thumbs_up', BOB, false)
    expect(r.map((x) => x.emoji)).toEqual(['fire'])
  })

  it('Lesestatus: DM gelesen/gesendet, Gruppe „gelesen von n“', () => {
    const m = msg(5, ME, '')
    expect(readState(conv({ reads: [{ uuid: BOB, seq: 5, at: null }] }), m, ME)).toEqual({ read: true, count: 1 })
    expect(readState(conv({ reads: [{ uuid: BOB, seq: 4, at: null }] }), m, ME)).toEqual({ read: false, count: 0 })
    const group = conv({ kind: 'group', reads: [{ uuid: BOB, seq: 9, at: null }, { uuid: CARL, seq: 6, at: null }] })
    expect(readState(group, m, ME)).toEqual({ read: true, count: 2 })
    expect(readState(group, { ...m, local: { state: 'sending', draft: {}, previews: [], sources: [] } }, ME)).toBeNull()
  })

  it('Zähler ohne Stummgeschaltete, abgelaufene Stummschaltung zählt nicht', () => {
    expect(unreadCount({ unread: 3, markedUnread: false, muted: false })).toBe(3)
    expect(unreadCount({ unread: 0, markedUnread: true, muted: false })).toBe(1)
    expect(unreadCount({ unread: 3, markedUnread: false, muted: true })).toBe(0)
    expect(isMuted({ muted: true, mutedUntil: null })).toBe(true)
    expect(isMuted({ muted: true, mutedUntil: '2000-01-01T00:00:00Z' })).toBe(false)
    const sorted = sortConversations([
      conv({ id: 'c00000000000000000001', updatedAt: '2026-09-20T00:00:00Z' }),
      conv({ id: 'c00000000000000000002', lastMessage: msg(1, BOB, '2026-09-25T00:00:00Z') }),
    ])
    expect(sorted.map((c) => c.id)).toEqual(['c00000000000000000002', 'c00000000000000000001'])
  })
})

describe('Schemas und Bild-URLs', () => {
  it('prüft Ereignisse aus dem Kern', () => {
    const ok = liveEventSchema.safeParse({ type: 'chat_typing', conversationId: CONV, uuid: BOB, typing: true, expiresInMs: 8000 })
    expect(ok.success).toBe(true)
    expect(liveEventSchema.safeParse({ type: 'chat_typing', conversationId: '../x', uuid: BOB, typing: true, expiresInMs: 8000 }).success).toBe(false)
    expect(liveEventSchema.safeParse({ type: 'unknown_event' }).success).toBe(false)
  })

  it('Bild-URLs enthalten nur geprüfte IDs', () => {
    setChatBase('http://trschat.localhost')
    expect(attachmentUrl('a0123456789abcdef01234567', true)).toBe('http://trschat.localhost/t/a0123456789abcdef01234567')
    expect(attachmentUrl('../../secret')).toBe('')
  })
})
