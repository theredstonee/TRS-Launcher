import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick, reactive } from 'vue'

// Event-Symbole: Der Events-Store meldet die laufenden Events an den Kern (`event_icon_sync`) – beim
// Start (sobald die Einstellungen da sind), bei `events_changed` und beim Umlegen des Schalters.
// Gleiche Lage → kein erneuter Aufruf.

const eventIconSync = vi.fn(async (_events: string[]) => null as string | null)
const activeEvents = vi.fn(async () => [] as string[])

vi.mock('../app/utils/backend', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../app/utils/backend')>()),
  backend: { eventIconSync, trs: { activeEvents } },
}))

type Ui = { eventIcons?: boolean }
const trs = reactive({ me: null as { events?: string[] } | null, status: null as object | null, enabled: false })
const settings = reactive({ current: null as { ui: Ui } | null })

beforeAll(() => {
  vi.stubGlobal('useTrsStore', () => trs)
  vi.stubGlobal('useSettingsStore', () => settings)
})

beforeEach(() => {
  setActivePinia(createPinia())
  vi.clearAllMocks()
  trs.me = null
  trs.status = null
  trs.enabled = false
  settings.current = null
})

let last: { $dispose(): void } | null = null

// Watcher des vorigen Stores beenden (die Attrappen sind global).
afterEach(() => {
  last?.$dispose()
  last = null
})

async function store() {
  const { useEventsStore } = await import('../app/stores/events')
  const s = useEventsStore()
  last = s
  return s
}

describe('Event-Symbole', () => {
  it('wartet auf die Einstellungen und meldet dann die Events', async () => {
    const events = await store()
    await nextTick()
    expect(eventIconSync).not.toHaveBeenCalled()
    trs.status = {}
    trs.enabled = true
    trs.me = { events: ['halloween'] }
    settings.current = { ui: { eventIcons: true } }
    await nextTick()
    expect(events.halloween).toBe(true)
    expect(eventIconSync).toHaveBeenCalledTimes(1)
    expect(eventIconSync).toHaveBeenLastCalledWith(['halloween'])
  })

  it('reagiert auf events_changed und den Schalter, aber nicht doppelt', async () => {
    trs.status = {}
    trs.enabled = true
    trs.me = { events: [] }
    settings.current = { ui: { eventIcons: true } }
    await store()
    await nextTick()
    expect(eventIconSync).toHaveBeenCalledTimes(1)
    expect(eventIconSync).toHaveBeenLastCalledWith([])

    // events_changed: Halloween an.
    trs.me = { events: ['halloween'] }
    await nextTick()
    expect(eventIconSync).toHaveBeenCalledTimes(2)
    expect(eventIconSync).toHaveBeenLastCalledWith(['halloween'])

    // Neues `me` mit denselben Events: nichts zu tun.
    trs.me = { events: ['halloween'] }
    await nextTick()
    expect(eventIconSync).toHaveBeenCalledTimes(2)

    // Schalter aus: Kern stellt zurück (er liest den Schalter selbst).
    settings.current = { ui: { eventIcons: false } }
    await nextTick()
    expect(eventIconSync).toHaveBeenCalledTimes(3)
    settings.current = { ui: { eventIcons: false } }
    await nextTick()
    expect(eventIconSync).toHaveBeenCalledTimes(3)

    // Event vorbei.
    settings.current = { ui: { eventIcons: true } }
    trs.me = { events: [] }
    await nextTick()
    expect(eventIconSync).toHaveBeenCalledTimes(4)
    expect(eventIconSync).toHaveBeenLastCalledWith([])
  })

  it('abgemeldet: kein Event', async () => {
    trs.status = {}
    trs.enabled = false
    settings.current = { ui: {} }
    const events = await store()
    await nextTick()
    expect(events.active).toEqual([])
    expect(eventIconSync).toHaveBeenLastCalledWith([])
  })

  it('ein Fehler im Kern stört die Oberfläche nicht', async () => {
    eventIconSync.mockRejectedValueOnce(new Error('kein Tauri'))
    trs.status = {}
    trs.enabled = true
    trs.me = { events: ['halloween'] }
    settings.current = { ui: { eventIcons: true } }
    const events = await store()
    await nextTick()
    await Promise.resolve()
    expect(events.halloween).toBe(true)
  })
})
