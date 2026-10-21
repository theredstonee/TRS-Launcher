// Drehbuch des Musikvideos: welche Einstellung (Shot) zu welcher Liedzeit läuft und welche Übergänge an den
// Schnitten liegen. Rein und ohne DOM (getestet in tests/wait-video.test.ts). Alles hängt NUR am Zeitraster
// (timeline.json bzw. Platzhalter) – neu timen = JSON ändern.
import { WAIT_SECTIONS, sectionAt, type SectionAt, type WaitCue, type WaitSection, type WaitTimeline } from '#shared/wait-song'

export type ShotId =
  | 'night'
  | 'wide' | 'face' | 'screen' | 'wide2' | 'fuse' | 'boom'
  | 'phones' | 'gauge' | 'liftoff'
  | 'breach' | 'float' | 'comets'
  | 'street' | 'party' | 'outside' | 'clock'
  | 'limb' | 'orbit' | 'swarm'
  | 'wall' | 'rocket'
  | 'ignite' | 'stop' | 'breakout'
  | 'console' | 'launch' | 'friday' | 'door'

/** Anteil im Teil (0…1) oder ein Cue-Name. */
type Mark = number | WaitCue

const SHOTS: Record<WaitSection, [ShotId, Mark][]> = {
  intro: [['night', 0]],
  verse1: [['wide', 0], ['face', 0.2], ['screen', 0.38], ['wide2', 0.58], ['fuse', 0.78], ['boom', 0.9]],
  pre1: [['phones', 0], ['gauge', 0.38], ['liftoff', 0.68]],
  chorus1: [['breach', 0], ['float', 0.16], ['comets', 0.55]],
  verse2: [['street', 0], ['party', 0.28], ['outside', 0.52], ['clock', 0.8]],
  pre2: [['gauge', 0], ['liftoff', 0.55]],
  chorus2: [['limb', 0], ['orbit', 0.18], ['swarm', 0.58]],
  bridge: [['wall', 0], ['rocket', 0.5]],
  final: [['ignite', 0], ['stop', 'finalStop'], ['breakout', 'finalHit']],
  outro: [['console', 0], ['launch', 'launch'], ['friday', 'fade'], ['door', 'swell']],
}

/** Szene (Zeichen-Modul) je Shot. */
export type SceneId = 'room' | 'space' | 'street' | 'bridge' | 'launch' | 'finale'
export const SCENE_OF: Record<ShotId, SceneId> = {
  night: 'room', wide: 'room', face: 'room', screen: 'room', wide2: 'room', fuse: 'room', boom: 'room',
  phones: 'room', gauge: 'room', liftoff: 'room',
  breach: 'space', float: 'space', comets: 'space', limb: 'space', orbit: 'space', swarm: 'space',
  street: 'street', party: 'street', outside: 'street', clock: 'street',
  wall: 'bridge', rocket: 'bridge',
  ignite: 'launch', stop: 'launch', breakout: 'launch',
  console: 'finale', launch: 'finale', friday: 'finale', door: 'finale',
}

/** Ersatzwerte der Cues als Anteil eines Teils. */
const CUE_DEFAULT: Record<WaitCue, [WaitSection, number]> = {
  finalStop: ['final', 0.5],
  finalHit: ['final', 0.62],
  launch: ['outro', 0.27],
  fade: ['outro', 0.6],
  swell: ['outro', 0.85],
}

const sectionEnd = (tl: WaitTimeline, s: WaitSection) => {
  const i = WAIT_SECTIONS.indexOf(s)
  return i + 1 < WAIT_SECTIONS.length ? tl[WAIT_SECTIONS[i + 1]!] : tl.end
}

/** Länge eines Takts (4 Schläge) in Sekunden, `null` ohne Tempo. */
export const barLength = (tl: WaitTimeline) => (tl.beats ? 240 / tl.beats.bpm : null)

/** Zeit eines Cues (aus timeline.json oder geschätzt, auf den Takt gerastet). */
export function cueTime(tl: WaitTimeline, cue: WaitCue): number {
  const given = tl.cues?.[cue]
  if (given !== undefined) return given
  const [s, f] = CUE_DEFAULT[cue]
  return snapToBar(tl, tl[s] + (sectionEnd(tl, s) - tl[s]) * f, tl[s], sectionEnd(tl, s))
}

/** Rastet `t` auf die nächste Eins ein (nur mit Tempo, nur innerhalb lo…hi). */
export function snapToBar(tl: WaitTimeline, t: number, lo: number, hi: number): number {
  const bar = barLength(tl)
  if (!bar || !tl.beats) return t
  const k = Math.round((t - tl.beats.offset) / bar)
  const s = tl.beats.offset + k * bar
  return s > lo + 0.05 && s < hi - 0.05 && Math.abs(s - t) <= bar * 0.6 ? s : t
}

export interface ShotSpan {
  shot: ShotId
  section: WaitSection
  start: number
  end: number
}

/** Alle Shots des Lieds in Reihenfolge (lückenlos von 0 bis `end`). */
export function shotList(tl: WaitTimeline): ShotSpan[] {
  const out: ShotSpan[] = []
  for (const s of WAIT_SECTIONS) {
    const a = tl[s]
    const b = sectionEnd(tl, s)
    let prev = a
    const marks = SHOTS[s].map(([shot, m], i) => {
      let t = i === 0 ? a : typeof m === 'number' ? snapToBar(tl, a + (b - a) * m, a, b) : cueTime(tl, m)
      if (i > 0) t = Math.min(b - 0.01, Math.max(prev + 0.01, t))
      prev = t
      return { shot, t }
    })
    marks.forEach((m, i) => out.push({ shot: m.shot, section: s, start: m.t, end: i + 1 < marks.length ? marks[i + 1]!.t : b }))
  }
  return out
}

export interface Shot extends ShotSpan {
  scene: SceneId
  /** Sekunden seit Shot-Beginn. */
  t: number
  /** Fortschritt im Shot 0…1. */
  p: number
  sec: SectionAt
}

/** Shot zur Zeit `t` (vor 0 → erster, nach dem Ende → letzter mit p = 1). */
export function shotAt(list: readonly ShotSpan[], tl: WaitTimeline, t: number): Shot {
  let span = list[0]!
  for (let i = list.length - 1; i >= 0; i--) {
    if (t >= list[i]!.start) {
      span = list[i]!
      break
    }
  }
  const local = Math.max(0, t - span.start)
  return { ...span, scene: SCENE_OF[span.shot], t: local, p: Math.min(1, local / Math.max(0.001, span.end - span.start)), sec: sectionAt(tl, t) }
}

export type CutKind = 'flash' | 'whip' | 'whipDown' | 'fade' | 'cut' | 'white'

/** Übergang an jedem Teil-Anfang bzw. Cue. */
const CUTS: [WaitSection | WaitCue | 'end', CutKind][] = [
  ['verse1', 'flash'], ['pre1', 'cut'], ['chorus1', 'whip'], ['verse2', 'whipDown'], ['pre2', 'cut'],
  ['chorus2', 'whip'], ['bridge', 'fade'], ['final', 'flash'], ['finalHit', 'flash'], ['outro', 'fade'],
  ['launch', 'flash'], ['fade', 'fade'], ['swell', 'white'], ['end', 'fade'],
]

export interface Cut {
  at: number
  kind: CutKind
}

export function cutList(tl: WaitTimeline): Cut[] {
  return CUTS.map(([k, kind]) => ({
    at: k === 'end' ? tl.end : (WAIT_SECTIONS as readonly string[]).includes(k) ? tl[k as WaitSection] : cueTime(tl, k as WaitCue),
    kind,
  }))
}

const clamp01 = (v: number) => Math.min(1, Math.max(0, v))

export interface CutFx {
  /** Weißblitz 0…1. */
  flash: number
  /** Schwarzblende 0…1. */
  black: number
  /** Wischschwenk: Stärke −1…1 (negativ = nach unten). */
  whip: number
  /** Weiß-Überblendung 0…1 (warm). */
  white: number
}

/** Wirkung der Übergänge zur Zeit `t`. */
export function cutFx(cuts: readonly Cut[], t: number, end: number): CutFx {
  const fx: CutFx = { flash: 0, black: 0, whip: 0, white: 0 }
  for (const c of cuts) {
    const d = t - c.at
    if (c.kind === 'fade' && c.at >= end - 0.01) {
      // Ende: langsam nach Schwarz, danach bleibt es schwarz.
      fx.black = Math.max(fx.black, clamp01((d + 2.2) / 2.2))
      continue
    }
    if (Math.abs(d) > 2.5) continue
    switch (c.kind) {
      case 'flash':
        if (d >= -0.06 && d < 0.9) fx.flash = Math.max(fx.flash, d < 0 ? 1 + d / 0.06 : Math.exp(-d * 4.2))
        break
      case 'whip':
      case 'whipDown': {
        const s = c.kind === 'whip' ? 1 : -1
        const w = d < 0 ? Math.max(0, 1 + d / 0.4) ** 2 : Math.max(0, 1 - d / 0.45) ** 2
        if (Math.abs(w) > Math.abs(fx.whip)) fx.whip = w * s
        if (d >= 0 && d < 0.5) fx.flash = Math.max(fx.flash, 0.55 * Math.exp(-d * 6))
        break
      }
      case 'fade':
        if (d > -0.7 && d < 0.7) {
          fx.black = Math.max(fx.black, 1 - Math.abs(d) / 0.7)
        }
        break
      case 'white':
        if (d > -1.2 && d < 1.6) fx.white = Math.max(fx.white, d < 0 ? (1 + d / 1.2) ** 2 * 0.85 : 0.85 * Math.exp(-d * 1.6))
        break
      case 'cut':
        break
    }
  }
  return fx
}
