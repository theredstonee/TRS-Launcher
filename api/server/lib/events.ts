/** Ereignisse, die per SSE (`GET /v1/events`) an einen Nutzer gehen. */
export type ApiEvent =
  | { type: 'friend_request', from: { uuid: string, name: string } }
  | { type: 'friend_request_cancelled', uuid: string }
  | { type: 'friend_added', friend: { uuid: string, name: string } }
  | { type: 'friend_removed', uuid: string }
  | {
    type: 'presence'
    uuid: string
    presence: { state: string, game: { version: string, loader: string, server?: string } | null, updatedAt: string } | null
  }

export type Listener = (e: ApiEvent) => void

export interface Subscription {
  close: () => void
}

/** Verteilt Ereignisse an offene Streams (ein Prozess). */
export class EventHub {
  private listeners = new Map<string, Map<number, { fn: Listener, onKick: () => void }>>()
  private seq = 0
  private total = 0

  constructor(
    private readonly maxPerUser: number,
    private readonly maxTotal: number,
  ) {}

  /** `null`, wenn die Grenzen erreicht sind. `onKick` wird aufgerufen, wenn der Server den Stream beendet. */
  subscribe(uuid: string, fn: Listener, onKick: () => void): Subscription | null {
    let m = this.listeners.get(uuid)
    if ((m?.size ?? 0) >= this.maxPerUser || this.total >= this.maxTotal) return null
    if (!m) {
      m = new Map()
      this.listeners.set(uuid, m)
    }
    const id = ++this.seq
    m.set(id, { fn, onKick })
    this.total++
    let closed = false
    return {
      close: () => {
        if (closed) return
        closed = true
        const mm = this.listeners.get(uuid)
        if (mm?.delete(id)) this.total--
        if (mm && mm.size === 0) this.listeners.delete(uuid)
      },
    }
  }

  publish(uuid: string, e: ApiEvent): void {
    const m = this.listeners.get(uuid)
    if (!m) return
    for (const { fn } of m.values()) {
      try {
        fn(e)
      } catch {
        // Ein kaputter Stream darf die anderen nicht stören.
      }
    }
  }

  /** Beendet alle Streams eines Nutzers (Abmelden, Sperre, Kontolöschung). */
  kick(uuid: string): void {
    const m = this.listeners.get(uuid)
    if (!m) return
    for (const { onKick } of [...m.values()]) onKick()
  }

  isListening(uuid: string): boolean {
    return (this.listeners.get(uuid)?.size ?? 0) > 0
  }

  get size(): number {
    return this.total
  }
}
