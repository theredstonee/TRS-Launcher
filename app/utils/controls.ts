// Touch-Steuerung der mobilen App: Layout-Format (gleich in Rust, Kotlin und
// Swift), Geometrie und reine Editor-Schritte – getestet in tests/controls.test.ts.
// Keine Nuxt-Auto-Imports, damit die Tests die Datei direkt laden können.
import { z } from 'zod'
import { t, type MessageKey } from './i18n'

export const LAYOUT_VERSION = 1
export const MAX_BUTTONS = 48
export const MAX_NAME_CHARS = 48
export const MAX_LABEL_CHARS = 12
export const MAX_CHORD = 3
export const MIN_SIZE = 0.02
export const MIN_OPACITY = 0.05
export const MIN_SENSITIVITY = 0.1
export const MAX_SENSITIVITY = 5
/** Code-Präfix beim Teilen (Rust packt/entpackt). */
export const CODE_PREFIX = 'TRSC1-'
export const BUILTIN_IDS = ['pvp', 'build', 'redstone'] as const
export type BuiltinId = (typeof BUILTIN_IDS)[number]
/** Gilt, wenn eine Instanz nichts gewählt hat. */
export const DEFAULT_ID: BuiltinId = 'pvp'
const EPS = 1e-6

// --- Schema -----------------------------------------------------------------------

export const profiles = ['pvp', 'build', 'redstone', 'custom'] as const
export const specials = ['keyboard', 'menu', 'trsMenu', 'emoteWheel', 'chat', 'hotbarSwipe', 'scrollUp', 'scrollDown'] as const
export const joystickModes = ['wasd', 'camera'] as const
export const showModes = ['game', 'menu', 'always'] as const
export const iconNames = [
  'attack', 'use', 'place', 'break', 'jump', 'sneak', 'sprint', 'inventory', 'chat', 'keyboard', 'menu', 'trs', 'emote',
  'pick', 'flyUp', 'flyDown', 'drop', 'perspective', 'zoom', 'debug', 'redstone', 'hotbar', 'prev', 'next', 'swap',
] as const

export type Profile = (typeof profiles)[number]
export type Special = (typeof specials)[number]
export type JoystickMode = (typeof joystickModes)[number]
export type ShowMode = (typeof showModes)[number]
export type ControlIcon = (typeof iconNames)[number]

const idRe = /^[a-z0-9][a-z0-9-]{0,39}$/
const buttonIdRe = /^[A-Za-z0-9_-]{1,32}$/
// eslint-disable-next-line no-control-regex
const controlRe = /[\u0000-\u001f\u007f-\u009f]/

/** GLFW-Tastencodes (druckbare Zeichen bis Menü-Taste). */
export const keyCodeSchema = z.number().int().min(32).max(348)
const chordSchema = z.array(keyCodeSchema).max(MAX_CHORD).refine((c) => new Set(c).size === c.length, 'chord')

export const actionSchema = z.discriminatedUnion('type', [
  z.object({ type: z.literal('key'), key: keyCodeSchema, chord: chordSchema.optional() }),
  z.object({ type: z.literal('mouse'), button: z.number().int().min(0).max(7), chord: chordSchema.optional() }),
  z.object({ type: z.literal('toggle'), key: keyCodeSchema }),
  z.object({ type: z.literal('joystick'), mode: z.enum(joystickModes) }),
  z.object({ type: z.literal('special'), special: z.enum(specials) }),
])
export type ControlAction = z.infer<typeof actionSchema>

const unit = z.number().min(-EPS).max(1 + EPS)
const size = z.number().min(MIN_SIZE - EPS).max(1 + EPS)

export const buttonSchema = z
  .object({
    id: z.string().regex(buttonIdRe),
    label: z
      .string()
      .max(MAX_LABEL_CHARS)
      .refine((l) => l.trim().length > 0 && !controlRe.test(l), 'label')
      .optional(),
    icon: z.enum(iconNames).optional(),
    x: unit,
    y: unit,
    w: size,
    h: size,
    opacity: z.number().min(MIN_OPACITY - EPS).max(1 + EPS).default(0.6),
    shape: z.enum(['round', 'rect']).default('round'),
    action: actionSchema,
    toggle: z.boolean().optional(),
    passThrough: z.boolean().optional(),
    show: z.enum(showModes).optional(),
  })
  .refine((b) => b.label !== undefined || b.icon !== undefined, 'label')
  .refine((b) => b.x + b.w <= 1 + EPS && b.y + b.h <= 1 + EPS, 'position')
  .refine((b) => !(b.action.type === 'key' && b.action.chord?.includes(b.action.key)), 'chord')
export type ControlButton = z.infer<typeof buttonSchema>

export const gesturesSchema = z.object({
  tapAttack: z.boolean(),
  holdUse: z.boolean(),
  swipeHotbar: z.boolean(),
  cameraSensitivity: z.number().min(MIN_SENSITIVITY).max(MAX_SENSITIVITY),
  haptics: z.boolean().default(true),
})
export type ControlGestures = z.infer<typeof gesturesSchema>

export const layoutSchema = z
  .object({
    version: z.literal(LAYOUT_VERSION),
    id: z.string().regex(idRe),
    name: z
      .string()
      .refine((n) => {
        const len = [...n.trim()].length
        return len > 0 && len <= MAX_NAME_CHARS && !controlRe.test(n)
      }, 'name'),
    profile: z.enum(profiles),
    builtinRev: z.number().int().nonnegative().optional(),
    buttons: z.array(buttonSchema).min(1).max(MAX_BUTTONS),
    gestures: gesturesSchema,
  })
  .refine((l) => new Set(l.buttons.map((b) => b.id)).size === l.buttons.length, 'duplicate')
export type ControlLayout = z.infer<typeof layoutSchema>

export const storedLayoutSchema = z.object({
  layout: layoutSchema,
  builtin: z.boolean(),
  modified: z.boolean(),
})
export type StoredLayout = z.infer<typeof storedLayoutSchema>

export function isBuiltinId(id: string): id is BuiltinId {
  return (BUILTIN_IDS as readonly string[]).includes(id)
}

/** Name in der Oberfläche: fertige Layouts übersetzt, eigene wie gespeichert. */
export function layoutName(stored: Pick<StoredLayout, 'layout' | 'builtin'>): string {
  const id = stored.layout.id
  return stored.builtin && isBuiltinId(id) ? t(builtinNames[id]) : stored.layout.name
}

const builtinNames: Record<BuiltinId, MessageKey> = {
  pvp: 'controls.builtin.pvp',
  build: 'controls.builtin.build',
  redstone: 'controls.builtin.redstone',
}

export const builtinHints: Record<BuiltinId, MessageKey> = {
  pvp: 'controls.builtin.pvpHint',
  build: 'controls.builtin.buildHint',
  redstone: 'controls.builtin.redstoneHint',
}

// --- Sichtbarkeit ----------------------------------------------------------------

/** Ohne `show`: Menü, Tastatur, Chat und TRS-Menü immer, der Rest nur im Spiel. */
export function defaultShow(action: ControlAction): ShowMode {
  if (action.type === 'special' && ['keyboard', 'menu', 'chat', 'trsMenu'].includes(action.special)) return 'always'
  if (action.type === 'key' && action.key === KEY.ESCAPE) return 'always'
  return 'game'
}

export function isVisible(button: ControlButton, grabbed: boolean): boolean {
  const show = button.show ?? defaultShow(button.action)
  return show === 'always' || (show === 'game') === grabbed
}

// --- Tasten -------------------------------------------------------------------------

export const KEY = {
  SPACE: 32,
  ESCAPE: 256,
  ENTER: 257,
  TAB: 258,
  BACKSPACE: 259,
  RIGHT: 262,
  LEFT: 263,
  DOWN: 264,
  UP: 265,
  F1: 290,
  LEFT_SHIFT: 340,
  LEFT_CONTROL: 341,
  LEFT_ALT: 342,
  RIGHT_SHIFT: 344,
} as const

/** Benannte Tasten mit übersetztem Namen. */
const namedKeys: Record<number, MessageKey> = {
  32: 'controls.keys.space',
  256: 'controls.keys.escape',
  257: 'controls.keys.enter',
  258: 'controls.keys.tab',
  259: 'controls.keys.backspace',
  262: 'controls.keys.right',
  263: 'controls.keys.left',
  264: 'controls.keys.down',
  265: 'controls.keys.up',
  340: 'controls.keys.leftShift',
  341: 'controls.keys.leftCtrl',
  342: 'controls.keys.leftAlt',
  344: 'controls.keys.rightShift',
  345: 'controls.keys.rightCtrl',
  346: 'controls.keys.rightAlt',
}

const symbolKeys: Record<number, string> = { 39: "'", 44: ',', 45: '-', 46: '.', 47: '/', 59: ';', 61: '=', 91: '[', 92: '\\', 93: ']', 96: '`' }

/** Anzeigename eines GLFW-Codes (`null` = unbekannt). */
export function keyName(code: number): string | null {
  if (namedKeys[code]) return t(namedKeys[code])
  if (code >= 65 && code <= 90) return String.fromCharCode(code)
  if (code >= 48 && code <= 57) return String.fromCharCode(code)
  if (code >= 290 && code <= 314) return `F${code - 289}`
  if (code >= 320 && code <= 329) return `Num ${code - 320}`
  return symbolKeys[code] ?? null
}

/** Tasten für die Auswahl im Editor (Reihenfolge = Anzeige). */
export const pickableKeys: number[] = [
  32, 340, 341, 342, 344, 345, 346, 256, 257, 258, 259, 265, 264, 263, 262,
  ...Array.from({ length: 26 }, (_, i) => 65 + i),
  ...Array.from({ length: 10 }, (_, i) => 48 + i),
  ...Array.from({ length: 12 }, (_, i) => 290 + i),
  39, 44, 45, 46, 47, 59, 61, 91, 92, 93, 96,
]

export const mouseButtonKeys: MessageKey[] = ['controls.mouse.left', 'controls.mouse.right', 'controls.mouse.middle']

const specialKeys: Record<Special, MessageKey> = {
  keyboard: 'controls.special.keyboard',
  menu: 'controls.special.menu',
  trsMenu: 'controls.special.trsMenu',
  emoteWheel: 'controls.special.emoteWheel',
  chat: 'controls.special.chat',
  hotbarSwipe: 'controls.special.hotbarSwipe',
  scrollUp: 'controls.special.scrollUp',
  scrollDown: 'controls.special.scrollDown',
}

export function specialName(s: Special): string {
  return t(specialKeys[s])
}

function mouseName(button: number): string {
  const key = mouseButtonKeys[button]
  return key ? t(key) : t('controls.mouse.other', { n: button + 1 })
}

/** Kurzbeschreibung einer Aktion („F3 + G“, „Rechte Maustaste“, …). */
export function actionText(action: ControlAction): string {
  const withChord = (main: string, chord?: number[]) =>
    [...(chord ?? []).map((k) => keyName(k) ?? `#${k}`), main].join(' + ')
  switch (action.type) {
    case 'key':
      return withChord(keyName(action.key) ?? `#${action.key}`, action.chord)
    case 'mouse':
      return withChord(mouseName(action.button), action.chord)
    case 'toggle':
      return t('controls.action.toggleOf', { key: keyName(action.key) ?? `#${action.key}` })
    case 'joystick':
      return action.mode === 'wasd' ? t('controls.action.joystickMove') : t('controls.action.joystickCamera')
    case 'special':
      return specialName(action.special)
  }
}

// --- Pixel-Symbole (8×8, gleich in Kotlin und Swift) --------------------------------

export const ICON_SIZE = 8

export const controlIcons: Record<ControlIcon, readonly string[]> = {
  attack: ['......##', '.....###', '....###.', '#..###..', '##.##...', '.###....', '.##.#...', '#....#..'],
  use: ['..#.#...', '.##.##..', '.##.##.#', '.######.', '.######.', '..#####.', '..####..', '..####..'],
  place: ['########', '#......#', '#..##..#', '#.####.#', '#.####.#', '#..##..#', '#......#', '########'],
  break: ['.######.', '#..##..#', '...##...', '...##...', '...##...', '...##...', '...##...', '...##...'],
  jump: ['...##...', '..####..', '.######.', '########', '...##...', '...##...', '...##...', '...##...'],
  sneak: ['...##...', '...##...', '...##...', '...##...', '########', '.######.', '..####..', '...##...'],
  sprint: ['#...#...', '##..##..', '.##..##.', '..##..##', '..##..##', '.##..##.', '##..##..', '#...#...'],
  inventory: ['########', '#......#', '#......#', '########', '#..##..#', '#......#', '#......#', '########'],
  chat: ['########', '#......#', '#.#.##.#', '#......#', '########', '.##.....', '.#......', '........'],
  keyboard: ['........', '########', '#.#.#.##', '########', '##.#.#.#', '########', '#.####.#', '########'],
  menu: ['........', '.##..##.', '.##..##.', '.##..##.', '.##..##.', '.##..##.', '.##..##.', '........'],
  trs: ['....###.', '...###..', '..###...', '.######.', '...###..', '..###...', '.##.....', '.#......'],
  emote: ['.######.', '#......#', '#.#..#.#', '#......#', '#.#..#.#', '#..##..#', '#......#', '.######.'],
  pick: ['...##...', '...##...', '........', '##.##.##', '##.##.##', '........', '...##...', '...##...'],
  flyUp: ['...##...', '..####..', '.##..##.', '##....##', '...##...', '..####..', '.##..##.', '##....##'],
  flyDown: ['##....##', '.##..##.', '..####..', '...##...', '##....##', '.##..##.', '..####..', '...##...'],
  drop: ['...##...', '...##...', '.#.##.#.', '..####..', '...##...', '#......#', '#......#', '########'],
  perspective: ['........', '..####..', '.#....#.', '#..##..#', '#..##..#', '.#....#.', '..####..', '........'],
  zoom: ['.####...', '#....#..', '#....#..', '#....#..', '.####...', '....##..', '.....##.', '......##'],
  debug: ['#.#..#.#', '.#.##.#.', '..####..', '#.####.#', '.######.', '#.####.#', '..####..', '.#....#.'],
  redstone: ['...##...', '..####..', '..####..', '...##...', '...##...', '...##...', '...##...', '...##...'],
  hotbar: ['........', '........', '########', '#..#..##', '#..#..##', '########', '........', '........'],
  prev: ['.....##.', '....##..', '...##...', '..##....', '..##....', '...##...', '....##..', '.....##.'],
  next: ['.##.....', '..##....', '...##...', '....##..', '....##..', '...##...', '..##....', '.##.....'],
  swap: ['..#.....', '.##.....', '########', '.##.....', '.....##.', '########', '.....##.', '.....#..'],
}

/** Gesetzte Pixel eines Symbols als [x, y]. */
export function iconPixels(icon: ControlIcon): [number, number][] {
  const out: [number, number][] = []
  controlIcons[icon].forEach((row, y) => [...row].forEach((c, x) => c === '#' && out.push([x, y])))
  return out
}

// --- Geometrie ---------------------------------------------------------------------

/** Ränder (Notch, Gestenleiste) in Pixeln. */
export interface Insets {
  left: number
  top: number
  right: number
  bottom: number
}

export interface Rect {
  x: number
  y: number
  w: number
  h: number
}

export const noInsets: Insets = { left: 0, top: 0, right: 0, bottom: 0 }

/** Sichere Fläche in Pixeln – darauf beziehen sich die Anteile eines Layouts. */
export function safeRect(width: number, height: number, insets: Insets = noInsets): Rect {
  const w = Math.max(0, width - insets.left - insets.right)
  const h = Math.max(0, height - insets.top - insets.bottom)
  return { x: insets.left, y: insets.top, w, h }
}

/** Anteile → Pixel auf dem Bildschirm. */
export function toPx(b: Rect, safe: Rect): Rect {
  return { x: safe.x + b.x * safe.w, y: safe.y + b.y * safe.h, w: b.w * safe.w, h: b.h * safe.h }
}

/** Pixel → Anteile (Umkehrung von `toPx`). */
export function fromPx(px: Rect, safe: Rect): Rect {
  if (safe.w <= 0 || safe.h <= 0) return { x: 0, y: 0, w: MIN_SIZE, h: MIN_SIZE }
  return { x: (px.x - safe.x) / safe.w, y: (px.y - safe.y) / safe.h, w: px.w / safe.w, h: px.h / safe.h }
}

/** Runde Knöpfe sind Kreise: Durchmesser = kürzere Seite, mittig. */
export function circleOf(px: Rect): { cx: number; cy: number; r: number } {
  return { cx: px.x + px.w / 2, cy: px.y + px.h / 2, r: Math.min(px.w, px.h) / 2 }
}

/** Liegt der Punkt (Pixel) auf dem Knopf? Runde Knöpfe nur im Kreis. */
export function hitTest(button: Pick<ControlButton, 'x' | 'y' | 'w' | 'h' | 'shape'>, safe: Rect, px: number, py: number): boolean {
  const r = toPx(button, safe)
  if (button.shape === 'round') {
    const c = circleOf(r)
    // Etwas großzügiger als der Kreis: Finger treffen ungenau.
    return (px - c.cx) ** 2 + (py - c.cy) ** 2 <= (c.r * 1.1) ** 2
  }
  return px >= r.x && px <= r.x + r.w && py >= r.y && py <= r.y + r.h
}

// --- Editor -----------------------------------------------------------------------

/** Rasterweite in Anteilen (Einrasten beim Ziehen). */
export const GRID_STEP = 0.01

export function snap(value: number, step = GRID_STEP): number {
  return Math.round(value / step) * step
}

const round4 = (v: number) => Math.round(v * 10000) / 10000

/** Hält einen Knopf vollständig in der sicheren Fläche (und groß genug). */
export function clampRect(r: Rect): Rect {
  const w = Math.min(1, Math.max(MIN_SIZE, r.w))
  const h = Math.min(1, Math.max(MIN_SIZE, r.h))
  const x = Math.min(1 - w, Math.max(0, r.x))
  const y = Math.min(1 - h, Math.max(0, r.y))
  return { x: round4(x), y: round4(y), w: round4(w), h: round4(h) }
}

function patchButton(layout: ControlLayout, id: string, patch: (b: ControlButton) => ControlButton): ControlLayout {
  return { ...layout, buttons: layout.buttons.map((b) => (b.id === id ? patch(b) : b)) }
}

/** Verschiebt um (dx, dy) Anteile, optional am Raster. */
export function moveButton(layout: ControlLayout, id: string, dx: number, dy: number, grid: boolean): ControlLayout {
  return patchButton(layout, id, (b) => {
    let x = b.x + dx
    let y = b.y + dy
    if (grid) {
      x = snap(x)
      y = snap(y)
    }
    return { ...b, ...clampRect({ x, y, w: b.w, h: b.h }) }
  })
}

/** Ändert die Größe (rechte untere Ecke), optional am Raster. */
export function resizeButton(layout: ControlLayout, id: string, dw: number, dh: number, grid: boolean): ControlLayout {
  return patchButton(layout, id, (b) => {
    let w = b.w + dw
    let h = b.h + dh
    if (grid) {
      w = snap(w)
      h = snap(h)
    }
    // Erst die Größe an den Rand anpassen, Position bleibt.
    w = Math.min(w, 1 - b.x)
    h = Math.min(h, 1 - b.y)
    return { ...b, ...clampRect({ x: b.x, y: b.y, w, h }) }
  })
}

export function updateButton(layout: ControlLayout, id: string, patch: Partial<Omit<ControlButton, 'id'>>): ControlLayout {
  return patchButton(layout, id, (b) => ({ ...b, ...patch }))
}

export function removeButton(layout: ControlLayout, id: string): ControlLayout {
  return { ...layout, buttons: layout.buttons.filter((b) => b.id !== id) }
}

/** Freie Knopf-ID („b1“, „b2“, …). */
export function nextButtonId(layout: ControlLayout): string {
  const taken = new Set(layout.buttons.map((b) => b.id))
  let n = layout.buttons.length + 1
  while (taken.has(`b${n}`)) n++
  return `b${n}`
}

/** Neuer Knopf in der Mitte (leicht versetzt, falls dort schon einer liegt). */
export function addButton(layout: ControlLayout, action: ControlAction = { type: 'key', key: KEY.SPACE }): { layout: ControlLayout; id: string } {
  if (layout.buttons.length >= MAX_BUTTONS) return { layout, id: '' }
  const id = nextButtonId(layout)
  let x = 0.47
  let y = 0.42
  while (layout.buttons.some((b) => Math.abs(b.x - x) < 0.005 && Math.abs(b.y - y) < 0.005) && y < 0.8) {
    x += 0.02
    y += 0.04
  }
  const button: ControlButton = {
    id,
    label: buttonLabelFor(action),
    x,
    y,
    w: 0.06,
    h: 0.12,
    opacity: 0.6,
    shape: 'round',
    action,
  }
  return { layout: { ...layout, buttons: [...layout.buttons, button] }, id }
}

/** Kurze Beschriftung für einen neuen Knopf (passt in MAX_LABEL_CHARS). */
export function buttonLabelFor(action: ControlAction): string {
  const text = actionText(action)
  return [...text].slice(0, MAX_LABEL_CHARS).join('').trim() || '?'
}

/** Prüft ein Layout wie Rust; `null` = in Ordnung, sonst der erste Fehler. */
export function layoutIssue(layout: unknown): string | null {
  const r = layoutSchema.safeParse(layout)
  if (r.success) return null
  const issue = r.error.issues[0]
  return issue ? [issue.path.join('.'), issue.message].filter(Boolean).join(': ') : 'invalid'
}

/** Läuft der Launcher als mobile App (Android, iPhone, iPad)? */
export function isTouchApp(userAgent = globalThis.navigator?.userAgent ?? '', touchPoints = globalThis.navigator?.maxTouchPoints ?? 0): boolean {
  if (/Android|iPhone|iPad|iPod/i.test(userAgent)) return true
  // iPadOS meldet sich als Mac – erkennbar an mehreren Touch-Punkten.
  return /Macintosh/i.test(userAgent) && touchPoints > 1
}
