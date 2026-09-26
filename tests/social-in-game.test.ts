import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { ref } from 'vue'
import { decide, defaultSocialPrefs, type Situation, type SocialToastKind } from '../app/utils/socialToasts'
import type { ChatConversation, ChatMessage } from '../app/utils/chat'
import { HOST, ROOM_ID, room } from './fixtures/hosting'

// Läuft ein Spiel mit verbundenem TRS Client, zeigt der die Sozial-Hinweise im
// Spiel – der Launcher schweigt dann (kein Hinweis, kein Ton, keine Windows-
// Benachrichtigung), zählt aber weiter als ungelesen und holt nichts nach.

const ME = '75c1a6f3112240abbdb57b9d21c64232'
const CONV = 'c1f0e2d3c4b5a6978899a'

function message(seq: number): ChatMessage {
  return {
    id: `m${seq.toString(16).padStart(20, '0')}`,
    conversationId: CONV,
    seq,
    kind: 'text',
    sender: HOST,
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
  }
}

function conversation(): ChatConversation {
  return {
    id: CONV,
    kind: 'dm',
    name: null,
    owner: null,
    members: [],
    peer: HOST,
    canWrite: true,
    readOnlyReason: null,
    lastMessage: message(1),
    lastSeq: 1,
    unread: 0,
    markedUnread: false,
    readSeq: 1,
    muted: false,
    mutedUntil: null,
    reads: [],
    createdAt: null,
    updatedAt: '2026-09-26T10:01:00.000Z',
  }
}

const social = {
  quietHours: vi.fn(async () => false),
  notifyNative: vi.fn(async () => undefined),
  focusWindow: vi.fn(async () => undefined),
  gameClients: vi.fn(async () => [] as string[]),
  conversations: vi.fn(async () => ({ conversations: [conversation()], nextCursor: null })),
  conversation: vi.fn(async () => conversation()),
  messages: vi.fn(async () => ({ messages: [message(1)], hasMore: false })),
  read: vi.fn(async () => conversation()),
  myModeration: vi.fn(async () => ({ mute: null, warnings: [] })),
}
const hosting = {
  friendsRooms: vi.fn(async () => [room()]),
  myRooms: vi.fn(async () => []),
}
vi.mock('../app/utils/backend', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../app/utils/backend')>()),
  backend: { social, hosting },
}))
vi.mock('../app/utils/sound', () => ({ playNotificationSound: vi.fn() }))

const { useSocialToasts } = await import('../app/stores/socialToasts')
const { useChatStore } = await import('../app/stores/chat')
const { useHostingStore } = await import('../app/stores/hosting')
const { playNotificationSound } = await import('../app/utils/sound')

// Normale Toasts (Fehler, Update-Hinweise …): der echte Store; seine Nuxt-Auto-Imports als Attrappen.
beforeAll(async () => {
  vi.stubGlobal('ref', ref)
  vi.stubGlobal('errorMessage', (e: unknown) => String(e))
  const { useToasts } = await import('../app/stores/toasts')
  vi.stubGlobal('useToasts', useToasts)
  vi.stubGlobal('useRouter', () => ({ push: vi.fn(async () => {}) }))
  vi.stubGlobal('useGamesStore', () => ({ state: () => ({ phase: 'running' }), launch: vi.fn() }))
  vi.stubGlobal('useInstancesStore', () => ({ items: [], loaded: true, load: vi.fn(async () => {}) }))
  // Beim Spielen ist das Launcher-Fenster im Hintergrund (sonst gäbe es nie eine Windows-Benachrichtigung).
  vi.stubGlobal('document', { hasFocus: () => false, visibilityState: 'visible' })
})

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
})

const KINDS: SocialToastKind[] = ['message', 'invite', 'friendRequest', 'capeOffer', 'online', 'report', 'moderation']
const here: Situation = { focused: false, visible: true, fullscreen: false, looking: false, muted: false, clientInGame: false }
const toast = (key: string) => ({ key, title: 'Bob', body: 'hi', face: HOST, actions: [] })

describe('Spiel mit TRS Client läuft', () => {
  it('Regel: nichts im Launcher – kein Hinweis, kein Ton, keine Systembenachrichtigung (alle Arten)', () => {
    for (const kind of KINDS) {
      expect(decide(kind, defaultSocialPrefs, here).toast, kind).toBe(true)
      expect(decide(kind, defaultSocialPrefs, { ...here, clientInGame: true }), kind).toEqual({ toast: false, native: false, sound: false })
    }
  })

  it('Store: still, solange verbunden; danach sofort wieder – ohne Nachflut', async () => {
    const store = useSocialToasts()
    expect(store.clientInGame).toBe(false)
    store.setGameClients(['survival'])
    expect(store.clientInGame).toBe(true)
    for (const kind of KINDS) expect(await store.notify(kind, toast(`k:${kind}`)), kind).toBe(false)
    expect(store.items).toHaveLength(0)
    expect(playNotificationSound).not.toHaveBeenCalled()
    expect(social.notifyNative).not.toHaveBeenCalled()
    expect(social.quietHours).not.toHaveBeenCalled()

    // Vorschau aus den Einstellungen kommt trotzdem (der Nutzer hat geklickt).
    expect(await store.notify('report', toast('preview'), { preview: true })).toBe(true)
    store.clear()
    vi.clearAllMocks()

    // Spiel beendet oder Link abgebrochen: sofort wieder Hinweise, Verpasstes kommt nicht nach.
    store.setGameClients([])
    expect(await store.notify('message', toast('msg:neu'))).toBe(true)
    expect(store.items.map((t) => t.key)).toEqual(['msg:neu'])
    expect(playNotificationSound).toHaveBeenCalledTimes(1)
    expect(social.notifyNative).toHaveBeenCalledTimes(1)
  })

  it('Fehler und andere Launcher-Hinweise bleiben; schlichte Sozial-Hinweise schweigen', () => {
    const store = useSocialToasts()
    const toasts = useToasts()
    store.setGameClients(['survival'])
    toasts.error('Spielstart fehlgeschlagen')
    toasts.info('Update verfügbar')
    expect(store.notice('Aus der Welt entfernt')).toBe(false)
    expect(toasts.items.map((t) => t.text)).toEqual(['Spielstart fehlgeschlagen', 'Update verfügbar'])
    store.setGameClients([])
    expect(store.notice('Aus der Welt entfernt')).toBe(true)
    expect(toasts.items).toHaveLength(3)
  })

  it('Ungelesen zählt weiter, obwohl der Launcher schweigt', async () => {
    const store = useSocialToasts()
    const chat = useChatStore()
    chat.setAccount(ME)
    await chat.loadList()
    store.setGameClients(['survival'])
    for (const seq of [2, 3]) {
      await chat.onEvent({ type: 'chat_message', conversationId: CONV, message: message(seq) })
      expect(await store.notify('message', toast(`msg:${CONV}`))).toBe(false)
    }
    expect(chat.conversations[CONV]!.unread).toBe(2)
    expect(chat.unreadTotal).toBe(2)
    expect(store.items).toHaveLength(0)
  })

  it('Welt-Hosting: Einladung und „rausgeworfen“ nur ohne verbundenen TRS Client', async () => {
    const store = useSocialToasts()
    const toasts = useToasts()
    const worlds = useHostingStore()
    await worlds.load()
    store.setGameClients(['survival'])
    await worlds.onEvent({ type: 'hosting_invite', room: room('h00000000000000000007', { myState: 'invited' }), from: HOST })
    expect(worlds.invitedCount).toBe(1)
    await worlds.onEvent({ type: 'hosting_kicked', roomId: ROOM_ID, banned: false })
    await Promise.resolve()
    expect(store.items).toHaveLength(0)
    expect(toasts.items).toHaveLength(0)

    store.setGameClients([])
    await worlds.onEvent({ type: 'hosting_invite', room: room('h00000000000000000008', { myState: 'invited' }), from: HOST })
    await vi.waitFor(() => expect(store.items).toHaveLength(1))
  })
})

// Nuxt-Auto-Import im Test (siehe beforeAll).
declare function useToasts(): ReturnType<typeof import('../app/stores/toasts').useToasts>
