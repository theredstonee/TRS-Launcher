// Ton der Start-Animation: optionaler Jingle je Design/Länge plus eigene, mit Web Audio erzeugte Effekte
// (Tiefschlag, Redstone-Klicks, Kolben, Wusch, Zünd-Schimmer …), alle aus dem gleichen Zeitplan wie das Bild.
// Keine Dateien für die Effekte, kein Netzwerk. Fenster ohne Fokus → stumm (weich).

import type { IntroAudioStyle } from './designs'
import { hash } from './kit'
import type { SfxEvent } from './sfx'
import type { IntroCoreCues } from './timeline'

export interface IntroAudioOptions {
  style: IntroAudioStyle
  /** Schnittpunkte (für Jingle-Lage und Ausblenden). */
  cues: IntroCoreCues
  /** Effekt-Fahrplan des Szenen-Sets. */
  plan: SfxEvent[]
  /** Gesamtlautstärke 0…1. */
  volume?: number
  /** Jingle: URL oder Lader (liefert die Datei). Sein Schlag (`style.jingleHit`) wird auf `impact` gelegt. */
  jingle?: string | (() => Promise<ArrayBuffer>) | null
}

type Ctx = AudioContext

function audioCtor(): typeof AudioContext | null {
  const g = globalThis as { AudioContext?: typeof AudioContext; webkitAudioContext?: typeof AudioContext }
  return g.AudioContext ?? g.webkitAudioContext ?? null
}

export class IntroAudio {
  readonly ctx: Ctx | null
  private master: GainNode | null = null
  private sfx: GainNode | null = null
  private music: GainNode | null = null
  private verb: ConvolverNode | null = null
  private noiseBuf: AudioBuffer | null = null
  private startAt = 0
  private started = false
  private jingle: AudioBuffer | null = null
  private jingleSrc: AudioBufferSourceNode | null = null
  private focused = true
  private closing = false
  private readonly volume: number
  private readonly sources: AudioScheduledSourceNode[] = []

  constructor(private readonly o: IntroAudioOptions) {
    this.volume = o.volume ?? 0.8
    const C = audioCtor()
    let ctx: Ctx | null = null
    try {
      ctx = C ? new C({ latencyHint: 'interactive' }) : null
    } catch {
      ctx = null
    }
    this.ctx = ctx
    if (!ctx) return
    const comp = ctx.createDynamicsCompressor()
    comp.threshold.value = -14
    comp.knee.value = 8
    comp.ratio.value = 4
    comp.attack.value = 0.004
    comp.release.value = 0.2
    this.master = ctx.createGain()
    this.master.gain.value = this.volume
    this.master.connect(comp).connect(ctx.destination)
    this.sfx = ctx.createGain()
    this.sfx.gain.value = 1
    this.sfx.connect(this.master)
    this.music = ctx.createGain()
    this.music.gain.value = 0.95
    this.music.connect(this.master)
    this.verb = ctx.createConvolver()
    this.verb.buffer = this.impulse(2.6)
    const verbGain = ctx.createGain()
    verbGain.gain.value = 0.55
    this.verb.connect(verbGain).connect(this.sfx)
    const len = ctx.sampleRate * 2
    this.noiseBuf = ctx.createBuffer(1, len, ctx.sampleRate)
    const d = this.noiseBuf.getChannelData(0)
    for (let i = 0; i < len; i++) d[i] = Math.random() * 2 - 1
  }

  /** Jingle laden (bricht nie ab – fehlt er, laufen nur die Effekte). */
  async load(): Promise<boolean> {
    if (!this.ctx || !this.o.jingle) return false
    try {
      const src = this.o.jingle
      let buf: ArrayBuffer
      if (typeof src === 'string') {
        const res = await fetch(src)
        if (!res.ok) return false
        buf = await res.arrayBuffer()
      } else buf = await src()
      this.jingle = await this.ctx.decodeAudioData(buf)
      if (this.started) this.startJingle()
      return true
    } catch {
      return false
    }
  }

  get hasJingle() {
    return !!this.jingle
  }

  /** Läuft der Ton wirklich (Autoplay erlaubt)? */
  get running() {
    return this.ctx?.state === 'running'
  }

  /** Audio-Uhr in Animationssekunden (oder null, wenn kein Ton läuft). */
  time(): number | null {
    if (!this.ctx || !this.started || this.ctx.state !== 'running') return null
    return this.ctx.currentTime - this.startAt
  }

  /** Startet bei Animationszeit `t0` (Sekunden). */
  start(t0 = 0) {
    const ctx = this.ctx
    if (!ctx || this.started) return
    this.started = true
    if (ctx.state === 'suspended') void ctx.resume().catch(() => {})
    this.startAt = ctx.currentTime + 0.03 - t0
    this.schedule(t0)
    if (this.jingle) this.startJingle()
  }

  /** Nach einer Nutzergeste (Autoplay-Sperre) fortsetzen. */
  resume() {
    void this.ctx?.resume().catch(() => {})
  }

  setFocused(focused: boolean) {
    this.focused = focused
    this.applyGain(0.25)
  }

  private applyGain(ramp: number) {
    if (!this.ctx || !this.master) return
    const now = this.ctx.currentTime
    this.master.gain.cancelScheduledValues(now)
    this.master.gain.setValueAtTime(this.master.gain.value, now)
    this.master.gain.linearRampToValueAtTime(this.focused ? this.volume : 0, now + ramp)
  }

  /** Weich ausblenden und aufräumen. */
  stop(fade = 0.35) {
    const ctx = this.ctx
    if (!ctx || !this.master || this.closing) return
    this.closing = true
    const now = ctx.currentTime
    this.master.gain.cancelScheduledValues(now)
    this.master.gain.setValueAtTime(this.master.gain.value, now)
    this.master.gain.linearRampToValueAtTime(0, now + fade)
    setTimeout(() => {
      for (const s of this.sources) {
        try {
          s.stop()
        } catch {
          /* schon beendet */
        }
      }
      void ctx.close().catch(() => {})
    }, (fade + 0.1) * 1000)
  }

  // ───────────────────────── Jingle ─────────────────────────

  private startJingle() {
    const ctx = this.ctx
    if (!ctx || !this.jingle || !this.music || this.jingleSrc) return
    const c = this.o.cues
    const src = ctx.createBufferSource()
    src.buffer = this.jingle
    const g = ctx.createGain()
    src.connect(g).connect(this.music)
    // Dateizeit = Animationszeit + (Schlag in der Datei − impact)
    const shift = this.o.style.jingleHit - c.impact
    const now = ctx.currentTime
    const animNow = Math.max(0, now - this.startAt)
    const animStart = Math.max(animNow, -shift)
    const offset = animStart + shift
    const when = this.at(animStart)
    if (offset >= this.jingle.duration) return
    src.start(Math.max(now, when), offset)
    // Einblenden, wenn mitten im Stück begonnen wird; Ausblenden zur Übergabe
    const fin = offset > 0.05 ? 0.25 : 0.01
    g.gain.setValueAtTime(0.0001, Math.max(now, when))
    g.gain.linearRampToValueAtTime(1, Math.max(now, when) + fin)
    g.gain.setValueAtTime(1, this.at(c.fadeOut))
    g.gain.linearRampToValueAtTime(0.0001, this.at(c.duration + 0.15))
    this.jingleSrc = src
    this.sources.push(src)
    // Mit Jingle treten die Klangflächen zurück, Schläge bleiben
    this.sfx?.gain.setValueAtTime(0.6, now)
  }

  // ───────────────────────── Effekte ─────────────────────────

  private at(t: number) {
    return this.startAt + t
  }

  private schedule(t0: number) {
    const mood = this.o.style.mood
    for (const e of this.o.plan) {
      if (e.t < t0 - 0.01 && e.kind !== 'drone') continue
      const pan = e.pan ?? 0
      const level = e.level ?? 1
      switch (e.kind) {
        case 'drone':
          this.drone(Math.max(t0, e.t), e.t + (e.dur ?? 8), mood)
          break
        case 'whoosh':
          this.whoosh(e.t, e.dur ?? 0.6, 0.55 * level, !!e.down)
          break
        case 'ignite':
          this.ignite(e.t, pan, mood)
          break
        case 'click':
          this.click(e.t, pan, level)
          break
        case 'piston':
          this.piston(e.t, pan)
          break
        case 'bats':
          this.bats(e.t, level)
          break
        case 'hiss':
          this.hiss(e.t)
          break
        case 'squeak':
          this.squeak(e.t, pan, level)
          break
        case 'riser':
          this.riser(e.t, e.t + (e.dur ?? 1.4))
          break
        case 'subHit':
          this.subHit(e.t)
          break
        case 'shimmer':
          this.shimmer(e.t, mood)
          break
        case 'resolve':
          if (!this.o.jingle) this.resolve(e.t, e.t + (e.dur ?? 2), mood)
          break
        case 'creak':
          this.creak(e.t, e.dur ?? 0.75)
          break
        case 'ghost':
          this.ghost(e.t, e.dur ?? 1.4)
          break
        case 'chime':
          this.chime(e.t, pan, level)
          break
        case 'crystal':
          this.crystal(e.t, pan, level)
          break
        case 'boing':
          this.boing(e.t, pan, level)
          break
      }
    }
  }

  /** Knarrendes Friedhofstor. */
  private creak(t: number, dur: number) {
    const ctx = this.ctx!
    const a = this.at(t)
    const o = this.osc('sawtooth', 70)
    o.frequency.setValueAtTime(70, a)
    for (let i = 1; i <= 8; i++) o.frequency.linearRampToValueAtTime(70 + (i % 2 ? 40 : 10) + i * 6, a + (dur * i) / 8)
    const bp = ctx.createBiquadFilter()
    bp.type = 'bandpass'
    bp.frequency.setValueAtTime(700, a)
    bp.frequency.linearRampToValueAtTime(1300, a + dur)
    bp.Q.value = 6
    const g = ctx.createGain()
    g.gain.setValueAtTime(0.0001, a)
    g.gain.linearRampToValueAtTime(0.28, a + 0.06)
    g.gain.setValueAtTime(0.28, a + dur * 0.8)
    g.gain.linearRampToValueAtTime(0.0001, a + dur)
    o.connect(bp).connect(g)
    this.out(g, 0.2, 0.5)
    o.start(a)
    o.stop(a + dur + 0.05)
    // Anschlag des Torflügels
    const n = this.noise()
    const lp = ctx.createBiquadFilter()
    lp.type = 'lowpass'
    lp.frequency.value = 400
    const ng = ctx.createGain()
    this.env(ng, a + dur, 0.35, 0.002, 0.25)
    n.connect(lp).connect(ng)
    this.out(ng, 0.2, 0.6)
    n.start(a + dur, Math.random())
    n.stop(a + dur + 0.3)
  }

  /** Geisterheulen (leise, mit Vibrato). */
  private ghost(t: number, dur: number) {
    const ctx = this.ctx!
    const a = this.at(t)
    const o = this.osc('sine', 520)
    o.frequency.setValueAtTime(440, a)
    o.frequency.linearRampToValueAtTime(620, a + dur * 0.4)
    o.frequency.linearRampToValueAtTime(380, a + dur)
    const vib = this.osc('sine', 6)
    const vg = ctx.createGain()
    vg.gain.value = 14
    vib.connect(vg).connect(o.frequency)
    const g = ctx.createGain()
    g.gain.setValueAtTime(0.0001, a)
    g.gain.linearRampToValueAtTime(0.07, a + dur * 0.3)
    g.gain.linearRampToValueAtTime(0.0001, a + dur)
    o.connect(g)
    this.out(g, -0.3, 0.9)
    o.start(a)
    o.stop(a + dur + 0.05)
    vib.start(a)
    vib.stop(a + dur + 0.05)
  }

  /** Eiszapfen klingt (glockig, unharmonisch). */
  private chime(t: number, pan: number, level: number) {
    const ctx = this.ctx!
    const a = this.at(t)
    for (const [f, amp, dec] of [[1760, 0.07, 1.6], [2654, 0.04, 1.1], [4120, 0.025, 0.6], [5280, 0.015, 0.4]] as const) {
      const o = this.osc('sine', f * (1 + (Math.random() - 0.5) * 0.003))
      const g = ctx.createGain()
      this.env(g, a, amp * level, 0.002, dec)
      o.connect(g)
      this.out(g, pan, 0.7)
      o.start(a)
      o.stop(a + dec + 0.1)
    }
  }

  /** Kleiner Hüpfer (Pinguine): kurzer, weicher Ton nach oben. */
  private boing(t: number, pan: number, level: number) {
    const ctx = this.ctx!
    const a = this.at(t)
    const o = this.osc('triangle', 330)
    o.frequency.setValueAtTime(300, a)
    o.frequency.exponentialRampToValueAtTime(720, a + 0.09)
    o.frequency.exponentialRampToValueAtTime(520, a + 0.2)
    const g = ctx.createGain()
    this.env(g, a, 0.09 * level, 0.004, 0.2)
    o.connect(g)
    this.out(g, pan, 0.25)
    o.start(a)
    o.stop(a + 0.26)
  }

  /** Frostkristall wächst: gläsernes Aufglitzern. */
  private crystal(t: number, pan: number, level: number) {
    const ctx = this.ctx!
    const a = this.at(t)
    ;[2093, 2637, 3136, 4186].forEach((f, i) => {
      const o = this.osc('triangle', f)
      const g = ctx.createGain()
      this.env(g, a + i * 0.045, 0.035 * level, 0.003, 0.7)
      o.connect(g)
      this.out(g, pan + (i - 1.5) * 0.15, 0.8)
      o.start(a + i * 0.045)
      o.stop(a + i * 0.045 + 0.8)
    })
    const n = this.noise()
    const hp = ctx.createBiquadFilter()
    hp.type = 'highpass'
    hp.frequency.value = 6000
    const ng = ctx.createGain()
    this.env(ng, a, 0.06 * level, 0.01, 0.4)
    n.connect(hp).connect(ng)
    this.out(ng, pan, 0.5)
    n.start(a, Math.random())
    n.stop(a + 0.5)
  }

  private impulse(sec: number): AudioBuffer {
    const ctx = this.ctx!
    const len = Math.floor(ctx.sampleRate * sec)
    const buf = ctx.createBuffer(2, len, ctx.sampleRate)
    for (let ch = 0; ch < 2; ch++) {
      const d = buf.getChannelData(ch)
      for (let i = 0; i < len; i++) {
        const t = i / len
        d[i] = (Math.random() * 2 - 1) * (1 - t) ** 2.6 * (i < ctx.sampleRate * 0.012 ? 0.2 : 1)
      }
    }
    return buf
  }

  private noise(): AudioBufferSourceNode {
    const src = this.ctx!.createBufferSource()
    src.buffer = this.noiseBuf
    src.loop = true
    this.sources.push(src)
    return src
  }

  private osc(type: OscillatorType, f: number): OscillatorNode {
    const o = this.ctx!.createOscillator()
    o.type = type
    o.frequency.value = f
    this.sources.push(o)
    return o
  }

  private out(node: AudioNode, pan = 0, send = 0.3) {
    const ctx = this.ctx!
    const p = ctx.createStereoPanner()
    p.pan.value = Math.max(-1, Math.min(1, pan))
    node.connect(p)
    p.connect(this.sfx!)
    if (send > 0) {
      const sg = ctx.createGain()
      sg.gain.value = send
      p.connect(sg).connect(this.verb!)
    }
  }

  private env(g: GainNode, t: number, peak: number, attack: number, dec: number) {
    g.gain.setValueAtTime(0.0001, t)
    g.gain.linearRampToValueAtTime(peak, t + attack)
    g.gain.exponentialRampToValueAtTime(0.0001, t + attack + dec)
  }

  /** Tiefe Klangfläche (zwei verstimmte Sägezähne + Sub), je Stimmung eingefärbt. */
  private drone(t0: number, t1: number, mood: string) {
    const ctx = this.ctx!
    const a = this.at(t0)
    const b = this.at(t1)
    const lp = ctx.createBiquadFilter()
    lp.type = 'lowpass'
    lp.frequency.setValueAtTime(180, a)
    lp.frequency.linearRampToValueAtTime(520, b)
    lp.Q.value = 0.7
    const g = ctx.createGain()
    g.gain.setValueAtTime(0.0001, a)
    g.gain.linearRampToValueAtTime(0.22, a + 1.2)
    g.gain.setValueAtTime(0.22, Math.max(a + 1.2, b - 0.8))
    g.gain.linearRampToValueAtTime(0.0001, b)
    const root = mood === 'spooky' ? 49 : mood === 'frost' ? 58.3 : 55
    const fs = mood === 'spooky' ? [root, root * 1.06, root * 1.5] : [root, root * 1.004, root * 1.5]
    for (const f of fs) {
      const o = this.osc('sawtooth', f)
      o.connect(lp)
      o.start(a)
      o.stop(b + 0.1)
    }
    const sub = this.osc('sine', root / 2)
    const sg = ctx.createGain()
    sg.gain.value = 0.5
    sub.connect(sg).connect(lp)
    sub.start(a)
    sub.stop(b + 0.1)
    lp.connect(g)
    this.out(g, 0, 0.25)
    // Wind/Luft
    const n = this.noise()
    const bp = ctx.createBiquadFilter()
    bp.type = 'bandpass'
    bp.frequency.value = mood === 'frost' ? 1400 : 600
    bp.Q.value = 0.8
    const lfo = this.osc('sine', 0.25)
    const lg = ctx.createGain()
    lg.gain.value = mood === 'frost' ? 500 : 250
    lfo.connect(lg).connect(bp.frequency)
    const ng = ctx.createGain()
    ng.gain.setValueAtTime(0.0001, a)
    ng.gain.linearRampToValueAtTime(mood === 'frost' ? 0.07 : 0.035, a + 1)
    ng.gain.linearRampToValueAtTime(0.0001, b)
    n.connect(bp).connect(ng)
    this.out(ng, 0, 0.4)
    n.start(a)
    n.stop(b + 0.1)
    lfo.start(a)
    lfo.stop(b + 0.1)
  }

  /** Wusch: gefiltertes Rauschen mit Frequenzfahrt und Schwenk. */
  private whoosh(t: number, dur: number, peak: number, down: boolean) {
    const ctx = this.ctx!
    const a = this.at(t)
    const n = this.noise()
    const bp = ctx.createBiquadFilter()
    bp.type = 'bandpass'
    bp.Q.value = 1.4
    bp.frequency.setValueAtTime(down ? 2600 : 300, a)
    bp.frequency.exponentialRampToValueAtTime(down ? 220 : 2400, a + dur)
    const g = ctx.createGain()
    g.gain.setValueAtTime(0.0001, a)
    g.gain.linearRampToValueAtTime(peak, a + dur * 0.55)
    g.gain.exponentialRampToValueAtTime(0.0001, a + dur)
    const p = ctx.createStereoPanner()
    p.pan.setValueAtTime(-0.6, a)
    p.pan.linearRampToValueAtTime(0.6, a + dur)
    n.connect(bp).connect(g).connect(p)
    p.connect(this.sfx!)
    const sg = ctx.createGain()
    sg.gain.value = 0.3
    p.connect(sg).connect(this.verb!)
    n.start(a)
    n.stop(a + dur + 0.05)
  }

  /** Redstone-Klick (Verstärker/Hebel). */
  private click(t: number, pan: number, level: number) {
    const ctx = this.ctx!
    const a = this.at(t)
    const n = this.noise()
    const bp = ctx.createBiquadFilter()
    bp.type = 'bandpass'
    bp.frequency.value = 2600 + hash(Math.floor(t * 100)) * 800
    bp.Q.value = 3
    const g = ctx.createGain()
    this.env(g, a, 0.5 * level, 0.001, 0.035)
    n.connect(bp).connect(g)
    this.out(g, pan, 0.35)
    n.start(a, Math.random())
    n.stop(a + 0.06)
    const o = this.osc('square', 1650)
    const og = ctx.createGain()
    this.env(og, a, 0.08 * level, 0.001, 0.025)
    o.connect(og)
    this.out(og, pan, 0.2)
    o.start(a)
    o.stop(a + 0.05)
  }

  /** Fackel/Laterne zündet: weiches Fauchen (+ Glas-Klingeln bei Frost). */
  private ignite(t: number, pan: number, mood: string) {
    const ctx = this.ctx!
    const a = this.at(t)
    const n = this.noise()
    const lp = ctx.createBiquadFilter()
    lp.type = 'lowpass'
    lp.frequency.setValueAtTime(400, a)
    lp.frequency.exponentialRampToValueAtTime(2200, a + 0.12)
    lp.frequency.exponentialRampToValueAtTime(700, a + 0.4)
    const g = ctx.createGain()
    this.env(g, a, 0.22, 0.04, 0.38)
    n.connect(lp).connect(g)
    this.out(g, pan, 0.3)
    n.start(a, Math.random())
    n.stop(a + 0.5)
    if (mood === 'frost') {
      for (const [f, dt] of [[2637, 0], [3951, 0.05]] as const) {
        const o = this.osc('sine', f)
        const og = ctx.createGain()
        this.env(og, a + dt, 0.05, 0.002, 0.5)
        o.connect(og)
        this.out(og, pan, 0.6)
        o.start(a + dt)
        o.stop(a + dt + 0.6)
      }
    }
  }

  /** Kolben: Zischen, Holz-Schlag, metallisches Klonk und Tiefe. */
  private piston(t: number, pan: number) {
    const ctx = this.ctx!
    const a = this.at(t)
    const n = this.noise()
    const bp = ctx.createBiquadFilter()
    bp.type = 'bandpass'
    bp.frequency.setValueAtTime(900, a)
    bp.frequency.exponentialRampToValueAtTime(300, a + 0.15)
    bp.Q.value = 0.9
    const g = ctx.createGain()
    this.env(g, a, 0.6, 0.003, 0.18)
    n.connect(bp).connect(g)
    this.out(g, pan, 0.45)
    n.start(a, Math.random())
    n.stop(a + 0.3)
    const thump = this.osc('sine', 110)
    thump.frequency.setValueAtTime(120, a)
    thump.frequency.exponentialRampToValueAtTime(42, a + 0.25)
    const tg = ctx.createGain()
    this.env(tg, a, 0.75, 0.002, 0.32)
    thump.connect(tg)
    this.out(tg, pan * 0.3, 0.3)
    thump.start(a)
    thump.stop(a + 0.4)
    for (const f of [163, 241, 389]) {
      const o = this.osc('square', f * (0.98 + Math.random() * 0.04))
      const flt = ctx.createBiquadFilter()
      flt.type = 'bandpass'
      flt.frequency.value = 800
      flt.Q.value = 2
      const og = ctx.createGain()
      this.env(og, a + 0.01, 0.06, 0.002, 0.14)
      o.connect(flt).connect(og)
      this.out(og, pan, 0.5)
      o.start(a)
      o.stop(a + 0.2)
    }
    // Brocken fallen in den Abgrund (leises Poltern danach)
    for (let i = 0; i < 4; i++) {
      const tt = a + 0.25 + i * 0.13 + Math.random() * 0.05
      const r = this.noise()
      const lp = ctx.createBiquadFilter()
      lp.type = 'lowpass'
      lp.frequency.value = 500
      const rg = ctx.createGain()
      this.env(rg, tt, 0.12 / (i + 1), 0.002, 0.12)
      r.connect(lp).connect(rg)
      this.out(rg, pan + 0.3, 0.6)
      r.start(tt, Math.random())
      r.stop(tt + 0.2)
    }
  }

  /** Spannung: steigendes Rauschen + Ton bis zur Stille. */
  private riser(t0: number, t1: number) {
    const ctx = this.ctx!
    const a = this.at(t0)
    const b = this.at(t1)
    const n = this.noise()
    const hp = ctx.createBiquadFilter()
    hp.type = 'highpass'
    hp.frequency.setValueAtTime(300, a)
    hp.frequency.exponentialRampToValueAtTime(5000, b)
    const g = ctx.createGain()
    g.gain.setValueAtTime(0.0001, a)
    g.gain.exponentialRampToValueAtTime(0.28, b - 0.02)
    g.gain.linearRampToValueAtTime(0.0001, b + 0.01)
    n.connect(hp).connect(g)
    this.out(g, 0, 0.3)
    n.start(a)
    n.stop(b + 0.05)
    const o = this.osc('sawtooth', 110)
    o.frequency.setValueAtTime(110, a)
    o.frequency.exponentialRampToValueAtTime(440, b)
    const lp = ctx.createBiquadFilter()
    lp.type = 'lowpass'
    lp.frequency.value = 1200
    const og = ctx.createGain()
    og.gain.setValueAtTime(0.0001, a)
    og.gain.exponentialRampToValueAtTime(0.08, b - 0.02)
    og.gain.linearRampToValueAtTime(0.0001, b + 0.01)
    o.connect(lp).connect(og)
    this.out(og, 0, 0.2)
    o.start(a)
    o.stop(b + 0.05)
  }

  /** Großer Tiefschlag beim Zünden. */
  private subHit(t: number) {
    const ctx = this.ctx!
    const a = this.at(t)
    const o = this.osc('sine', 62)
    o.frequency.setValueAtTime(70, a)
    o.frequency.exponentialRampToValueAtTime(27, a + 1.4)
    const g = ctx.createGain()
    this.env(g, a, 1.0, 0.004, 1.6)
    o.connect(g)
    this.out(g, 0, 0.15)
    o.start(a)
    o.stop(a + 1.8)
    const n = this.noise()
    const hp = ctx.createBiquadFilter()
    hp.type = 'highpass'
    hp.frequency.value = 1800
    const ng = ctx.createGain()
    this.env(ng, a, 0.5, 0.001, 0.12)
    n.connect(hp).connect(ng)
    this.out(ng, 0, 0.9)
    n.start(a)
    n.stop(a + 0.2)
    const body = this.noise()
    const lp = ctx.createBiquadFilter()
    lp.type = 'lowpass'
    lp.frequency.value = 260
    const bg = ctx.createGain()
    this.env(bg, a, 0.7, 0.002, 0.5)
    body.connect(lp).connect(bg)
    this.out(bg, 0, 0.5)
    body.start(a)
    body.stop(a + 0.6)
  }

  /** Schimmer der Lampe: Obertöne mit Tremolo in den Hall. */
  private shimmer(t: number, mood: string) {
    const ctx = this.ctx!
    const a = this.at(t)
    const base = mood === 'spooky' ? [622, 932, 1244, 1865, 2489] : mood === 'frost' ? [698, 1047, 1397, 2093, 2794] : [659, 988, 1319, 1976, 2637]
    base.forEach((f, i) => {
      const o = this.osc('sine', f * (1 + (Math.random() - 0.5) * 0.004))
      const g = ctx.createGain()
      this.env(g, a + i * 0.03, 0.07 / (1 + i * 0.3), 0.02 + i * 0.02, 2.4)
      const trem = this.osc('sine', 5 + i)
      const tg = ctx.createGain()
      tg.gain.value = 0.3
      const amp = ctx.createGain()
      amp.gain.value = 0.7
      trem.connect(tg).connect(amp.gain)
      o.connect(amp).connect(g)
      this.out(g, (i % 2 ? 1 : -1) * 0.35, 0.9)
      o.start(a)
      o.stop(a + 2.8)
      trem.start(a)
      trem.stop(a + 2.8)
    })
  }

  /** Abschluss-Akkord (nur ohne Jingle). */
  private resolve(t0: number, t1: number, mood: string) {
    const ctx = this.ctx!
    const a = this.at(t0)
    const b = this.at(t1)
    const notes = mood === 'spooky' ? [196, 233, 294, 392] : mood === 'frost' ? [233, 294, 349, 466] : [220, 277, 330, 440]
    notes.forEach((f, i) => {
      const o = this.osc('triangle', f)
      const lp = ctx.createBiquadFilter()
      lp.type = 'lowpass'
      lp.frequency.value = 1400
      const g = ctx.createGain()
      g.gain.setValueAtTime(0.0001, a)
      g.gain.linearRampToValueAtTime(0.05, a + 0.4 + i * 0.05)
      g.gain.setValueAtTime(0.05, Math.max(a + 0.5, b - 0.6))
      g.gain.linearRampToValueAtTime(0.0001, b)
      o.connect(lp).connect(g)
      this.out(g, (i - 1.5) * 0.25, 0.8)
      o.start(a)
      o.stop(b + 0.1)
    })
  }

  /** Creeper-Zischen. */
  private hiss(t: number) {
    const ctx = this.ctx!
    const a = this.at(t)
    const n = this.noise()
    const hp = ctx.createBiquadFilter()
    hp.type = 'highpass'
    hp.frequency.value = 3200
    const g = ctx.createGain()
    g.gain.setValueAtTime(0.0001, a)
    g.gain.linearRampToValueAtTime(0.2, a + 0.1)
    g.gain.linearRampToValueAtTime(0.0001, a + 0.7)
    const trem = this.osc('sine', 18)
    const tg = ctx.createGain()
    tg.gain.value = 0.5
    const amp = ctx.createGain()
    amp.gain.value = 0.5
    trem.connect(tg).connect(amp.gain)
    n.connect(hp).connect(amp).connect(g)
    this.out(g, -0.4, 0.3)
    n.start(a)
    n.stop(a + 0.8)
    trem.start(a)
    trem.stop(a + 0.8)
  }

  /** Flatternde Fledermäuse. */
  private bats(t: number, level: number) {
    const ctx = this.ctx!
    for (let i = 0; i < 3; i++) {
      const a = this.at(t + i * 0.09)
      const n = this.noise()
      const bp = ctx.createBiquadFilter()
      bp.type = 'bandpass'
      bp.frequency.value = 1800 + i * 500
      bp.Q.value = 1.5
      const am = this.osc('square', 26 + i * 5)
      const ag = ctx.createGain()
      ag.gain.value = 0.5
      const amp = ctx.createGain()
      amp.gain.value = 0.5
      am.connect(ag).connect(amp.gain)
      const g = ctx.createGain()
      g.gain.setValueAtTime(0.0001, a)
      g.gain.linearRampToValueAtTime(0.12 * level, a + 0.08)
      g.gain.linearRampToValueAtTime(0.0001, a + 0.9)
      n.connect(bp).connect(amp).connect(g)
      const p = ctx.createStereoPanner()
      p.pan.setValueAtTime(-0.7 + i * 0.3, a)
      p.pan.linearRampToValueAtTime(0.8, a + 0.9)
      g.connect(p).connect(this.sfx!)
      n.start(a, Math.random())
      n.stop(a + 1)
      am.start(a)
      am.stop(a + 1)
    }
  }

  /** Pinguin-Piepser. */
  private squeak(t: number, pan = -0.3, level = 1) {
    const ctx = this.ctx!
    for (const [dt, f0, f1] of [[0, 1100, 1500], [0.16, 1200, 1700]] as const) {
      const a = this.at(t + dt)
      const o = this.osc('sine', f0)
      o.frequency.setValueAtTime(f0, a)
      o.frequency.exponentialRampToValueAtTime(f1, a + 0.08)
      const g = ctx.createGain()
      this.env(g, a, 0.08 * level, 0.005, 0.1)
      o.connect(g)
      this.out(g, pan, 0.3)
      o.start(a)
      o.stop(a + 0.15)
    }
  }
}
