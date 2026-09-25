// Takt der Pixel-Szenen. Eigene Klasse, damit sich Pausieren/Fortsetzen und
// Fehler im Tick ohne Canvas prüfen lassen (tests/redstone-loop.test.ts).
//
// Früher hing ein Fehler im Tick (z. B. im Umbau) bei jedem weiteren Tick an
// derselben Stelle – die Szene stand still, obwohl der Takt weiterlief.
// Jetzt: erster Fehler wird gemeldet, nach ein paar Fehlern in Folge baut
// `recover` die Szene neu auf; der Takt selbst läuft immer weiter.

/** Warum die Szene gerade steht. Läuft nur, wenn keiner der Gründe gilt. */
export type PauseReason = 'motion' | 'offscreen' | 'hidden'

export interface SceneLoopOptions {
  tick: () => void
  interval: number
  /** Szene neu aufbauen, wenn der Tick mehrmals hintereinander scheitert. */
  recover?: () => void
  /** Wie viele Fehler in Folge bis `recover` (Standard 3). */
  maxErrors?: number
  report?: (error: unknown) => void
  /** Austauschbar für Tests. */
  timers?: {
    setInterval: (fn: () => void, ms: number) => unknown
    clearInterval: (id: unknown) => void
  }
}

export class SceneLoop {
  private readonly paused = new Set<PauseReason>()
  private timer: unknown = null
  private errors = 0
  private reports = 0
  private disposed = false

  constructor(private readonly options: SceneLoopOptions) {}

  get running(): boolean {
    return this.timer !== null
  }

  /** Grund setzen (`true`) oder aufheben (`false`), danach Takt starten/stoppen. */
  set(reason: PauseReason, active: boolean) {
    if (active) this.paused.add(reason)
    else this.paused.delete(reason)
    this.sync()
  }

  has(reason: PauseReason): boolean {
    return this.paused.has(reason)
  }

  dispose() {
    this.disposed = true
    this.stop()
  }

  private sync() {
    if (this.disposed || this.paused.size > 0) this.stop()
    else this.start()
  }

  private start() {
    if (this.timer !== null) return
    const timers = this.options.timers ?? globalThis
    this.timer = timers.setInterval(this.run, this.options.interval)
  }

  private stop() {
    if (this.timer === null) return
    const timers = this.options.timers ?? globalThis
    timers.clearInterval(this.timer as ReturnType<typeof setInterval>)
    this.timer = null
  }

  /** Höchstens ein paar Meldungen – sonst flutet es die Konsole zehnmal pro Sekunde. */
  private report(error: unknown) {
    if (this.reports >= 3) return
    this.reports++
    ;(this.options.report ?? console.error)(error)
  }

  private readonly run = () => {
    try {
      this.options.tick()
      this.errors = 0
    } catch (error) {
      this.errors++
      this.report(error)
      if (this.errors >= (this.options.maxErrors ?? 3)) {
        this.errors = 0
        try {
          this.options.recover?.()
        } catch (again) {
          this.report(again)
        }
      }
    }
  }
}
