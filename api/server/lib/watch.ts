import type { LookupCape, LookupCosmetics } from './lookup'

/** Ereignisse des Spieler-Streams `GET /v1/events/players` (nur für beobachtete Spieler). */
export type PlayerEvent =
  | { type: 'emote', uuid: string, emote: string, durationMs: number, at: string }
  | { type: 'skin', uuid: string, at: string }
  | { type: 'cosmetics', uuid: string, cosmetics: LookupCosmetics }
  | { type: 'cape', uuid: string, cape: LookupCape | null }

export interface Watcher {
  /** Wer zuschaut (Konto des Streams). */
  readonly viewer: string
  readonly uuids: ReadonlySet<string>
  send: (e: PlayerEvent) => void
  kick: () => void
}

/**
 * Speicher-Verteiler (ein Prozess): Ziel-UUID → Streams, die diesen Spieler
 * beobachten. Die Menge je Stream ist fest; zum Ändern öffnet der Client einen
 * neuen Stream und schließt den alten.
 */
export class PlayerWatchHub {
  private byTarget = new Map<string, Set<Watcher>>()
  private byViewer = new Map<string, Set<Watcher>>()
  private total = 0

  constructor(
    private readonly maxPerUser: number,
    private readonly maxTotal: number,
  ) {}

  /** `null`, wenn die Grenzen erreicht sind. */
  subscribe(
    viewer: string,
    uuids: Iterable<string>,
    send: (e: PlayerEvent) => void,
    kick: () => void,
  ): { close: () => void, watching: number } | null {
    const mine = this.byViewer.get(viewer)
    if ((mine?.size ?? 0) >= this.maxPerUser || this.total >= this.maxTotal) return null
    const w: Watcher = { viewer, uuids: new Set(uuids), send, kick }
    for (const u of w.uuids) {
      let s = this.byTarget.get(u)
      if (!s) {
        s = new Set()
        this.byTarget.set(u, s)
      }
      s.add(w)
    }
    if (mine) mine.add(w)
    else this.byViewer.set(viewer, new Set([w]))
    this.total++
    let closed = false
    return {
      watching: w.uuids.size,
      close: () => {
        if (closed) return
        closed = true
        this.total--
        for (const u of w.uuids) {
          const s = this.byTarget.get(u)
          s?.delete(w)
          if (s && s.size === 0) this.byTarget.delete(u)
        }
        const v = this.byViewer.get(viewer)
        v?.delete(w)
        if (v && v.size === 0) this.byViewer.delete(viewer)
      },
    }
  }

  watchersOf(target: string): Watcher[] {
    const s = this.byTarget.get(target)
    return s ? [...s] : []
  }

  isWatched(target: string): boolean {
    return (this.byTarget.get(target)?.size ?? 0) > 0
  }

  /** Beendet alle Streams eines Kontos (Abmelden überall, Sperre, Löschung). */
  kick(viewer: string): void {
    for (const w of [...(this.byViewer.get(viewer) ?? [])]) w.kick()
  }

  get size(): number {
    return this.total
  }
}
