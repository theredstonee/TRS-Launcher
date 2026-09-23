import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { ref } from 'vue'
import { addToast, MAX_VISIBLE_TOASTS, type Toast } from '../app/utils/toastQueue'

describe('addToast', () => {
  it('fasst gleiche Meldungen zu einer mit Zähler zusammen', () => {
    let items: Toast[] = []
    let id = 1
    for (let i = 0; i < 5; i++) {
      const result = addToast(items, 'error', 'Mojang bremst gerade', id)
      if (result.id === id) id++
      items = result.items
    }
    expect(items).toHaveLength(1)
    expect(items[0]).toMatchObject({ kind: 'error', text: 'Mojang bremst gerade', count: 5 })
  })

  it('unterscheidet Art und Text', () => {
    let items: Toast[] = []
    items = addToast(items, 'error', 'A', 1).items
    items = addToast(items, 'info', 'A', 2).items
    items = addToast(items, 'error', 'B', 3).items
    expect(items.map((t) => [t.kind, t.text, t.count])).toEqual([
      ['error', 'A', 1],
      ['info', 'A', 1],
      ['error', 'B', 1],
    ])
  })

  it('schiebt eine wiederholte Meldung nach unten (neueste zuletzt)', () => {
    let items: Toast[] = []
    items = addToast(items, 'error', 'A', 1).items
    items = addToast(items, 'ok', 'B', 2).items
    const result = addToast(items, 'error', 'A', 3)
    expect(result.id).toBe(1)
    expect(result.items.map((t) => t.text)).toEqual(['B', 'A'])
  })

  it('zeigt nie mehr als die Obergrenze – Fehler bleiben bevorzugt stehen', () => {
    let items: Toast[] = []
    const dropped: number[] = []
    const texts = ['Fehler 1', 'ok 1', 'Fehler 2', 'ok 2', 'Fehler 3']
    texts.forEach((text, i) => {
      const result = addToast(items, text.startsWith('ok') ? 'ok' : 'error', text, i + 1)
      items = result.items
      dropped.push(...result.dropped)
    })
    expect(items).toHaveLength(MAX_VISIBLE_TOASTS)
    expect(items.map((t) => t.text)).toEqual(['Fehler 1', 'Fehler 2', 'Fehler 3'])
    expect(dropped).toEqual([2, 4])
  })

  it('verdrängt bei lauter Fehlern den ältesten', () => {
    let items: Toast[] = []
    for (let i = 1; i <= 5; i++) items = addToast(items, 'error', `Fehler ${i}`, i).items
    expect(items.map((t) => t.text)).toEqual(['Fehler 3', 'Fehler 4', 'Fehler 5'])
  })
})

describe('useToasts', () => {
  // Der Store nutzt Nuxts Auto-Imports – hier von Hand bereitgestellt.
  beforeAll(() => {
    vi.stubGlobal('ref', ref)
    vi.stubGlobal('errorMessage', (e: unknown) => (e instanceof Error ? e.message : 'Fehler'))
  })

  beforeEach(() => {
    vi.useFakeTimers()
    setActivePinia(createPinia())
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  async function store() {
    const { useToasts } = await import('../app/stores/toasts')
    return useToasts()
  }

  it('stapelt gleiche Fehler nicht und startet die Zeit neu', async () => {
    const toasts = await store()
    toasts.error('Zu viele Änderungen')
    vi.advanceTimersByTime(7000)
    toasts.error('Zu viele Änderungen')
    toasts.error('Zu viele Änderungen')
    expect(toasts.items).toHaveLength(1)
    expect(toasts.items[0]!.count).toBe(3)

    // Der erste Zeitgeber (8 s) darf den zusammengefassten Toast nicht schließen.
    vi.advanceTimersByTime(2000)
    expect(toasts.items).toHaveLength(1)
    vi.advanceTimersByTime(6100)
    expect(toasts.items).toHaveLength(0)
  })

  it('begrenzt die sichtbaren Toasts und räumt sauber ab', async () => {
    const toasts = await store()
    for (let i = 0; i < 10; i++) toasts.info(`Info ${i}`)
    expect(toasts.items).toHaveLength(MAX_VISIBLE_TOASTS)
    toasts.dismiss(toasts.items[0]!.id)
    expect(toasts.items).toHaveLength(MAX_VISIBLE_TOASTS - 1)
    vi.advanceTimersByTime(4100)
    expect(toasts.items).toHaveLength(0)
  })
})
