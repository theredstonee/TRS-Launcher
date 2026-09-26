import { randomBytes } from 'node:crypto'
import type { IncomingOffer } from './capeshares'
import type { ConversationView, MessageView, ReactionView } from './chat'
import type { HostRoomView, RoomCloseReason, RoomView, SignalKind } from './hosting'
import type { Settings } from './users'

export interface PlayerRef {
  uuid: string
  name: string
}

export interface PresenceEventView {
  state: string
  game: { version: string, loader: string, server?: string } | null
  updatedAt: string
}

/**
 * Ereignisse an einen Nutzer. `GET /v1/events/me` bekommt alle, der alte Stream `GET /v1/events`
 * nur die Arten aus {@link LEGACY_EVENT_TYPES} (wie bisher).
 */
export type ApiEvent =
  /** Ein Freund bietet dir einen Umhang an. */
  | { type: 'cape_offer', offer: IncomingOffer }
  /** Dein Angebot wurde angenommen (Ablehnen bleibt still). */
  | { type: 'cape_offer_accepted', capeId: string, by: PlayerRef }
  /** Ein Angebot an dich wurde zurückgezogen oder ein geteilter Umhang entzogen/gelöscht. */
  | { type: 'cape_share_removed', capeId: string }
  | { type: 'friend_request', from: PlayerRef }
  | { type: 'friend_request_cancelled', uuid: string }
  | { type: 'friend_added', friend: PlayerRef }
  | { type: 'friend_removed', uuid: string }
  | { type: 'presence', uuid: string, presence: PresenceEventView | null }
  // ---------------------------------------------------------------- nur /v1/events/me
  /** Ein Freund ist gerade online gekommen (vorher offline/unsichtbar) – für „X ist online“. */
  | { type: 'friend_online', friend: PlayerRef, presence: PresenceEventView }
  /** Eigene Freundesliste/Anfragen/Blockaden durch eigene Aktion geändert (anderes Gerät): `GET /v1/friends` neu laden. */
  | { type: 'friends_changed' }
  /** Eigene Einstellungen wurden (auf einem anderen Gerät) geändert. */
  | { type: 'settings', settings: Settings }
  | { type: 'chat_message', conversationId: string, message: MessageView }
  | { type: 'chat_message_edited', conversationId: string, message: MessageView }
  /** Für alle gelöscht – `message` ist der Grabstein. */
  | { type: 'chat_message_deleted', conversationId: string, message: MessageView }
  | { type: 'chat_reactions', conversationId: string, messageId: string, reactions: ReactionView[] }
  /** Flüchtig (kein Wiederholen): läuft beim Empfänger nach `expiresInMs` ohne Auffrischung ab. */
  | { type: 'chat_typing', conversationId: string, uuid: string, typing: boolean, expiresInMs: number }
  /** Lesebestätigung eines anderen Mitglieds (oder eigener Stand von einem anderen Gerät). */
  | { type: 'chat_read', conversationId: string, uuid: string, seq: number, at: string }
  /** Nur an dich: Ungelesen-Zahl, Markierung, Stummschaltung dieser Unterhaltung haben sich geändert. */
  | {
    type: 'chat_state'
    conversationId: string
    unread: number
    markedUnread: boolean
    readSeq: number
    muted: boolean
    mutedUntil: string | null
  }
  /** Unterhaltung neu oder geändert (Name, Mitglieder, Besitzer, Schreibrecht). */
  | { type: 'chat_conversation', conversation: ConversationView }
  | { type: 'chat_conversation_removed', conversationId: string, reason: 'left' | 'removed' | 'deleted' }
  /** Nachrichten wurden serverseitig entfernt (Konto gelöscht, Moderation): Verlauf neu laden. */
  | { type: 'chat_reload', conversationId: string }
  /** Rückmeldung zu einer eigenen Meldung. */
  | { type: 'report_update', report: { id: string, kind: string, status: string, outcome: string | null, updatedAt: string } }
  /** Moderation betrifft dich: Verwarnung, Stummschaltung (mit Ende oder `null` = bis zur Prüfung), aufgehoben. */
  | { type: 'moderation', action: 'warn' | 'mute' | 'unmute', reason: string | null, until: string | null }
  // ---------------------------------------------------------------- Welt-Hosting (§21, nur /v1/events/me)
  /** An den Host (alle Geräte): voller Raumzustand nach jeder Änderung (Mitglieder, Einstellungen, Spielerzahl). */
  | { type: 'hosting_room', room: HostRoomView }
  /** An den Host: jemand möchte beitreten (für Toast/Popup). */
  | { type: 'hosting_join_request', roomId: string, from: PlayerRef }
  /** An dich: ein Freund lädt dich in seine Welt ein. */
  | { type: 'hosting_invite', room: RoomView, from: PlayerRef }
  /** An dich: Einladung zurückgezogen (oder Freundschaft beendet). */
  | { type: 'hosting_invite_revoked', roomId: string }
  /** An dich: du bist drin – jetzt `POST …/connect` bzw. direkt verbinden. */
  | { type: 'hosting_join_accepted', room: RoomView }
  | { type: 'hosting_join_declined', roomId: string }
  /** An dich: der Host hat dich entfernt (`banned`: auch gesperrt). */
  | { type: 'hosting_kicked', roomId: string, banned: boolean }
  /** Raum geändert (Einstellungen, offen/zu, Spielerzahl) – an alle, die ihn sehen dürfen. */
  | { type: 'hosting_room_updated', room: RoomView }
  /** Raum weg oder für dich nicht mehr sichtbar. */
  | { type: 'hosting_room_closed', roomId: string, reason: RoomCloseReason }
  /** Verbindungsaufbau (ICE): nur zwischen Host und angenommenen Gästen. */
  | { type: 'hosting_signal', roomId: string, from: string, kind: SignalKind, sid: string | null, data: string }

export type ApiEventType = ApiEvent['type']

/** Arten, die auch der alte Stream `GET /v1/events` liefert. */
export const LEGACY_EVENT_TYPES: ReadonlySet<ApiEventType> = new Set<ApiEventType>([
  'cape_offer', 'cape_offer_accepted', 'cape_share_removed',
  'friend_request', 'friend_request_cancelled', 'friend_added', 'friend_removed', 'presence',
])

/** `id` = Ereignis-ID für SSE (`<epoch>.<seq>`), `null` bei flüchtigen Ereignissen (Tippen). */
export type Listener = (e: ApiEvent, id: string | null) => void

export interface Subscription {
  close: () => void
}

export type StreamKind = 'legacy' | 'me'

interface Buffered {
  seq: number
  at: number
  e: ApiEvent
}

interface UserBuffer {
  events: Buffered[]
  /** Zähler beim Anlegen: ältere IDs können hier nie lückenlos wiederholt werden. */
  since: number
  /** Höchste verworfene seq (Ringpuffer voll / zu alt). */
  trimmedUpTo: number
  /** Letzte Aktivität (Stream offen/geschlossen, Ereignis) – ohne Stream bleibt der Puffer `windowMs` lang. */
  touched: number
}

export type ReplayResult =
  | { ok: true, events: { id: string, e: ApiEvent }[] }
  | { ok: false, reason: 'restart' | 'gap' | 'invalid' }

export interface EventHubOptions {
  maxPerUserMe?: number
  bufferSize?: number
  windowMs?: number
  now?: () => number
}

/**
 * Verteilt Ereignisse an offene Streams (ein Prozess) und hält je Nutzer einen kleinen
 * Ringpuffer für die Wiederaufnahme per `Last-Event-ID` (`GET /v1/events/me`).
 *
 * - IDs: `<epoch>.<seq>` – `epoch` wechselt mit jedem Serverstart (dann `resync`), `seq` zählt global.
 * - Puffer nur für Nutzer, die gerade einen `me`-Stream haben oder in den letzten `windowMs` hatten.
 *   Flüchtige Ereignisse (Tippen) werden nie gepuffert.
 */
export class EventHub {
  private listeners = new Map<string, Map<number, { fn: Listener, onKick: () => void, kind: StreamKind }>>()
  private buffers = new Map<string, UserBuffer>()
  private subSeq = 0
  private counter = 0
  private total = 0
  readonly epoch: string
  private readonly maxPerUserMe: number
  private readonly bufferSize: number
  private readonly windowMs: number
  private readonly now: () => number

  constructor(
    private readonly maxPerUser: number,
    private readonly maxTotal: number,
    opts: EventHubOptions = {},
  ) {
    this.maxPerUserMe = opts.maxPerUserMe ?? maxPerUser
    this.bufferSize = opts.bufferSize ?? 300
    this.windowMs = opts.windowMs ?? 10 * 60_000
    this.now = opts.now ?? Date.now
    this.epoch = `${Date.now().toString(36)}${randomBytes(3).toString('hex')}`
  }

  /** `null`, wenn die Grenzen erreicht sind. `onKick` wird aufgerufen, wenn der Server den Stream beendet. */
  subscribe(uuid: string, fn: Listener, onKick: () => void, kind: StreamKind = 'legacy'): Subscription | null {
    let m = this.listeners.get(uuid)
    const same = m ? [...m.values()].filter((l) => l.kind === kind).length : 0
    const max = kind === 'me' ? this.maxPerUserMe : this.maxPerUser
    if (same >= max || this.total >= this.maxTotal) return null
    if (!m) {
      m = new Map()
      this.listeners.set(uuid, m)
    }
    const id = ++this.subSeq
    m.set(id, { fn, onKick, kind })
    this.total++
    if (kind === 'me') this.ensureBuffer(uuid)
    let closed = false
    return {
      close: () => {
        if (closed) return
        closed = true
        const mm = this.listeners.get(uuid)
        if (mm?.delete(id)) this.total--
        if (mm && mm.size === 0) this.listeners.delete(uuid)
        const b = this.buffers.get(uuid)
        if (b) b.touched = this.now()
      },
    }
  }

  private ensureBuffer(uuid: string): UserBuffer {
    let b = this.buffers.get(uuid)
    if (!b) {
      b = { events: [], since: this.counter, trimmedUpTo: 0, touched: this.now() }
      this.buffers.set(uuid, b)
    }
    b.touched = this.now()
    return b
  }

  private trim(b: UserBuffer, t: number): void {
    while (b.events.length > this.bufferSize || (b.events.length > 0 && t - b.events[0]!.at > this.windowMs)) {
      b.trimmedUpTo = b.events.shift()!.seq
    }
  }

  /**
   * Verteilt ein Ereignis. `ephemeral` (Tippen) = ohne ID, nicht gepuffert. `meOnly` = nicht an
   * den alten Stream (z. B. Abgleich der eigenen Geräte, den es dort früher nicht gab).
   */
  publish(uuid: string, e: ApiEvent, opts: { ephemeral?: boolean, meOnly?: boolean } = {}): void {
    const m = this.listeners.get(uuid)
    const b = this.buffers.get(uuid)
    if (!m && !b) return
    let id: string | null = null
    if (!opts.ephemeral) {
      const seq = ++this.counter
      id = `${this.epoch}.${seq}`
      if (b) {
        const t = this.now()
        b.events.push({ seq, at: t, e })
        this.trim(b, t)
      }
    }
    if (!m) return
    const legacy = !opts.meOnly && LEGACY_EVENT_TYPES.has(e.type)
    for (const l of m.values()) {
      if (l.kind === 'legacy' && !legacy) continue
      try {
        l.fn(e, id)
      } catch {
        // Ein kaputter Stream darf die anderen nicht stören.
      }
    }
  }

  /**
   * Ereignisse nach `lastEventId` (für die Wiederaufnahme). Nicht lückenlos möglich → `ok: false`
   * (Client lädt dann den Zustand per REST neu). Direkt danach im selben Tick abonnieren, dann
   * geht nichts verloren (alles läuft in einem Thread).
   */
  replay(uuid: string, lastEventId: string): ReplayResult {
    const m = /^([a-z0-9]{1,24})\.(\d{1,15})$/.exec(lastEventId)
    if (!m) return { ok: false, reason: 'invalid' }
    if (m[1] !== this.epoch) return { ok: false, reason: 'restart' }
    const last = Number(m[2])
    if (last > this.counter) return { ok: false, reason: 'invalid' }
    const b = this.buffers.get(uuid)
    if (!b) return { ok: false, reason: 'gap' }
    this.trim(b, this.now())
    if (last < b.since || b.trimmedUpTo > last) return { ok: false, reason: 'gap' }
    return {
      ok: true,
      events: b.events.filter((x) => x.seq > last).map((x) => ({ id: `${this.epoch}.${x.seq}`, e: x.e })),
    }
  }

  /** Aktuelle ID (für `hello`, damit auch ein Client ohne Ereignisse fortsetzen kann). */
  currentId(): string {
    return `${this.epoch}.${this.counter}`
  }

  /** Beendet alle Streams eines Nutzers (Abmelden, Sperre, Kontolöschung) und verwirft seinen Puffer. */
  kick(uuid: string): void {
    this.buffers.delete(uuid)
    const m = this.listeners.get(uuid)
    if (!m) return
    for (const { onKick } of [...m.values()]) onKick()
  }

  /** Hat der Nutzer einen offenen Stream? */
  isListening(uuid: string): boolean {
    return (this.listeners.get(uuid)?.size ?? 0) > 0
  }

  /** Lohnt sich ein Ereignis (Stream offen oder Puffer für die Wiederaufnahme)? */
  wants(uuid: string): boolean {
    return this.isListening(uuid) || this.buffers.has(uuid)
  }

  /** Puffer ohne Stream, die länger als `windowMs` unberührt sind, verwerfen. */
  sweep(): void {
    const t = this.now()
    for (const [uuid, b] of this.buffers) {
      if (!this.isListening(uuid) && t - b.touched > this.windowMs) this.buffers.delete(uuid)
      else this.trim(b, t)
    }
  }

  get size(): number {
    return this.total
  }

  get bufferedUsers(): number {
    return this.buffers.size
  }
}
