// Big-Picture-Modus (Fernseher, Steam Deck, Controller): reine Logik ohne Vue/DOM,
// damit Tests sie direkt laden können – räumliche Navigation zwischen Kacheln,
// Gamepad-Belegung (Standard-Mapping der Gamepad-API), Tastatur und Knopf-Hinweise.

export type NavDirection = 'up' | 'down' | 'left' | 'right'

/** Was eine Eingabe im Big-Picture-Modus bedeutet. */
export type BigAction = NavDirection | 'confirm' | 'back' | 'context' | 'menu' | 'prevSection' | 'nextSection'

/** Controller-Familie für die Knopf-Hinweise; `keyboard` = Tastatur/Maus. */
export type ControllerType = 'xbox' | 'playstation' | 'nintendo' | 'steamdeck' | 'generic' | 'keyboard'

/** Bereiche der Big-Picture-Ansicht, in der Reihenfolge von LB/RB. */
export const bigSections = ['home', 'instances', 'servers', 'friends', 'settings'] as const
export type BigSection = (typeof bigSections)[number]

export function isBigSection(value: unknown): value is BigSection {
  return typeof value === 'string' && (bigSections as readonly string[]).includes(value)
}

/** Nächster/vorheriger Bereich (LB/RB), läuft rundherum. */
export function cycleSection<T>(list: readonly T[], current: T, delta: number): T {
  if (!list.length) return current
  const i = list.indexOf(current)
  const from = i < 0 ? 0 : i
  return list[(((from + delta) % list.length) + list.length) % list.length]!
}

// --- Räumliche Navigation --------------------------------------------------------

export interface NavBox {
  left: number
  top: number
  width: number
  height: number
}

interface Edges {
  l: number
  r: number
  t: number
  b: number
  cx: number
  cy: number
}

function edges(b: NavBox): Edges {
  return { l: b.left, r: b.left + b.width, t: b.top, b: b.top + b.height, cx: b.left + b.width / 2, cy: b.top + b.height / 2 }
}

/** Abstand zweier Strecken (0, wenn sie sich überlappen). */
function gap(a0: number, a1: number, b0: number, b1: number): number {
  return Math.max(0, b0 - a1, a0 - b1)
}

/** Kleine Überlappungen (Ränder, Rundungen) zählen nicht als „daneben“. */
const SLOP = 2
/** Aufschlag für Kandidaten, die quer zur Richtung nicht überlappen. */
const BEAM_PENALTY = 40

/**
 * Wertung eines Kandidaten in Richtung `dir` – kleiner ist besser, `null` = liegt
 * nicht in dieser Richtung. Hauptachse: Abstand der Kanten; Querachse: Versatz
 * stark gewichtet, damit die Nachbarkachel in derselben Reihe/Spalte gewinnt.
 */
export function navScore(from: NavBox, to: NavBox, dir: NavDirection): number | null {
  const f = edges(from)
  const c = edges(to)
  let main: number
  let cross: number
  let centre: number
  if (dir === 'right' || dir === 'left') {
    const ahead = dir === 'right' ? c.l >= f.r - SLOP || (c.cx > f.cx && c.l > f.l) : c.r <= f.l + SLOP || (c.cx < f.cx && c.r < f.r)
    if (!ahead) return null
    main = dir === 'right' ? Math.max(0, c.l - f.r) : Math.max(0, f.l - c.r)
    cross = gap(f.t, f.b, c.t, c.b)
    centre = Math.abs(c.cy - f.cy)
  } else {
    const ahead = dir === 'down' ? c.t >= f.b - SLOP || (c.cy > f.cy && c.t > f.t) : c.b <= f.t + SLOP || (c.cy < f.cy && c.b < f.b)
    if (!ahead) return null
    main = dir === 'down' ? Math.max(0, c.t - f.b) : Math.max(0, f.t - c.b)
    cross = gap(f.l, f.r, c.l, c.r)
    centre = Math.abs(c.cx - f.cx)
  }
  // Außerhalb der Spur (keine Überlappung quer zur Richtung) kostet extra.
  return main + cross * 4 + (cross > 0 ? BEAM_PENALTY : 0) + centre * 0.05
}

/** Index des besten Nachbarn von `from` in Richtung `dir`, `-1` = keiner (kein Umlauf). */
export function spatialNext(from: NavBox, candidates: readonly NavBox[], dir: NavDirection): number {
  let best = -1
  let bestScore = Infinity
  candidates.forEach((box, i) => {
    if (box === from) return
    const score = navScore(from, box, dir)
    if (score !== null && score < bestScore) {
      best = i
      bestScore = score
    }
  })
  return best
}

/** Einstieg ohne Fokus: die Kachel oben links (erst Zeile, dann Spalte). */
export function firstInReadingOrder(candidates: readonly NavBox[]): number {
  let best = -1
  candidates.forEach((box, i) => {
    const b = candidates[best]
    if (!b || box.top < b.top - SLOP || (Math.abs(box.top - b.top) <= SLOP && box.left < b.left)) best = i
  })
  return best
}

// --- Gamepad ---------------------------------------------------------------------

/**
 * Standard-Mapping der Gamepad-API (W3C): Knöpfe nach Position, nicht nach
 * Aufdruck – 0 unten, 1 rechts, 2 links, 3 oben, 4/5 Schultertasten,
 * 8 Back/View/Select, 9 Start/Menu/Options, 12–15 Steuerkreuz.
 */
export const padButtons: Partial<Record<number, BigAction>> = {
  0: 'confirm',
  1: 'back',
  3: 'context',
  4: 'prevSection',
  5: 'nextSection',
  9: 'menu',
  12: 'up',
  13: 'down',
  14: 'left',
  15: 'right',
}

const directions: readonly BigAction[] = ['up', 'down', 'left', 'right']

/** Was ein Pad gerade gedrückt hält (mehrere Pads werden zusammengelegt). */
export interface PadSnapshot {
  buttons: readonly boolean[]
  axes: readonly number[]
}

/** Gehaltene Aktionen: Knöpfe plus linker Stick (nur die stärkere Achse, keine Diagonalen). */
export function heldActions(pad: PadSnapshot, deadzone = 0.5): Set<BigAction> {
  const held = new Set<BigAction>()
  pad.buttons.forEach((pressed, i) => {
    const action = padButtons[i]
    if (pressed && action) held.add(action)
  })
  const x = pad.axes[0] ?? 0
  const y = pad.axes[1] ?? 0
  if (Math.max(Math.abs(x), Math.abs(y)) >= deadzone) {
    if (Math.abs(x) >= Math.abs(y)) held.add(x < 0 ? 'left' : 'right')
    else held.add(y < 0 ? 'up' : 'down')
  }
  return held
}

/** Mehrere Pads zu einem: Knöpfe ODER-verknüpft, je Achse der stärkste Ausschlag. */
export function mergePads(pads: readonly PadSnapshot[]): PadSnapshot {
  const buttons: boolean[] = []
  const axes: number[] = []
  for (const pad of pads) {
    pad.buttons.forEach((b, i) => (buttons[i] = !!buttons[i] || b))
    pad.axes.forEach((a, i) => {
      if (Math.abs(a) > Math.abs(axes[i] ?? 0)) axes[i] = a
    })
  }
  for (let i = 0; i < buttons.length; i++) buttons[i] = !!buttons[i]
  return { buttons, axes }
}

export interface PadReaderOptions {
  deadzone?: number
  /** Ab wann ein gehaltenes Steuerkreuz wiederholt (ms). */
  repeatDelay?: number
  /** Abstand der Wiederholungen (ms). */
  repeatInterval?: number
}

/**
 * Wandelt Pad-Zustände (je Frame) in Ereignisse: ein Druck = eine Aktion,
 * Richtungen wiederholen sich beim Halten wie eine Taste.
 */
export function createPadReader(options: PadReaderOptions = {}) {
  const deadzone = options.deadzone ?? 0.5
  const delay = options.repeatDelay ?? 380
  const interval = options.repeatInterval ?? 110
  const pressedAt = new Map<BigAction, number>()
  const repeatedAt = new Map<BigAction, number>()
  let primed = false

  function update(pad: PadSnapshot, now: number): BigAction[] {
    const held = heldActions(pad, deadzone)
    const out: BigAction[] = []
    for (const action of [...pressedAt.keys()]) {
      if (!held.has(action)) {
        pressedAt.delete(action)
        repeatedAt.delete(action)
      }
    }
    for (const action of held) {
      const since = pressedAt.get(action)
      if (since === undefined) {
        pressedAt.set(action, now)
        // Was beim ersten Lesen schon gedrückt ist, war für etwas anderes gedacht (z. B. Spielstart).
        if (primed) out.push(action)
      } else if (directions.includes(action) && now - since >= delay) {
        const last = repeatedAt.get(action) ?? since
        if (now - last >= (last === since ? delay : interval)) {
          repeatedAt.set(action, now)
          out.push(action)
        }
      }
    }
    primed = true
    return out
  }

  function reset() {
    pressedAt.clear()
    repeatedAt.clear()
    primed = false
  }

  return { update, reset }
}

/** Controller-Familie aus `Gamepad.id` (Name plus Hersteller-/Produkt-ID). */
export function detectController(id: string): ControllerType {
  const s = id.toLowerCase()
  if (/steam deck|28de[^0-9a-f]*1205|vendor: 28de product: 1205/.test(s)) return 'steamdeck'
  if (/\b28de\b|steam (virtual )?(gamepad|controller)|valve/.test(s)) return 'steamdeck'
  if (/\b045e\b|xbox|xinput|x-box/.test(s)) return 'xbox'
  if (/\b054c\b|playstation|dualshock|dualsense|wireless controller|ps[345] /.test(s)) return 'playstation'
  if (/\b057e\b|nintendo|switch|pro controller|joy-con/.test(s)) return 'nintendo'
  return 'generic'
}

/** Glyphe eines Hinweises: Text, Farbe (Xbox-Buchstaben) bzw. Form (PlayStation). */
export interface ButtonGlyph {
  label: string
  /** `face` = runder Knopf, `shoulder` = Schultertaste, `key` = Tastatur, `menu` = Start/Options. */
  shape: 'face' | 'shoulder' | 'key' | 'menu'
  color?: string
}

type HintAction = Exclude<BigAction, NavDirection>

const xbox: Record<HintAction, ButtonGlyph> = {
  confirm: { label: 'A', shape: 'face', color: '#3fb950' },
  back: { label: 'B', shape: 'face', color: '#f85149' },
  context: { label: 'Y', shape: 'face', color: '#e3b341' },
  menu: { label: '☰', shape: 'menu' },
  prevSection: { label: 'LB', shape: 'shoulder' },
  nextSection: { label: 'RB', shape: 'shoulder' },
}

const glyphs: Record<ControllerType, Record<HintAction, ButtonGlyph>> = {
  xbox,
  generic: xbox,
  steamdeck: {
    ...xbox,
    confirm: { label: 'A', shape: 'face' },
    back: { label: 'B', shape: 'face' },
    context: { label: 'Y', shape: 'face' },
    prevSection: { label: 'L1', shape: 'shoulder' },
    nextSection: { label: 'R1', shape: 'shoulder' },
  },
  playstation: {
    confirm: { label: '✕', shape: 'face', color: '#7aa7ff' },
    back: { label: '○', shape: 'face', color: '#ff6b81' },
    context: { label: '△', shape: 'face', color: '#3ed3a3' },
    menu: { label: 'Options', shape: 'menu' },
    prevSection: { label: 'L1', shape: 'shoulder' },
    nextSection: { label: 'R1', shape: 'shoulder' },
  },
  // Standard-Mapping nach Position: unten ist bei Nintendo „B“, rechts „A“, oben „X“.
  nintendo: {
    confirm: { label: 'B', shape: 'face' },
    back: { label: 'A', shape: 'face' },
    context: { label: 'X', shape: 'face' },
    menu: { label: '+', shape: 'menu' },
    prevSection: { label: 'L', shape: 'shoulder' },
    nextSection: { label: 'R', shape: 'shoulder' },
  },
  keyboard: {
    confirm: { label: 'Enter', shape: 'key' },
    back: { label: 'Esc', shape: 'key' },
    context: { label: 'C', shape: 'key' },
    menu: { label: 'M', shape: 'key' },
    prevSection: { label: 'Q', shape: 'key' },
    nextSection: { label: 'E', shape: 'key' },
  },
}

export function buttonGlyph(type: ControllerType, action: HintAction): ButtonGlyph {
  return glyphs[type][action]
}

// --- Tastatur --------------------------------------------------------------------

/** Tastendruck → Aktion (Pfeile, Enter, Esc, C, M, Q/E, Bild auf/ab); `null` = nicht unsere. */
export function keyAction(e: Pick<KeyboardEvent, 'key' | 'ctrlKey' | 'altKey' | 'metaKey'>): BigAction | null {
  if (e.ctrlKey || e.altKey || e.metaKey) return null
  switch (e.key) {
    case 'ArrowUp':
      return 'up'
    case 'ArrowDown':
      return 'down'
    case 'ArrowLeft':
      return 'left'
    case 'ArrowRight':
      return 'right'
    case 'Enter':
    case ' ':
      return 'confirm'
    case 'Escape':
    case 'Backspace':
      return 'back'
    case 'c':
    case 'C':
    case 'ContextMenu':
      return 'context'
    case 'm':
    case 'M':
      return 'menu'
    case 'q':
    case 'Q':
    case 'PageUp':
      return 'prevSection'
    case 'e':
    case 'E':
    case 'PageDown':
      return 'nextSection'
    default:
      return null
  }
}

/** Taste zum Ein- und Ausschalten des Modus (kollidiert mit nichts im Launcher). */
export const BIG_PICTURE_KEY = 'F11'

/** Route der Big-Picture-Ansicht. */
export const BIG_PICTURE_PATH = '/big'

// --- Start und Speicher ----------------------------------------------------------

/** Beim Start öffnen: auf Wunsch immer, sonst auf Steam Deck/gamescope (abschaltbar). */
export function shouldOpenOnStart(ui: { bigPictureOnStart?: boolean; bigPictureAuto?: boolean } | undefined, consoleSession: boolean): boolean {
  if (!ui) return false
  return ui.bigPictureOnStart === true || (consoleSession && ui.bigPictureAuto !== false)
}

export const MEMORY_MIN_MB = 1024
export const MEMORY_STEP_MB = 512
export const MEMORY_MAX_MB = 32768

/** Arbeitsspeicher auf die Schritte des Schiebereglers runden und begrenzen. */
export function clampMemory(mb: number, max = MEMORY_MAX_MB): number {
  if (!Number.isFinite(mb)) return MEMORY_MIN_MB
  const top = Math.max(MEMORY_MIN_MB, Math.floor(max / MEMORY_STEP_MB) * MEMORY_STEP_MB)
  const snapped = Math.round(mb / MEMORY_STEP_MB) * MEMORY_STEP_MB
  return Math.min(top, Math.max(MEMORY_MIN_MB, snapped))
}
