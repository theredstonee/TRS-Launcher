import { describe, expect, it } from 'vitest'
import {
  bigSections,
  buttonGlyph,
  clampMemory,
  createPadReader,
  cycleSection,
  detectController,
  firstInReadingOrder,
  heldActions,
  keyAction,
  mergePads,
  navScore,
  spatialNext,
  shouldOpenOnStart,
  type NavBox,
  type PadSnapshot,
} from '../app/utils/bigPicture'

const box = (left: number, top: number, width = 100, height = 60): NavBox => ({ left, top, width, height })

/** 3×3-Raster mit 20 px Abstand: Index = Zeile * 3 + Spalte. */
const grid = Array.from({ length: 9 }, (_, i) => box((i % 3) * 120, Math.floor(i / 3) * 80))

function pad(pressed: number[] = [], axes: number[] = [0, 0]): PadSnapshot {
  const buttons = Array.from({ length: 17 }, (_, i) => pressed.includes(i))
  return { buttons, axes }
}

describe('Big Picture: räumliche Navigation', () => {
  it('geht im Raster zum direkten Nachbarn', () => {
    const centre = grid[4]!
    const others = grid.filter((b) => b !== centre)
    expect(others[spatialNext(centre, others, 'right')]).toBe(grid[5])
    expect(others[spatialNext(centre, others, 'left')]).toBe(grid[3])
    expect(others[spatialNext(centre, others, 'up')]).toBe(grid[1])
    expect(others[spatialNext(centre, others, 'down')]).toBe(grid[7])
  })

  it('läuft am Rand nicht herum', () => {
    expect(spatialNext(grid[2]!, grid, 'right')).toBe(-1)
    expect(spatialNext(grid[0]!, grid, 'up')).toBe(-1)
    expect(spatialNext(grid[6]!, grid, 'left')).toBe(-1)
    expect(spatialNext(grid[8]!, grid, 'down')).toBe(-1)
    expect(spatialNext(grid[0]!, [], 'down')).toBe(-1)
  })

  it('bevorzugt die überlappende Kachel vor der näheren schrägen', () => {
    const from = box(0, 0, 300, 60)
    const diagonal = box(320, 70, 100, 60) // rechts unten, knapp
    const below = box(200, 140, 100, 60) // weiter weg, aber direkt darunter
    expect(spatialNext(from, [diagonal, below], 'down')).toBe(1)
  })

  it('wählt aus einer breiten Kachel die Kachel unter der Mitte', () => {
    const hero = box(0, 0, 600, 200)
    const row = [box(0, 240, 180, 100), box(210, 240, 180, 100), box(420, 240, 180, 100)]
    expect(spatialNext(hero, row, 'down')).toBe(1)
    // Und von der rechten Kachel nach oben wieder in die breite.
    expect(spatialNext(row[2]!, [hero, row[0]!, row[1]!], 'up')).toBe(0)
  })

  it('ignoriert Kacheln hinter der Blickrichtung', () => {
    expect(navScore(grid[4]!, grid[3]!, 'right')).toBeNull()
    expect(navScore(grid[4]!, grid[1]!, 'down')).toBeNull()
    expect(navScore(grid[4]!, grid[5]!, 'right')).not.toBeNull()
  })

  it('findet ohne Fokus die Kachel oben links', () => {
    const shuffled = [grid[5]!, grid[1]!, grid[3]!, grid[0]!, grid[8]!]
    expect(firstInReadingOrder(shuffled)).toBe(3)
    expect(firstInReadingOrder([])).toBe(-1)
  })
})

describe('Big Picture: Gamepad-Belegung', () => {
  it('ordnet das Standard-Mapping zu', () => {
    expect([...heldActions(pad([0]))]).toEqual(['confirm'])
    expect([...heldActions(pad([1]))]).toEqual(['back'])
    expect([...heldActions(pad([3]))]).toEqual(['context'])
    expect([...heldActions(pad([9]))]).toEqual(['menu'])
    expect([...heldActions(pad([4, 5]))]).toEqual(['prevSection', 'nextSection'])
    expect([...heldActions(pad([12, 13, 14, 15]))]).toEqual(['up', 'down', 'left', 'right'])
    // X, Trigger, Back und Stick-Klicks sind frei.
    expect(heldActions(pad([2, 6, 7, 8, 10, 11])).size).toBe(0)
  })

  it('liest den linken Stick mit Totzone und ohne Diagonalen', () => {
    expect([...heldActions(pad([], [0.3, -0.2]))]).toEqual([])
    expect([...heldActions(pad([], [0.9, 0.1]))]).toEqual(['right'])
    expect([...heldActions(pad([], [-0.6, 0.2]))]).toEqual(['left'])
    expect([...heldActions(pad([], [0.6, -0.8]))]).toEqual(['up'])
    expect([...heldActions(pad([], [0.1, 0.7]))]).toEqual(['down'])
  })

  it('legt mehrere Pads zusammen', () => {
    const merged = mergePads([pad([0], [0.2, 0]), pad([5], [-0.9, 0.1])])
    expect(merged.buttons[0]).toBe(true)
    expect(merged.buttons[5]).toBe(true)
    expect(merged.buttons[1]).toBe(false)
    expect(merged.axes[0]).toBe(-0.9)
  })

  it('meldet einen Druck einmal und wiederholt nur Richtungen', () => {
    const reader = createPadReader({ repeatDelay: 300, repeatInterval: 100 })
    expect(reader.update(pad(), 0)).toEqual([])
    expect(reader.update(pad([0]), 16)).toEqual(['confirm'])
    expect(reader.update(pad([0]), 2000)).toEqual([])
    expect(reader.update(pad([]), 2016)).toEqual([])
    expect(reader.update(pad([15]), 3000)).toEqual(['right'])
    expect(reader.update(pad([15]), 3200)).toEqual([])
    expect(reader.update(pad([15]), 3300)).toEqual(['right'])
    expect(reader.update(pad([15]), 3350)).toEqual([])
    expect(reader.update(pad([15]), 3400)).toEqual(['right'])
    // Loslassen und neu drücken: sofort wieder.
    expect(reader.update(pad([]), 3420)).toEqual([])
    expect(reader.update(pad([15]), 3440)).toEqual(['right'])
  })

  it('übergeht Knöpfe, die beim ersten Lesen schon gedrückt sind', () => {
    const reader = createPadReader()
    expect(reader.update(pad([0]), 0)).toEqual([])
    expect(reader.update(pad([0]), 50)).toEqual([])
    expect(reader.update(pad([]), 100)).toEqual([])
    expect(reader.update(pad([0]), 150)).toEqual(['confirm'])
    reader.reset()
    expect(reader.update(pad([0]), 200)).toEqual([])
  })

  it('erkennt die Controller-Familie', () => {
    expect(detectController('Xbox 360 Controller (XInput STANDARD GAMEPAD)')).toBe('xbox')
    expect(detectController('045e-0b13-Xbox Wireless Controller')).toBe('xbox')
    expect(detectController('DualSense Wireless Controller (STANDARD GAMEPAD Vendor: 054c Product: 0ce6)')).toBe('playstation')
    expect(detectController('Wireless Controller (STANDARD GAMEPAD Vendor: 054c Product: 09cc)')).toBe('playstation')
    expect(detectController('Pro Controller (STANDARD GAMEPAD Vendor: 057e Product: 2009)')).toBe('nintendo')
    expect(detectController('Steam Deck (Vendor: 28de Product: 1205)')).toBe('steamdeck')
    expect(detectController('Steam Virtual Gamepad (Vendor: 28de Product: 11ff)')).toBe('steamdeck')
    expect(detectController('Generic USB Joystick')).toBe('generic')
  })

  it('zeigt Hinweise passend zum Controller', () => {
    expect(buttonGlyph('xbox', 'confirm').label).toBe('A')
    expect(buttonGlyph('playstation', 'confirm').label).toBe('✕')
    expect(buttonGlyph('playstation', 'back').label).toBe('○')
    expect(buttonGlyph('nintendo', 'confirm').label).toBe('B')
    expect(buttonGlyph('steamdeck', 'nextSection').label).toBe('R1')
    expect(buttonGlyph('keyboard', 'back').label).toBe('Esc')
    expect(buttonGlyph('generic', 'context')).toEqual(buttonGlyph('xbox', 'context'))
  })
})

describe('Big Picture: Tastatur, Bereiche, Start', () => {
  const key = (k: string, mods: Partial<Pick<KeyboardEvent, 'ctrlKey' | 'altKey' | 'metaKey'>> = {}) =>
    keyAction({ key: k, ctrlKey: false, altKey: false, metaKey: false, ...mods })

  it('ordnet Tasten wie den Controller zu', () => {
    expect(key('ArrowLeft')).toBe('left')
    expect(key('Enter')).toBe('confirm')
    expect(key('Escape')).toBe('back')
    expect(key('c')).toBe('context')
    expect(key('M')).toBe('menu')
    expect(key('PageDown')).toBe('nextSection')
    expect(key('q')).toBe('prevSection')
    expect(key('x')).toBeNull()
    // Kürzel mit Strg/Alt gehören anderen (Strg+K, Strg+N).
    expect(key('k', { ctrlKey: true })).toBeNull()
    expect(key('Enter', { altKey: true })).toBeNull()
  })

  it('wechselt Bereiche rundherum', () => {
    expect(cycleSection(bigSections, 'home', 1)).toBe('instances')
    expect(cycleSection(bigSections, 'home', -1)).toBe('settings')
    expect(cycleSection(bigSections, 'settings', 1)).toBe('home')
    const withoutFriends = bigSections.filter((s) => s !== 'friends')
    expect(cycleSection(withoutFriends, 'servers', 1)).toBe('settings')
    expect(cycleSection([] as string[], 'x', 1)).toBe('x')
  })

  it('öffnet beim Start nur auf Wunsch oder auf Steam Deck/gamescope', () => {
    expect(shouldOpenOnStart(undefined, true)).toBe(false)
    expect(shouldOpenOnStart({ bigPictureOnStart: false, bigPictureAuto: true }, false)).toBe(false)
    expect(shouldOpenOnStart({ bigPictureOnStart: true, bigPictureAuto: false }, false)).toBe(true)
    expect(shouldOpenOnStart({ bigPictureOnStart: false, bigPictureAuto: true }, true)).toBe(true)
    expect(shouldOpenOnStart({ bigPictureOnStart: false, bigPictureAuto: false }, true)).toBe(false)
  })

  it('rundet den Arbeitsspeicher auf Schritte und Grenzen', () => {
    expect(clampMemory(4000)).toBe(4096)
    expect(clampMemory(100)).toBe(1024)
    expect(clampMemory(999_999)).toBe(32768)
    expect(clampMemory(Number.NaN)).toBe(1024)
    expect(clampMemory(9000, 8192)).toBe(8192)
  })
})
