// „Freitag um sechs am Abend“ – kleines Chiptune-Lied mit Karaoke-Zeilen für die persönliche Wartezeit im Creator-Dashboard.
// Alles wird im Browser mit der Web Audio API erzeugt (keine Datei, nichts von außen).

export interface SongLine {
  part: 'verse' | 'pre' | 'chorus' | 'bridge' | 'outro'
  text: string
}

export const SONG_TITLE = 'Freitag um sechs am Abend'

const PRE: SongLine[] = [
  { part: 'pre', text: 'Ich mach die Kopfhörer auf laut,' },
  { part: 'pre', text: 'dreh den Launcher auf Turbo und heb ab, so hoch es geht …' },
]
const CHORUS: SongLine[] = [
  { part: 'chorus', text: 'Und ich schwebe überm Server, so weit weg wie ein Astronaut,' },
  { part: 'chorus', text: 'hör dein „Wann, wann, wann?“ bis hoch ins All, so laut.' },
  { part: 'chorus', text: 'Weil du so oft gefragt hast, Freund, das hast du dir gebaut:' },
  { part: 'chorus', text: 'Für dich kommt’s erst Freitag um sechs am Abend – der Countdown läuft, schau!' },
]

export const SONG_LINES: SongLine[] = [
  { part: 'verse', text: 'Jeden Tag die gleiche Frage: „Ist es fertig, sag mal, wann?“' },
  { part: 'verse', text: 'Zehnmal morgens, zehnmal abends – und dann fängst du wieder an.' },
  { part: 'verse', text: 'Ich sitz hier zwischen Redstone-Kabeln, Kaffee kalt und Augen rot,' },
  { part: 'verse', text: 'und mein Discord blinkt im Sekundentakt – Lencraft schreibt schon wieder: „Yo?!“' },
  { part: 'verse', text: 'Der Code braucht noch ein bisschen Liebe, jede Zeile, jeder Knopf,' },
  { part: 'verse', text: 'doch du fragst so oft nach Updates – gleich explodiert mir wie ein Creeper der Kopf.' },
  ...PRE,
  ...CHORUS,
  { part: 'verse', text: 'Alle andern sind schon drinnen, klicken rum im Dashboard,' },
  { part: 'verse', text: 'nur bei dir dreht sich die Uhr – und sie dreht sich ziemlich hart.' },
  { part: 'verse', text: 'Die andern haben längst ihr Fan-Symbol, ihr Code ist live, ihr Umhang glänzt,' },
  { part: 'verse', text: 'und du stehst draußen vor der Tür, während drinnen schon die Party tanzt.' },
  { part: 'verse', text: 'Geduld ist auch ein Feature, das man trainieren kann,' },
  { part: 'verse', text: 'also hol dir Popcorn, Lencraft, und fang Freitag wieder an.' },
  ...PRE,
  ...CHORUS,
  { part: 'bridge', text: 'Jedes „Wann?“ ist noch ein Block mehr auf der Mauer,' },
  { part: 'bridge', text: 'jedes „Ist es fertig?“ macht das Warten nur noch länger – Bruder.' },
  { part: 'bridge', text: 'Doch ich bau dir die Rakete, Stück für Stück aus Redstone und aus Licht,' },
  { part: 'bridge', text: 'und wenn sie zündet, Freitag Abend – dann vergisst du diesen Moment nicht.' },
  ...CHORUS.slice(0, 3),
  { part: 'chorus', text: 'Freitag um sechs am Abend – dann bist du endlich drin, schau!' },
  { part: 'outro', text: 'Zehn, neun, acht … die Rakete steht bereit.' },
  { part: 'outro', text: 'Sieben, sechs … um sechs am Abend ist es so weit.' },
  { part: 'outro', text: 'Und bis dahin: nicht mehr fragen!' },
]

const BPM = 112
export const BEAT = 60 / BPM
const BAR = BEAT * 4
/** Vorspiel: 4 Takte, danach 2 Takte je Zeile, am Ende 2 Takte Ausklang. */
export const INTRO = BAR * 4
export const LINE = BAR * 2
export const SONG_LENGTH = INTRO + SONG_LINES.length * LINE + BAR * 2

/** Startzeit (Sekunden) einer Zeile. */
export const lineStart = (i: number) => INTRO + i * LINE

// Akkorde je Takt (Grundton als MIDI-Note, Dreiklang moll/dur)
type Chord = { root: number, minor: boolean }
const Am: Chord = { root: 57, minor: true }
const F: Chord = { root: 53, minor: false }
const C: Chord = { root: 48, minor: false }
const G: Chord = { root: 55, minor: false }
const Em: Chord = { root: 52, minor: true }
const VERSE = [Am, F, C, G]
const CHORUS_PROG = [F, C, G, Am]
const OUTRO = [F, G, C, C]
const PRE_PROG = [F, G, F, G]
const BRIDGE = [F, G, Em, Am]

const freq = (m: number) => 440 * 2 ** ((m - 69) / 12)
const triad = (c: Chord) => [c.root, c.root + (c.minor ? 3 : 4), c.root + 7]

function chordAt(bar: number): { chord: Chord, part: SongLine['part'] | 'intro' | 'end' } {
  const introBars = 4
  if (bar < introBars) return { chord: [Am, F, C, Em][bar % 4]!, part: 'intro' }
  const lineIdx = Math.floor((bar - introBars) / 2)
  const line = SONG_LINES[lineIdx]
  if (!line) return { chord: C, part: 'end' }
  const prog = line.part === 'chorus' ? CHORUS_PROG : line.part === 'outro' ? OUTRO : line.part === 'pre' ? PRE_PROG : line.part === 'bridge' ? BRIDGE : VERSE
  const barInSection = (bar - introBars) % 4
  return { chord: prog[barInSection]!, part: line.part }
}

/**
 * Spielt das Lied ab (Liedzeit 0 = AudioContext-Zeit `start`; liegt `start` in der Vergangenheit, beginnt es mitten drin).
 * Gibt eine Stopp-Funktion zurück.
 */
export function playWaitSong(ctx: AudioContext, start = ctx.currentTime + 0.1, dest: AudioNode = ctx.destination): () => void {
  const master = ctx.createGain()
  master.gain.value = 0.55
  const comp = ctx.createDynamicsCompressor()
  master.connect(comp).connect(dest)
  // Bereits vergangene Töne (Start mitten im Lied) nicht nachholen.
  const past = (t: number) => t < ctx.currentTime - 0.005
  const nodes: AudioScheduledSourceNode[] = []

  const note = (type: OscillatorType, f: number, t: number, len: number, vol: number, slideTo?: number) => {
    if (past(t)) return
    const o = ctx.createOscillator()
    const g = ctx.createGain()
    o.type = type
    o.frequency.setValueAtTime(f, t)
    if (slideTo) o.frequency.exponentialRampToValueAtTime(slideTo, t + len)
    g.gain.setValueAtTime(0, t)
    g.gain.linearRampToValueAtTime(vol, t + 0.01)
    g.gain.setTargetAtTime(0, t + len * 0.6, len * 0.25)
    o.connect(g).connect(master)
    o.start(t)
    o.stop(t + len + 0.3)
    nodes.push(o)
  }

  // Rauschen für Hi-Hat/Snare
  const noiseBuf = ctx.createBuffer(1, ctx.sampleRate * 0.3, ctx.sampleRate)
  const data = noiseBuf.getChannelData(0)
  for (let i = 0; i < data.length; i++) data[i] = Math.random() * 2 - 1
  const noise = (t: number, len: number, vol: number, hp: number) => {
    if (past(t)) return
    const s = ctx.createBufferSource()
    s.buffer = noiseBuf
    s.loop = true
    const f = ctx.createBiquadFilter()
    f.type = 'highpass'
    f.frequency.value = hp
    const g = ctx.createGain()
    g.gain.setValueAtTime(vol, t)
    g.gain.exponentialRampToValueAtTime(0.001, t + len)
    s.connect(f).connect(g).connect(master)
    s.start(t)
    s.stop(t + len + 0.05)
    nodes.push(s)
  }

  const totalBars = Math.ceil(SONG_LENGTH / BAR)
  for (let bar = 0; bar < totalBars; bar++) {
    const t0 = start + bar * BAR
    const { chord, part } = chordAt(bar)
    const tones = triad(chord)
    const drums = part !== 'intro' || bar >= 2

    // Bass: Grundton auf 1 und 3, Quinte auf 2+ und 4+
    for (let b = 0; b < 4; b++) {
      const m = b % 2 === 0 ? chord.root - 12 : chord.root - 5
      note('triangle', freq(m), t0 + b * BEAT, BEAT * 0.9, part === 'end' ? 0.25 : 0.42)
    }
    // Arpeggio (Achtel) – im Refrain eine Oktave höher und heller
    const arpOct = part === 'chorus' ? 24 : 12
    for (let e = 0; e < 8; e++) {
      const m = tones[[0, 1, 2, 1, 0, 2, 1, 2][e]!]! + arpOct
      note('square', freq(m), t0 + e * BEAT / 2, BEAT / 2 * 0.8, part === 'chorus' ? 0.07 : 0.05)
    }
    // Lead-Melodie in Strophe/Refrain/Outro: kleine Phrasen aus Akkordtönen
    if (part !== 'intro' && part !== 'end') {
      const hi = part === 'chorus' ? 24 : 12
      const phrase = part === 'chorus' ? [2, 2, 1, 0, 1, 2, 2, 1] : [0, 1, 2, 2, 1, 0, 1, 1]
      const rhythm = part === 'chorus' ? [1, 0.5, 0.5, 1, 1, 0.5, 0.5, 0] : [0.5, 0.5, 1, 0.5, 0.5, 1, 0, 0]
      let t = t0
      phrase.forEach((idx, k) => {
        const len = rhythm[k]! * BEAT
        if (len > 0) note('square', freq(tones[idx]! + hi), t, len * 0.95, part === 'chorus' ? 0.11 : 0.09)
        t += len || BEAT * 0.5
      })
    }
    if (drums && part !== 'end') {
      for (let b = 0; b < 4; b++) {
        const t = t0 + b * BEAT
        if (b % 2 === 0) note('sine', 140, t, 0.18, 0.9, 40) // Kick
        else noise(t, 0.16, 0.35, 1200) // Snare
        noise(t + BEAT / 2, 0.05, 0.12, 7000) // Hi-Hat
      }
    }
  }
  // Raketen-Sound am Schluss
  const tEnd = start + SONG_LENGTH - BAR * 2
  note('sawtooth', 80, tEnd, BAR * 2, 0.12, 900)
  noise(tEnd, BAR * 2, 0.2, 400)

  return () => {
    for (const n of nodes) {
      try { n.stop() } catch { /* schon gestoppt */ }
    }
    master.disconnect()
  }
}

/** Zeitraster des Platzhalter-Lieds (gleiches Format wie timeline.json, s. shared/wait-song.ts). */
export function fallbackTimeline() {
  const nth = (part: SongLine['part'], n: number) => {
    let seen = 0
    for (let i = 0; i < SONG_LINES.length; i++) {
      if (SONG_LINES[i]!.part !== part) continue
      if (i > 0 && SONG_LINES[i - 1]!.part === part) continue
      if (seen++ === n) return lineStart(i)
    }
    throw new Error(`no ${part} #${n}`)
  }
  return {
    intro: 0,
    verse1: nth('verse', 0),
    pre1: nth('pre', 0),
    chorus1: nth('chorus', 0),
    verse2: nth('verse', 1),
    pre2: nth('pre', 1),
    chorus2: nth('chorus', 1),
    bridge: nth('bridge', 0),
    final: nth('chorus', 2),
    outro: nth('outro', 0),
    end: SONG_LENGTH,
    beats: { bpm: BPM, offset: 0 },
  }
}
