import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
// Relativ importiert, damit Tests den Store ohne Nuxt laden können.
import { BackendError, backend, errorMessage } from '../utils/backend'
import {
  type ChatConversation,
  type ChatMessage,
  type LiveEvent,
  type LocalMessage,
  type MyModeration,
  type MyReport,
  type OutgoingMessage,
  type ReactionId,
  type UploadSource,
  isMuted,
  lastSeq,
  mergeMessages,
  newNonce,
  sortConversations,
  toggleReaction,
  unreadCount,
  upsertMessage,
} from '../utils/chat'

/** Nachrichten einer Unterhaltung, soweit geladen. */
export interface Thread {
  items: LocalMessage[]
  hasMore: boolean
  loaded: boolean
  loading: boolean
}

/** Bild, das mit einer Nachricht rausgeht: Quelle + Vorschau-URL für die Anzeige. */
export interface DraftImage {
  source: UploadSource
  preview: string
}

/** Was der Nutzer abschickt. */
export interface Draft {
  text: string
  replyTo?: ChatMessage | null
  images?: DraftImage[]
  invite?: { address: string; name: string | null } | null
}

/** „Schreibt …“ höchstens so oft senden (API §18.5). */
export const TYPING_EVERY_MS = 3000
/** Lesestand nicht bei jeder Nachricht einzeln senden. */
const READ_DELAY_MS = 400

// Chat-Zustand: Unterhaltungen, geladene Nachrichten, Tippen, Lesestand und
// eigene Meldungen. Aktualisiert sich über den Echtzeit-Kanal (`onEvent`);
// REST nur zum Laden, beim Nachholen und als Rückfall.
export const useChatStore = defineStore('chat', () => {
  /** Eigene UUID (aktiver Account mit TRS). */
  const me = ref<string | null>(null)
  const conversations = ref<Record<string, ChatConversation>>({})
  const listState = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
  const listError = ref<string | null>(null)
  const nextCursor = ref<string | null>(null)
  const threads = ref<Record<string, Thread>>({})
  /** Unterhaltung → UUID → läuft ab (ms). */
  const typing = ref<Record<string, Record<string, number>>>({})
  /** Geöffnete Unterhaltung. */
  const activeId = ref<string | null>(null)
  /** Der Nutzer sieht die geöffnete Unterhaltung gerade (Seite offen, Fenster aktiv). */
  const watching = ref(false)
  const moderation = ref<MyModeration | null>(null)
  const reports = ref<MyReport[] | null>(null)
  /** Entwürfe je Unterhaltung (bleiben beim Wechseln erhalten). */
  const drafts = ref<Record<string, string>>({})

  const list = computed(() => sortConversations(Object.values(conversations.value)))
  const unreadTotal = computed(() =>
    Object.values(conversations.value).reduce((sum, c) => sum + unreadCount({ ...c, muted: isMuted(c) }), 0),
  )
  const active = computed(() => (activeId.value ? (conversations.value[activeId.value] ?? null) : null))
  /** Stummgeschaltet durch die Moderation (Eingabe gesperrt). */
  const chatMuted = computed(() => {
    const mute = moderation.value?.mute
    if (!mute) return false
    if (!mute.until) return true
    const until = Date.parse(mute.until)
    return Number.isNaN(until) || until > Date.now()
  })

  const typingSent: Record<string, number> = {}
  const readTimers: Record<string, ReturnType<typeof setTimeout>> = {}

  function reset() {
    conversations.value = {}
    threads.value = {}
    typing.value = {}
    activeId.value = null
    listState.value = 'idle'
    listError.value = null
    nextCursor.value = null
    moderation.value = null
    reports.value = null
    drafts.value = {}
  }

  /** Anderer Account → alles verwerfen. */
  function setAccount(uuid: string | null) {
    if (uuid === me.value) return
    reset()
    me.value = uuid
  }

  function thread(id: string): Thread {
    let t = threads.value[id]
    if (!t) {
      t = { items: [], hasMore: false, loaded: false, loading: false }
      threads.value[id] = t
    }
    return t
  }

  function upsertConversation(c: ChatConversation) {
    const old = conversations.value[c.id]
    // Eine ältere Antwort darf eine neuere Nachricht nicht überschreiben.
    if (old?.lastMessage && c.lastMessage && old.lastMessage.seq > c.lastMessage.seq) {
      conversations.value[c.id] = { ...c, lastMessage: old.lastMessage, lastSeq: Math.max(c.lastSeq, old.lastSeq) }
    } else {
      conversations.value[c.id] = c
    }
  }

  function removeConversation(id: string) {
    delete conversations.value[id]
    delete threads.value[id]
    delete typing.value[id]
    delete drafts.value[id]
    if (activeId.value === id) activeId.value = null
  }

  async function loadList(more = false) {
    if (!me.value) return
    if (more && !nextCursor.value) return
    listState.value = more ? listState.value : 'loading'
    try {
      const page = await backend.social.conversations(more ? nextCursor.value : null)
      if (!more) {
        // Nicht mehr gelieferte (verlassen, gelöscht) fallen weg – außer die offene.
        const keep = new Set(page.conversations.map((c) => c.id))
        for (const id of Object.keys(conversations.value)) if (!keep.has(id) && id !== activeId.value) removeConversation(id)
      }
      for (const c of page.conversations) upsertConversation(c)
      nextCursor.value = page.nextCursor
      listState.value = 'ready'
      listError.value = null
    } catch (e) {
      listError.value = errorMessage(e)
      listState.value = Object.keys(conversations.value).length ? 'ready' : 'error'
      throw e
    }
  }

  /** Rückfall ohne Echtzeit-Kanal: Ungelesen-Zähler abgleichen. */
  async function refreshUnread() {
    if (!me.value) return
    const summary = await backend.social.unread()
    const byId = new Map(summary.conversations.map((c) => [c.id, c]))
    let unknown = false
    for (const c of Object.values(conversations.value)) {
      const s = byId.get(c.id)
      conversations.value[c.id] = { ...c, unread: s?.unread ?? 0, markedUnread: s?.markedUnread ?? false, muted: s?.muted ?? c.muted }
    }
    for (const id of byId.keys()) if (!conversations.value[id]) unknown = true
    if (unknown) await loadList().catch(() => {})
  }

  async function fetchConversation(id: string) {
    try {
      upsertConversation(await backend.social.conversation(id))
    } catch (e) {
      if (e instanceof BackendError && e.apiCode === 'conversation_not_found') removeConversation(id)
    }
  }

  async function loadMessages(id: string) {
    const t = thread(id)
    if (t.loading) return
    t.loading = true
    try {
      const page = await backend.social.messages(id)
      // Eigene, noch nicht bestätigte Nachrichten bleiben stehen.
      t.items = mergeMessages(
        t.items.filter((m) => m.local),
        page.messages,
      )
      t.hasMore = page.hasMore
      t.loaded = true
    } finally {
      t.loading = false
    }
  }

  async function loadOlder(id: string) {
    const t = thread(id)
    const oldest = t.items.find((m) => !m.local)
    if (t.loading || !t.hasMore || !oldest) return
    t.loading = true
    try {
      const page = await backend.social.messages(id, { before: oldest.seq })
      t.items = mergeMessages(t.items, page.messages)
      t.hasMore = page.hasMore
    } finally {
      t.loading = false
    }
  }

  /** Verpasstes nachholen (nach Wiederverbinden oder im Rückfall). */
  async function catchUp(id: string) {
    const t = threads.value[id]
    if (!t?.loaded) return
    for (let page = 0; page < 5; page++) {
      const after = lastSeq(t.items)
      const result = await backend.social.messages(id, { after, limit: 100 })
      t.items = mergeMessages(t.items, result.messages)
      if (!result.hasMore) break
    }
  }

  function markReadSoon(id: string) {
    clearTimeout(readTimers[id])
    readTimers[id] = setTimeout(() => void markRead(id), READ_DELAY_MS)
  }

  async function markRead(id: string) {
    const c = conversations.value[id]
    if (!c) return
    const seq = Math.max(c.lastSeq, lastSeq(threads.value[id]?.items ?? []))
    if (c.readSeq >= seq && c.unread === 0 && !c.markedUnread) return
    conversations.value[id] = { ...c, unread: 0, markedUnread: false, readSeq: Math.max(c.readSeq, seq) }
    try {
      upsertConversation(await backend.social.read(id, seq))
    } catch {
      // Nicht schlimm – beim nächsten Mal wieder.
    }
  }

  /** Unterhaltung öffnen: Nachrichten laden, gelesen markieren. */
  async function open(id: string) {
    activeId.value = id
    const t = thread(id)
    if (!conversations.value[id]) await fetchConversation(id)
    if (!t.loaded) await loadMessages(id)
    else void catchUp(id).catch(() => {})
    if (watching.value) markReadSoon(id)
  }

  function close() {
    if (activeId.value) void stopTyping(activeId.value)
    activeId.value = null
  }

  /** Sichtbarkeit (Seite offen + Fenster aktiv) – dann zählt Neues sofort als gelesen. */
  function setWatching(value: boolean) {
    watching.value = value
    if (value && activeId.value) markReadSoon(activeId.value)
  }

  async function openDm(uuid: string): Promise<string> {
    const c = await backend.social.openDm(uuid)
    upsertConversation(c)
    return c.id
  }

  function localMessage(id: string, nonce: string, draft: OutgoingMessage, images: DraftImage[], reply: ChatMessage | null): LocalMessage {
    return {
      id: `local-${nonce}`,
      conversationId: id,
      seq: Number.MAX_SAFE_INTEGER,
      kind: 'text',
      sender: me.value ? { uuid: me.value, name: conversations.value[id]?.members.find((m) => m.uuid === me.value)?.name ?? '' } : null,
      text: draft.text ?? null,
      invite: draft.invite ? { address: draft.invite.address, name: draft.invite.name } : null,
      world: null,
      attachments: [],
      replyTo: reply
        ? {
            id: reply.id,
            seq: reply.seq,
            sender: reply.sender,
            preview: reply.text?.slice(0, 120) ?? null,
            attachments: reply.attachments.length,
            invite: !!reply.invite,
            world: !!reply.world,
            deleted: reply.deleted,
          }
        : null,
      system: null,
      reactions: [],
      createdAt: new Date().toISOString(),
      editedAt: null,
      deleted: false,
      deletedBy: null,
      hidden: false,
      nonce,
      local: { state: 'sending', draft, previews: images.map((i) => i.preview), sources: images.map((i) => i.source) },
    }
  }

  function replaceLocal(id: string, nonce: string, patch: Partial<NonNullable<LocalMessage['local']>>) {
    const t = thread(id)
    t.items = t.items.map((m) => (m.local && m.nonce === nonce ? { ...m, local: { ...m.local, ...patch } } : m))
  }

  /** Bilder hochladen (höchstens 3 gleichzeitig), Reihenfolge bleibt. */
  async function uploadAll(images: DraftImage[]): Promise<string[]> {
    const ids: string[] = new Array(images.length)
    let next = 0
    async function worker() {
      while (next < images.length) {
        const i = next++
        ids[i] = (await backend.social.upload(images[i]!.source)).id
      }
    }
    await Promise.all(Array.from({ length: Math.min(3, images.length) }, worker))
    return ids
  }

  /**
   * Nachricht senden: erscheint sofort (als „wird gesendet“), Bilder werden
   * erst hochgeladen, dann ersetzt die bestätigte Nachricht die lokale.
   */
  async function send(id: string, draft: Draft): Promise<boolean> {
    const text = draft.text.replace(/\s+$/, '').replace(/^\n+/, '')
    const images = draft.images ?? []
    if (!text.trim() && !images.length && !draft.invite) return false
    const nonce = newNonce()
    const outgoing: OutgoingMessage = { nonce }
    if (text.trim()) outgoing.text = text
    if (draft.replyTo) outgoing.replyTo = draft.replyTo.id
    if (draft.invite) outgoing.invite = draft.invite
    const t = thread(id)
    t.items = upsertMessage(t.items, localMessage(id, nonce, outgoing, images, draft.replyTo ?? null))
    void stopTyping(id, true)
    return deliver(id, nonce, outgoing, images)
  }

  async function deliver(id: string, nonce: string, outgoing: OutgoingMessage, images: DraftImage[]): Promise<boolean> {
    try {
      if (images.length && !outgoing.attachments?.length) outgoing.attachments = await uploadAll(images)
      const message = await backend.social.send(id, outgoing)
      const t = thread(id)
      t.items = upsertMessage(t.items, message)
      applyIncoming(id, message)
      return true
    } catch (e) {
      replaceLocal(id, nonce, { state: 'failed', error: errorMessage(e) })
      if (e instanceof BackendError && e.apiCode === 'chat_muted') void loadModeration()
      return false
    }
  }

  /** Fehlgeschlagene Nachricht erneut senden (gleiche Nonce → keine Doppelten). */
  async function retry(id: string, message: LocalMessage) {
    if (!message.local || !message.nonce) return
    replaceLocal(id, message.nonce, { state: 'sending', error: undefined })
    const local = message.local
    const images = local.draft.attachments?.length
      ? []
      : local.sources.map((source, i) => ({ source, preview: local.previews[i] ?? '' }))
    await deliver(id, message.nonce, { ...local.draft }, images)
  }

  function discard(id: string, message: LocalMessage) {
    const t = thread(id)
    t.items = t.items.filter((m) => m !== message && !(m.local && m.nonce === message.nonce))
  }

  function replaceMessage(id: string, message: ChatMessage) {
    const t = threads.value[id]
    if (t?.loaded) t.items = upsertMessage(t.items, message)
    const c = conversations.value[id]
    if (c?.lastMessage?.id === message.id) conversations.value[id] = { ...c, lastMessage: message }
  }

  async function edit(id: string, messageId: string, text: string) {
    replaceMessage(id, await backend.social.edit(messageId, text))
  }

  async function remove(id: string, messageId: string) {
    replaceMessage(id, await backend.social.remove(messageId))
  }

  async function react(id: string, message: ChatMessage, emoji: ReactionId) {
    if (!me.value) return
    const on = !message.reactions.some((r) => r.emoji === emoji && r.users.includes(me.value!))
    const before = message.reactions
    const t = threads.value[id]
    const set = (reactions: ChatMessage['reactions']) => {
      if (t) t.items = t.items.map((m) => (m.id === message.id ? { ...m, reactions } : m))
    }
    set(toggleReaction(before, emoji, me.value, on))
    try {
      set(await backend.social.react(message.id, emoji, on))
    } catch (e) {
      set(before)
      throw e
    }
  }

  async function markUnread(id: string, seq: number | null = null) {
    upsertConversation(await backend.social.markUnread(id, seq))
  }

  async function mute(id: string, muted: boolean, until: string | null = null) {
    upsertConversation(await backend.social.mute(id, muted, until))
  }

  /** Beim Tippen aufrufen – sendet höchstens alle 3 s. */
  function noteTyping(id: string) {
    const now = Date.now()
    if (now - (typingSent[id] ?? 0) < TYPING_EVERY_MS) return
    typingSent[id] = now
    void backend.social.typing(id, true).catch(() => {})
  }

  /** Tippen beenden (Eingabe geleert, Unterhaltung verlassen). `sent` = durch Senden (der Server beendet es selbst). */
  async function stopTyping(id: string, sent = false) {
    if (!typingSent[id]) return
    delete typingSent[id]
    if (!sent) await backend.social.typing(id, false).catch(() => {})
  }

  /** Neue Nachricht in der Liste und im Zähler berücksichtigen. */
  function applyIncoming(id: string, message: ChatMessage) {
    const c = conversations.value[id]
    if (!c) return
    const newer = !c.lastMessage || message.seq >= c.lastMessage.seq
    const mine = message.sender?.uuid === me.value
    const counts = !mine && !message.hidden && !message.deleted && message.kind === 'text' && message.seq > c.readSeq
    const looking = watching.value && activeId.value === id
    conversations.value[id] = {
      ...c,
      lastMessage: newer ? message : c.lastMessage,
      lastSeq: Math.max(c.lastSeq, message.seq),
      updatedAt: newer ? (message.createdAt ?? c.updatedAt) : c.updatedAt,
      unread: counts && !looking ? c.unread + 1 : mine ? 0 : c.unread,
      readSeq: mine ? Math.max(c.readSeq, message.seq) : c.readSeq,
      markedUnread: mine ? false : c.markedUnread,
    }
    if (counts && looking) markReadSoon(id)
  }

  /** Ereignis aus dem Echtzeit-Kanal. */
  async function onEvent(e: LiveEvent) {
    switch (e.type) {
      case 'chat_message': {
        const id = e.conversationId
        if (!conversations.value[id]) await fetchConversation(id)
        const t = threads.value[id]
        if (t?.loaded) t.items = upsertMessage(t.items, e.message)
        applyIncoming(id, e.message)
        if (e.message.sender) delete typing.value[id]?.[e.message.sender.uuid]
        break
      }
      case 'chat_message_edited':
      case 'chat_message_deleted':
        replaceMessage(e.conversationId, e.message)
        break
      case 'chat_reactions': {
        const t = threads.value[e.conversationId]
        if (t) t.items = t.items.map((m) => (m.id === e.messageId ? { ...m, reactions: e.reactions } : m))
        break
      }
      case 'chat_typing': {
        const map = (typing.value[e.conversationId] ??= {})
        if (e.typing && e.uuid !== me.value) map[e.uuid] = Date.now() + e.expiresInMs
        else delete map[e.uuid]
        break
      }
      case 'chat_read': {
        const c = conversations.value[e.conversationId]
        if (!c) break
        if (e.uuid === me.value) {
          // Auf einem anderen Gerät gelesen.
          const readSeq = Math.max(c.readSeq, e.seq)
          conversations.value[c.id] = { ...c, readSeq, unread: readSeq >= c.lastSeq ? 0 : c.unread, markedUnread: false }
        } else {
          const reads = c.reads.filter((r) => r.uuid !== e.uuid)
          reads.push({ uuid: e.uuid, seq: Math.max(e.seq, c.reads.find((r) => r.uuid === e.uuid)?.seq ?? 0), at: e.at })
          conversations.value[c.id] = { ...c, reads }
        }
        break
      }
      case 'chat_state': {
        const c = conversations.value[e.conversationId]
        if (c) {
          conversations.value[c.id] = {
            ...c,
            unread: e.unread,
            markedUnread: e.markedUnread,
            readSeq: e.readSeq,
            muted: e.muted,
            mutedUntil: e.mutedUntil,
          }
        }
        break
      }
      case 'chat_conversation':
        upsertConversation(e.conversation)
        break
      case 'chat_conversation_removed':
        removeConversation(e.conversationId)
        break
      case 'chat_reload':
        await fetchConversation(e.conversationId)
        if (threads.value[e.conversationId]?.loaded) await loadMessages(e.conversationId).catch(() => {})
        break
      case 'moderation':
        await loadModeration()
        break
      case 'report_update':
        if (reports.value) {
          const others = reports.value.filter((r) => r.id !== e.report.id)
          reports.value = [e.report, ...others]
        }
        break
      default:
        break
    }
  }

  /** Nach `resync` oder einer Lücke: alles Wichtige neu. */
  async function resync() {
    if (!me.value) return
    await loadList().catch(() => {})
    await Promise.allSettled(Object.keys(threads.value).map((id) => catchUp(id)))
    void loadModeration()
  }

  async function loadModeration() {
    try {
      moderation.value = await backend.social.myModeration()
    } catch {
      // Ältere Server kennen den Endpunkt nicht – dann eben ohne Hinweis.
    }
  }

  async function loadReports() {
    reports.value = await backend.social.myReports()
  }

  // --- Gruppen ----------------------------------------------------------------------

  async function createGroup(name: string, members: string[]): Promise<string> {
    const c = await backend.social.createGroup(name, members)
    upsertConversation(c)
    return c.id
  }

  async function renameGroup(id: string, name: string) {
    upsertConversation(await backend.social.renameGroup(id, name))
  }

  async function addMembers(id: string, members: string[]) {
    upsertConversation(await backend.social.addMembers(id, members))
  }

  async function removeMember(id: string, uuid: string) {
    await backend.social.removeMember(id, uuid)
    await fetchConversation(id)
  }

  async function transferOwner(id: string, uuid: string) {
    upsertConversation(await backend.social.transferGroup(id, uuid))
  }

  async function leaveGroup(id: string) {
    await backend.social.leaveGroup(id)
    removeConversation(id)
  }

  async function deleteGroup(id: string) {
    await backend.social.deleteGroup(id)
    removeConversation(id)
  }

  return {
    me,
    conversations,
    list,
    listState,
    listError,
    nextCursor,
    threads,
    typing,
    activeId,
    active,
    watching,
    moderation,
    chatMuted,
    reports,
    drafts,
    unreadTotal,
    setAccount,
    reset,
    thread,
    upsertConversation,
    removeConversation,
    loadList,
    refreshUnread,
    fetchConversation,
    loadMessages,
    loadOlder,
    catchUp,
    markRead,
    markReadSoon,
    open,
    close,
    setWatching,
    openDm,
    send,
    retry,
    discard,
    edit,
    remove,
    react,
    markUnread,
    mute,
    noteTyping,
    stopTyping,
    onEvent,
    resync,
    loadModeration,
    loadReports,
    createGroup,
    renameGroup,
    addMembers,
    removeMember,
    transferOwner,
    leaveGroup,
    deleteGroup,
  }
})
