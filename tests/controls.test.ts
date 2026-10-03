import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  BUILTIN_IDS,
  GRID_STEP,
  MAX_BUTTONS,
  MIN_SIZE,
  actionText,
  addButton,
  circleOf,
  clampRect,
  controlIcons,
  defaultShow,
  fromPx,
  hitTest,
  iconNames,
  iconPixels,
  isTouchApp,
  isVisible,
  keyName,
  layoutIssue,
  layoutSchema,
  moveButton,
  nextButtonId,
  pickableKeys,
  removeButton,
  resizeButton,
  safeRect,
  snap,
  specials,
  storedLayoutSchema,
  toPx,
  updateButton,
  type ControlButton,
  type ControlLayout,
} from '../app/utils/controls'

const root = path.resolve(__dirname, '..')
const builtinDir = path.join(root, 'src-tauri', 'crates', 'core', 'src', 'controls')
const plugin = path.join(root, 'src-tauri', 'plugins', 'tauri-plugin-trs-game')
const kotlinDir = path.join(plugin, 'android', 'src', 'main', 'java', 'dev', 'theredstonee', 'trsgame', 'overlay')
const swiftDir = path.join(plugin, 'ios', 'Sources', 'Overlay')

function builtin(id: string): ControlLayout {
  return layoutSchema.parse(JSON.parse(fs.readFileSync(path.join(builtinDir, `${id}.json`), 'utf8')))
}

const overlaps = (a: ControlButton, b: ControlButton) =>
  a.x < b.x + b.w - 1e-6 && b.x < a.x + a.w - 1e-6 && a.y < b.y + b.h - 1e-6 && b.y < a.y + a.h - 1e-6

describe('fertige Layouts', () => {
  it('sind gültig, überlappen nicht und haben das Nötigste', () => {
    for (const id of BUILTIN_IDS) {
      const l = builtin(id)
      expect(l.id).toBe(id)
      expect(l.profile).toBe(id)
      expect(l.builtinRev).toBe(1)
      for (const [i, a] of l.buttons.entries()) {
        for (const b of l.buttons.slice(i + 1)) expect(overlaps(a, b), `${id}: ${a.id}/${b.id}`).toBe(false)
      }
      const actions = l.buttons.map((b) => b.action)
      expect(actions).toContainEqual({ type: 'joystick', mode: 'wasd' })
      expect(actions).toContainEqual({ type: 'special', special: 'menu' })
      expect(actions).toContainEqual({ type: 'special', special: 'keyboard' })
      expect(actions).toContainEqual({ type: 'special', special: 'hotbarSwipe' })
      // Im Menü bleibt immer etwas zum Schließen/Tippen sichtbar.
      expect(l.buttons.some((b) => isVisible(b, false))).toBe(true)
    }
  })

  it('passen zum Zweck', () => {
    const pvp = builtin('pvp')
    expect(pvp.gestures.tapAttack && pvp.gestures.holdUse).toBe(true)
    expect(pvp.buttons.find((b) => b.id === 'sprint')?.action).toEqual({ type: 'toggle', key: 341 })
    // Angriff ist der größte Knopf außer dem Stick und der Hotbar.
    const attack = pvp.buttons.find((b) => b.id === 'attack')!
    const others = pvp.buttons.filter((b) => !['attack', 'move', 'hotbar'].includes(b.id))
    expect(others.every((b) => b.w * b.h < attack.w * attack.h)).toBe(true)

    const build = builtin('build')
    expect(build.gestures.tapAttack || build.gestures.holdUse).toBe(false)
    expect(build.buttons.map((b) => b.action)).toContainEqual({ type: 'mouse', button: 2 })

    const red = builtin('redstone')
    const keys = red.buttons.flatMap((b) => (b.action.type === 'key' ? [b.action.key] : []))
    expect(keys).toEqual(expect.arrayContaining([292, 295, 297]))
    expect(red.buttons.map((b) => b.action)).toContainEqual({ type: 'mouse', button: 1, chord: [340] })
  })
})

describe('Schema', () => {
  const good = builtin('pvp')
  const bad = (change: (l: ControlLayout) => unknown) => layoutIssue(change(structuredClone(good))) !== null

  it('nimmt gültige Layouts und Speicher-Einträge an', () => {
    expect(layoutIssue(good)).toBeNull()
    expect(storedLayoutSchema.parse({ layout: good, builtin: true, modified: false }).layout.id).toBe('pvp')
    // Fehlende Standardwerte werden ergänzt.
    const minimal = layoutSchema.parse({
      version: 1,
      id: 'x',
      name: 'X',
      profile: 'custom',
      buttons: [{ id: 'a', label: 'A', x: 0, y: 0, w: 0.1, h: 0.1, action: { type: 'key', key: 65 } }],
      gestures: { tapAttack: true, holdUse: false, swipeHotbar: false, cameraSensitivity: 1 },
    })
    expect(minimal.buttons[0]!.opacity).toBe(0.6)
    expect(minimal.buttons[0]!.shape).toBe('round')
    expect(minimal.gestures.haptics).toBe(true)
  })

  it('lehnt ab, was Rust auch ablehnt', () => {
    expect(bad((l) => ({ ...l, version: 2 }))).toBe(true)
    expect(bad((l) => ({ ...l, id: '../x' }))).toBe(true)
    expect(bad((l) => ({ ...l, name: '  ' }))).toBe(true)
    expect(bad((l) => ({ ...l, buttons: [] }))).toBe(true)
    expect(bad((l) => ({ ...l, buttons: [...l.buttons, l.buttons[0]] }))).toBe(true)
    expect(bad((l) => updateButton(l, 'jump', { x: 0.95 }))).toBe(true)
    expect(bad((l) => updateButton(l, 'jump', { opacity: 0 }))).toBe(true)
    expect(bad((l) => updateButton(l, 'jump', { label: 'x'.repeat(13) }))).toBe(true)
    expect(bad((l) => updateButton(l, 'jump', { label: undefined, icon: undefined }))).toBe(true)
    expect(bad((l) => updateButton(l, 'jump', { action: { type: 'key', key: 5 } }))).toBe(true)
    expect(bad((l) => updateButton(l, 'jump', { action: { type: 'key', key: 71, chord: [71] } }))).toBe(true)
    expect(bad((l) => updateButton(l, 'jump', { action: { type: 'key', key: 71, chord: [292, 292] } }))).toBe(true)
    expect(bad((l) => updateButton(l, 'jump', { action: { type: 'mouse', button: 9 } }))).toBe(true)
    expect(bad((l) => ({ ...l, gestures: { ...l.gestures, cameraSensitivity: 9 } }))).toBe(true)
    expect(bad((l) => ({ ...l, buttons: Array.from({ length: MAX_BUTTONS + 1 }, (_, i) => ({ ...l.buttons[1], id: `b${i}` })) }))).toBe(true)
  })
})

describe('Geometrie', () => {
  it('rechnet Anteile und Pixel hin und zurück (mit Notch)', () => {
    const safe = safeRect(2000, 1000, { left: 100, top: 0, right: 0, bottom: 40 })
    expect(safe).toEqual({ x: 100, y: 0, w: 1900, h: 960 })
    const px = toPx({ x: 0.5, y: 0.5, w: 0.1, h: 0.2 }, safe)
    expect(px).toEqual({ x: 1050, y: 480, w: 190, h: 192 })
    const back = fromPx(px, safe)
    expect(back.x).toBeCloseTo(0.5)
    expect(back.h).toBeCloseTo(0.2)
    expect(fromPx(px, { x: 0, y: 0, w: 0, h: 0 })).toEqual({ x: 0, y: 0, w: MIN_SIZE, h: MIN_SIZE })
    expect(safeRect(10, 10, { left: 20, top: 0, right: 0, bottom: 0 }).w).toBe(0)
  })

  it('trifft runde Knöpfe nur im Kreis', () => {
    const safe = safeRect(2000, 1000)
    const round = { x: 0.1, y: 0.1, w: 0.2, h: 0.2, shape: 'round' as const }
    expect(circleOf(toPx(round, safe))).toEqual({ cx: 400, cy: 200, r: 100 })
    expect(hitTest(round, safe, 400, 200)).toBe(true)
    expect(hitTest(round, safe, 210, 110)).toBe(false)
    expect(hitTest({ ...round, shape: 'rect' }, safe, 210, 110)).toBe(true)
  })

  it('rastet ein und hält Knöpfe im Bild', () => {
    expect(snap(0.1312)).toBeCloseTo(0.13)
    expect(GRID_STEP).toBe(0.01)
    expect(clampRect({ x: 1.2, y: -0.3, w: 0.1, h: 0.1 })).toEqual({ x: 0.9, y: 0, w: 0.1, h: 0.1 })
    expect(clampRect({ x: 0, y: 0, w: 0, h: 3 })).toEqual({ x: 0, y: 0, w: MIN_SIZE, h: 1 })
  })
})

describe('Editor-Schritte', () => {
  const pvp = builtin('pvp')

  it('verschiebt mit Raster und hält am Rand', () => {
    const jump = pvp.buttons.find((b) => b.id === 'jump')!
    const moved = moveButton(pvp, 'jump', 0.0106, 0, true).buttons.find((b) => b.id === 'jump')!
    expect(moved.x).toBeCloseTo(0.9)
    const edge = moveButton(pvp, 'jump', 5, 5, false).buttons.find((b) => b.id === 'jump')!
    expect(edge.x).toBeCloseTo(1 - jump.w)
    expect(edge.y).toBeCloseTo(1 - jump.h)
    // Andere Knöpfe bleiben unberührt.
    expect(moveButton(pvp, 'jump', 0.1, 0, false).buttons.filter((b) => b.id !== 'jump')).toEqual(pvp.buttons.filter((b) => b.id !== 'jump'))
  })

  it('ändert die Größe ohne über den Rand zu wachsen', () => {
    const r = resizeButton(pvp, 'jump', 1, 1, false).buttons.find((b) => b.id === 'jump')!
    expect(r.x + r.w).toBeLessThanOrEqual(1)
    expect(r.y + r.h).toBeLessThanOrEqual(1)
    const tiny = resizeButton(pvp, 'jump', -1, -1, true).buttons.find((b) => b.id === 'jump')!
    expect(tiny.w).toBe(MIN_SIZE)
    expect(layoutIssue(resizeButton(pvp, 'jump', 1, 1, true))).toBeNull()
  })

  it('fügt hinzu und entfernt', () => {
    expect(nextButtonId(pvp)).toBe(`b${pvp.buttons.length + 1}`)
    const { layout, id } = addButton(pvp, { type: 'key', key: 86 })
    expect(layout.buttons).toHaveLength(pvp.buttons.length + 1)
    expect(layout.buttons.at(-1)?.label).toBe('V')
    expect(layoutIssue(layout)).toBeNull()
    // Zweimal hinzufügen: nicht genau übereinander.
    const second = addButton(layout)
    const [a, b] = second.layout.buttons.slice(-2)
    expect(a!.x === b!.x && a!.y === b!.y).toBe(false)
    expect(removeButton(layout, id).buttons).toEqual(pvp.buttons)
    // Voll: nichts passiert.
    const full = { ...pvp, buttons: Array.from({ length: MAX_BUTTONS }, (_, i) => ({ ...pvp.buttons[1]!, id: `x${i}` })) }
    expect(addButton(full).id).toBe('')
  })
})

describe('Aktionen und Sichtbarkeit', () => {
  it('beschreibt Aktionen lesbar', () => {
    expect(keyName(71)).toBe('G')
    expect(keyName(292)).toBe('F3')
    expect(keyName(48)).toBe('0')
    expect(keyName(9999)).toBeNull()
    expect(actionText({ type: 'key', key: 71, chord: [292] })).toBe('F3 + G')
    for (const s of specials) expect(actionText({ type: 'special', special: s })).not.toMatch(/^controls\./)
    expect(actionText({ type: 'mouse', button: 5 })).toContain('6')
    expect(pickableKeys.every((k) => keyName(k) !== null)).toBe(true)
  })

  it('zeigt im Menü nur Menü-Knöpfe', () => {
    expect(defaultShow({ type: 'special', special: 'menu' })).toBe('always')
    expect(defaultShow({ type: 'key', key: 256 })).toBe('always')
    expect(defaultShow({ type: 'key', key: 32 })).toBe('game')
    const b = builtin('pvp').buttons.find((x) => x.id === 'jump')!
    expect(isVisible(b, true) && !isVisible(b, false)).toBe(true)
    expect(isVisible({ ...b, show: 'menu' }, false)).toBe(true)
  })

  it('erkennt die mobile App', () => {
    expect(isTouchApp('Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit')).toBe(true)
    expect(isTouchApp('Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X)')).toBe(true)
    expect(isTouchApp('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)', 5)).toBe(true)
    expect(isTouchApp('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)', 0)).toBe(false)
    expect(isTouchApp('Mozilla/5.0 (Windows NT 10.0; Win64; x64) Edg/140')).toBe(false)
  })
})

describe('Gleichstand mit Rust, Kotlin und Swift', () => {
  const parseKotlin = (file: string) => {
    const src = fs.readFileSync(path.join(kotlinDir, file), 'utf8')
    return Object.fromEntries([...src.matchAll(/"([\w.]+)" to listOf\((.*)\),?\s*$/gm)].map((m) => [m[1], [...m[2]!.matchAll(/"((?:[^"\\]|\\.)*)"/g)].map((x) => x[1])]))
  }
  const parseSwift = (file: string) => {
    const src = fs.readFileSync(path.join(swiftDir, file), 'utf8')
    return Object.fromEntries([...src.matchAll(/"([\w.]+)": \[([^\]]*)\]/g)].map((m) => [m[1], [...m[2]!.matchAll(/"((?:[^"\\]|\\.)*)"/g)].map((x) => x[1])]))
  }

  it('Pixel-Symbole sind überall gleich (8×8)', () => {
    for (const name of iconNames) {
      expect(controlIcons[name]).toHaveLength(8)
      expect(controlIcons[name].every((row) => /^[#.]{8}$/.test(row))).toBe(true)
      expect(iconPixels(name).length).toBeGreaterThan(0)
    }
    const expected = Object.fromEntries(iconNames.map((n) => [n, [...controlIcons[n]]]))
    expect(parseKotlin('Icons.kt')).toEqual(expected)
    expect(parseSwift('Icons.swift')).toEqual(expected)
  })

  it('Rust kennt dieselben Symbole und Sonderfunktionen', () => {
    const rust = fs.readFileSync(path.join(root, 'src-tauri', 'crates', 'core', 'src', 'controls.rs'), 'utf8')
    const variants = (name: string) => {
      const body = rust.match(new RegExp(`pub enum ${name} \\{([\\s\\S]*?)\\n\\}`))![1]!
      return [...body.matchAll(/^\s{4}([A-Z]\w*)\s*[,{]/gm)].map((m) => m[1]!.charAt(0).toLowerCase() + m[1]!.slice(1))
    }
    expect(variants('Icon')).toEqual([...iconNames])
    expect(variants('Special')).toEqual([...specials])
  })

  it('Texte des Editors im Spiel sind in Kotlin und Swift gleich und vollständig', () => {
    const kt = parseKotlin('OverlayStrings.kt')
    const swift = parseSwift('OverlayStrings.swift')
    expect(Object.keys(kt).length).toBeGreaterThan(30)
    expect(swift).toEqual(kt)
    for (const [key, list] of Object.entries(kt)) {
      expect(list, key).toHaveLength(8)
      expect(list.every((s) => s && s.trim().length > 0), key).toBe(true)
    }
  })
})
