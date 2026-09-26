import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import type { ChatConversation, ChatMessage } from '../app/utils/chat'

const ME = '75c1a6f3112240abbdb57b9d21c64232'
const BOB = 'b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0'
const CONV = 'c1f0e2d3c4b5a6978899a'

function message(seq: number, sender = BOB, extra: Partial<ChatMessage> = {}): ChatMessage {
  return {
    id: `m${seq.toString(16).padStart(20, '0')}`,
    conversationId: CONV,
    seq,
    kind: 'text',
    sender: { uuid: sender, name: sender === ME ? 'Theredstonee' : 'Bob' },
    text: `Text ${seq}`,
    invite: null,
    world: null,
    attachments: [],
    replyTo: null,
    system: null,
    reactions: [],
    createdAt: `2026-09-26T10:0${seq % 10}:00.000Z`,
    editedAt: null,
    deleted: false,
    deletedBy: null,
    hidden: false,
    nonce: null,
    ...extra,
  }
}

function conversation(extra: Partial<ChatConversation> = {}): ChatConversation {
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
    lastMessage: message(2),
    lastSeq: 2,
    unread: 0,
    markedUnread: false,
    readSeq: 2,
    muted: false,
    mutedUntil: null,
    reads: [],
    createdAt: null,
    updatedAt: '2026-09-26T10:02:00.000Z',
    ...extra,
  }
}

const social = {
  conversations: vi.fn(async () => ({ conversations: [conversation()], nextCursor: null })),
  conversation: vi.fn(async () => conversation()),
  messages: vi.fn(async () => ({ messages: [message(1), message(2)], hasMore: false })),
  send: vi.fn(async (_id: string, out: { nonce?: string; text?: string }) => message(3, ME, { nonce: out.nonce ?? null, text: out.text ?? null })),
  upload: vi.fn(async () => ({ id: 'a0123456789abcdef01234567', mime: 'image/png', width: 1, height: 1, bytes: 1, thumbWidth: 1, thumbHeight: 1 })),
  read: vi.fn(async () => conversation({ unread: 0 })),
  react: vi.fn(async () => [{ emoji: 'fire', count: 1, users: [ME] }]),
  typing: vi.fn(async () => undefined),
  edit: vi.fn(async (messageId: string, text: string) => ({ ...message(2), id: messageId, text, editedAt: '2026-09-26T11:00:00.000Z' })),
  unread: vi.fn(async () => ({ total: 4, conversations: [{ id: CONV, unread: 4, markedUnread: false, muted: false }] })),
  myModeration: vi.fn(async () => ({ mute: null, warnings: [] })),
}

vi.mock('../app/utils/backend', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../app/utils/backend')>()),
  backend: { social },
}))

const { useChatStore } = await import('../app/stores/chat')
const { BackendError } = await import('../app/utils/backend')

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  vi.useRealTimers()
})

async function ready() {
  const chat = useChatStore()
  chat.setAccount(ME)
  await chat.loadList()
  await chat.open(CONV)
  return chat
}

describe('Chat-Store', () => {
  it('sendet sofort sichtbar und ersetzt die lokale Nachricht durch die bestätigte', async () => {
    const chat = await ready()
    let resolve: (m: ChatMessage) => void = () => {}
    social.send.mockImplementationOnce(
      (_id, out) => new Promise((r) => (resolve = () => r(message(3, ME, { nonce: out.nonce ?? null, text: out.text ?? null })))),
    )
    const sending = chat.send(CONV, { text: 'Hallo  \n' })
    const pending = chat.threads[CONV]!.items.at(-1)!
    expect(pending.local?.state).toBe('sending')
    expect(pending.text).toBe('Hallo')
    resolve(message(3))
    expect(await sending).toBe(true)
    const items = chat.threads[CONV]!.items
    expect(items.map((m) => m.seq)).toEqual([1, 2, 3])
    expect(items.some((m) => m.local)).toBe(false)
    expect(chat.conversations[CONV]!.lastMessage?.seq).toBe(3)
  })

  it('markiert Fehlschläge und sendet mit derselben Nonce erneut', async () => {
    const chat = await ready()
    social.send.mockRejectedValueOnce(new BackendError('trs_api', 'x', 'trsApi.spam_detected', undefined, 'spam_detected'))
    expect(await chat.send(CONV, { text: 'Spam?' })).toBe(false)
    const failed = chat.threads[CONV]!.items.at(-1)!
    expect(failed.local?.state).toBe('failed')
    const nonce = failed.nonce
    await chat.retry(CONV, failed)
    expect(social.send).toHaveBeenLastCalledWith(CONV, expect.objectContaining({ nonce, text: 'Spam?' }))
    expect(chat.threads[CONV]!.items.some((m) => m.local)).toBe(false)
  })

  it('lädt Bilder vor dem Senden hoch und schickt ihre IDs mit', async () => {
    const chat = await ready()
    await chat.send(CONV, { text: '', images: [{ source: { kind: 'local', id: 'l00000000000000000001' }, preview: '' }] })
    expect(social.upload).toHaveBeenCalledWith({ kind: 'local', id: 'l00000000000000000001' })
    expect(social.send).toHaveBeenLastCalledWith(CONV, expect.objectContaining({ attachments: ['a0123456789abcdef01234567'] }))
  })

  it('zählt neue Nachrichten als ungelesen, solange man nicht hinschaut', async () => {
    const chat = await ready()
    chat.setWatching(false)
    await chat.onEvent({ type: 'chat_message', conversationId: CONV, message: message(3) })
    await chat.onEvent({ type: 'chat_message', conversationId: CONV, message: message(4) })
    expect(chat.conversations[CONV]!.unread).toBe(2)
    expect(chat.unreadTotal).toBe(2)
    expect(chat.threads[CONV]!.items.map((m) => m.seq)).toEqual([1, 2, 3, 4])
    // Eigene Nachricht von einem anderen Gerät: nichts ungelesen.
    await chat.onEvent({ type: 'chat_message', conversationId: CONV, message: message(5, ME) })
    expect(chat.conversations[CONV]!.unread).toBe(0)
  })

  it('schaut man hin, wird gleich als gelesen gemeldet', async () => {
    vi.useFakeTimers()
    const chat = await ready()
    chat.setWatching(true)
    await chat.onEvent({ type: 'chat_message', conversationId: CONV, message: message(3) })
    expect(chat.conversations[CONV]!.unread).toBe(0)
    await vi.advanceTimersByTimeAsync(500)
    expect(social.read).toHaveBeenCalledWith(CONV, 3)
  })

  it('Tippen läuft ab, eigenes Gerät liest mit, Zustand und Entfernen', async () => {
    const chat = await ready()
    await chat.onEvent({ type: 'chat_typing', conversationId: CONV, uuid: BOB, typing: true, expiresInMs: 8000 })
    expect(chat.typing[CONV]![BOB]).toBeGreaterThan(Date.now())
    await chat.onEvent({ type: 'chat_message', conversationId: CONV, message: message(3) })
    expect(chat.typing[CONV]![BOB]).toBeUndefined()

    chat.conversations[CONV]!.unread = 5
    await chat.onEvent({ type: 'chat_read', conversationId: CONV, uuid: ME, seq: 3, at: null })
    expect(chat.conversations[CONV]!.unread).toBe(0)
    await chat.onEvent({ type: 'chat_read', conversationId: CONV, uuid: BOB, seq: 3, at: null })
    expect(chat.conversations[CONV]!.reads).toEqual([{ uuid: BOB, seq: 3, at: null }])

    await chat.onEvent({ type: 'chat_state', conversationId: CONV, unread: 0, markedUnread: true, readSeq: 1, muted: true, mutedUntil: null })
    expect(chat.conversations[CONV]).toMatchObject({ markedUnread: true, muted: true, readSeq: 1 })
    expect(chat.unreadTotal).toBe(0)

    await chat.onEvent({ type: 'chat_conversation_removed', conversationId: CONV, reason: 'deleted' })
    expect(chat.conversations[CONV]).toBeUndefined()
    expect(chat.activeId).toBeNull()
  })

  it('Reaktionen erscheinen sofort und werden bei Fehlern zurückgenommen', async () => {
    const chat = await ready()
    const target = chat.threads[CONV]!.items[0]!
    social.react.mockRejectedValueOnce(new Error('offline'))
    await expect(chat.react(CONV, target, 'fire')).rejects.toThrow()
    expect(chat.threads[CONV]!.items[0]!.reactions).toEqual([])
    await chat.react(CONV, chat.threads[CONV]!.items[0]!, 'fire')
    expect(social.react).toHaveBeenLastCalledWith(target.id, 'fire', true)
    expect(chat.threads[CONV]!.items[0]!.reactions[0]!.count).toBe(1)
  })

  it('Bearbeiten und Löschen ersetzen die Nachricht (auch in der Liste)', async () => {
    const chat = await ready()
    await chat.edit(CONV, message(2).id, 'neu')
    expect(chat.threads[CONV]!.items[1]!.text).toBe('neu')
    expect(chat.conversations[CONV]!.lastMessage?.text).toBe('neu')
    await chat.onEvent({ type: 'chat_message_deleted', conversationId: CONV, message: { ...message(2), text: null, deleted: true, deletedBy: 'sender' } })
    expect(chat.threads[CONV]!.items[1]!.deleted).toBe(true)
  })

  it('„Schreibt …“ höchstens alle 3 Sekunden und Rückfall-Abgleich der Zähler', async () => {
    const chat = await ready()
    chat.noteTyping(CONV)
    chat.noteTyping(CONV)
    expect(social.typing).toHaveBeenCalledTimes(1)
    await chat.stopTyping(CONV)
    expect(social.typing).toHaveBeenLastCalledWith(CONV, false)
    await chat.refreshUnread()
    expect(chat.conversations[CONV]!.unread).toBe(4)
  })

  it('Account-Wechsel verwirft alles', async () => {
    const chat = await ready()
    chat.setAccount(BOB)
    expect(chat.list).toEqual([])
    expect(chat.threads).toEqual({})
  })
})
