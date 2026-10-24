// Effekt-Fahrpläne je Szenen-Set: aus denselben Schnittpunkten wie das Bild, damit Ton und Bild nie auseinanderlaufen.
// Rein (ohne Web Audio) – `audio.ts` spielt die Ereignisse ab, Tests prüfen die Zeiten.

import { FOXDEN_BEAT, type FoxdenCues } from './foxden'
import type { FrostCues } from './frost'
import type { GraveyardCues } from './graveyard'
import type { IntroCoreCues, IntroScript } from './timeline'

export type SfxKind =
  | 'drone'
  | 'whoosh'
  | 'ignite'
  | 'click'
  | 'piston'
  | 'bats'
  | 'hiss'
  | 'squeak'
  | 'riser'
  | 'subHit'
  | 'shimmer'
  | 'resolve'
  | 'creak'
  | 'ghost'
  | 'chime'
  | 'crystal'
  | 'boing'
  | 'crackle'
  | 'yip'
  | 'rustle'
  | 'pop'
  | 'pluck'
  | 'gust'

export interface SfxEvent {
  t: number
  kind: SfxKind
  /** Dauer (Flächen, Wusch, Spannung). */
  dur?: number
  pan?: number
  level?: number
  /** Wusch nach unten (Sturzflug). */
  down?: boolean
  /** Tonhöhe (Hz) für gezupfte Töne. */
  note?: number
  /** Nur ohne Jingle (musikalische Effekte, die mit dem Jingle kollidieren würden). */
  solo?: boolean
}

/** Gemeinsamer Abschluss: Spannung → Stille → Schlag + Schimmer (+ Akkord, wenn kein Jingle). */
function finale(c: IntroCoreCues, out: SfxEvent[]) {
  out.push({ t: c.hush - 1.4, kind: 'riser', dur: 1.4 })
  out.push({ t: c.impact, kind: 'subHit' })
  out.push({ t: c.impact + 0.02, kind: 'shimmer' })
  out.push({ t: c.impact + 0.25, kind: 'resolve', dur: c.fadeOut + 0.5 - c.impact - 0.25 })
}

export function planDungeon(s: IntroScript): SfxEvent[] {
  const c = s.cues
  const out: SfxEvent[] = [{ t: 0, kind: 'drone', dur: c.fadeOut + 0.4 }]
  if (c.descent) out.push({ t: c.descent[0] + 0.1, kind: 'whoosh', dur: c.descent[1] - c.descent[0], level: 0.55, down: true })
  for (const tq of s.torches) if (tq.t >= 0) out.push({ t: tq.t, kind: 'ignite', pan: (tq.d % 7) / 7 - 0.4 })
  out.push({ t: c.wireStart, kind: 'click', level: 1.2 })
  out.push({ t: c.wireStart + 0.05, kind: 'whoosh', dur: 0.9, level: 0.25 })
  for (const r of s.repeaters) {
    out.push({ t: r.t, kind: 'click', pan: 0.15 })
    out.push({ t: r.t + 0.06, kind: 'click', pan: 0.15, level: 0.8 })
  }
  s.doors.forEach((d, i) => {
    out.push({ t: d.t, kind: 'piston', pan: i % 2 ? 0.3 : -0.3 })
    out.push({ t: d.t + 0.1, kind: 'whoosh', dur: 0.5, level: 0.18 })
  })
  if (s.cameo !== null) out.push({ t: s.cameo + 0.12, kind: 'hiss' })
  for (let i = 0; i < 4; i++) out.push({ t: c.hush + i * 0.08, kind: 'click', level: 0.5 + i * 0.1 })
  finale(c, out)
  return out.sort((a, b) => a.t - b.t)
}

export function planGraveyard(c: GraveyardCues): SfxEvent[] {
  const out: SfxEvent[] = [{ t: 0, kind: 'drone', dur: c.fadeOut + 0.4 }]
  const first = c.cam[0]!
  const last = c.cam[c.cam.length - 1]!
  out.push({ t: first[0] + 0.2, kind: 'whoosh', dur: Math.min(2.4, last[0] - first[0]), level: 0.3, down: true })
  c.lanterns.forEach(([t, idx]) => out.push({ t, kind: 'ignite', pan: (idx % 5) / 5 - 0.4 }))
  if (c.witch) out.push({ t: c.witch[0] + 0.2, kind: 'whoosh', dur: c.witch[1] - c.witch[0], level: 0.22 })
  if (c.ghost) out.push({ t: c.ghost[0] + 0.3, kind: 'ghost', dur: Math.min(1.6, c.ghost[1] - c.ghost[0]) })
  if (c.gate !== null) out.push({ t: c.gate, kind: 'creak', dur: 0.75 })
  for (const b of c.bats) out.push({ t: b, kind: 'bats', level: 1 })
  finale(c, out)
  return out.sort((a, b) => a.t - b.t)
}

export function planFrost(c: FrostCues): SfxEvent[] {
  const out: SfxEvent[] = [{ t: 0, kind: 'drone', dur: c.fadeOut + 0.4 }]
  const first = c.cam[0]!
  out.push({ t: first[0] + 0.3, kind: 'whoosh', dur: 2.2, level: 0.25, down: true })
  c.chimes.forEach((t, i) => out.push({ t, kind: 'chime', pan: i % 2 ? 0.5 : -0.5 }))
  c.crystals.forEach((t, i) => out.push({ t, kind: 'crystal', level: 0.6 + i * 0.12, pan: (i % 2 ? 1 : -1) * 0.4 }))
  // Pinguine: Bauchrutscher, Ausrutscher, Schneeball, Hüpfer
  c.slides.forEach((t, i) => {
    out.push({ t, kind: 'whoosh', dur: 0.6, level: 0.22, pan: -0.5 + i * 0.25 })
    out.push({ t: t + 0.7, kind: 'squeak', level: 0.7, pan: -0.3 + i * 0.2 })
  })
  if (c.slip !== null) {
    out.push({ t: c.slip, kind: 'squeak', level: 1 })
    for (let j = 0; j < 3; j++) out.push({ t: c.slip + j * 0.12 + 0.05, kind: 'boing', level: 0.6, pan: j * 0.2 })
  }
  if (c.snowball) out.push({ t: c.snowball[0], kind: 'whoosh', dur: c.snowball[1] - c.snowball[0], level: 0.12 })
  for (const h of c.hops) out.push({ t: h, kind: 'boing', level: 0.8 })
  finale(c, out)
  return out.sort((a, b) => a.t - b.t)
}

/** Dur-Pentatonik (C) für verspielte Zupfer: C5 D5 E5 G5 A5 C6 D6 E6. */
export const FOX_SCALE = [523.25, 587.33, 659.25, 783.99, 880.0, 1046.5, 1174.66, 1318.51] as const

export function planFoxden(c: FoxdenCues): SfxEvent[] {
  const out: SfxEvent[] = [{ t: 0, kind: 'drone', dur: c.fadeOut + 0.4 }]
  const first = c.cam[0]!
  if (first[2] < -100) out.push({ t: 0.25, kind: 'whoosh', dur: Math.max(0.8, c.fire - 0.3), level: 0.22, down: true })
  // Lagerfeuer: Aufflammen + Knistern; Laterne: leises Zünden
  if (c.fire >= 0) {
    out.push({ t: c.fire, kind: 'ignite', pan: -0.4, level: 1.2 })
    out.push({ t: c.fire + 0.05, kind: 'crackle', dur: c.impact - c.fire, pan: -0.4 })
  } else out.push({ t: 0, kind: 'crackle', dur: c.impact, pan: -0.4, level: 0.8 })
  if (c.lantern >= 0) out.push({ t: c.lantern, kind: 'ignite', pan: 0.3, level: 0.5 })
  // Junge purzeln heraus: Plopp + Fiepen, Zupfer aufwärts
  c.kits.forEach((t, i) => {
    if (t < 0) return
    out.push({ t, kind: 'pop', pan: -0.2 + i * 0.2 })
    out.push({ t: t + 0.12, kind: 'squeak', pan: -0.2 + i * 0.2, level: 0.6 })
    out.push({ t, kind: 'pluck', note: FOX_SCALE[2 + i * 2], pan: -0.2 + i * 0.2, solo: true })
  })
  // Süßbeeren abgelegt
  if (c.berries) for (let j = 0; j < 3; j++) out.push({ t: c.berries[1] + j * 0.07, kind: 'pop', level: 0.5 - j * 0.1, pan: 0.15 })
  // Pounce: Ducken (Rascheln), Absprung (Fiepen + Luft), Eintauchen in den Busch (Rascheln + Beeren ploppen)
  const [p0, p1, p2] = c.pounce
  if (p0 > 0) out.push({ t: p0, kind: 'rustle', dur: 0.25, level: 0.35, pan: 0.2 })
  out.push({ t: p1, kind: 'yip', pan: 0.2 })
  out.push({ t: p1 + 0.03, kind: 'whoosh', dur: p2 - p1, level: 0.32 })
  out.push({ t: p2, kind: 'rustle', dur: 0.6, level: 1, pan: 0.45 })
  for (let j = 0; j < 4; j++) out.push({ t: p2 + 0.04 + j * 0.06, kind: 'pop', level: 0.7 - j * 0.12, pan: 0.3 + j * 0.1 })
  out.push({ t: p2 + 0.05, kind: 'gust', dur: c.impact - p2 + 0.3 })
  finale(c, out)
  // Abschluss-Glockenspiel (nur ohne Jingle)
  ;[0, 2, 4, 5, 7].forEach((n, i) => out.push({ t: c.impact + 0.3 + i * FOXDEN_BEAT * 0.25, kind: 'pluck', note: FOX_SCALE[n], pan: -0.4 + i * 0.2, solo: true }))
  return out.sort((a, b) => a.t - b.t)
}
