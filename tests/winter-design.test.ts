import { describe, expect, it } from 'vitest'
import { accentForDesign, applyDesign, DESIGN_STORAGE_KEY, designs, normalizeDesign } from '../app/utils/design'
import { uiSettingsSchema } from '../app/utils/schemas'
import { rng } from '../app/utils/redstone/scene'
import { AURORA_PERIOD, auroraEnvelope, buildLandscape, CABIN_W } from '../app/utils/winter/landscape'
import { paintSky } from '../app/utils/winter/paint'
import { buildWinterPalette } from '../app/utils/winter/palette'
import { Smoke, Snowfall } from '../app/utils/winter/snow'

describe('Design-Einstellung', () => {
  function harness() {
    const dataset: Record<string, string | undefined> = { accent: 'ice', theme: 'light' }
    const mem = new Map<string, string>()
    const storage = {
      setItem: (key: string, value: string) => mem.set(key, value),
      removeItem: (key: string) => {
        mem.delete(key)
      },
    }
    return { dataset, mem, target: { root: { dataset }, storage } }
  }

  it('setzt data-design nur für Winternacht und merkt es für den Splash', () => {
    const h = harness()
    expect(applyDesign('winter', h.target)).toBe('winter')
    expect(h.dataset.design).toBe('winter')
    expect(h.mem.get(DESIGN_STORAGE_KEY)).toBe('winter')
    // Theme und Akzent bleiben unberührt.
    expect(h.dataset.accent).toBe('ice')
    expect(h.dataset.theme).toBe('light')

    expect(applyDesign('redstone', h.target)).toBe('redstone')
    expect(h.dataset.design).toBeUndefined()
    expect(h.mem.has(DESIGN_STORAGE_KEY)).toBe(false)
  })

  it('unbekannte oder fehlende Werte werden Redstone', () => {
    expect(normalizeDesign(undefined)).toBe('redstone')
    expect(normalizeDesign('neon')).toBe('redstone')
    expect(designs.map(normalizeDesign)).toEqual([...designs])
    const h = harness()
    expect(applyDesign(42, h.target)).toBe('redstone')
  })

  it('Winternacht bringt Eisblau mit, andere Akzente bleiben', () => {
    expect(accentForDesign('winter', 'redstone')).toBe('ice')
    expect(accentForDesign('winter', 'emerald')).toBe('emerald')
    expect(accentForDesign('winter', 'ice')).toBe('ice')
    expect(accentForDesign('redstone', 'ice')).toBe('redstone')
    expect(accentForDesign('redstone', 'lapis')).toBe('lapis')
  })

  it('zod: design ist optional (ältere Kerne) und nachsichtig', () => {
    const base = {
      theme: 'dark',
      accent: 'ice',
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
    expect(uiSettingsSchema.parse(base).design).toBe('redstone')
    expect(uiSettingsSchema.parse({ ...base, design: 'winter' }).design).toBe('winter')
    expect(uiSettingsSchema.parse({ ...base, design: 'neon' }).design).toBe('redstone')
    expect(uiSettingsSchema.safeParse({ ...base, accent: 'sakura' }).success).toBe(false)
  })
})

describe('Winterlandschaft', () => {
  it('ist aus dem Seed stabil und bleibt im Bild', () => {
    const a = buildLandscape({ cols: 400, rows: 250, seed: 7 })
    const b = buildLandscape({ cols: 400, rows: 250, seed: 7 })
    expect(Array.from(a.ground)).toEqual(Array.from(b.ground))
    expect(a.pines).toEqual(b.pines)
    expect(a.stars).toEqual(b.stars)
    for (let x = 0; x < a.w; x++) {
      // Ebenen von hinten nach vorn: Berge über dem Wald, Wald über den Hügeln, Hügel über dem Boden.
      expect(a.mountains[x]!).toBeLessThanOrEqual(a.horizon)
      expect(a.forest[x]!).toBeLessThan(a.hills[x]!)
      expect(a.hills[x]!).toBeLessThan(a.ground[x]!)
      expect(a.ground[x]!).toBeLessThan(a.h)
    }
    expect(a.lanterns).toEqual([])
    expect(a.cabin).not.toBeNull()
    expect(a.stars.length).toBeGreaterThan(50)
    for (const s of a.stars) expect(s.y).toBeLessThan(a.horizon)
    expect(buildLandscape({ cols: 400, rows: 250, seed: 8 }).pines).not.toEqual(a.pines)
  })

  it('stellt die Laternen bis zum Anker auf den Boden darunter', () => {
    const l = buildLandscape({ cols: 330, rows: 118, seed: 3, anchorRow: 96, anchorEnd: 230 })
    expect(l.lanterns.length).toBeGreaterThan(10)
    for (const lantern of l.lanterns) {
      expect(lantern.x).toBeLessThan(230)
      expect(lantern.y).toBe(96)
      expect(l.ground[lantern.x]!).toBeGreaterThan(lantern.y + 2)
    }
    // Die Hütte steht rechts vom Knopf-Anfang und ganz im Bild.
    expect(l.cabin!.x).toBeGreaterThanOrEqual(230)
    expect(l.cabin!.x + CABIN_W).toBeLessThanOrEqual(l.w)
  })

  it('Banner-Varianten: Hütte abschaltbar, Mond und Hütte verschiebbar', () => {
    const without = buildLandscape({ cols: 128, rows: 48, seed: 9, cabin: false, moonX: 0.2 })
    expect(without.cabin).toBeNull()
    expect(without.moon.x).toBe(Math.round(128 * 0.2))
    const left = buildLandscape({ cols: 128, rows: 48, seed: 9, cabinX: 0 })
    expect(left.cabin!.x).toBeGreaterThanOrEqual(4)
    const right = buildLandscape({ cols: 128, rows: 48, seed: 9, cabinX: 1 })
    expect(right.cabin!.x + CABIN_W).toBeLessThanOrEqual(128 - 4)
  })

  it('kommt mit winzigen Flächen zurecht', () => {
    const l = buildLandscape({ cols: 3, rows: 2, seed: 1 })
    expect(l.w).toBeGreaterThanOrEqual(8)
    expect(l.cabin).toBeNull()
  })

  it('Polarlicht: erst nach dem Start, dann in Durchgängen, Werte 0…1', () => {
    expect(auroraEnvelope(0, 1)).toBe(0)
    expect(auroraEnvelope(20, 1)).toBe(1)
    let on = 0
    for (let t = 0; t < AURORA_PERIOD * 10; t += 1) {
      const v = auroraEnvelope(t, 1)
      expect(v).toBeGreaterThanOrEqual(0)
      expect(v).toBeLessThanOrEqual(1)
      if (v > 0) on++
    }
    // Etwa ein Drittel der Zeit, nie dauernd.
    expect(on).toBeGreaterThan(AURORA_PERIOD * 2)
    expect(on).toBeLessThan(AURORA_PERIOD * 6)
    // Auch nach Stunden noch.
    expect([...Array(200).keys()].some((k) => auroraEnvelope(36_000 + k, 1) > 0)).toBe(true)
  })
})

describe('Schnee und Rauch', () => {
  it('Flocken fallen, kommen oben wieder und bleiben in der Breite', () => {
    const snow = new Snowfall(100, 60, rng(5))
    expect(snow.flakes.length).toBe(20)
    const start = snow.flakes.map((f) => f.y)
    snow.step(1)
    expect(snow.flakes.some((f, i) => f.y > start[i]!)).toBe(true)
    for (let t = 2; t < 2000; t++) snow.step(t)
    for (const f of snow.flakes) {
      expect(f.y).toBeLessThanOrEqual(62 + 1.2)
      expect(f.x).toBeGreaterThanOrEqual(-3)
      expect(f.x).toBeLessThanOrEqual(103)
    }
  })

  it('Rauch steigt auf und vergeht', () => {
    const smoke = new Smoke(rng(2))
    for (let t = 0; t < 400; t++) smoke.step({ x: 10, y: 50 }, t)
    expect(smoke.puffs.length).toBeGreaterThan(0)
    expect(smoke.puffs.length).toBeLessThanOrEqual(24)
    for (const p of smoke.puffs) expect(p.y).toBeLessThanOrEqual(50)
    for (let t = 400; t < 600; t++) smoke.step(null, t)
    expect(smoke.puffs).toEqual([])
  })
})

describe('Himmel malen', () => {
  it('Hof um Mond und Sonne hat keine schwarzen Löcher', () => {
    for (const variant of ['dark', 'light'] as const) {
      for (const [cols, rows] of [[404, 255], [333, 117], [128, 48]] as const) {
        const land = buildLandscape({ cols, rows, seed: 3 })
        const sky = paintSky(land, buildWinterPalette(() => '', variant))
        const { x, y, r } = land.moon
        for (let dy = -r * 5; dy <= r * 5; dy++) {
          for (let dx = -r * 5; dx <= r * 5; dx++) {
            const px = x + dx
            const py = y + dy
            if (px < 0 || py < 0 || px >= land.w || py >= land.h) continue
            const i = (py * land.w + px) * 4
            const d = sky.data
            expect(d[i + 3]).toBe(255)
            expect(d[i]! + d[i + 1]! + d[i + 2]!, `${variant} ${cols}x${rows} @${px},${py}`).toBeGreaterThan(0)
          }
        }
      }
    }
  })
})

describe('Winter-Palette', () => {
  it('folgt dem Akzent, Landschaft je Farbschema', () => {
    const read = (name: string) => (name === '--color-redstone-400' ? '#4ade80' : '')
    const night = buildWinterPalette(read, 'dark')
    const day = buildWinterPalette(read, 'light')
    const oled = buildWinterPalette(() => '', 'oled')
    expect(night.glowAccent).toEqual([0x4a, 0xde, 0x80])
    expect(day.variant).toBe('light')
    expect(day.sky[0]).not.toEqual(night.sky[0])
    // OLED-Himmel beginnt schwarz.
    expect(oled.sky[0]).toEqual([0, 0, 0])
    // Ohne Theme-Variablen: Eisblau.
    expect(oled.glowAccent).toEqual([0x7f, 0xd3, 0xff])
  })
})
