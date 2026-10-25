import { describe, expect, it } from 'vitest'
import { accentForDesign, applyDesign, DESIGN_ACCENTS, DESIGN_STORAGE_KEY, designs, normalizeDesign } from '../app/utils/design'
import { buildFoxLandscape, DEN_BLOCKS, denParts } from '../app/utils/fox/landscape'
import { paintLand, paintSky, spruceHalf } from '../app/utils/fox/paint'
import { buildFoxPalette } from '../app/utils/fox/palette'
import { breeze, fireflyAt, Leaves, makeFireflies, Sparks } from '../app/utils/fox/particles'
import { BREATH_PERIOD, foxBreath } from '../app/utils/fox/sleep'
import { sleepingFoxPixels, SLEEPING_FOX_H, SLEEPING_FOX_W } from '../app/utils/fox/sprites'
import { INTRO_DESIGNS, pickIntroDesign } from '../app/utils/intro/designs'
import { rng } from '../app/utils/redstone/scene'
import { uiSettingsSchema } from '../app/utils/schemas'

describe('Design „Fuchsbau“: Einstellung', () => {
  it('setzt data-design="fox" und merkt es für den Splash', () => {
    const dataset: Record<string, string | undefined> = { theme: 'light' }
    const mem = new Map<string, string>()
    const storage = { setItem: (k: string, v: string) => mem.set(k, v), removeItem: (k: string) => void mem.delete(k) }
    expect(applyDesign('fox', { root: { dataset }, storage })).toBe('fox')
    expect(dataset.design).toBe('fox')
    expect(mem.get(DESIGN_STORAGE_KEY)).toBe('fox')
    expect(dataset.theme).toBe('light')
    expect(designs).toContain('fox')
    expect(normalizeDesign('fox')).toBe('fox')
  })

  it('bringt Fuchsorange mit; Design-Akzente tauschen, eigene Farben bleiben', () => {
    expect(DESIGN_ACCENTS.fox).toBe('fox')
    expect(accentForDesign('fox', 'redstone')).toBe('fox')
    expect(accentForDesign('fox', 'ice')).toBe('fox')
    expect(accentForDesign('winter', 'fox')).toBe('ice')
    expect(accentForDesign('redstone', 'fox')).toBe('redstone')
    expect(accentForDesign('fox', 'emerald')).toBe('emerald')
    expect(accentForDesign('fox', 'lamp')).toBe('lamp')
    // Bisheriges Verhalten bleibt.
    expect(accentForDesign('winter', 'redstone')).toBe('ice')
    expect(accentForDesign('redstone', 'ice')).toBe('redstone')
  })

  it('zod: Design und Akzent „fox“ sind gültig', () => {
    const base = {
      theme: 'dark',
      accent: 'fox',
      design: 'fox',
      advancedRendering: true,
      animatedBackground: true,
      motion: 'full',
      worldsTab: true,
      screenshotsTab: true,
      historyTab: true,
      sidebarRecent: true,
      sidebarAccount: true,
      hideRightSidebar: false,
      compactLibrary: false,
      showPlayTime: true,
      language: 'de',
    }
    const parsed = uiSettingsSchema.parse(base)
    expect([parsed.design, parsed.accent]).toEqual(['fox', 'fox'])
  })

  it('keine eigene Start-Animation: mit Fuchsbau läuft die Redstone-Fassung', () => {
    expect(pickIntroDesign({ design: 'fox' }).id).toBe('redstone')
    expect(INTRO_DESIGNS.some((d) => d.id === 'fox' || d.design === 'fox')).toBe(false)
    // Ein Event geht weiter vor.
    expect(pickIntroDesign({ design: 'fox', events: ['halloween'] }).id).toBe('halloween')
  })
})

describe('Fuchsbau-Landschaft', () => {
  it('ist aus dem Seed stabil, Ebenen von hinten nach vorn', () => {
    const a = buildFoxLandscape({ cols: 420, rows: 240, seed: 7 })
    const b = buildFoxLandscape({ cols: 420, rows: 240, seed: 7 })
    expect(Array.from(a.ground)).toEqual(Array.from(b.ground))
    expect(a.trees).toEqual(b.trees)
    expect(a.den).toEqual(b.den)
    for (let x = 0; x < a.w; x++) {
      expect(a.hillsBack[x]!).toBeLessThanOrEqual(a.horizon)
      expect(a.forest[x]!).toBeLessThan(a.meadow[x]!)
      expect(a.meadow[x]!).toBeLessThan(a.ground[x]!)
      expect(a.ground[x]!).toBeLessThan(a.h)
    }
    expect(buildFoxLandscape({ cols: 420, rows: 240, seed: 8 }).trees).not.toEqual(a.trees)
    for (const s of a.stars) expect(s.y).toBeLessThan(a.horizon)
  })

  it('ein einziger kleiner Fuchs schläft zwischen Feuer und Bau', () => {
    const l = buildFoxLandscape({ cols: 420, rows: 240, seed: 3 })
    expect(l.den?.size).toBe('large')
    expect(l.den!.fox).toBe(true)
    const p = denParts(l.den!)
    expect(p.fox).not.toBeNull()
    // Rechts vom Feuer, vor dem Loch – klein im Verhältnis zum Bild.
    expect(p.fox!.x).toBeGreaterThanOrEqual(p.fire.x + p.fire.w)
    expect(p.fox!.x + SLEEPING_FOX_W).toBeLessThanOrEqual(p.hole.x0)
    expect(SLEEPING_FOX_W).toBeLessThan(l.w * 0.06)
    expect(SLEEPING_FOX_H).toBeLessThanOrEqual(10)
    // Der Bau liegt ganz im Bild.
    expect(l.den!.x).toBeGreaterThanOrEqual(0)
    expect(l.den!.x + DEN_BLOCKS * l.den!.bs).toBeLessThanOrEqual(l.w)
  })

  it('Startseite: Zaunlaternen bis zum Knopf, der Bau darüber', () => {
    const l = buildFoxLandscape({ cols: 400, rows: 118, seed: 5, anchorRow: 98, anchorEnd: 280, anchorTop: 89, anchorRight: 365 })
    expect(l.lanterns.length).toBeGreaterThan(10)
    for (const lantern of l.lanterns) {
      expect(lantern.x).toBeLessThan(280)
      expect(l.ground[lantern.x]!).toBeGreaterThan(lantern.y + 6)
    }
    expect(l.den!.x + DEN_BLOCKS * l.den!.bs).toBeLessThanOrEqual(366)
    expect(l.den!.y).toBeLessThanOrEqual(89)
  })

  it('die Sonne bleibt frei von Bäumen auf der Wiese und steht über den Hügeln', () => {
    for (const seed of [1, 2, 3, 4, 5, 6]) {
      const l = buildFoxLandscape({ cols: 420, rows: 240, seed })
      for (const t of l.trees) if (t.layer === 1) expect(Math.abs(t.x - l.sun.x)).toBeGreaterThan(l.sun.half)
      expect(l.sun.y).toBeLessThan(l.horizon)
    }
  })

  it('Kirschhain: viele Kirschbäume, mehrere vorn, ein großer gleich links vom Lagerfeuer', () => {
    for (const seed of [0xf0c5, 3, 7]) {
      const l = buildFoxLandscape({ cols: 427, rows: 240, seed })
      const cherries = l.trees.filter((t) => t.kind === 'cherry')
      expect(cherries.length * 5).toBeGreaterThanOrEqual(l.trees.length)
      expect(cherries.filter((t) => t.layer >= 1).length).toBeGreaterThanOrEqual(3)
      const fire = denParts(l.den!).fire
      expect(cherries.some((t) => t.layer === 2 && t.bs >= 7 && t.x < fire.x && t.x > fire.x - 3 * t.bs)).toBe(true)
    }
  })

  it('Banner: kleiner Bau ohne großen Fuchs, auf Wunsch ganz ohne Bau', () => {
    const small = buildFoxLandscape({ cols: 128, rows: 48, seed: 9, fox: true })
    expect(small.den?.size).toBe('small')
    expect(denParts(small.den!).fox).toBeNull()
    expect(buildFoxLandscape({ cols: 128, rows: 48, seed: 9, den: false }).den).toBeNull()
  })
})

describe('Fuchsbau malen', () => {
  it('Himmel ganz gefüllt, eckige Sonne mit hellem Kern', () => {
    for (const variant of ['dark', 'oled', 'light'] as const) {
      const l = buildFoxLandscape({ cols: 300, rows: 160, seed: 2 })
      const P = buildFoxPalette(() => '', variant)
      const sky = paintSky(l, P)
      for (let i = 3; i < sky.data.length; i += 4) expect(sky.data[i]).toBe(255)
      const at = (x: number, y: number) => Array.from(sky.data.slice((y * l.w + x) * 4, (y * l.w + x) * 4 + 3))
      expect(at(l.sun.x, l.sun.y)).toEqual(P.sunCore.map(Math.round))
    }
  })

  it('Landschaft deckt alles unter den Hügeln, das Loch im Bau ist dunkel', () => {
    const l = buildFoxLandscape({ cols: 300, rows: 160, seed: 4 })
    const P = buildFoxPalette(() => '', 'dark')
    const land = paintLand(l, P)
    for (let x = 0; x < l.w; x++) for (let y = Math.max(l.horizon + 4, l.hillsBack[x]!); y < l.h; y++) expect(land.filled(x, y), `${x},${y}`).toBe(true)
    const p = denParts(l.den!)
    const i = ((p.hole.y - 2) * l.w + Math.round((p.hole.x0 + p.hole.x1) / 2)) * 4
    expect(land.data[i]! + land.data[i + 1]! + land.data[i + 2]!).toBeLessThan(150)
  })

  it('kleine und schmale Flächen (Leer-Zustände, Banner, Vorschau) gehen auch', () => {
    for (const [cols, rows] of [[8, 8], [40, 20], [128, 48], [192, 64], [90, 300], [700, 60]] as const) {
      const l = buildFoxLandscape({ cols, rows, seed: 11 })
      const P = buildFoxPalette(() => '', 'dark')
      expect(() => paintSky(l, P)).not.toThrow()
      expect(() => paintLand(l, P)).not.toThrow()
      expect(l.sun.x).toBeGreaterThanOrEqual(0)
      expect(l.sun.x).toBeLessThan(l.w)
    }
  })

  it('Fichten wachsen im Zickzack nach unten breiter', () => {
    const halves = Array.from({ length: 7 }, (_, i) => spruceHalf(i, 7))
    expect(halves[0]).toBe(0)
    expect(halves[6]).toBe(Math.max(...halves))
    expect(halves.some((h, i) => i > 0 && h < halves[i - 1]!)).toBe(true)
  })
})

describe('Der schlafende Fuchs', () => {
  it('passt ins Raster; beim Einatmen hebt sich der Rücken, beim Zucken klappt ein Ohr', () => {
    const rest = sleepingFoxPixels({ inhale: false, twitch: false })
    const breath = sleepingFoxPixels({ inhale: true, twitch: false })
    for (const [x, y] of [...rest, ...breath]) {
      expect(x).toBeGreaterThanOrEqual(0)
      expect(x).toBeLessThan(SLEEPING_FOX_W)
      expect(y).toBeGreaterThanOrEqual(0)
      expect(y).toBeLessThan(SLEEPING_FOX_H)
    }
    const backTop = (px: [number, number, string][]) => Math.min(...px.filter(([x]) => x >= 10 && x <= 14).map(([, y]) => y))
    expect(backTop(breath)).toBe(backTop(rest) - 1)
    const twitch = sleepingFoxPixels({ inhale: false, twitch: true })
    const ears = (px: [number, number, string][]) => px.filter(([, y, c]) => y === 0 && c === 'K').length
    expect(ears(twitch)).toBe(ears(rest) - 1)
    // Weiße Schwanzspitze vorn über der Schnauze.
    expect(rest.some(([x, y, c]) => x === 0 && y >= 6 && c === 'W')).toBe(true)
  })

  it('atmet ruhig (einmal je Periode), zuckt selten, steht bei reduzierter Bewegung still', () => {
    let inhales = 0
    let twitchFrames = 0
    let last = false
    const frames = 60 / 0.08
    for (let i = 0; i < frames; i++) {
      const v = foxBreath(i * 0.08)
      if (v.inhale && !last) inhales++
      if (v.twitch) twitchFrames++
      last = v.inhale
    }
    expect(inhales).toBeGreaterThanOrEqual(Math.floor(60 / BREATH_PERIOD))
    expect(inhales).toBeLessThanOrEqual(Math.ceil(60 / BREATH_PERIOD) + 1)
    expect(twitchFrames).toBeGreaterThan(0)
    expect(twitchFrames / frames).toBeLessThan(0.05)
    for (let t = 0; t < 30; t += 0.37) expect(foxBreath(t, true)).toEqual({ inhale: false, twitch: false })
  })
})

describe('Blätter, Funken, Glühwürmchen', () => {
  it('Blätter treiben, kommen oben wieder und bleiben in der Breite; ein paar sind Blüten', () => {
    const leaves = new Leaves(200, 120, rng(5))
    const n = leaves.leaves.length
    expect(n).toBeGreaterThan(6)
    for (let t = 1; t < 3000; t++) leaves.step(t)
    expect(leaves.leaves.length).toBe(n)
    for (const f of leaves.leaves) {
      expect(f.y).toBeLessThanOrEqual(123)
      expect(f.x).toBeGreaterThanOrEqual(-3)
      expect(f.x).toBeLessThanOrEqual(203)
    }
    const many = new Leaves(1200, 120, rng(9)).leaves
    const petals = many.filter((f) => f.petal).length / many.length
    // Meist Kirschblüten, dazwischen ein paar Herbstblätter.
    expect(petals).toBeGreaterThan(0.5)
    expect(petals).toBeLessThan(0.85)
    expect(Math.abs(breeze(10))).toBeLessThan(0.3)
  })

  it('Funken steigen auf und vergehen; mehr, wenn das Feuer lodert', () => {
    const count = (power: number) => {
      const s = new Sparks(rng(2))
      let seen = 0
      for (let t = 0; t < 400; t++) {
        s.step({ x: 10, y: 50, w: 6 }, t, power)
        seen += s.sparks.length
        for (const sp of s.sparks) expect(sp.y).toBeLessThanOrEqual(50)
      }
      for (let t = 400; t < 500; t++) s.step(null, t, power)
      expect(s.sparks).toEqual([])
      return seen
    }
    expect(count(1)).toBeGreaterThan(count(0.3))
  })

  it('Glühwürmchen stehen bei reduzierter Bewegung still', () => {
    const [f] = makeFireflies(rng(1), 1, { x0: 0, x1: 100, y0: 0, y1: 50 })
    expect(fireflyAt(f!, 1, true)).toEqual(fireflyAt(f!, 9, true))
    expect(fireflyAt(f!, 1)).not.toEqual(fireflyAt(f!, 3))
  })
})

describe('Fuchsbau-Palette', () => {
  it('Zaunlaternen folgen dem Akzent, die Welt dem Farbschema', () => {
    const read = (name: string) => (name === '--color-redstone-400' ? '#4ade80' : '')
    const dusk = buildFoxPalette(read, 'dark')
    const gold = buildFoxPalette(read, 'light')
    const oled = buildFoxPalette(() => '', 'oled')
    expect(dusk.glowAccent).toEqual([0x4a, 0xde, 0x80])
    expect(gold.sky[0]).not.toEqual(dusk.sky[0])
    expect(oled.sky[0]).toEqual([0, 0, 0])
    // Ohne Theme-Variablen: Fuchsorange; Feuer bleibt warm, egal welcher Akzent.
    expect(oled.glowAccent).toEqual([0xff, 0x9a, 0x52])
    expect(dusk.glowFire).toEqual(oled.glowFire)
  })
})
