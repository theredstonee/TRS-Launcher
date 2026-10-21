// Zeitplan der Start-Animation „Redstone-Dungeon“. Alle Schnittpunkte stehen hier als Sekunden – zum Nachschneiden
// auf einen Jingle nur diese Zahlen ändern (vor allem `impact` = Lampe zündet = Schlag im Jingle).
// Die Jingles (Redstone/Halloween ~136 BPM, ein Schlag = 0,441 s) liegen mit ihrem Schlag auf `impact`; Hebel, Türen
// und `hush` sitzen auf ganzen Schlägen davor (impact − k · 0,441 s). Alles andere
// (Klicks der Verstärker, Kamera, Auftritt im Seitengang) wird daraus abgeleitet und bleibt so immer synchron.

export type IntroLength = 'short' | 'medium' | 'long'
export const INTRO_LENGTHS = ['short', 'medium', 'long'] as const satisfies readonly IntroLength[]

/** Welt: Abstand entlang des Gangs (Blöcke). Der Gang läuft auf dem Bildschirm nach rechts oben in die Tiefe. */
export const WORLD = {
  /** Gangbreite (Spalten 0 … width−1), Leitung in der Mitte. */
  width: 5,
  wireCol: 2,
  /** Kolben-Türen quer über den Gang. */
  doors: [14, 26, 38],
  /** Verstärker auf der Leitung. */
  repeaters: [8, 20, 31, 44],
  /** Fackeln/Laternen an der Rückwand. */
  torches: [3, 9, 15, 21, 27, 33, 39, 45, 51],
  /** Nische in der Rückwand für den kurzen Auftritt (Creeper, Fledermäuse, Pinguin). */
  alcove: 29,
  /** Hohe Fenster (Mond bei Halloween, Himmel bei Winter). */
  windows: [23, 42],
  /** Redstone-Lampe am Ende des Gangs. */
  lamp: 50,
  /** Gangende (Abschlusswand). */
  end: 55,
} as const

/** Schnittpunkte, die jedes Szenen-Set hat (Logo-Enthüllung, Übergabe). */
export interface IntroCoreCues {
  /** Gesamtlänge inkl. Ausblenden (s). */
  duration: number
  /** Aus Schwarz einblenden bis … */
  fadeIn: number
  /** Kurze Stille vor dem Schlag. */
  hush: number
  /** Der große Schlag (Lampe/Laterne/Polarlicht zündet). Hier liegt der Schlag des Jingles. */
  impact: number
  /** Das Logo steht scharf in der Mitte. */
  logo: number
  /** Schriftzug „TRS Launcher“ erscheint. */
  wordmark: number
  /** Übergabe an den Launcher beginnt (Ausblenden bis `duration`). */
  fadeOut: number
}

export interface IntroCues extends IntroCoreCues {
  /** Sturzflug von oben in den Dungeon [Start, Ende] – nur die lange Fassung. */
  descent: [number, number] | null
  /** Fackeln zünden nacheinander [erste, letzte]; [0, 0] = brennen schon. */
  torches: [number, number]
  /** Hier beginnt die Leitung (Abstand im Gang). */
  from: number
  /** Hebel klickt, der Impuls läuft los. */
  wireStart: number
  /** Kolben-Türen schlagen auf (in Reihenfolge auf dem Weg; Anzahl = Türen hinter `from`). */
  doors: number[]
}

export const INTRO_CUES: Record<IntroLength, IntroCues> = {
  // ~4 s bei jedem Start: nur der Höhepunkt – letzte Tür, Lampe, Logo.
  short: {
    duration: 4,
    fadeIn: 0.3,
    descent: null,
    torches: [0, 0],
    from: 31,
    wireStart: 0.3,
    doors: [0.927],
    hush: 1.809,
    impact: 2.25,
    logo: 2.95,
    wordmark: 3.13,
    fadeOut: 3.57,
  },
  // ~7 s beim ersten Start des Tages: Fackeln zünden, zwei Türen, Auftritt im Seitengang.
  medium: {
    duration: 7,
    fadeIn: 0.45,
    descent: null,
    torches: [0.15, 1.15],
    from: 19,
    wireStart: 1.372,
    doors: [2.254, 3.577],
    hush: 4.459,
    impact: 4.9,
    logo: 5.65,
    wordmark: 5.78,
    fadeOut: 6.66,
  },
  // ~10 s nach einem Update: Sturzflug in den Dungeon, alle Fackeln, drei Türen, Auftritt, großes Finale.
  long: {
    duration: 10,
    fadeIn: 0.25,
    descent: [0, 2.7],
    torches: [1.5, 3.3],
    from: 1,
    wireStart: 2.608,
    doors: [4.372, 5.695, 6.577],
    hush: 7.459,
    impact: 7.9,
    logo: 8.65,
    wordmark: 8.78,
    fadeOut: 9.66,
  },
}

/** Reduzierte Bewegung: nur das Logo blendet weich ein und wieder aus. */
export const INTRO_REDUCED_DURATION = 1.8

/** Wartezeit des Impulses an einer Tür, bis sie offen ist (s). */
export const DOOR_WAIT = 0.12

export interface WireKey {
  t: number
  d: number
}

export interface IntroScript {
  length: IntroLength
  cues: IntroCues
  /** Stützpunkte des Impulses (Zeit → Abstand), stückweise linear. */
  wire: WireKey[]
  /** Türen auf dem Weg: Abstand + Zeitpunkt. */
  doors: { d: number; t: number }[]
  /** Verstärker auf dem Weg: Abstand + Klick-Zeitpunkt. */
  repeaters: { d: number; t: number }[]
  /** Fackeln: Abstand + Zündzeit (≤ 0 = brennt schon). */
  torches: { d: number; t: number }[]
  /** Auftritt in der Nische (Impuls läuft vorbei) oder `null`, wenn die Nische nicht auf dem Weg liegt. */
  cameo: number | null
}

/** Türen hinter dem Startpunkt. */
export const doorsAfter = (from: number) => WORLD.doors.filter((d) => d > from)

/** Baut aus den Schnittpunkten alle abgeleiteten Zeitpunkte (rein, testbar). */
export function buildScript(length: IntroLength, cues: IntroCues = INTRO_CUES[length]): IntroScript {
  const doorPos = doorsAfter(cues.from)
  const wire: WireKey[] = [{ t: cues.wireStart, d: cues.from }]
  const doors: { d: number; t: number }[] = []
  doorPos.forEach((d, i) => {
    const t = cues.doors[i] ?? cues.wireStart
    wire.push({ t, d: d - 0.5 })
    wire.push({ t: t + DOOR_WAIT, d: d - 0.5 })
    doors.push({ d, t })
  })
  wire.push({ t: cues.hush, d: WORLD.lamp - 0.5 })
  const repeaters = WORLD.repeaters.filter((d) => d > cues.from && d < WORLD.lamp).map((d) => ({ d, t: wireTimeAt(wire, d) }))
  const [t0, t1] = cues.torches
  const lit = WORLD.torches.filter((d) => d >= cues.from - 8)
  const torches = WORLD.torches.map((d) => {
    if (t1 <= 0) return { d, t: -1 }
    const i = lit.indexOf(d)
    if (i < 0) return { d, t: -1 }
    return { d, t: t0 + (lit.length > 1 ? (i / (lit.length - 1)) * (t1 - t0) : 0) }
  })
  const cameo = WORLD.alcove > cues.from && WORLD.alcove < WORLD.lamp ? wireTimeAt(wire, WORLD.alcove) : null
  return { length, cues, wire, doors, repeaters, torches, cameo }
}

/** Position des Impulses zur Zeit t (vor dem Start: `from`, danach: an der Lampe). */
export function wireHead(wire: readonly WireKey[], t: number): number {
  const first = wire[0]!
  if (t <= first.t) return first.d
  for (let i = 1; i < wire.length; i++) {
    const a = wire[i - 1]!
    const b = wire[i]!
    if (t <= b.t) return b.t === a.t ? b.d : a.d + ((b.d - a.d) * (t - a.t)) / (b.t - a.t)
  }
  return wire[wire.length - 1]!.d
}

/** Zeitpunkt, an dem der Impuls den Abstand d erreicht. */
export function wireTimeAt(wire: readonly WireKey[], d: number): number {
  for (let i = 1; i < wire.length; i++) {
    const a = wire[i - 1]!
    const b = wire[i]!
    if (d <= b.d && b.d > a.d) return a.t + ((Math.max(d, a.d) - a.d) / (b.d - a.d)) * (b.t - a.t)
  }
  return wire[wire.length - 1]!.t
}
