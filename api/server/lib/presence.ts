export type PresenceState = 'online' | 'in-game'

export interface GameInfo {
  version: string
  loader: string
  /** Nur gespeichert, wenn der Nutzer `shareServer` erlaubt. */
  server?: string
}

export interface Presence {
  state: PresenceState
  game: GameInfo | null
  updatedAt: number
  expiresAt: number
}

/**
 * Präsenz liegt nur im Arbeitsspeicher (flüchtig, kein Verlauf) und läuft nach
 * `ttlMs` ohne Heartbeat ab.
 */
export class PresenceStore {
  private map = new Map<string, Presence>()
  constructor(
    private readonly ttlMs: number,
    private readonly now: () => number = Date.now,
  ) {}

  /** Setzt die Präsenz. Rückgabe: `true`, wenn sich Zustand/Spiel geändert hat (für Ereignisse). */
  set(uuid: string, state: PresenceState, game: GameInfo | null): boolean {
    const t = this.now()
    const prev = this.get(uuid)
    this.map.set(uuid, { state, game, updatedAt: t, expiresAt: t + this.ttlMs })
    return !prev || prev.state !== state || JSON.stringify(prev.game) !== JSON.stringify(game)
  }

  get(uuid: string): Presence | null {
    const p = this.map.get(uuid)
    if (!p) return null
    if (p.expiresAt <= this.now()) {
      this.map.delete(uuid)
      return null
    }
    return p
  }

  /** Entfernt die Präsenz. Rückgabe: `true`, wenn der Nutzer vorher sichtbar online war. */
  delete(uuid: string): boolean {
    const was = this.get(uuid) !== null
    this.map.delete(uuid)
    return was
  }

  /** Entfernt den Serverwert aller Einträge eines Nutzers (wenn er `shareServer` abschaltet). */
  stripServer(uuid: string): void {
    const p = this.get(uuid)
    if (p?.game?.server !== undefined) {
      const { server: _drop, ...rest } = p.game
      p.game = rest
    }
  }

  /** Entfernt abgelaufene Einträge und liefert deren UUIDs (→ „offline“-Ereignisse). */
  sweep(): string[] {
    const t = this.now()
    const expired: string[] = []
    for (const [uuid, p] of this.map) {
      if (p.expiresAt <= t) {
        this.map.delete(uuid)
        expired.push(uuid)
      }
    }
    return expired
  }

  count(): number {
    this.sweep()
    return this.map.size
  }
}
