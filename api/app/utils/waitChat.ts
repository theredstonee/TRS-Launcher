// Chat-Nachrichten im Musikvideo (einziger Text im Bild, immer „im Bild“: Monitor, Handy, Kometen, Mauer, Konsole).
// Lencraft fragt immer wieder „Wann?“, das Team antwortet. Zeitlich nur aus dem Zeitraster abgeleitet. Rein, ohne DOM.
import type { WaitSection, WaitTimeline } from '#shared/wait-song'

export type ChatFrom = 'fan' | 'team' | 'system'
export interface ChatEvent {
  at: number
  from: ChatFrom
  text: string
  section: WaitSection
  /** Laufende Nummer im Teil (für Platz/Farbe in der Szene). */
  n: number
}

export const ASK = [
  'ist creator schon fertig?', 'wann kommt das dashboard?', 'und jetzt?', 'yo?!', 'schon fertig?',
  'wie lange noch', 'heute noch?', 'kannst du mich früher freischalten?', 'nur ganz kurz reinschauen bitte',
  'ich hab gehört es ist fertig', 'ping', 'hallo??',
]
export const BURST = ['Wann?', 'wann wann wann', 'jetzt?', '?', '??', 'WANN', 'fertig??', 'Wann? 🚀', 'bitte 🙏', 'wann 😭', 'JETZT?', 'hallooo', '...', 'yo?!']
const TEAM = ['Bald.', 'Gleich.', 'Noch kurz …', 'Fast fertig.', 'Ich arbeite dran 🔧', 'Geduld ist auch ein Feature.', 'Kaffee ist alle, Code noch nicht.']
const OUTSIDE = ['darf ich rein?', 'die andern sind schon drin??', 'ich seh euch durchs fenster 👀', 'lasst mich rein 😭', 'hallo??', 'nur kurz!!']
export const FLOOD = ['Wann?', '?', 'wann', '??', 'Ist es fertig?', 'WANN', '?!', 'wann?', 'fertig?', '???', 'Wann?', 'jetzt?']

const SECTIONS_END = (tl: WaitTimeline): Record<WaitSection, number> => ({
  intro: tl.verse1, verse1: tl.pre1, pre1: tl.chorus1, chorus1: tl.verse2, verse2: tl.pre2, pre2: tl.chorus2,
  chorus2: tl.bridge, bridge: tl.final, final: tl.outro, outro: tl.end,
})

/** Alle Chat-Ereignisse des Lieds, nach Zeit sortiert. */
export function buildChat(tl: WaitTimeline, launch: number, fade: number): ChatEvent[] {
  const out: ChatEvent[] = []
  const ends = SECTIONS_END(tl)
  const beat = tl.beats ? 60 / tl.beats.bpm : 0.6
  const counter: Partial<Record<WaitSection, number>> = {}
  const add = (section: WaitSection, at: number, from: ChatFrom, text: string) => {
    const n = counter[section] ?? 0
    counter[section] = n + 1
    out.push({ at, from, text, section, n })
  }
  const span = (s: WaitSection, f: number) => tl[s] + (ends[s] - tl[s]) * f
  let a = 0
  let b = 0
  let tm = 0

  // Intro: nachts, das Handy summt.
  ;[['hey', 0.12], ['Wann?', 0.32], ['wann??', 0.5], ['ist creator schon fertig?', 0.7]].forEach(([text, f]) => add('intro', span('intro', f as number), 'fan', text as string))

  // Strophe 1: alle paar Sekunden eine Frage, ab und zu eine Antwort.
  const v1 = ends.verse1 - tl.verse1
  const n1 = Math.max(4, Math.round(v1 / 3.4))
  for (let i = 0; i < n1; i++) {
    const at = tl.verse1 + v1 * (0.04 + 0.86 * i / n1)
    add('verse1', at, 'fan', i === Math.floor(n1 * 0.45) ? 'yo?!' : ASK[a++ % ASK.length]!)
    if (i % 3 === 2) add('verse1', at + v1 * 0.4 / n1, 'team', TEAM[tm++ % TEAM.length]!)
  }

  // Vor dem Refrain: noch ein paar Fragen.
  add('pre1', span('pre1', 0.15), 'fan', '…')
  add('pre1', span('pre1', 0.45), 'fan', ASK[a % ASK.length]!)
  add('pre2', span('pre2', 0.2), 'fan', '???')

  // Refrains: „Wann?“ als Kometen, etwa jeder Schlag (im zweiten dichter), das Team antwortet.
  const comets = (s: WaitSection, every: number) => {
    const len = ends[s] - tl[s]
    for (let at = tl[s] + 0.6; at < tl[s] + len - 0.3; at += every) add(s, at, 'fan', BURST[b++ % BURST.length]!)
    add(s, span(s, 0.45), 'team', 'Freitag. 18 Uhr.')
    add(s, span(s, 0.8), 'team', 'Weil du so oft gefragt hast 😌')
  }
  comets('chorus1', beat)
  comets('chorus2', beat * 0.75)
  comets('final', beat * 0.75)

  // Strophe 2: Lencraft draußen vor der Tür, tippt aufs Handy.
  const v2 = ends.verse2 - tl.verse2
  const n2 = Math.max(4, Math.round(v2 / 3.2))
  for (let i = 0; i < n2; i++) add('verse2', tl.verse2 + v2 * (0.06 + 0.8 * i / n2), 'fan', OUTSIDE[i % OUTSIDE.length]!)
  add('verse2', span('verse2', 0.82), 'team', 'Hol dir Popcorn 🍿 Freitag.')

  // Brücke: jedes „Wann?“ wird ein Block der Mauer (erste Hälfte, je Schlag einer).
  const bridgeHalf = (ends.bridge - tl.bridge) * 0.5
  const nb = Math.max(6, Math.floor(bridgeHalf / beat))
  for (let i = 0; i < nb; i++) add('bridge', tl.bridge + (i + 0.5) * (bridgeHalf / nb), 'fan', FLOOD[i % FLOOD.length]!)

  // Outro: Countdown auf der Raketen-Konsole bis zum Start, dann Ruhe.
  const steps = ['10 …', '9 …', '8 …', '7 …', '6 …']
  const cd = Math.max(1, launch - tl.outro)
  steps.forEach((text, i) => add('outro', tl.outro + cd * (0.08 + i * 0.17), 'team', text))
  add('outro', launch, 'team', 'Um sechs am Abend ist es so weit 🚀')
  add('outro', fade + 0.8, 'fan', 'okay 😭')
  add('outro', Math.min(tl.end - 1, fade + 3.2), 'fan', 'ich frag nicht mehr. versprochen.')
  return out.sort((x, y) => x.at - y.at)
}
