// @vitest-environment jsdom
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick, reactive } from 'vue'
import {
  canTrick,
  HALLOWEEN_END,
  halloweenFeaturesOn,
  halloweenStateSchema,
  isHalloweenReward,
  LAUNCHER_PUMPKINS,
  localText,
  pumpkinFoundSchema,
  showPumpkin,
  stickerSchema,
  trickOutcomeSchema,
  type HalloweenState,
} from '../app/utils/halloween'
import { liveEventSchema, chatMessageSchema, messageSummary } from '../app/utils/chat'
import { trsCapeSchema, trsPlayerCapeSchema, trsUnlockLabel } from '../app/utils/trs'
import { drawMoonPhase, moonPixel, moonState, phaseKey } from '../app/utils/moonnight/moon'
import { boltFlash, makeScene } from '../app/utils/moonnight/scene'
import { emitBolt, onBolt, whispersFor, WHISPERS } from '../app/utils/moonnight/bus'
import { SPRITES, spritePixel, spriteSize } from '../app/utils/moonnight/sprites'
import { capeOrigin } from '../app/utils/capeFilter'

// Halloween 2026 „Nacht der langen Schatten“ (API §36): Schemas, Regeln, Mondlauf, Szene,
// Sprites und der Store (Kürbis melden, Anklopfen, alte API, Echtzeit).

const sample = {
  active: true,
  endsAt: '2026-11-02T23:00:00Z',
  pumpkins: { total: 13, found: ['web-home', 'launcher-titlebar'], nextHint: { de: 'Ganz unten …', en: 'At the very bottom …', es: 'Abajo …' } },
  trick: { usedToday: false, result: null, reward: null, next: null },
  badges: ['halloween2026'],
  effects: { hits: true, trail: false },
}
const OCT = Date.parse('2026-10-20T12:00:00Z')

describe('Halloween 2026 – Regeln', () => {
  it('liest den Stand des Kerns und lässt kaputte Teile weich fallen', () => {
    const s = halloweenStateSchema.parse(sample)
    expect(s.pumpkins.found).toHaveLength(2)
    const odd = halloweenStateSchema.parse({ ...sample, trick: { usedToday: true, result: 'boom', reward: { kind: 'hat', id: 'witch_hat', name: {} }, next: null }, badges: 'x', effects: null })
    expect(odd.trick.result).toBeNull()
    expect(odd.trick.reward?.kind).toBe('other')
    expect(odd.badges).toEqual([])
    expect(odd.effects).toEqual({ hits: true, trail: true })
    expect(pumpkinFoundSchema.parse({ found: ['launcher-logs'], count: 1, complete: false, reward: null }).count).toBe(1)
    expect(trickOutcomeSchema.parse({ result: 'sour', reward: null, next: null }).result).toBe('sour')
    expect(s.wings).toEqual({ choice: null, canChoose: false, options: [] })
    expect(halloweenStateSchema.parse({ ...sample, wings: { choice: null, canChoose: true, options: ['bat_wings', 'ghost_wings'] } }).wings.canChoose).toBe(true)
    expect(stickerSchema.safeParse({ id: '../x', name: { de: '', en: '', es: '' }, image: null, owned: false }).success).toBe(false)
  })

  it('wählt die Sprache mit Rückfall auf Englisch', () => {
    const text = { de: 'Hallo', en: 'Hello', es: '' }
    expect(localText(text, 'de')).toBe('Hallo')
    expect(localText(text, 'es')).toBe('Hello')
    expect(localText(text, 'pt-BR')).toBe('Hello')
    expect(localText({ de: 'Nur Deutsch', en: '', es: '' }, 'fr')).toBe('Nur Deutsch')
    expect(localText(null, 'en')).toBe('')
  })

  it('Funktionen laufen nur, solange der Server es sagt und vor dem 3. November', () => {
    const s = halloweenStateSchema.parse(sample)
    expect(halloweenFeaturesOn(s, OCT)).toBe(true)
    expect(halloweenFeaturesOn(s, Date.parse(HALLOWEEN_END))).toBe(false)
    expect(halloweenFeaturesOn({ ...s, active: false }, OCT)).toBe(false)
    expect(halloweenFeaturesOn({ ...s, endsAt: null }, OCT)).toBe(true)
    expect(halloweenFeaturesOn(null, OCT)).toBe(false)
  })

  it('zeigt nur ungefundene Kürbisse und klopft einmal am Tag', () => {
    const s = halloweenStateSchema.parse(sample)
    expect(LAUNCHER_PUMPKINS).toHaveLength(5)
    expect(showPumpkin(s, 'launcher-titlebar', OCT)).toBe(false)
    expect(showPumpkin(s, 'launcher-logs', OCT)).toBe(true)
    expect(showPumpkin(null, 'launcher-logs', OCT)).toBe(false)
    expect(canTrick(s, OCT)).toBe(true)
    const used: HalloweenState = { ...s, trick: { ...s.trick, usedToday: true, next: '2026-10-20T22:00:00Z' } }
    expect(canTrick(used, OCT)).toBe(false)
    expect(canTrick(used, Date.parse('2026-10-20T22:00:01Z'))).toBe(true)
  })

  it('kennzeichnet Belohnungs-Umhänge als Halloween-Belohnung', () => {
    expect(isHalloweenReward('reward', 'halloween2026')).toBe(true)
    expect(isHalloweenReward('reward', null)).toBe(true)
    expect(isHalloweenReward('event', 'halloween2026')).toBe(false)
    const cape = trsCapeSchema.parse({ id: 'halloween_blood_moon', name: 'Blutmond', kind: 'builtin', unlock: 'reward', rewardOf: 'halloween2026', status: 'approved', width: 512, height: 256, scale: 8, frames: 16, frameTimeMs: 100, owned: false, active: false, rejectReason: null, texture: null, shareable: false, shared: null, holders: 0 })
    expect(cape.unlock).toBe('reward')
    expect(trsUnlockLabel(cape)).toBe('Halloween reward')
    expect(capeOrigin(cape)).toBe('reward')
    // Unbekannte Freischaltarten brechen den Katalog nicht.
    expect(trsCapeSchema.parse({ ...cape, unlock: 'nuke' }).unlock).toBe('other')
  })

  it('Abzeichen im Spieler-Lookup und Sticker im Chat', () => {
    const p = trsPlayerCapeSchema.parse({ uuid: 'a'.repeat(32), badge: true, capeId: null, upload: false, scale: 1, frames: 1, frameTimeMs: null, texture: null, badges: ['halloween2026'], fx: ['hits'] })
    expect(p.badges).toEqual(['halloween2026'])
    const old = trsPlayerCapeSchema.parse({ uuid: 'a'.repeat(32), badge: true, capeId: null, upload: false, scale: 1, frames: 1, frameTimeMs: null, texture: null })
    expect(old.badges).toEqual([])
    const m = chatMessageSchema.parse({
      id: 'm0123456789abcdef0123', conversationId: 'c0123456789abcdef0123', seq: 1, kind: 'text', sender: null, text: null, invite: null,
      sticker: 'ghost_boo', attachments: [], replyTo: null, system: null, reactions: [], createdAt: null, editedAt: null, deleted: false, deletedBy: null, hidden: false, nonce: null,
    })
    expect(m.sticker).toBe('ghost_boo')
    expect(messageSummary(m)).toBe('Sticker')
  })

  it('kennt das Echtzeit-Ereignis halloween_updated', () => {
    const e = liveEventSchema.parse({ type: 'halloween_updated', ...sample })
    expect(e.type).toBe('halloween_updated')
  })
})

describe('Mondlauf', () => {
  it('Neumond bei 0 %, Vollmond bei 100 %', () => {
    const lit = (f: number) => {
      let n = 0
      for (let y = 0; y < 13; y++) for (let x = 0; x < 13; x++) if (moonPixel(x, y, f) === '#eef1f8') n++
      return n
    }
    expect(lit(0)).toBe(0)
    expect(lit(1)).toBeGreaterThan(80)
    expect(lit(0.5)).toBeGreaterThan(lit(0.2))
    expect(moonPixel(0, 0, 1)).toBeNull()
  })

  it('bestimmt, unbestimmt und Dauerstrom', () => {
    expect(moonState(0, 30, false)).toEqual({ pos: 0.3, fill: 0.3, phase: 0.3, full: false })
    expect(moonState(0, 140, false).full).toBe(true)
    expect(moonState(0, Number.NaN, false).pos).toBe(0)
    expect(moonState(0, 0, false, true).full).toBe(true)
    const a = moonState(0, 0, true)
    const b = moonState(Math.PI / 1.2, 0, true)
    expect(a.pos).toBeCloseTo(0)
    expect(b.pos).toBeCloseTo(1)
    expect(a.fill).toBe(0)
    expect(phaseKey(0.5)).toBe(12)
    // Ohne 2D-Kontext (jsdom) passiert einfach nichts.
    expect(() => drawMoonPhase(document.createElement('canvas'), 0.5)).not.toThrow()
  })
})

describe('Mondnacht-Szene und Atmosphäre', () => {
  it('Blitzlicht: Stottern nur mit „Flackern & Rauschen“', () => {
    expect(boltFlash(0.01, true)).toBe(0.55)
    expect(boltFlash(0.1, true)).toBe(0.05)
    expect(boltFlash(0.01, false)).toBeLessThan(0.25)
    expect(boltFlash(0.1, false)).toBeGreaterThan(boltFlash(0.3, false))
    expect(boltFlash(0.8, true)).toBe(0)
    expect(makeScene(document.createElement('canvas'), 1)).toBeNull()
  })

  it('Flüstern je Sprache, Blitz-Kanal', () => {
    expect(whispersFor('de')).toBe(WHISPERS.de)
    expect(whispersFor('es')).toBe(WHISPERS.es)
    expect(whispersFor('fr')).toBe(WHISPERS.en)
    expect(WHISPERS.en).toHaveLength(WHISPERS.de.length)
    const fn = vi.fn()
    const off = onBolt(fn)
    emitBolt()
    off()
    emitBolt()
    expect(fn).toHaveBeenCalledTimes(1)
  })

  it('Sprites sind rechteckig und nutzen nur ihre Palette', () => {
    for (const [name, s] of Object.entries(SPRITES)) {
      const w = s.m[0]!.length
      for (const row of s.m) {
        expect(row).toHaveLength(w)
        for (const ch of row) if (ch !== '.') expect(s.pal, `${name}:${ch}`).toHaveProperty(ch)
      }
    }
    expect(spriteSize('pumpkin')).toEqual({ w: 12, h: 12 })
    expect(spriteSize('moonbat')).toEqual({ w: 9, h: 8 })
    expect(spritePixel('pumpkin', 5, 1)).toBe('#4d7a45')
    expect(spritePixel('pumpkin', 0, 0)).toBeNull()
  })
})

// --- Store ---------------------------------------------------------------------------------

const state = vi.fn(async () => halloweenStateSchema.parse(sample))
const findPumpkin = vi.fn(async (_id: string) => ({ found: ['web-home', 'launcher-titlebar', 'launcher-logs'], count: 3, complete: false, reward: null }))
const trick = vi.fn(async () => ({ result: 'sweet' as const, reward: { kind: 'sticker' as const, id: 'ghost_boo', name: { de: 'Buh!', en: 'Boo!', es: '¡Bu!' } }, next: '2026-10-20T22:00:00Z' }))
const playerCapes = vi.fn(async (uuids: string[]) => uuids.map((uuid) => ({ uuid, badges: ['pumpkinhunter2026'] })))
const badgeImages = vi.fn(async (ids: string[]) => ids.map((id) => ({ id, image: 'data:image/png;base64,AAAA' })))

vi.mock('../app/utils/backend', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../app/utils/backend')>()),
  backend: {
    halloween: { state, findPumpkin, trick, setEffects: vi.fn(async () => {}), stickers: vi.fn(async () => []), badgeImages },
    trs: { playerCapes },
  },
}))

const events = reactive({ halloween: true })
const trs = reactive({ enabled: true, status: { account: 'a'.repeat(32) } as { account: string } | null, capesRevision: 0 })
const toasts = { ok: vi.fn(), error: vi.fn(), info: vi.fn() }

describe('Halloween-Store', () => {
  beforeAll(() => {
    vi.stubGlobal('useEventsStore', () => events)
    vi.stubGlobal('useTrsStore', () => trs)
    vi.stubGlobal('useToasts', () => toasts)
  })
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    events.halloween = true
    trs.enabled = true
    vi.useFakeTimers({ now: OCT, toFake: ['Date', 'setTimeout', 'setInterval'] })
  })
  let last: { $dispose(): void } | null = null
  afterEach(() => {
    last?.$dispose()
    last = null
    vi.useRealTimers()
  })
  async function store() {
    const { useHalloweenStore } = await import('../app/stores/halloween')
    const s = useHalloweenStore()
    last = s
    await vi.waitFor(() => expect(state).toHaveBeenCalled())
    await nextTick()
    return s
  }

  it('lädt den Stand, meldet Kürbisse und zeigt den Toast', async () => {
    const s = await store()
    expect(s.found).toBe(2)
    expect(s.visible('launcher-logs')).toBe(true)
    // Danach lädt der Store den Stand neu (nächster Hinweis vom Server).
    state.mockResolvedValueOnce(halloweenStateSchema.parse({ ...sample, pumpkins: { ...sample.pumpkins, found: [...sample.pumpkins.found, 'launcher-logs'] } }))
    expect(await s.find('launcher-logs')).toBe(true)
    expect(findPumpkin).toHaveBeenCalledWith('launcher-logs')
    expect(s.visible('launcher-logs')).toBe(false)
    expect(toasts.ok).toHaveBeenCalledWith('Pumpkin found! 3/13')
    // Schon gefunden → kein zweiter Aufruf.
    expect(await s.find('launcher-titlebar')).toBe(false)
    expect(findPumpkin).toHaveBeenCalledTimes(1)
  })

  it('Süßes oder Saures schließt für heute', async () => {
    const s = await store()
    expect(s.trickOpen).toBe(true)
    const r = await s.trick()
    expect(r.result).toBe('sweet')
    expect(s.trickOpen).toBe(false)
    expect(trs.capesRevision).toBeGreaterThan(0)
  })

  it('alte API oder offline: kein Stand, keine Kürbisse, kein Fehler-Toast', async () => {
    state.mockRejectedValueOnce(new Error('offline'))
    const s = await store()
    expect(s.state).toBeNull()
    expect(s.visible('launcher-logs')).toBe(false)
    expect(toasts.error).not.toHaveBeenCalled()
  })

  it('übernimmt halloween_updated und lädt Abzeichen gebündelt', async () => {
    const s = await store()
    s.onLiveEvent({ type: 'halloween_updated', ...halloweenStateSchema.parse({ ...sample, badges: ['halloween2026', 'pumpkinhunter2026'] }) })
    expect(s.state?.badges).toContain('pumpkinhunter2026')
    s.wantPlayers(['b'.repeat(32), 'c'.repeat(32), 'nope'])
    await vi.advanceTimersByTimeAsync(100)
    expect(playerCapes).toHaveBeenCalledWith(['b'.repeat(32), 'c'.repeat(32)])
    expect(s.playerBadges['b'.repeat(32)]).toEqual(['pumpkinhunter2026'])
    await vi.waitFor(() => expect(s.badgeImages.pumpkinhunter2026).toMatch(/^data:image\/png/))
  })

  it('ohne Event nichts laden', async () => {
    events.halloween = false
    const { useHalloweenStore } = await import('../app/stores/halloween')
    const s = useHalloweenStore()
    last = s
    await nextTick()
    expect(state).not.toHaveBeenCalled()
    expect(s.state).toBeNull()
  })
})
