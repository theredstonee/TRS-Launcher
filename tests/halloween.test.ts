import { describe, expect, it } from 'vitest'
import { applyEventTheme, EVENT_STORAGE_KEY, HALLOWEEN_EVENT, isEventPlayer, witchMotion } from '../app/utils/halloween'

describe('Halloween-Theme', () => {
  function harness() {
    const dataset: Record<string, string | undefined> = { accent: 'emerald', theme: 'light' }
    const mem = new Map<string, string>()
    const storage = {
      setItem: (key: string, value: string) => mem.set(key, value),
      removeItem: (key: string) => {
        mem.delete(key)
      },
    }
    return { dataset, mem, storage, root: { dataset } }
  }

  it('setzt data-event, ohne die gespeicherte Akzentfarbe anzufassen', () => {
    const h = harness()
    applyEventTheme([HALLOWEEN_EVENT, 'other'], { root: h.root, storage: h.storage })
    expect(h.dataset.event).toBe('halloween')
    expect(h.dataset.accent).toBe('emerald')
    expect(h.dataset.theme).toBe('light')
    expect(h.mem.get(EVENT_STORAGE_KEY)).toBe('halloween')
  })

  it('nimmt das Theme wieder weg, wenn das Event aus ist', () => {
    const h = harness()
    h.dataset.event = 'halloween'
    h.mem.set(EVENT_STORAGE_KEY, 'halloween')
    applyEventTheme([], { root: h.root, storage: h.storage })
    expect(h.dataset.event).toBeUndefined()
    expect(h.dataset.accent).toBe('emerald')
    expect(h.mem.has(EVENT_STORAGE_KEY)).toBe(false)
  })

  it('erkennt Namen und UUIDs für die Allowlist', () => {
    expect(isEventPlayer('Notch')).toBe(true)
    expect(isEventPlayer('a'.repeat(16))).toBe(true)
    expect(isEventPlayer('069a79f444e94726a5befca90e38aaf5')).toBe(true)
    expect(isEventPlayer('069a79f4-44e9-4726-a5be-fca90e38aaf5')).toBe(true)
    expect(isEventPlayer('')).toBe(false)
    expect(isEventPlayer('bad name')).toBe(false)
    expect(isEventPlayer('../x')).toBe(false)
  })

  it('füllt den Balken und schaltet die Lampe erst bei 100 % an', () => {
    expect(witchMotion(0, 0, false, false).p).toBe(0)
    expect(witchMotion(0, 40, false, false).lamp).toBe(false)
    expect(witchMotion(0, 100, false, false)).toMatchObject({ p: 1, lamp: true })
    expect(witchMotion(0, 0, false, true)).toMatchObject({ p: 1, lamp: true })
    expect(witchMotion(0, 0, true, false)).toMatchObject({ p: 0, lamp: false })
    expect(witchMotion(4000, 0, true, false)).toMatchObject({ p: 1, lamp: true })
    expect(witchMotion(2000, 0, true, false)).toMatchObject({ p: 0.5, lamp: false })
    expect(witchMotion(8000, 0, true, false).p).toBe(0)
    expect(witchMotion(0, 0, false, false).frame).toBe(0)
    expect(witchMotion(110, 0, false, false).frame).toBe(1)
  })
})
