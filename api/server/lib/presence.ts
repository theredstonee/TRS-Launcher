export type PresenceState = 'online' | 'in-game'

/**
 * Wer die Präsenz meldet: `launcher` (TRS Launcher – offen bzw. ein von ihm
 * gestartetes Spiel läuft) oder `client` (TRS Client im Spiel, solange man in
 * einer Welt / auf einem Server ist).
 */
export type PresenceVia = 'client' | 'launcher'

export interface GameInfo {
  version: string
  loader: string
  /** Nur gespeichert, wenn der Nutzer `shareServer` erlaubt. */
  server?: string
}

/** Zusammengeführte Sicht über beide Quellen (siehe {@link PresenceStore}). */
export interface Presence {
  state: PresenceState
  game: GameInfo | null
  /** Quelle der gezeigten Werte. */
  via: PresenceVia
  updatedAt: number
  expiresAt: number
}

interface Slot {
  state: PresenceState
  game: GameInfo | null
  updatedAt: number
  expiresAt: number
}

type Entry = Partial<Record<PresenceVia, Slot>>

const SOURCES: readonly PresenceVia[] = ['client', 'launcher']

/**
 * Welche Meldung zählt: `in-game` vor `online`, bei `in-game` der Mod zuerst
 * (kennt den Server), sonst die neueste. Meldungen, die zum Zeitpunkt `t`
 * abgelaufen sind, zählen nicht.
 */
function merge(e: Entry, t: number): Presence | null {
  const live = (s: Slot | undefined) => (s && s.expiresAt > t ? s : undefined)
  const c = live(e.client)
  const l = live(e.launcher)
  let via: PresenceVia
  if (c?.state === 'in-game') via = 'client'
  else if (l?.state === 'in-game') via = 'launcher'
  else if (c && l) via = c.updatedAt >= l.updatedAt ? 'client' : 'launcher'
  else if (c) via = 'client'
  else if (l) via = 'launcher'
  else return null
  const s = e[via]!
  return {
    state: s.state,
    game: s.game,
    via,
    updatedAt: s.updatedAt,
    expiresAt: Math.max(c?.expiresAt ?? 0, l?.expiresAt ?? 0),
  }
}

/** Sichtbarer Unterschied für Freunde (Zustand oder Spiel; die Quelle allein zählt nicht). */
function differs(a: Presence | null, b: Presence | null): boolean {
  if (!a || !b) return a !== b
  return a.state !== b.state || JSON.stringify(a.game) !== JSON.stringify(b.game)
}

/** Entfernt abgelaufene Meldungen; `true`, wenn etwas entfernt wurde. */
function dropExpired(e: Entry, t: number): boolean {
  let dropped = false
  for (const via of SOURCES) {
    if (e[via] && e[via].expiresAt <= t) {
      delete e[via]
      dropped = true
    }
  }
  return dropped
}

/**
 * Präsenz liegt nur im Arbeitsspeicher (flüchtig, kein Verlauf) und läuft nach
 * `ttlMs` ohne Heartbeat ab – je Quelle getrennt. So überschreiben sich
 * Launcher und Mod nicht gegenseitig: Der Launcher meldet `in-game`, solange
 * ein von ihm gestartetes Spiel läuft, der Mod, solange man in einer Welt ist.
 * Nach außen zählt die zusammengeführte Sicht ({@link PresenceStore.get}).
 */
export class PresenceStore {
  private map = new Map<string, Entry>()
  constructor(
    private readonly ttlMs: number,
    private readonly now: () => number = Date.now,
  ) {}

  /**
   * Setzt die Meldung einer Quelle (Standard wie bei alten Clients: `in-game`
   * kommt vom Mod, `online` vom Launcher). Rückgabe: `true`, wenn sich die
   * sichtbare Präsenz (Zustand/Spiel) geändert hat (für Ereignisse).
   */
  set(uuid: string, state: PresenceState, game: GameInfo | null, via: PresenceVia = state === 'in-game' ? 'client' : 'launcher'): boolean {
    const t = this.now()
    const prev = this.get(uuid)
    const entry = this.map.get(uuid) ?? {}
    entry[via] = { state, game, updatedAt: t, expiresAt: t + this.ttlMs }
    this.map.set(uuid, entry)
    return differs(prev, this.get(uuid))
  }

  /**
   * Zusammengeführte Präsenz oder `null` = offline. Abgelaufene Meldungen
   * zählen nicht; entfernt werden sie erst von {@link PresenceStore.sweepChanges},
   * damit dabei die Ereignisse („offline“, Abzeichen weg) nicht verloren gehen.
   */
  get(uuid: string): Presence | null {
    const e = this.map.get(uuid)
    return e ? merge(e, this.now()) : null
  }

  /** Spielt der Nutzer gerade (TRS Client in einer Welt oder ein vom Launcher gestartetes Spiel)? */
  isInGame(uuid: string): boolean {
    return this.get(uuid)?.state === 'in-game'
  }

  /** Entfernt nur die Meldung einer Quelle. Rückgabe wie bei {@link PresenceStore.set}. */
  clear(uuid: string, via: PresenceVia): boolean {
    const prev = this.get(uuid)
    const e = this.map.get(uuid)
    if (!e?.[via]) return false
    delete e[via]
    if (!e.client && !e.launcher) this.map.delete(uuid)
    return differs(prev, this.get(uuid))
  }

  /** Entfernt die Präsenz ganz. Rückgabe: `true`, wenn der Nutzer vorher sichtbar online war. */
  delete(uuid: string): boolean {
    const was = this.get(uuid) !== null
    this.map.delete(uuid)
    return was
  }

  /** Entfernt den Serverwert aller Meldungen eines Nutzers (wenn er `shareServer` abschaltet). */
  stripServer(uuid: string): void {
    const e = this.map.get(uuid)
    if (!e) return
    for (const via of SOURCES) {
      const s = e[via]
      if (s?.game?.server !== undefined) {
        const { server: _drop, ...rest } = s.game
        s.game = rest
      }
    }
  }

  /**
   * Entfernt abgelaufene Meldungen und liefert die Nutzer, deren sichtbare
   * Präsenz sich dadurch geändert hat (→ Ereignisse), mit dem Spielzustand davor.
   */
  sweepChanges(): { uuid: string, wasInGame: boolean }[] {
    const t = this.now()
    const changed: { uuid: string, wasInGame: boolean }[] = []
    for (const [uuid, e] of this.map) {
      // Sicht vor dem Ablauf (so haben Freunde/Beobachter sie zuletzt gesehen).
      const prev = merge(e, -Infinity)
      if (!dropExpired(e, t)) continue
      const next = merge(e, t)
      if (!next) this.map.delete(uuid)
      if (differs(prev, next)) changed.push({ uuid, wasInGame: prev?.state === 'in-game' })
    }
    return changed
  }

  /** Wie {@link PresenceStore.sweepChanges}, nur die UUIDs. */
  sweep(): string[] {
    return this.sweepChanges().map((c) => c.uuid)
  }

  /** Anzahl der Nutzer mit gültiger Präsenz (räumt nicht auf – das macht der Sweep samt Ereignissen). */
  count(): number {
    const t = this.now()
    let n = 0
    for (const e of this.map.values()) {
      if (merge(e, t)) n++
    }
    return n
  }
}
