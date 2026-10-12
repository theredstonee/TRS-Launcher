import { watch, type Directive } from 'vue'
import { animateMini, MotionGlobalConfig } from 'motion-v'
import { sharedMotion, type MotionController } from './motion'

// Bewegung in der Oberfläche (Motion for Vue): kurz (150–300 ms), ease-out, kleine
// Wege, nur transform/opacity, nie endlos. Alles fragt `motionAllowed()` – die
// Einstellung „Animationen“ (siehe utils/motion.ts) und ein verstecktes Fenster
// (Spiel läuft, Launcher minimiert) schalten ab; dann steht alles sofort da.
//
// Einmal-Effekte (gestaffelter Auftritt, FLIP, Dialoge) laufen über `animateMini`
// (WAAPI, startet synchron mit `fill: both` – kein Aufblitzen im ersten Bild).
// Komponenten (`motion.*`, `AnimatePresence`, `layoutId`) folgen
// `MotionGlobalConfig.skipAnimations` (plugins/motion.client.ts).

// (Jede Konstante mit eigenem Kommentar darüber: der Auto-Import-Scanner
// verschluckt sonst die Zeile nach einem Array-Literal mit Kommas.)
/** Auftritte: schnell los, weich aus. */
export const MOTION_EASE = [0.2, 0.8, 0.2, 1] as const
/** Abgänge: beschleunigt hinaus. */
export const MOTION_EASE_IN = [0.4, 0, 1, 1] as const
/** Bottom-Sheets am Handy: schnell los, weich aus. */
export const MOTION_SHEET_EASE = [0.32, 0.72, 0, 1] as const
/** Dauern in Sekunden. */
export const MOTION_DURATION = { fast: 0.15, base: 0.22, flip: 0.24, sheet: 0.28 } as const

/** Höchstens so viele Elemente laufen gestaffelt ein – der Rest steht sofort da. */
export const STAGGER_MAX = 12
export const STAGGER_STEP = 0.03
/** FLIP: über so vielen Elementen wird gar nicht gemessen … */
export const FLIP_MEASURE_MAX = 400
/** … und höchstens so viele (sichtbare) bewegt. */
export const FLIP_ANIMATE_MAX = 40
/** Dialoge mit mehr Knoten verschwinden ohne Ausblenden (Kopie wäre zu teuer). */
export const GHOST_NODE_MAX = 2500

/** Motion-Komponenten (Layout, Präsenz) richten sich nach der Einstellung. */
export function syncMotionLibrary(reduced: boolean) {
  MotionGlobalConfig.skipAnimations = reduced
}

/** Hält Motion dauerhaft auf dem Stand der Einstellung; gibt das Abmelden zurück. */
export function bindMotionLibrary(motion: Pick<MotionController, 'reduced'> = sharedMotion()): () => void {
  return watch(motion.reduced, syncMotionLibrary, { immediate: true, flush: 'sync' })
}

/** Darf gerade animiert werden? Einstellung „Animationen“ + sichtbares Fenster. */
export function motionAllowed(): boolean {
  if (sharedMotion().reduced.value) return false
  if (typeof document === 'undefined') return false
  return document.visibilityState !== 'hidden'
}

type El = HTMLElement | SVGElement
type Keyframes = { opacity?: [number, number]; transform?: [string, string] }
type StyleKey = keyof Keyframes

interface PlayOptions {
  duration?: number
  delay?: number
  ease?: readonly [number, number, number, number]
  /** Inline-Werte am Ende nicht zurücksetzen (Geister-Kopie, wird danach entfernt). */
  keep?: boolean
}

// Wer hat das Element zuletzt animiert, und welche Inline-Werte hatte es vorher?
// Nur die letzte Animation räumt auf – eine abgelöste darf nichts zurücksetzen.
const owners = new WeakMap<Element, { token: number; saved: Partial<Record<StyleKey, string>> }>()

/**
 * Spielt Keyframes einmal ab. Motion schreibt am Ende den Zielwert inline –
 * danach stellen wir die vorherigen Inline-Werte wieder her, damit CSS (Hover,
 * Klassen) wieder greift.
 */
export function motionPlay(el: El, keyframes: Keyframes, options: PlayOptions = {}): Promise<void> {
  const { duration = MOTION_DURATION.base, delay = 0, ease = MOTION_EASE, keep = false } = options
  const style = el.style
  let rec = owners.get(el)
  if (!rec) {
    rec = { token: 0, saved: {} }
    owners.set(el, rec)
  }
  for (const key of Object.keys(keyframes) as StyleKey[]) {
    if (!(key in rec.saved)) rec.saved[key] = style[key]
  }
  const own = rec
  const token = ++own.token
  return new Promise<void>((resolve) => {
    let settled = false
    const done = () => {
      if (settled) return
      settled = true
      if (!keep && owners.get(el) === own && own.token === token) {
        for (const [key, value] of Object.entries(own.saved) as [StyleKey, string][]) style[key] = value
        owners.delete(el)
      }
      resolve()
    }
    try {
      animateMini(el, keyframes, { duration, delay, ease: [...ease] }).then(done, done)
    } catch {
      done()
      return
    }
    // Sicherheitsnetz, falls eine abgebrochene Animation nie fertig meldet.
    setTimeout(done, (duration + delay) * 1000 + 1000)
  })
}

/** Rechteck, in dem Elemente sichtbar sind (Fenster, optional geschnitten mit einem Scroll-Bereich). */
export interface Box {
  x: number
  y: number
  w: number
  h: number
}

function toBox(r: DOMRect): Box {
  return { x: r.left, y: r.top, w: r.width, h: r.height }
}

export function boxesOverlap(a: Box, b: Box): boolean {
  return a.x < b.x + b.w && a.x + a.w > b.x && a.y < b.y + b.h && a.y + a.h > b.y
}

function viewBox(clip?: Element | null): Box {
  const win: Box = { x: 0, y: 0, w: window.innerWidth, h: window.innerHeight }
  if (!clip) return win
  const c = toBox(clip.getBoundingClientRect())
  const x = Math.max(win.x, c.x)
  const y = Math.max(win.y, c.y)
  return { x, y, w: Math.max(0, Math.min(win.x + win.w, c.x + c.w) - x), h: Math.max(0, Math.min(win.y + win.h, c.y + c.h) - y) }
}

/**
 * Welche Elemente sollen gestaffelt einlaufen? Nur sichtbare, höchstens `max`;
 * unterhalb des Sichtbereichs ist Schluss (die Liste läuft nach unten weiter).
 */
export function pickStagger<E>(items: [E, Box][], view: Box, max = STAGGER_MAX): E[] {
  const out: E[] = []
  for (const [el, box] of items) {
    if (out.length >= max) break
    if (box.y > view.y + view.h) break
    if (box.w === 0 && box.h === 0) continue
    if (boxesOverlap(box, view)) out.push(el)
  }
  return out
}

/** Gestaffelter Auftritt der Kinder eines Rasters/einer Liste (erste sichtbare, höchstens 12). */
export function motionStaggerIn(container: Element, options: { selector?: string; max?: number } = {}) {
  if (!motionAllowed()) return
  const list = options.selector ? container.querySelectorAll(options.selector) : container.children
  if (!list.length) return
  const items: [El, Box][] = []
  // Nur so weit messen, wie es nötig ist (große Listen bleiben billig).
  const view = viewBox()
  for (const el of Array.from(list)) {
    if (!(el instanceof HTMLElement || el instanceof SVGElement)) continue
    const box = toBox(el.getBoundingClientRect())
    items.push([el, box])
    if (box.y > view.y + view.h || items.length > (options.max ?? STAGGER_MAX) * 4) break
  }
  pickStagger(items, view, options.max).forEach((el, i) => {
    void motionPlay(el, { opacity: [0, 1], transform: ['translateY(8px)', 'translateY(0px)'] }, { delay: i * STAGGER_STEP })
  })
}

/**
 * Direktive `v-stagger`: Kinder laufen beim Einhängen gestaffelt ein – und erneut,
 * wenn sich der gebundene Wert ändert (z. B. erste ID neuer Suchergebnisse).
 * `data-stagger-items="<Selektor>"` wählt tiefer liegende Elemente statt der Kinder.
 */
export const vStagger: Directive<HTMLElement, unknown> = {
  mounted(el) {
    motionStaggerIn(el, { selector: el.dataset.staggerItems })
  },
  updated(el, binding) {
    if (binding.value !== binding.oldValue) motionStaggerIn(el, { selector: el.dataset.staggerItems })
  },
}

// --- FLIP: Elemente gleiten an ihren neuen Platz (Filtern, Sortieren, Gruppen) ----

export type FlipSnapshot = Map<Element, Box>

export function flipMeasure(els: ArrayLike<Element>): FlipSnapshot | null {
  if (!els.length || els.length > FLIP_MEASURE_MAX) return null
  const snap: FlipSnapshot = new Map()
  for (const el of Array.from(els)) snap.set(el, toBox(el.getBoundingClientRect()))
  return snap
}

export interface FlipPlan<E> {
  moves: { el: E; dx: number; dy: number }[]
  enters: E[]
}

/** Wer bewegt sich wie weit, wer ist neu? Nur Sichtbares, höchstens `max` Elemente. */
export function flipPlan<E>(before: Map<E, Box>, after: [E, Box][], view: Box, max = FLIP_ANIMATE_MAX): FlipPlan<E> {
  const plan: FlipPlan<E> = { moves: [], enters: [] }
  for (const [el, box] of after) {
    if (plan.moves.length + plan.enters.length >= max) break
    if (box.w === 0 && box.h === 0) continue
    const old = before.get(el)
    if (!boxesOverlap(box, view) && !(old && boxesOverlap(old, view))) continue
    if (!old) {
      plan.enters.push(el)
      continue
    }
    const dx = Math.round(old.x - box.x)
    const dy = Math.round(old.y - box.y)
    if (dx || dy) plan.moves.push({ el, dx, dy })
  }
  return plan
}

export type FlipEnter = 'pop' | 'fade' | false

export function flipPlay(before: FlipSnapshot | null, els: ArrayLike<Element>, options: { enter?: FlipEnter; clip?: Element | null } = {}) {
  // Beim ersten Befüllen (vorher leer) übernimmt `v-stagger`.
  if (!before?.size || !motionAllowed() || els.length > FLIP_MEASURE_MAX) return
  const after: [Element, Box][] = Array.from(els).map((el) => [el, toBox(el.getBoundingClientRect())])
  const plan = flipPlan(before, after, viewBox(options.clip))
  for (const { el, dx, dy } of plan.moves) {
    void motionPlay(el as El, { transform: [`translate(${dx}px, ${dy}px)`, 'translate(0px, 0px)'] }, { duration: MOTION_DURATION.flip })
  }
  if (!options.enter) return
  for (const el of plan.enters) {
    void motionPlay(
      el as El,
      options.enter === 'pop' ? { opacity: [0, 1], transform: ['scale(0.96)', 'scale(1)'] } : { opacity: [0, 1] },
      { duration: MOTION_DURATION.base },
    )
  }
}

/** Aktiv-Markierung in Seitenleiste/Tab-Leiste gleitet zum neuen Eintrag (`layoutId`). */
export const NAV_SIGNAL_TRANSITION = { duration: MOTION_DURATION.flip, ease: [...MOTION_EASE] }

// --- Toasts (Motion-Komponenten mit `layout`) ---------------------------------

export type ToastSide = 'left' | 'right' | 'top' | 'bottom'

/** Toasts gleiten von ihrer Seite herein und dorthin wieder hinaus. */
export function toastMotion(side: ToastSide) {
  const off = side === 'top' ? { y: -16 } : side === 'bottom' ? { y: 16 } : { x: side === 'left' ? -24 : 24 }
  return {
    initial: { opacity: 0, ...off },
    animate: { opacity: 1, x: 0, y: 0 },
    exit: { opacity: 0, ...off, transition: { duration: MOTION_DURATION.fast, ease: [...MOTION_EASE_IN] } },
    transition: { duration: MOTION_DURATION.base, ease: [...MOTION_EASE] },
  }
}

// --- Dialoge und Sheets --------------------------------------------------------

export type OverlayKind = 'dialog' | 'sheet'

/** Der eigentliche Kasten im Hintergrund-Element (`data-motion-panel`). */
function panelOf(backdrop: Element): El | null {
  return backdrop.querySelector<HTMLElement>('[data-motion-panel]')
}

/** Auftritt: Hintergrund blendet ein, der Kasten zoomt leicht heran bzw. das Sheet fährt hoch. */
export function overlayEnter(backdrop: HTMLElement | null, kind: OverlayKind) {
  if (!backdrop || !motionAllowed()) return
  void motionPlay(backdrop, { opacity: [0, 1] }, { duration: MOTION_DURATION.fast })
  const panel = panelOf(backdrop)
  if (!panel) return
  if (kind === 'sheet') {
    void motionPlay(panel, { transform: ['translateY(100%)', 'translateY(0%)'] }, { duration: MOTION_DURATION.sheet, ease: MOTION_SHEET_EASE })
  } else {
    void motionPlay(panel, { opacity: [0, 1], transform: ['translateY(6px) scale(0.97)', 'translateY(0px) scale(1)'] })
  }
}

/** Damit die Kopie nichts tut: keine Medien, keine IDs, nichts Fokussierbares. */
function quietGhost(source: HTMLElement, ghost: HTMLElement) {
  ghost.setAttribute('inert', '')
  ghost.setAttribute('aria-hidden', 'true')
  for (const el of [ghost, ...Array.from(ghost.querySelectorAll('*'))]) {
    el.removeAttribute('id')
    el.removeAttribute('role')
    el.removeAttribute('aria-modal')
    el.removeAttribute('autofocus')
    el.removeAttribute('data-dialog-over-settings')
    el.removeAttribute('data-testid')
  }
  for (const el of Array.from(ghost.querySelectorAll('video, audio, iframe, object, embed'))) {
    const blank = document.createElement('div')
    blank.className = el.getAttribute('class') ?? ''
    blank.style.cssText = el.getAttribute('style') ?? ''
    blank.style.visibility = 'hidden'
    el.replaceWith(blank)
  }
  // WebGL-Leinwände (Skin-Vorschau) lassen sich nicht kopieren – lieber leer als schwarz.
  for (const el of Array.from(ghost.querySelectorAll('canvas'))) el.style.visibility = 'hidden'
  // Eingaben und Scroll-Stand übernehmen (cloneNode kopiert nur Attribute).
  const from = source.querySelectorAll<HTMLElement>('input, textarea, select')
  const to = ghost.querySelectorAll<HTMLElement>('input, textarea, select')
  from.forEach((el, i) => {
    const copy = to[i]
    if (!copy) return
    if (el instanceof HTMLInputElement && copy instanceof HTMLInputElement) {
      copy.checked = el.checked
      if (el.type !== 'file') copy.value = el.value
    } else if (el instanceof HTMLTextAreaElement && copy instanceof HTMLTextAreaElement) copy.value = el.value
    else if (el instanceof HTMLSelectElement && copy instanceof HTMLSelectElement) copy.selectedIndex = el.selectedIndex
  })
}

function copyScroll(source: HTMLElement, ghost: HTMLElement) {
  const from = source.querySelectorAll<HTMLElement>('*')
  const to = ghost.querySelectorAll<HTMLElement>('*')
  from.forEach((el, i) => {
    if (!el.scrollTop && !el.scrollLeft) return
    const copy = to[i]
    if (!copy) return
    copy.scrollTop = el.scrollTop
    copy.scrollLeft = el.scrollLeft
  })
}

/**
 * Abgang: Dialoge hängen ihre Eltern per `v-if` ab – dafür gibt es keine
 * Leave-Transition. Stattdessen blendet eine stumme Kopie (inert, ohne Klicks)
 * an derselben Stelle aus und wird danach entfernt.
 */
export function overlayExitGhost(backdrop: HTMLElement | null, kind: OverlayKind) {
  if (!backdrop?.isConnected || !motionAllowed()) return
  if (backdrop.getElementsByTagName('*').length > GHOST_NODE_MAX) return
  const rect = backdrop.getBoundingClientRect()
  if (!rect.width || !rect.height) return
  const ghost = backdrop.cloneNode(true) as HTMLElement
  quietGhost(backdrop, ghost)
  Object.assign(ghost.style, {
    position: 'fixed',
    inset: 'auto',
    margin: '0',
    left: `${rect.left}px`,
    top: `${rect.top}px`,
    width: `${rect.width}px`,
    height: `${rect.height}px`,
    pointerEvents: 'none',
  })
  document.body.appendChild(ghost)
  copyScroll(backdrop, ghost)

  const jobs = [motionPlay(ghost, { opacity: [1, 0] }, { duration: kind === 'sheet' ? 0.2 : MOTION_DURATION.fast, ease: MOTION_EASE_IN, keep: true })]
  const panel = panelOf(ghost)
  if (panel) {
    jobs.push(
      kind === 'sheet'
        ? motionPlay(panel, { transform: [panel.style.transform || 'translateY(0px)', 'translateY(100%)'] }, { duration: 0.2, ease: MOTION_EASE_IN, keep: true })
        : motionPlay(panel, { transform: ['translateY(0px) scale(1)', 'translateY(4px) scale(0.97)'] }, { duration: MOTION_DURATION.fast, ease: MOTION_EASE_IN, keep: true }),
    )
  }
  void Promise.all(jobs).then(() => ghost.remove())
}
