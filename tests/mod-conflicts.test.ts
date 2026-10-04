import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { setLocale } from '../app/utils/i18n'
import {
  type ConflictParty,
  type ConflictReport,
  type FitResult,
  type ModConflict,
  actionableParties,
  conflictReportSchema,
  conflictSentence,
  filesToDisable,
  fitMessage,
  formatRanges,
  isModConflictError,
  MOD_CONFLICT_CODE,
} from '../app/utils/modConflicts'

// Mod-Konflikt-Helfer: Texte aus den Daten des Kerns und der Ablauf im Store
// (prüfen → beheben → neu prüfen → schließen + Starten, Trotzdem starten).

const party = (p: Partial<ConflictParty>): ConflictParty => ({
  kind: 'mod',
  modId: 'x',
  name: 'X',
  version: '1.0',
  fileName: null,
  bundledIn: null,
  projectId: null,
  platform: null,
  slug: null,
  iconUrl: null,
  enabled: true,
  adjustable: false,
  ...p,
})

/** Der Fall aus dem Fehlerbericht: zwei Mods für 1.21.x unter Minecraft 26.1. */
const betterAdvancements: ModConflict = {
  kind: 'depends',
  declarer: party({ modId: 'betteradvancements', name: 'Better Advancements', version: '0.4.8.54', fileName: 'BetterAdvancements.jar', projectId: 'Q2OqKxDG', platform: 'modrinth', adjustable: true }),
  other: party({ kind: 'game', modId: 'minecraft', name: 'Minecraft', version: '26.1' }),
  ranges: ['1.21.x'],
  text: 'Better Advancements 0.4.8.54 ↔ Minecraft 26.1',
}
const betterPing: ModConflict = {
  ...betterAdvancements,
  declarer: party({ modId: 'betterpingdisplay', name: 'Better Ping Display', version: '1.2.0', fileName: 'BetterPing.jar' }),
  text: 'Better Ping Display 1.2.0 ↔ Minecraft 26.1',
}
const sodiumIris: ModConflict = {
  kind: 'breaks',
  declarer: party({ modId: 'sodium', name: 'Sodium', version: '0.8.14', fileName: 'sodium.jar', adjustable: true }),
  other: party({ modId: 'iris', name: 'Iris', version: '1.10.7', fileName: 'iris.jar', adjustable: true }),
  ranges: ['<=1.10.7'],
  text: 'Sodium 0.8.14 ↔ Iris 1.10.7',
}
const oldBuild: ModConflict = {
  kind: 'oldBuild',
  declarer: party({ modId: 'dynamiccrosshair', name: 'Dynamic Crosshair', version: '9.12', fileName: 'dyn.jar' }),
  other: party({ kind: 'game', modId: 'minecraft', name: 'Minecraft', version: '26.1' }),
  ranges: [],
  text: 'Dynamic Crosshair 9.12 ↔ Minecraft 26.1',
}

afterAll(() => setLocale('en'))

describe('Texte', () => {
  it('erklärt jeden Konflikt in Spielersprache (Deutsch)', async () => {
    await setLocale('de')
    expect(conflictSentence(betterAdvancements)).toBe('Better Advancements 0.4.8.54 unterstützt Minecraft 26.1 nicht (braucht 1.21.x).')
    expect(conflictSentence(sodiumIris)).toBe('Sodium 0.8.14 läuft nicht mit Iris 1.10.7 (ausgeschlossen: <=1.10.7).')
    expect(conflictSentence(oldBuild)).toContain('für eine ältere Minecraft-Version gebaut')
    const loader: ModConflict = { ...betterAdvancements, other: party({ kind: 'loader', name: 'Fabric Loader', version: '0.15.0' }), ranges: ['>=0.16.0'] }
    expect(conflictSentence(loader)).toBe('Better Advancements 0.4.8.54 braucht Fabric Loader >=0.16.0, installiert ist 0.15.0.')
    const bundled: ModConflict = { ...betterAdvancements, other: party({ name: 'Fabric API Base', version: '1.0', bundledIn: 'Bobby', fileName: 'bobby.jar' }) }
    expect(conflictSentence(bundled)).toContain('Fabric API Base (in Bobby)')
  })

  it('Englisch und Bereiche', async () => {
    await setLocale('en')
    expect(conflictSentence(betterAdvancements)).toBe("Better Advancements 0.4.8.54 doesn't support Minecraft 26.1 (needs 1.21.x).")
    expect(formatRanges(['>=1.20', '<1.19'])).toBe('>=1.20 or <1.19')
    expect(formatRanges([])).toBe('any version')
    expect(formatRanges(['*'])).toBe('any version')
  })

  it('Ergebnis von „Passende Version suchen“', async () => {
    await setLocale('de')
    expect(fitMessage({ status: 'installed', title: 'Sodium', from: '0.8.9', to: '0.9.2' })).toBe('Sodium von 0.8.9 auf 0.9.2 getauscht.')
    expect(fitMessage({ status: 'none', title: 'Better Advancements', from: null, to: null })).toContain('noch keine Version')
    expect(fitMessage({ status: 'unsupported', title: 'X', from: null, to: null })).toContain('nicht über Modrinth')
  })
})

describe('Daten', () => {
  it('prüft die Antwort des Kerns', () => {
    const report = { conflicts: [betterAdvancements, sodiumIris], blocksLaunch: true }
    expect(conflictReportSchema.parse(report)).toEqual(report)
    expect(() => conflictReportSchema.parse({ conflicts: [{ ...sodiumIris, kind: 'boom' }], blocksLaunch: true })).toThrow()
  })

  it('wer sich anfassen lässt und was „Alle deaktivieren“ trifft', () => {
    // Minecraft selbst hat keine Datei – nur die Mod.
    expect(actionableParties(betterAdvancements).map((p) => p.fileName)).toEqual(['BetterAdvancements.jar'])
    expect(actionableParties(sodiumIris).map((p) => p.fileName)).toEqual(['sodium.jar', 'iris.jar'])
    const report: ConflictReport = { conflicts: [betterAdvancements, betterPing, sodiumIris, { ...sodiumIris, kind: 'depends' }], blocksLaunch: true }
    expect(filesToDisable(report)).toEqual(['BetterAdvancements.jar', 'BetterPing.jar', 'sodium.jar'])
  })

  it('erkennt den gestoppten Start am Fehlercode', () => {
    expect(isModConflictError({ kind: 'launch', code: MOD_CONFLICT_CODE, message: '…' })).toBe(true)
    expect(isModConflictError({ kind: 'launch', code: 'launcher.alreadyRunning' })).toBe(false)
    expect(isModConflictError(null)).toBe(false)
  })
})

// --- Store --------------------------------------------------------------------------

const backend = {
  modConflicts: vi.fn(async (): Promise<ConflictReport> => ({ conflicts: [betterAdvancements, betterPing], blocksLaunch: true })),
  modConflictFit: vi.fn(async (): Promise<FitResult> => ({ status: 'none', title: 'Better Advancements', from: null, to: null })),
  setContentEnabled: vi.fn(async () => {}),
  trashContent: vi.fn(async () => {}),
}
vi.mock('../app/utils/backend', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../app/utils/backend')>()),
  backend,
}))

const games = { launch: vi.fn(async () => true), state: () => ({ phase: 'idle' }) }
const toasts = { ok: vi.fn(), info: vi.fn(), error: vi.fn() }
const router = { push: vi.fn(async () => {}) }
const tasks = {
  run: vi.fn(async (_spec: unknown, work: (ctx: { taskId: string; update: () => void }) => Promise<unknown>) => {
    try {
      return { ok: true, value: await work({ taskId: 't1', update: () => {} }) }
    } catch (error) {
      return { ok: false, cancelled: false, discarded: false, error }
    }
  }),
}

beforeAll(() => {
  vi.stubGlobal('useInstancesStore', () => ({ items: [{ id: 'fabric-26', name: 'Fabric 26.1' }] }))
  vi.stubGlobal('useGamesStore', () => games)
  vi.stubGlobal('useToasts', () => toasts)
  vi.stubGlobal('useRouter', () => router)
  vi.stubGlobal('useTasksStore', () => tasks)
})

beforeEach(async () => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  await setLocale('de')
})

async function store() {
  const { useModConflictsStore } = await import('../app/stores/modConflicts')
  return useModConflictsStore()
}

describe('Konflikt-Helfer', () => {
  it('öffnet mit den Konflikten der Instanz', async () => {
    const s = await store()
    await s.open('fabric-26', 'launch')
    expect(backend.modConflicts).toHaveBeenCalledWith('fabric-26')
    expect(s.isOpen).toBe(true)
    expect(s.origin).toBe('launch')
    expect(s.report?.conflicts).toHaveLength(2)
    expect(s.resolved).toBe(false)
  })

  it('Deaktivieren prüft neu – ist alles gelöst, schließt er und bietet Starten an', async () => {
    const s = await store()
    await s.open('fabric-26')
    backend.modConflicts.mockResolvedValueOnce({ conflicts: [betterPing], blocksLaunch: true })
    await s.disable(betterAdvancements.declarer)
    expect(backend.setContentEnabled).toHaveBeenCalledWith('fabric-26', 'mod', 'BetterAdvancements.jar', false)
    expect(s.isOpen).toBe(true)
    expect(s.report?.conflicts).toHaveLength(1)

    backend.modConflicts.mockResolvedValueOnce({ conflicts: [], blocksLaunch: true })
    await s.remove(betterPing.declarer)
    expect(backend.trashContent).toHaveBeenCalledWith('fabric-26', 'mod', 'BetterPing.jar')
    expect(s.isOpen).toBe(false)
    const [text, action] = toasts.ok.mock.calls.at(-1) as unknown as [string, { label: string; run: () => void }]
    expect(text).toBe('Mod-Konflikte gelöst.')
    expect(action.label).toBe('Starten')
    action.run()
    expect(games.launch).toHaveBeenCalledWith('fabric-26')
  })

  it('„Passende Version suchen“ läuft als Aufgabe und meldet, wenn es keine gibt', async () => {
    const s = await store()
    await s.open('fabric-26')
    await s.findVersion(betterAdvancements.declarer)
    expect(tasks.run).toHaveBeenCalledTimes(1)
    expect(backend.modConflictFit).toHaveBeenCalledWith('fabric-26', 'BetterAdvancements.jar', 't1')
    expect(s.notes['BetterAdvancements.jar']).toContain('noch keine Version')
    // Nichts installiert → nicht neu geprüft, Dialog bleibt offen.
    expect(backend.modConflicts).toHaveBeenCalledTimes(1)
    expect(s.isOpen).toBe(true)

    backend.modConflictFit.mockResolvedValueOnce({ status: 'installed', title: 'Better Advancements', from: '0.4.8.54', to: '0.5.0' })
    await s.findVersion(betterAdvancements.declarer)
    expect(backend.modConflicts).toHaveBeenCalledTimes(2)
  })

  it('„Trotzdem starten“ startet einmal ohne Prüfung', async () => {
    const s = await store()
    await s.open('fabric-26')
    s.launchAnyway()
    expect(s.isOpen).toBe(false)
    expect(games.launch).toHaveBeenCalledWith('fabric-26', null, null, null, { skipModCheck: true })
  })

  it('„Alle betroffenen deaktivieren und starten“', async () => {
    const s = await store()
    await s.open('fabric-26')
    await s.disableAllAndLaunch()
    expect(backend.setContentEnabled.mock.calls.map((c) => (c as unknown[])[2])).toEqual(['BetterAdvancements.jar', 'BetterPing.jar'])
    expect(s.isOpen).toBe(false)
    expect(games.launch).toHaveBeenCalledWith('fabric-26')
  })

  it('Mod-Seite öffnet die Projektseite im Launcher', async () => {
    const s = await store()
    await s.open('fabric-26')
    s.openPage(betterAdvancements.declarer)
    expect(router.push).toHaveBeenCalledWith({ path: '/project/Q2OqKxDG', query: { instance: 'fabric-26' } })
    expect(s.isOpen).toBe(false)
  })
})
