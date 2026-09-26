import { describe, expect, it } from 'vitest'
import {
  access,
  addMembers,
  conversationView,
  createGroup,
  deleteGroup,
  deleteMessage,
  editMessage,
  leaveGroup,
  listConversations,
  listMessages,
  markRead,
  markUnread,
  MUTED_FOREVER,
  openDm,
  removeMember,
  renameGroup,
  rotateMessageKeys,
  sendMessage,
  setConversationMute,
  setReaction,
  setTyping,
  transferOwner,
  unreadSummary,
} from '../server/lib/chat'
import { ChatCipher } from '../server/lib/crypto'
import { one } from '../server/lib/db'
import { block, removeFriend } from '../server/lib/friends'
import { activeMute } from '../server/lib/moderation'
import { addFilterWord, countLinks, sanitizeText } from '../server/lib/safety'
import { deleteUser, updateSettings } from '../server/lib/users'
import { befriend, code, listen, players } from './chathelpers'
import { makeEnv } from './helpers'

describe('direct messages', () => {
  it('friends can open a DM and chat; strangers get 404/403 without leaks', async () => {
    const env = makeEnv()
    const [a, b, c] = await players(env, 'Alex', 'Bob', 'Carl')
    befriend(env, a!, b!)
    const dm = openDm(env.ctx, a!.uuid, b!.uuid)
    expect(dm).toMatchObject({ kind: 'dm', peer: { uuid: b!.uuid, name: 'Bob' }, canWrite: true, unread: 0 })
    // Gleiche DM von beiden Seiten.
    expect(openDm(env.ctx, b!.uuid, a!.uuid).id).toBe(dm.id)

    const evB = listen(env, b!.uuid)
    const { message, created } = sendMessage(env.ctx, a!.uuid, dm.id, { text: 'Hallo Bob!' })
    expect(created).toBe(true)
    expect(message).toMatchObject({ seq: 1, text: 'Hallo Bob!', sender: { uuid: a!.uuid, name: 'Alex' }, deleted: false })
    expect(evB.of('chat_message')).toHaveLength(1)
    expect(evB.of('chat_message')[0]!.message.text).toBe('Hallo Bob!')
    expect(evB.events[0]!.id).toMatch(/^[a-z0-9]+\.\d+$/)

    expect(unreadSummary(env.ctx, b!.uuid)).toEqual({ total: 1, conversations: [{ id: dm.id, unread: 1, markedUnread: false, muted: false }] })
    expect(listMessages(env.ctx, b!.uuid, dm.id, { limit: 50 }).messages.map((m) => m.text)).toEqual(['Hallo Bob!'])

    // Fremde: weder DM öffnen noch Unterhaltung/Nachrichten sehen.
    expect(code(() => openDm(env.ctx, c!.uuid, a!.uuid))).toBe('not_friends')
    expect(code(() => openDm(env.ctx, c!.uuid, 'f'.repeat(32)))).toBe('not_friends')
    expect(code(() => access(env.ctx, c!.uuid, dm.id))).toBe('conversation_not_found')
    expect(code(() => sendMessage(env.ctx, c!.uuid, dm.id, { text: 'hi' }))).toBe('conversation_not_found')
    expect(code(() => listMessages(env.ctx, c!.uuid, dm.id, { limit: 10 }))).toBe('conversation_not_found')
    expect(code(() => editMessage(env.ctx, c!.uuid, message.id, 'x'))).toBe('message_not_found')
    expect(code(() => setReaction(env.ctx, c!.uuid, message.id, 'heart', true))).toBe('message_not_found')
    expect(code(() => openDm(env.ctx, a!.uuid, a!.uuid))).toBe('cannot_target_self')
  })

  it('unfriending makes the DM read-only, befriending again re-enables it', async () => {
    const env = makeEnv()
    const [a, b] = await players(env, 'Alex', 'Bob')
    befriend(env, a!, b!)
    const dm = openDm(env.ctx, a!.uuid, b!.uuid)
    sendMessage(env.ctx, a!.uuid, dm.id, { text: 'eins' })
    const evA = listen(env, a!.uuid)
    removeFriend(env.ctx, b!.uuid, a!.uuid)
    // Beide bekommen die neue Sicht (Schreibrecht weg).
    expect(evA.of('chat_conversation').at(-1)!.conversation).toMatchObject({ id: dm.id, canWrite: false, readOnlyReason: 'not_friends' })
    expect(code(() => sendMessage(env.ctx, a!.uuid, dm.id, { text: 'zwei' }))).toBe('not_friends')
    // Verlauf bleibt lesbar, auch per openDm.
    expect(openDm(env.ctx, a!.uuid, b!.uuid).canWrite).toBe(false)
    expect(listMessages(env.ctx, b!.uuid, dm.id, { limit: 10 }).messages).toHaveLength(1)
    befriend(env, a!, b!)
    expect(sendMessage(env.ctx, a!.uuid, dm.id, { text: 'wieder da' }).message.seq).toBe(2)
  })

  it('blocking ends the friendship: no more messages either way', async () => {
    const env = makeEnv()
    const [a, b] = await players(env, 'Alex', 'Bob')
    befriend(env, a!, b!)
    const dm = openDm(env.ctx, a!.uuid, b!.uuid)
    block(env.ctx, a!.uuid, { uuid: b!.uuid })
    expect(code(() => sendMessage(env.ctx, b!.uuid, dm.id, { text: 'hey' }))).toBe('not_friends')
    expect(code(() => sendMessage(env.ctx, a!.uuid, dm.id, { text: 'hey' }))).toBe('not_friends')
    expect(code(() => openDm(env.ctx, b!.uuid, 'c'.repeat(32)))).toBe('not_friends')
  })
})

describe('messages', () => {
  async function dmSetup() {
    const env = makeEnv()
    const [a, b] = await players(env, 'Alex', 'Bob')
    befriend(env, a!, b!)
    const dm = openDm(env.ctx, a!.uuid, b!.uuid)
    return { env, a: a!, b: b!, dm }
  }

  it('sanitises text, enforces length and rejects empty messages', async () => {
    const { env, a, dm } = await dmSetup()
    const m = sendMessage(env.ctx, a.uuid, dm.id, { text: '  Hi\u0000\u0007 there\u202e!\r\n\n\n\nZeile\t2  ' }).message
    expect(m.text).toBe('Hi there!\n\nZeile 2')
    expect(sanitizeText('👨‍👩‍👧 ok')).toBe('👨‍👩‍👧 ok')
    expect(code(() => sendMessage(env.ctx, a.uuid, dm.id, { text: 'x'.repeat(2001) }))).toBe('message_too_long')
    expect(sendMessage(env.ctx, a.uuid, dm.id, { text: '😀'.repeat(2000) }).message.text).toHaveLength(4000)
    expect(code(() => sendMessage(env.ctx, a.uuid, dm.id, { text: ' \u0000 ' }))).toBe('empty_message')
    expect(code(() => sendMessage(env.ctx, a.uuid, dm.id, {}))).toBe('empty_message')
  })

  it('reply, edit, delete for everyone (tombstone) and nonce idempotency', async () => {
    const { env, a, b, dm } = await dmSetup()
    const evB = listen(env, b.uuid)
    const first = sendMessage(env.ctx, a.uuid, dm.id, { text: 'Frage?', nonce: 'nonce-0001' }).message
    const again = sendMessage(env.ctx, a.uuid, dm.id, { text: 'Frage?', nonce: 'nonce-0001' })
    expect(again.created).toBe(false)
    expect(again.message.id).toBe(first.id)
    expect(again.message.nonce).toBe('nonce-0001')

    const reply = sendMessage(env.ctx, b.uuid, dm.id, { text: 'Antwort', replyTo: first.id }).message
    expect(reply.replyTo).toMatchObject({ id: first.id, preview: 'Frage?', sender: { name: 'Alex' }, deleted: false })
    expect(code(() => sendMessage(env.ctx, b.uuid, dm.id, { text: 'x', replyTo: 'm' + '0'.repeat(20) }))).toBe('message_not_found')

    // Nur eigene bearbeiten.
    expect(code(() => editMessage(env.ctx, b.uuid, first.id, 'gehackt'))).toBe('not_sender')
    const edited = editMessage(env.ctx, a.uuid, first.id, 'Frage (neu)?')
    expect(edited.text).toBe('Frage (neu)?')
    expect(edited.editedAt).not.toBeNull()
    expect(evB.of('chat_message_edited')[0]!.message.text).toBe('Frage (neu)?')

    // Nur eigene löschen (DM) → Grabstein, Antwort zeigt „gelöscht“.
    expect(code(() => deleteMessage(env.ctx, b.uuid, first.id))).toBe('not_sender')
    const tomb = deleteMessage(env.ctx, a.uuid, first.id)
    expect(tomb).toMatchObject({ deleted: true, deletedBy: 'sender', text: null, attachments: [], reactions: [] })
    expect(evB.of('chat_message_deleted')[0]!.message.deleted).toBe(true)
    const page = listMessages(env.ctx, b.uuid, dm.id, { limit: 10 }).messages
    expect(page.map((m) => [m.seq, m.deleted])).toEqual([[1, true], [2, false]])
    expect(page[1]!.replyTo).toMatchObject({ id: first.id, deleted: true, preview: null })
    expect(code(() => editMessage(env.ctx, a.uuid, first.id, 'x'))).toBe('message_deleted')
  })

  it('reactions: fixed set, one of each per user, toggling', async () => {
    const { env, a, b, dm } = await dmSetup()
    const m = sendMessage(env.ctx, a.uuid, dm.id, { text: 'GG' }).message
    const evA = listen(env, a.uuid)
    setReaction(env.ctx, b.uuid, m.id, 'fire', true)
    setReaction(env.ctx, b.uuid, m.id, 'fire', true) // doppelt = einmal
    setReaction(env.ctx, a.uuid, m.id, 'fire', true)
    const r = setReaction(env.ctx, b.uuid, m.id, 'heart', true)
    expect(r).toEqual([
      { emoji: 'fire', count: 2, users: [b.uuid, a.uuid] },
      { emoji: 'heart', count: 1, users: [b.uuid] },
    ])
    expect(evA.of('chat_reactions')).toHaveLength(3)
    expect(setReaction(env.ctx, b.uuid, m.id, 'fire', false)).toEqual([
      { emoji: 'fire', count: 1, users: [a.uuid] },
      { emoji: 'heart', count: 1, users: [b.uuid] },
    ])
  })

  it('cursor pagination (before/after) returns ascending pages', async () => {
    const { env, a, b, dm } = await dmSetup()
    for (let i = 1; i <= 7; i++) {
      env.clock.advance(1000)
      sendMessage(env.ctx, i % 2 ? a.uuid : b.uuid, dm.id, { text: `m${i}` })
    }
    const last = listMessages(env.ctx, a.uuid, dm.id, { limit: 3 })
    expect(last.messages.map((m) => m.text)).toEqual(['m5', 'm6', 'm7'])
    expect(last.hasMore).toBe(true)
    const older = listMessages(env.ctx, a.uuid, dm.id, { before: last.messages[0]!.seq, limit: 3 })
    expect(older.messages.map((m) => m.text)).toEqual(['m2', 'm3', 'm4'])
    const oldest = listMessages(env.ctx, a.uuid, dm.id, { before: older.messages[0]!.seq, limit: 3 })
    expect(oldest).toMatchObject({ hasMore: false })
    expect(oldest.messages.map((m) => m.text)).toEqual(['m1'])
    const newer = listMessages(env.ctx, a.uuid, dm.id, { after: 5, limit: 10 })
    expect(newer.messages.map((m) => m.text)).toEqual(['m6', 'm7'])
    expect(newer.hasMore).toBe(false)
  })

  it('read receipts (mutual), mark unread, mute and unread counts', async () => {
    const { env, a, b, dm } = await dmSetup()
    sendMessage(env.ctx, a.uuid, dm.id, { text: '1' })
    sendMessage(env.ctx, a.uuid, dm.id, { text: '2' })
    const evA = listen(env, a.uuid)
    const evB = listen(env, b.uuid)
    expect(conversationView(env.ctx, access(env.ctx, b.uuid, dm.id).conv, b.uuid).unread).toBe(2)
    markRead(env.ctx, b.uuid, dm.id, 2)
    expect(evA.of('chat_read')).toEqual([expect.objectContaining({ conversationId: dm.id, uuid: b.uuid, seq: 2 })])
    expect(evB.of('chat_state').at(-1)).toMatchObject({ unread: 0, readSeq: 2 })
    expect(conversationView(env.ctx, access(env.ctx, a.uuid, dm.id).conv, a.uuid).reads).toEqual([
      expect.objectContaining({ uuid: b.uuid, seq: 2 }),
    ])

    // Als ungelesen markieren (ab Nachricht 2): eigener Zähler 1, Lesebestätigung bleibt.
    const cv = markUnread(env.ctx, b.uuid, dm.id, 2)
    expect(cv).toMatchObject({ unread: 1, markedUnread: true, readSeq: 1 })
    expect(conversationView(env.ctx, access(env.ctx, a.uuid, dm.id).conv, a.uuid).reads[0]!.seq).toBe(2)
    expect(unreadSummary(env.ctx, b.uuid).total).toBe(1)

    // Stumm: zählt nicht zur Summe.
    setConversationMute(env.ctx, b.uuid, dm.id, MUTED_FOREVER)
    expect(unreadSummary(env.ctx, b.uuid)).toMatchObject({ total: 0, conversations: [{ id: dm.id, muted: true }] })
    expect(conversationView(env.ctx, access(env.ctx, b.uuid, dm.id).conv, b.uuid)).toMatchObject({ muted: true, mutedUntil: null })
    setConversationMute(env.ctx, b.uuid, dm.id, env.clock.t + 60_000)
    env.clock.advance(61_000)
    expect(conversationView(env.ctx, access(env.ctx, b.uuid, dm.id).conv, b.uuid).muted).toBe(false)

    // Lesebestätigungen abschalten: B sendet keine mehr und sieht keine.
    updateSettings(env.ctx, b.uuid, { chatReadReceipts: false })
    evA.clear()
    sendMessage(env.ctx, a.uuid, dm.id, { text: '3' })
    markRead(env.ctx, b.uuid, dm.id, 3)
    expect(evA.of('chat_read')).toEqual([])
    markRead(env.ctx, a.uuid, dm.id, 3)
    expect(conversationView(env.ctx, access(env.ctx, b.uuid, dm.id).conv, b.uuid).reads).toEqual([])
  })

  it('typing is ephemeral, expires and respects the setting', async () => {
    const { env, a, b, dm } = await dmSetup()
    const evB = listen(env, b.uuid)
    setTyping(env.ctx, a.uuid, dm.id, true)
    setTyping(env.ctx, a.uuid, dm.id, true) // sofort wiederholt → gedrosselt
    expect(evB.of('chat_typing')).toEqual([{ type: 'chat_typing', conversationId: dm.id, uuid: a.uuid, typing: true, expiresInMs: 8000 }])
    expect(evB.events[0]!.id).toBeNull()
    // Senden beendet das Tippen.
    sendMessage(env.ctx, a.uuid, dm.id, { text: 'fertig' })
    expect(evB.of('chat_typing').at(-1)!.typing).toBe(false)
    updateSettings(env.ctx, b.uuid, { chatTypingIndicator: false })
    evB.clear()
    setTyping(env.ctx, a.uuid, dm.id, true)
    expect(evB.of('chat_typing')).toEqual([])
  })

  it('conversation list is sorted by activity with a cursor', async () => {
    const env = makeEnv()
    const [a, b, c, d] = await players(env, 'Alex', 'Bob', 'Carl', 'Dora')
    for (const x of [b, c, d]) befriend(env, a!, x!)
    const ids = [b, c, d].map((x) => openDm(env.ctx, a!.uuid, x!.uuid).id)
    env.clock.advance(1000)
    sendMessage(env.ctx, a!.uuid, ids[0]!, { text: 'neu' })
    const p1 = listConversations(env.ctx, a!.uuid, { limit: 2 })
    expect(p1.conversations[0]!.id).toBe(ids[0])
    expect(p1.conversations[0]!.lastMessage?.text).toBe('neu')
    expect(p1.nextCursor).not.toBeNull()
    const p2 = listConversations(env.ctx, a!.uuid, { limit: 2, cursor: p1.nextCursor! })
    expect(p2.conversations).toHaveLength(1)
    expect(p2.nextCursor).toBeNull()
    expect(new Set([...p1.conversations, ...p2.conversations].map((x) => x.id))).toEqual(new Set(ids))
    expect(code(() => listConversations(env.ctx, a!.uuid, { limit: 2, cursor: 'kaputt' }))).toBe('invalid_cursor')
  })
})

describe('groups', () => {
  it('owner creates with friends only; members see history only from joining', async () => {
    const env = makeEnv()
    const [o, f1, f2, x] = await players(env, 'Owner', 'Finn', 'Fay', 'Xaver')
    befriend(env, o!, f1!)
    befriend(env, o!, f2!)
    expect(code(() => createGroup(env.ctx, o!.uuid, 'Crew', [f1!.uuid, x!.uuid]))).toBe('not_friends')
    const evF1 = listen(env, f1!.uuid)
    const g = createGroup(env.ctx, o!.uuid, '  Bau-Crew \u0007 ', [f1!.uuid])
    expect(g).toMatchObject({ kind: 'group', name: 'Bau-Crew', owner: o!.uuid, canWrite: true })
    expect(g.members.map((m) => [m.name, m.role])).toEqual([['Owner', 'owner'], ['Finn', 'member']])
    expect(evF1.of('chat_conversation')[0]!.conversation.id).toBe(g.id)
    sendMessage(env.ctx, f1!.uuid, g.id, { text: 'vor Fay' })

    // Nur der Besitzer fügt hinzu, und nur eigene Freunde.
    expect(code(() => addMembers(env.ctx, f1!.uuid, g.id, [f2!.uuid]))).toBe('not_owner')
    expect(code(() => addMembers(env.ctx, o!.uuid, g.id, [x!.uuid]))).toBe('not_friends')
    const evF2 = listen(env, f2!.uuid)
    addMembers(env.ctx, o!.uuid, g.id, [f2!.uuid])
    expect(evF2.of('chat_conversation').at(-1)!.conversation.id).toBe(g.id)
    const seen = listMessages(env.ctx, f2!.uuid, g.id, { limit: 50 }).messages
    expect(seen.map((m) => m.system?.event ?? m.text)).toEqual(['member_added'])
    expect(seen[0]!.system).toMatchObject({ actor: { name: 'Owner' }, target: { name: 'Fay' } })
    // Nachricht vor dem Beitritt ist für Fay nicht erreichbar.
    const before = listMessages(env.ctx, f1!.uuid, g.id, { limit: 50 }).messages.find((m) => m.text === 'vor Fay')!
    expect(code(() => setReaction(env.ctx, f2!.uuid, before.id, 'heart', true))).toBe('message_not_found')

    // Gruppenmitglieder dürfen schreiben, auch ohne befreundet zu sein.
    expect(sendMessage(env.ctx, f2!.uuid, g.id, { text: 'Hi Finn' }).created).toBe(true)
  })

  it('rename, remove, leave with owner transfer, transfer, delete', async () => {
    const env = makeEnv()
    const [o, f1, f2] = await players(env, 'Owner', 'Finn', 'Fay')
    befriend(env, o!, f1!)
    befriend(env, o!, f2!)
    const g = createGroup(env.ctx, o!.uuid, 'Crew', [f1!.uuid, f2!.uuid])
    expect(code(() => renameGroup(env.ctx, f1!.uuid, g.id, 'Nope'))).toBe('not_owner')
    expect(renameGroup(env.ctx, o!.uuid, g.id, 'Neue Crew').name).toBe('Neue Crew')

    const evF2 = listen(env, f2!.uuid)
    removeMember(env.ctx, o!.uuid, g.id, f2!.uuid)
    expect(evF2.of('chat_conversation_removed')).toEqual([{ type: 'chat_conversation_removed', conversationId: g.id, reason: 'removed' }])
    expect(code(() => listMessages(env.ctx, f2!.uuid, g.id, { limit: 5 }))).toBe('conversation_not_found')

    // Besitzer tritt aus → Finn wird Besitzer.
    leaveGroup(env.ctx, o!.uuid, g.id)
    const after = conversationView(env.ctx, access(env.ctx, f1!.uuid, g.id).conv, f1!.uuid)
    expect(after.owner).toBe(f1!.uuid)
    expect(after.members.map((m) => m.name)).toEqual(['Finn'])
    const events = listMessages(env.ctx, f1!.uuid, g.id, { limit: 50 }).messages.map((m) => m.system?.event)
    expect(events.slice(-2)).toEqual(['member_left', 'owner_changed'])

    // Übergeben + löschen.
    befriend(env, f1!, f2!)
    addMembers(env.ctx, f1!.uuid, g.id, [f2!.uuid])
    expect(transferOwner(env.ctx, f1!.uuid, g.id, f2!.uuid).owner).toBe(f2!.uuid)
    expect(code(() => deleteGroup(env.ctx, f1!.uuid, g.id))).toBe('not_owner')
    const evF1 = listen(env, f1!.uuid)
    deleteGroup(env.ctx, f2!.uuid, g.id)
    expect(evF1.of('chat_conversation_removed')[0]).toMatchObject({ reason: 'deleted' })
    expect(one(env.ctx.db, 'SELECT 1 AS x FROM chat_messages WHERE conversation_id = ?', g.id)).toBeUndefined()
  })

  it('group limits and owner deleting others\' messages', async () => {
    const env = makeEnv({ limits: { maxGroupMembers: 3 } })
    const [o, f1, f2, f3] = await players(env, 'Owner', 'Finn', 'Fay', 'Fritz')
    for (const f of [f1, f2, f3]) befriend(env, o!, f!)
    expect(code(() => createGroup(env.ctx, o!.uuid, 'Zu groß', [f1!.uuid, f2!.uuid, f3!.uuid]))).toBe('group_full')
    const g = createGroup(env.ctx, o!.uuid, 'Klein', [f1!.uuid, f2!.uuid])
    expect(code(() => addMembers(env.ctx, o!.uuid, g.id, [f3!.uuid]))).toBe('group_full')
    const m = sendMessage(env.ctx, f1!.uuid, g.id, { text: 'Spam' }).message
    expect(code(() => deleteMessage(env.ctx, f2!.uuid, m.id))).toBe('not_sender')
    expect(deleteMessage(env.ctx, o!.uuid, m.id).deletedBy).toBe('owner')
  })

  it('links and invites in groups only from the owner or players befriended with everyone', async () => {
    const env = makeEnv()
    const [o, f1, f2] = await players(env, 'Owner', 'Finn', 'Fay')
    befriend(env, o!, f1!)
    befriend(env, o!, f2!)
    const g = createGroup(env.ctx, o!.uuid, 'Crew', [f1!.uuid, f2!.uuid])
    expect(code(() => sendMessage(env.ctx, f1!.uuid, g.id, { text: 'schau: https://evil.example/login' }))).toBe('links_not_allowed')
    expect(code(() => sendMessage(env.ctx, f1!.uuid, g.id, { text: 'free nitro at disc0rd-gift.xyz' }))).toBe('links_not_allowed')
    expect(code(() => sendMessage(env.ctx, f1!.uuid, g.id, { invite: { address: 'play.example.net' } }))).toBe('links_not_allowed')
    expect(sendMessage(env.ctx, o!.uuid, g.id, { text: 'https://trs-launcher.theredstonee.de' }).created).toBe(true)
    befriend(env, f1!, f2!)
    const inv = sendMessage(env.ctx, f1!.uuid, g.id, { invite: { address: 'Play.Example.net:25566', name: 'Survival' } }).message
    expect(inv.invite).toEqual({ address: 'play.example.net:25566', name: 'Survival' })
    // DMs: Links zwischen Freunden sind erlaubt.
    const dm = openDm(env.ctx, f1!.uuid, f2!.uuid)
    expect(sendMessage(env.ctx, f1!.uuid, dm.id, { text: 'www.example.org' }).created).toBe(true)
    expect(countLinks('a https://x.de/y und www.y.org sowie z.gg')).toBe(3)
    expect(countLinks('Version 1.21.4 ist da')).toBe(0)
  })

  it('messages of players you blocked are hidden in groups', async () => {
    const env = makeEnv()
    const [o, f1, f2] = await players(env, 'Owner', 'Finn', 'Fay')
    befriend(env, o!, f1!)
    befriend(env, o!, f2!)
    const g = createGroup(env.ctx, o!.uuid, 'Crew', [f1!.uuid, f2!.uuid])
    block(env.ctx, f2!.uuid, { uuid: f1!.uuid })
    const evF2 = listen(env, f2!.uuid)
    sendMessage(env.ctx, f1!.uuid, g.id, { text: 'geheim' })
    expect(evF2.of('chat_message')[0]!.message).toMatchObject({ hidden: true, text: null })
    expect(listMessages(env.ctx, f2!.uuid, g.id, { limit: 10 }).messages.at(-1)).toMatchObject({ hidden: true, text: null })
    expect(listMessages(env.ctx, o!.uuid, g.id, { limit: 10 }).messages.at(-1)).toMatchObject({ hidden: false, text: 'geheim' })
    // Ausgeblendete Nachrichten zählen nicht als ungelesen.
    expect(unreadSummary(env.ctx, f2!.uuid).total).toBe(0)
  })
})

describe('safety', () => {
  it('word filter masks or blocks (normalised, no regex)', async () => {
    const env = makeEnv()
    const [a, b] = await players(env, 'Alex', 'Bob')
    befriend(env, a!, b!)
    const dm = openDm(env.ctx, a!.uuid, b!.uuid)
    addFilterWord(env.ctx, 'api-key', 'Mistwort', 'word', 'mask')
    addFilterWord(env.ctx, 'api-key', 'verboten', 'contains', 'block')
    expect(sendMessage(env.ctx, a!.uuid, dm.id, { text: 'Du M1stw0rt!' }).message.text).toBe('Du ********!')
    expect(code(() => sendMessage(env.ctx, a!.uuid, dm.id, { text: 'total VERBOTENes Zeug' }))).toBe('message_blocked')
    expect(code(() => addFilterWord(env.ctx, 'api-key', 'mistwort', 'word', 'mask'))).toBe('word_exists')
  })

  it('spam brake: repeated text is rejected, three strikes mute for 10 minutes', async () => {
    const env = makeEnv()
    const [a, b] = await players(env, 'Alex', 'Bob')
    befriend(env, a!, b!)
    const dm = openDm(env.ctx, a!.uuid, b!.uuid)
    const evA = listen(env, a!.uuid)
    sendMessage(env.ctx, a!.uuid, dm.id, { text: 'Kauf jetzt!!' })
    sendMessage(env.ctx, a!.uuid, dm.id, { text: 'kauf jetzt' })
    expect(code(() => sendMessage(env.ctx, a!.uuid, dm.id, { text: 'KAUF JETZT' }))).toBe('spam_detected')
    expect(code(() => sendMessage(env.ctx, a!.uuid, dm.id, { text: 'Kauf jetzt' }))).toBe('spam_detected')
    expect(activeMute(env.ctx, a!.uuid)).toBeUndefined()
    expect(code(() => sendMessage(env.ctx, a!.uuid, dm.id, { text: 'Kauf  jetzt' }))).toBe('spam_detected')
    expect(activeMute(env.ctx, a!.uuid)).toMatchObject({ auto: 'spam' })
    expect(evA.of('moderation')[0]).toMatchObject({ action: 'mute' })
    expect(code(() => sendMessage(env.ctx, a!.uuid, dm.id, { text: 'etwas anderes' }))).toBe('chat_muted')
    expect(conversationView(env.ctx, access(env.ctx, a!.uuid, dm.id).conv, a!.uuid).readOnlyReason).toBe('chat_muted')
    env.clock.advance(10 * 60_000 + 1)
    expect(sendMessage(env.ctx, a!.uuid, dm.id, { text: 'etwas anderes' }).created).toBe(true)
  })
})

describe('storage', () => {
  it('message text and group names are encrypted at rest; key rotation re-encrypts', async () => {
    const env = makeEnv()
    const [a, b] = await players(env, 'Alex', 'Bob')
    befriend(env, a!, b!)
    const g = createGroup(env.ctx, a!.uuid, 'Geheimclub', [b!.uuid])
    const m = sendMessage(env.ctx, a!.uuid, g.id, { text: 'streng geheim 4711' }).message
    const raw = one<{ body: Uint8Array }>(env.ctx.db, 'SELECT body FROM chat_messages WHERE id = ?', m.id)!.body
    expect(Buffer.from(raw).toString('latin1')).not.toContain('4711')
    const name = one<{ name: Uint8Array }>(env.ctx.db, 'SELECT name FROM chat_conversations WHERE id = ?', g.id)!.name
    expect(Buffer.from(name).toString('latin1')).not.toContain('Geheim')
    expect(ChatCipher.keyIdOf(raw)).toBe('s1')

    // Neuer Schlüssel vorne, alter dahinter → alles lesbar, Hintergrundjob verschlüsselt neu.
    const oldKeys = env.ctx.config.chatKeys.keys
    env.ctx.cipher = new ChatCipher([{ id: 'k2', key: Buffer.alloc(32, 7) }, ...oldKeys])
    expect(listMessages(env.ctx, b!.uuid, g.id, { limit: 5 }).messages.at(-1)!.text).toBe('streng geheim 4711')
    expect(rotateMessageKeys(env.ctx, 100)).toBeGreaterThanOrEqual(3)
    expect(rotateMessageKeys(env.ctx, 100)).toBe(0)
    env.ctx.cipher = new ChatCipher([{ id: 'k2', key: Buffer.alloc(32, 7) }])
    expect(listMessages(env.ctx, b!.uuid, g.id, { limit: 5 }).messages.at(-1)!.text).toBe('streng geheim 4711')
    expect(conversationView(env.ctx, access(env.ctx, b!.uuid, g.id).conv, b!.uuid).name).toBe('Geheimclub')
  })

  it('account deletion removes DMs for both sides, own messages and hands over groups', async () => {
    const env = makeEnv()
    const [a, b, c] = await players(env, 'Alex', 'Bob', 'Carl')
    befriend(env, a!, b!)
    befriend(env, a!, c!)
    const dm = openDm(env.ctx, a!.uuid, b!.uuid)
    sendMessage(env.ctx, b!.uuid, dm.id, { text: 'hi Alex' })
    const g = createGroup(env.ctx, a!.uuid, 'Crew', [b!.uuid, c!.uuid])
    const mine = sendMessage(env.ctx, a!.uuid, g.id, { text: 'von Alex' }).message
    const bobs = sendMessage(env.ctx, b!.uuid, g.id, { text: 'von Bob', replyTo: mine.id }).message
    setReaction(env.ctx, a!.uuid, bobs.id, 'heart', true)
    const solo = createGroup(env.ctx, a!.uuid, 'Allein', [])
    const evB = listen(env, b!.uuid)

    deleteUser(env.ctx, a!.uuid)

    expect(evB.of('chat_conversation_removed')).toEqual([{ type: 'chat_conversation_removed', conversationId: dm.id, reason: 'deleted' }])
    expect(evB.of('chat_reload')).toEqual([{ type: 'chat_reload', conversationId: g.id }])
    expect(one(env.ctx.db, 'SELECT 1 AS x FROM chat_conversations WHERE id = ?', dm.id)).toBeUndefined()
    expect(one(env.ctx.db, 'SELECT 1 AS x FROM chat_conversations WHERE id = ?', solo.id)).toBeUndefined()
    const view = conversationView(env.ctx, access(env.ctx, b!.uuid, g.id).conv, b!.uuid)
    expect(view.owner).toBe(b!.uuid)
    expect(view.members.map((m) => m.name)).toEqual(['Bob', 'Carl'])
    const msgs = listMessages(env.ctx, b!.uuid, g.id, { limit: 50 }).messages
    expect(msgs.some((m) => m.sender?.uuid === a!.uuid)).toBe(false)
    const left = msgs.find((m) => m.id === bobs.id)!
    expect(left.reactions).toEqual([])
    expect(left.replyTo).toMatchObject({ deleted: true })
    expect(one(env.ctx.db, 'SELECT COUNT(*) AS n FROM chat_members WHERE uuid = ?', a!.uuid)).toEqual({ n: 0 })
  })
})
