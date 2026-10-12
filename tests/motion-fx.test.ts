// @vitest-environment jsdom
import { afterEach, describe, expect, it } from 'vitest'
import { MotionGlobalConfig } from 'motion-v'
import { applyMotion, createMotion } from '../app/utils/motion'
import {
  bindMotionLibrary,
  type Box,
  FLIP_ANIMATE_MAX,
  FLIP_MEASURE_MAX,
  flipMeasure,
  flipPlan,
  motionAllowed,
  motionStaggerIn,
  overlayEnter,
  overlayExitGhost,
  pickStagger,
  STAGGER_MAX,
  toastMotion,
} from '../app/utils/motionFx'

const box = (x: number, y: number, w = 100, h = 50): Box => ({ x, y, w, h })
const VIEW = box(0, 0, 1000, 800)

function setHidden(hidden: boolean) {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => (hidden ? 'hidden' : 'visible') })
}

/** Element mit fester Größe (jsdom kennt kein Layout). */
function sized(el: HTMLElement, rect: Box = box(10, 10, 300, 200)) {
  el.getBoundingClientRect = () => ({ left: rect.x, top: rect.y, width: rect.w, height: rect.h, right: rect.x + rect.w, bottom: rect.y + rect.h, x: rect.x, y: rect.y, toJSON: () => ({}) }) as DOMRect
  return el
}

function dialog() {
  const backdrop = sized(document.createElement('div'))
  backdrop.setAttribute('data-dialog-over-settings', '')
  backdrop.innerHTML = '<section role="dialog" aria-modal="true" id="d1" data-motion-panel><input value="a"><video autoplay src="x.mp4"></video></section>'
  ;(backdrop.querySelector('input') as HTMLInputElement).value = 'typed'
  document.body.appendChild(backdrop)
  return backdrop
}

afterEach(() => {
  applyMotion('full')
  setHidden(false)
  document.body.innerHTML = ''
})

describe('Bewegung: zentrale Einstellung', () => {
  it('animiert nur bei „Animationen“ an und sichtbarem Fenster', () => {
    applyMotion('full')
    expect(motionAllowed()).toBe(true)
    applyMotion('reduced')
    expect(motionAllowed()).toBe(false)
    applyMotion('full')
    setHidden(true)
    expect(motionAllowed()).toBe(false)
  })

  it('schaltet Motion-Komponenten mit der Einstellung (auch „Wie System“)', () => {
    let listener: ((e: { matches: boolean }) => void) | null = null
    const media = { matches: false, addEventListener: (_: string, cb: (e: { matches: boolean }) => void) => (listener = cb), removeEventListener: () => {} }
    const motion = createMotion({ media })
    const stop = bindMotionLibrary(motion)
    expect(MotionGlobalConfig.skipAnimations).toBe(false)
    motion.apply('reduced')
    expect(MotionGlobalConfig.skipAnimations).toBe(true)
    motion.apply('system')
    expect(MotionGlobalConfig.skipAnimations).toBe(false)
    listener!({ matches: true })
    expect(MotionGlobalConfig.skipAnimations).toBe(true)
    motion.apply('full')
    expect(MotionGlobalConfig.skipAnimations).toBe(false)
    stop()
    motion.dispose()
  })
})

describe('Gestaffelter Auftritt', () => {
  it('nimmt höchstens 12 sichtbare Elemente und hört unterhalb des Fensters auf', () => {
    const items: [number, Box][] = Array.from({ length: 40 }, (_, i) => [i, box((i % 4) * 110, Math.floor(i / 4) * 60)])
    const picked = pickStagger(items, VIEW)
    expect(picked).toHaveLength(STAGGER_MAX)
    expect(picked[0]).toBe(0)

    const tall: [number, Box][] = [[0, box(0, 100)], [1, box(0, 900)], [2, box(0, 200)]]
    expect(pickStagger(tall, VIEW)).toEqual([0])
  })

  it('überspringt unsichtbare (Größe 0) und seitlich verdeckte Elemente', () => {
    const items: [string, Box][] = [['hidden', box(0, 0, 0, 0)], ['left', box(-500, 10)], ['ok', box(10, 10)]]
    expect(pickStagger(items, VIEW)).toEqual(['ok'])
  })

  it('lässt bei reduzierten Animationen alles unangetastet', () => {
    applyMotion('reduced')
    const list = document.createElement('ul')
    for (let i = 0; i < 3; i++) list.appendChild(sized(document.createElement('li')))
    document.body.appendChild(list)
    motionStaggerIn(list)
    for (const li of Array.from(list.children) as HTMLElement[]) expect(li.getAttribute('style')).toBeNull()
  })
})

describe('FLIP', () => {
  it('bewegt nur Verschobenes, meldet Neue und ignoriert Unsichtbares', () => {
    const before = new Map<string, Box>([['a', box(0, 0)], ['b', box(110, 0)], ['c', box(0, 2000)], ['same', box(220, 0)]])
    const after: [string, Box][] = [['b', box(0, 0)], ['a', box(110, 0)], ['c', box(0, 2100)], ['same', box(220, 0)], ['new', box(330, 0)]]
    const plan = flipPlan(before, after, VIEW)
    expect(plan.moves).toEqual([{ el: 'b', dx: 110, dy: 0 }, { el: 'a', dx: -110, dy: 0 }])
    expect(plan.enters).toEqual(['new'])
  })

  it('bewegt höchstens FLIP_ANIMATE_MAX Elemente', () => {
    const before = new Map<number, Box>()
    const after: [number, Box][] = []
    for (let i = 0; i < 100; i++) {
      before.set(i, box(0, i * 5, 50, 5))
      after.push([i, box(10, i * 5, 50, 5)])
    }
    expect(flipPlan(before, after, VIEW).moves).toHaveLength(FLIP_ANIMATE_MAX)
  })

  it('misst große Listen gar nicht erst', () => {
    const many = Array.from({ length: FLIP_MEASURE_MAX + 1 }, () => document.createElement('li'))
    expect(flipMeasure(many)).toBeNull()
    expect(flipMeasure([])).toBeNull()
    expect(flipMeasure(many.slice(0, 3))?.size).toBe(3)
  })
})

describe('Dialoge', () => {
  it('ohne Animationen: keine Kopie, keine Inline-Werte', () => {
    applyMotion('reduced')
    const backdrop = dialog()
    overlayEnter(backdrop, 'dialog')
    expect(backdrop.getAttribute('style')).toBeNull()
    overlayExitGhost(backdrop, 'dialog')
    expect(document.body.children).toHaveLength(1)
  })

  it('der Abgang ist eine stumme Kopie, die danach wieder verschwindet', async () => {
    const backdrop = dialog()
    overlayExitGhost(backdrop, 'sheet')
    const ghost = document.body.lastElementChild as HTMLElement
    expect(ghost).not.toBe(backdrop)
    expect(ghost.hasAttribute('inert')).toBe(true)
    expect(ghost.getAttribute('aria-hidden')).toBe('true')
    expect(ghost.style.pointerEvents).toBe('none')
    // Nichts, was Esc-Logik, Tests oder Screenreader verwirren könnte – und keine Medien.
    expect(ghost.querySelector('[role], [id], [aria-modal], [data-dialog-over-settings]')).toBeNull()
    expect(ghost.hasAttribute('data-dialog-over-settings')).toBe(false)
    expect(ghost.querySelector('video')).toBeNull()
    expect((ghost.querySelector('input') as HTMLInputElement).value).toBe('typed')
    // jsdom kann nicht animieren – die Kopie wird trotzdem aufgeräumt.
    await new Promise((r) => setTimeout(r, 0))
    expect(document.body.contains(ghost)).toBe(false)
    expect(document.body.contains(backdrop)).toBe(true)
  })

  it('verstecktes Fenster: Dialog geht ohne Kopie', () => {
    setHidden(true)
    const backdrop = dialog()
    overlayExitGhost(backdrop, 'dialog')
    expect(document.body.children).toHaveLength(1)
  })
})

describe('Toasts', () => {
  it('kommen von ihrer Seite und gehen dorthin zurück', () => {
    expect(toastMotion('right').initial).toEqual({ opacity: 0, x: 24 })
    expect(toastMotion('left').exit).toMatchObject({ opacity: 0, x: -24 })
    expect(toastMotion('bottom').initial).toEqual({ opacity: 0, y: 16 })
    expect(toastMotion('top').animate).toEqual({ opacity: 1, x: 0, y: 0 })
  })
})
