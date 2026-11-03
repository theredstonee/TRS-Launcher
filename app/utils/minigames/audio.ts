// Ton der Minispiele: alles wird zur Laufzeit erzeugt, es gibt keine Sound-Dateien.

const STORAGE_KEY = 'trs.minigames.sound'

export class GameAudio {
  private ctx: AudioContext | null = null
  private master: GainNode | null = null
  private rollGain: GainNode | null = null
  private rollFilter: BiquadFilterNode | null = null
  private rollSource: AudioBufferSourceNode | null = null
  private timers = new Set<ReturnType<typeof setTimeout>>()
  enabled = true

  constructor() {
    try {
      this.enabled = localStorage.getItem(STORAGE_KEY) !== 'off'
    }
    catch {
      this.enabled = true
    }
  }

  setEnabled(on: boolean) {
    this.enabled = on
    try {
      localStorage.setItem(STORAGE_KEY, on ? 'on' : 'off')
    }
    catch {
      // Ohne Speicher gilt die Wahl nur für diese Sitzung.
    }
  }

  private audio(): AudioContext | null {
    if (!this.ctx) {
      try {
        this.ctx = new AudioContext()
        this.master = this.ctx.createGain()
        this.master.gain.value = 0.7
        this.master.connect(this.ctx.destination)
      }
      catch {
        return null
      }
    }
    if (this.ctx.state === 'suspended') void this.ctx.resume()
    return this.ctx
  }

  /** Muss aus einer Nutzeraktion heraus laufen, sonst bleibt der Ton stumm. */
  unlock() {
    this.audio()
  }

  tone(frequency: number, duration: number, type: OscillatorType = 'square', volume = 0.05, slide?: number) {
    const a = this.enabled ? this.audio() : null
    if (!a || !this.master) return
    const osc = a.createOscillator()
    const gain = a.createGain()
    osc.type = type
    osc.frequency.value = frequency
    if (slide) osc.frequency.exponentialRampToValueAtTime(slide, a.currentTime + duration)
    gain.gain.value = volume
    gain.gain.exponentialRampToValueAtTime(0.0001, a.currentTime + duration)
    osc.connect(gain).connect(this.master)
    osc.start()
    osc.stop(a.currentTime + duration)
  }

  noise(duration: number, volume: number, cutoff: number) {
    const a = this.enabled ? this.audio() : null
    if (!a || !this.master) return
    const n = Math.floor(a.sampleRate * duration)
    const buffer = a.createBuffer(1, n, a.sampleRate)
    const channel = buffer.getChannelData(0)
    for (let i = 0; i < n; i++) channel[i] = (Math.random() * 2 - 1) * (1 - i / n)
    const source = a.createBufferSource()
    const filter = a.createBiquadFilter()
    const gain = a.createGain()
    source.buffer = buffer
    filter.type = 'lowpass'
    filter.frequency.value = cutoff
    gain.gain.value = volume
    source.connect(filter).connect(gain).connect(this.master)
    source.start()
  }

  later(ms: number, play: () => void) {
    const timer = setTimeout(() => {
      this.timers.delete(timer)
      play()
    }, ms)
    this.timers.add(timer)
  }

  /** Rollgeräusch der Lore: läuft dauerhaft, Lautstärke und Höhe folgen dem Tempo. */
  roll(active: boolean, speed: number) {
    if (!active && !this.rollGain) return
    const a = this.audio()
    if (!a || !this.master) return
    if (!this.rollGain) {
      const n = a.sampleRate * 2
      const buffer = a.createBuffer(1, n, a.sampleRate)
      const channel = buffer.getChannelData(0)
      for (let i = 0; i < n; i++) channel[i] = Math.random() * 2 - 1
      this.rollSource = a.createBufferSource()
      this.rollSource.buffer = buffer
      this.rollSource.loop = true
      this.rollFilter = a.createBiquadFilter()
      this.rollFilter.type = 'lowpass'
      this.rollFilter.frequency.value = 200
      this.rollGain = a.createGain()
      this.rollGain.gain.value = 0
      this.rollSource.connect(this.rollFilter).connect(this.rollGain).connect(this.master)
      this.rollSource.start()
    }
    const on = active && this.enabled
    this.rollGain.gain.setTargetAtTime(on ? 0.035 + Math.min(0.075, speed / 1600) : 0, a.currentTime, 0.05)
    this.rollFilter!.frequency.setTargetAtTime(on ? 180 + speed * 7 : 200, a.currentTime, 0.05)
  }

  dispose() {
    for (const timer of this.timers) clearTimeout(timer)
    this.timers.clear()
    this.rollSource?.stop()
    void this.ctx?.close()
    this.ctx = null
    this.master = null
    this.rollGain = null
    this.rollFilter = null
    this.rollSource = null
  }
}
