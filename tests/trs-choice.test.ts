import { describe, expect, it } from 'vitest'
import {
  trsAfterPreview,
  trsAsks,
  trsBulkAsking,
  trsBulkInit,
  trsBulkRequest,
  trsBulkSetAll,
  trsBulkToggle,
  trsBulkWith,
  trsPreselect,
  trsRequest,
  trsShowsChoice,
  trsSoftConflicts,
  trsStrongConflicts,
} from '../app/utils/trsChoice'
import { settingsSchema } from '../app/utils/schemas'
import type { TrsOffer } from '../app/types'

function offer(patch: Partial<TrsOffer> = {}): TrsOffer {
  return {
    applies: true,
    supported: true,
    unsupported: null,
    recommended: true,
    conflicts: [],
    policy: 'ask',
    ...patch,
  }
}

const essential = offer({ recommended: false, conflicts: [{ name: 'Essential', kind: 'clientMod' }] })
const zoomOnly = offer({ conflicts: [{ name: 'Zoomify', kind: 'zoom' }] })
const noBuild = offer({ supported: false, recommended: false, unsupported: { kind: 'noBuild', loader: 'forge', gameVersion: '1.12.2' } })
const vanilla = offer({ applies: false, recommended: false })

describe('Einzel-Dialog (Modpack installieren, Pack-Datei)', () => {
  it('fragt nur bei Packs mit Modloader, Build und Einstellung „fragen“', () => {
    expect(trsAsks(offer())).toBe(true)
    expect(trsAsks(essential)).toBe(true)
    expect(trsAsks(noBuild)).toBe(false)
    expect(trsAsks(vanilla)).toBe(false)
    expect(trsAsks(offer({ policy: 'always' }))).toBe(false)
    expect(trsAsks(null)).toBe(false)
  })

  it('zeigt ohne Build ausgegraute Karten, bei Vanilla und „immer …“ gar keine', () => {
    expect(trsShowsChoice(noBuild)).toBe(true)
    expect(trsShowsChoice(vanilla)).toBe(false)
    expect(trsShowsChoice(offer({ policy: 'never' }))).toBe(false)
  })

  it('wählt „mit“ vor – außer bei sich beißenden Mods oder ohne Build', () => {
    expect(trsPreselect(offer())).toBe(true)
    expect(trsPreselect(zoomOnly)).toBe(true)
    expect(trsPreselect(essential)).toBe(false)
    expect(trsPreselect(noBuild)).toBe(false)
    expect(trsPreselect(null)).toBe(true)
  })

  it('trennt starke Überschneidungen von bloßen Hinweisen', () => {
    const both = offer({ recommended: false, conflicts: [{ name: "Xaero's Minimap", kind: 'minimap' }, { name: 'Zoomify', kind: 'zoom' }] })
    expect(trsStrongConflicts(both).map((c) => c.name)).toEqual(["Xaero's Minimap"])
    expect(trsSoftConflicts(both).map((c) => c.name)).toEqual(['Zoomify'])
    expect(trsStrongConflicts(null)).toEqual([])
  })

  it('schickt die Wahl nur, wenn gefragt wurde', () => {
    expect(trsRequest(offer(), false)).toBe(false)
    expect(trsRequest(essential, true)).toBe(true)
    expect(trsRequest(noBuild, true)).toBe(false)
    expect(trsRequest(vanilla, true)).toBeNull()
    expect(trsRequest(offer({ policy: 'always' }), false)).toBeNull()
    // Vorschau fehlgeschlagen: Die Karten waren wählbar, die Wahl zählt.
    expect(trsRequest(null, false)).toBe(false)
  })

  it('übernimmt die Vorauswahl der Vorschau, solange niemand gewählt hat', () => {
    expect(trsAfterPreview(essential, false, true)).toBe(false)
    expect(trsAfterPreview(essential, true, true)).toBe(true)
    expect(trsAfterPreview(offer(), true, false)).toBe(false)
    expect(trsAfterPreview(noBuild, true, true)).toBe(false)
    expect(trsAfterPreview(null, false, false)).toBe(true)
  })
})

describe('Sammel-Import: eine Frage für alle, Ausnahmen je Instanz', () => {
  const items = [
    { id: 'plain', trsClient: offer() },
    { id: 'essential', trsClient: essential },
    { id: 'old', trsClient: noBuild },
    { id: 'vanilla', trsClient: vanilla },
    { id: 'unknown', trsClient: null },
  ]

  it('fragt nur Instanzen mit Mods und passendem Build', () => {
    expect(trsBulkAsking(items).map((i) => i.id)).toEqual(['plain', 'essential'])
  })

  it('startet mit „mit“ und nimmt Instanzen mit sich beißenden Mods aus', () => {
    const state = trsBulkInit(items)
    expect(state.all).toBe(true)
    expect([...state.exceptions]).toEqual(['essential'])
    expect(trsBulkRequest(state, items[0]!)).toBe(true)
    expect(trsBulkRequest(state, items[1]!)).toBe(false)
    expect(trsBulkRequest(state, items[2]!)).toBe(false)
    expect(trsBulkRequest(state, items[3]!)).toBeNull()
    expect(trsBulkRequest(state, items[4]!)).toBeNull()
  })

  it('startet mit „ohne“, wenn keine gefragte Instanz dazu passt', () => {
    expect(trsBulkInit([{ id: 'essential', trsClient: essential }]).all).toBe(false)
    expect(trsBulkInit([]).all).toBe(true)
  })

  it('Häkchen kehrt die Wahl für eine Instanz um, neue Wahl für alle setzt zurück', () => {
    let state = trsBulkInit(items)
    state = trsBulkToggle(state, 'essential')
    expect(trsBulkWith(state, 'essential')).toBe(true)
    state = trsBulkToggle(state, 'plain')
    expect(trsBulkWith(state, 'plain')).toBe(false)

    state = trsBulkSetAll(items, false)
    expect(state.exceptions.size).toBe(0)
    expect(trsBulkRequest(state, items[0]!)).toBe(false)
    state = trsBulkToggle(state, 'plain')
    expect(trsBulkRequest(state, items[0]!)).toBe(true)

    state = trsBulkSetAll(items, true)
    expect([...state.exceptions]).toEqual(['essential'])
  })

  it('Einstellung „immer …“: keine Frage, der Kern entscheidet', () => {
    const always = [{ id: 'a', trsClient: offer({ policy: 'always' }) }]
    expect(trsBulkAsking(always)).toEqual([])
    expect(trsBulkRequest(trsBulkInit(always), always[0]!)).toBeNull()
  })
})

describe('Einstellung „Bei Modpacks: TRS Client“', () => {
  const field = settingsSchema.shape.modpackTrsClient

  it('fehlt sie, gilt „immer fragen“; unbekannte Werte werden abgelehnt', () => {
    expect(field.parse(undefined)).toBe('ask')
    expect(field.parse('never')).toBe('never')
    expect(field.parse('always')).toBe('always')
    expect(field.safeParse('sometimes').success).toBe(false)
  })
})
