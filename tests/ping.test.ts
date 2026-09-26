import { afterEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import type { ServerStatus } from '../app/types'
import { latencyOf, runLimited, sortByPing, usePingSort } from '../app/utils/ping'

// Ping-Test: Sortierung nur für die Anzeige, begrenzte Parallelität, gemerkter Schalter.

const status = (online: boolean, latencyMs = 0): ServerStatus => ({
  online,
  playersOnline: 0,
  playersMax: 0,
  motd: '',
  version: '',
  favicon: null,
  latencyMs,
})

afterEach(() => vi.unstubAllGlobals())

describe('latencyOf', () => {
  it('nur erreichbare Server haben eine Latenz', () => {
    expect(latencyOf(status(true, 42))).toBe(42)
    expect(latencyOf(status(false, 42))).toBeNull()
    expect(latencyOf(undefined)).toBeNull()
  })
})

describe('sortByPing', () => {
  it('erreichbare aufsteigend, unbekannte/offline stabil am Ende', () => {
    const items = [
      { id: 'a', ms: null },
      { id: 'b', ms: 120 },
      { id: 'c', ms: 30 },
      { id: 'd', ms: null },
      { id: 'e', ms: 30 },
      { id: 'f', ms: 0 },
    ]
    const sorted = sortByPing(items, (i) => i.ms)
    expect(sorted.map((i) => i.id)).toEqual(['f', 'c', 'e', 'b', 'a', 'd'])
    // Nur eine sortierte Kopie – die Liste selbst bleibt unverändert.
    expect(items.map((i) => i.id)).toEqual(['a', 'b', 'c', 'd', 'e', 'f'])
  })
})

describe('runLimited', () => {
  it('arbeitet alles ab, höchstens `limit` gleichzeitig, auch bei Fehlern', async () => {
    let active = 0
    let max = 0
    const done: number[] = []
    await runLimited([1, 2, 3, 4, 5, 6, 7, 8, 9, 10], 4, async (n) => {
      active++
      max = Math.max(max, active)
      await new Promise((r) => setTimeout(r, 5))
      active--
      if (n === 3) throw new Error('offline')
      done.push(n)
    })
    expect(max).toBe(4)
    expect(done.sort((a, b) => a - b)).toEqual([1, 2, 4, 5, 6, 7, 8, 9, 10])
  })

  it('kommt mit einer leeren Liste klar', async () => {
    await expect(runLimited([], 4, async () => {})).resolves.toBeUndefined()
  })
})

describe('usePingSort', () => {
  it('merkt sich den Schalter je Ort', async () => {
    const store = new Map<string, string>()
    vi.stubGlobal('localStorage', {
      getItem: (k: string) => store.get(k) ?? null,
      setItem: (k: string, v: string) => void store.set(k, v),
      removeItem: (k: string) => void store.delete(k),
    })
    const sort = usePingSort('servers')
    expect(sort.value).toBe(false)
    sort.value = true
    await nextTick()
    expect(usePingSort('servers').value).toBe(true)
    expect(usePingSort('instanceServers').value).toBe(false)
    sort.value = false
    await nextTick()
    expect(usePingSort('servers').value).toBe(false)
  })

  it('funktioniert ohne Speicher (Standard: aus)', async () => {
    vi.stubGlobal('localStorage', {
      getItem: () => {
        throw new Error('blockiert')
      },
      setItem: () => {
        throw new Error('blockiert')
      },
      removeItem: () => {
        throw new Error('blockiert')
      },
    })
    const sort = usePingSort('servers')
    expect(sort.value).toBe(false)
    sort.value = true
    await nextTick()
    expect(sort.value).toBe(true)
  })
})
