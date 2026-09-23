import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { ref } from 'vue'
import type { NewTaskRecord, TaskRecord } from '../app/types'
import {
  formatAgo,
  formatEta,
  formatProgressBytes,
  formatSize,
  packPercent,
  SpeedMeter,
  taskKey,
} from '../app/utils/tasks'

// Kern-Aufrufe nachgebildet: Verlauf im Speicher, Abbrechen/Pausieren protokolliert.
const saved: TaskRecord[] = []
const calls: string[] = []
vi.mock('../app/utils/backend', async (importOriginal) => {
  const real = await importOriginal<typeof import('../app/utils/backend')>()
  return {
    ...real,
    backend: {
      recordTask: vi.fn(async (r: NewTaskRecord) => {
        const record: TaskRecord = {
          id: `r${saved.length + 1}`,
          kind: r.kind,
          title: r.title,
          outcome: r.outcome,
          finishedAt: new Date().toISOString(),
          ...(r.instanceId ? { instanceId: r.instanceId } : {}),
          ...(r.detail ? { detail: r.detail } : {}),
        }
        saved.unshift(record)
        return record
      }),
      taskHistory: vi.fn(async () => [...saved]),
      removeTaskRecord: vi.fn(async () => {}),
      clearTaskHistory: vi.fn(async () => {}),
      cancelTask: vi.fn(async (id: string) => {
        calls.push(`cancel ${id}`)
        return true
      }),
      pauseTask: vi.fn(async (id: string, paused: boolean) => {
        calls.push(`pause ${id} ${paused}`)
        return true
      }),
    },
  }
})

describe('Formatierung', () => {
  it('Größen mit drei Stellen', () => {
    expect(formatSize(0)).toBe('0 B')
    expect(formatSize(512)).toBe('512 B')
    expect(formatSize(20.6 * 1024 * 1024)).toBe('20,6 MB')
    expect(formatSize(9.79 * 1024 * 1024)).toBe('9,79 MB')
    expect(formatSize(172.2 * 1024 * 1024)).toBe('172 MB')
  })

  it('Fortschritt in der Einheit der Gesamtgröße', () => {
    expect(formatProgressBytes(17.9 * 1048576, 172.2 * 1048576)).toBe('17,9 / 172 MB')
    expect(formatProgressBytes(5 * 1048576, 0)).toBe('5,00 MB')
  })

  it('Restzeit', () => {
    expect(formatEta(0, 100)).toBeNull()
    expect(formatEta(100, 0)).toBeNull()
    expect(formatEta(800, 100)).toBe('8 s')
    expect(formatEta(180 * 100, 100)).toBe('3 min')
    expect(formatEta(65 * 60 * 100, 100)).toBe('1 h 5 min')
  })

  it('relative Zeit', () => {
    const now = Date.UTC(2026, 8, 23)
    expect(formatAgo(now - 10_000, now)).toBe('gerade eben')
    expect(formatAgo(now - 8 * 86_400_000, now)).toBe('letzte Woche')
    expect(formatAgo(now - 70 * 86_400_000, now)).toBe('vor 2 Monaten')
  })

  it('Modpack-Prozent und Aufgaben-IDs', () => {
    expect(packPercent({ phase: 'pack', percent: 50 })).toBe(5)
    expect(packPercent({ phase: 'files', percent: 100 })).toBe(95)
    expect(packPercent({ phase: 'overrides', percent: 100 })).toBe(100)
    expect(taskKey('modpack', 'AB c/d')).toBe('modpack:AB_c_d')
  })

  it('Geschwindigkeit über ein gleitendes Fenster', () => {
    const meter = new SpeedMeter(3000)
    expect(meter.add(0, 0)).toBe(0)
    expect(meter.add(1000, 1000)).toBe(1000)
    expect(meter.add(3000, 2000)).toBe(1500)
    // Zurückgenommener Fehlversuch → Messung beginnt neu.
    expect(meter.add(500, 2500)).toBe(0)
  })
})

describe('useTasksStore', () => {
  beforeAll(() => {
    vi.stubGlobal('ref', ref)
    vi.stubGlobal('errorMessage', (e: unknown) => (e instanceof Error ? e.message : 'Fehler'))
  })

  beforeEach(() => {
    saved.length = 0
    calls.length = 0
    setActivePinia(createPinia())
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  async function stores() {
    const { useTasksStore, cancelledError } = await import('../app/stores/tasks')
    const { useToasts } = await import('../app/stores/toasts')
    return { tasks: useTasksStore(), toasts: useToasts(), cancelledError }
  }

  /** Ein von außen steuerbares Versprechen. */
  function deferred<T>() {
    let resolve!: (v: T) => void
    let reject!: (e: unknown) => void
    const promise = new Promise<T>((res, rej) => {
      resolve = res
      reject = rej
    })
    return { promise, resolve, reject }
  }

  it('startet denselben Vorgang nicht doppelt, sondern zeigt ihn', async () => {
    const { tasks } = await stores()
    const gate = deferred<string>()
    const work = vi.fn(() => gate.promise)
    const spec = { key: 'modpack:x', kind: 'modpack' as const, title: 'Pack' }
    const first = tasks.run(spec, work)
    const second = tasks.run(spec, work)
    expect(work).toHaveBeenCalledTimes(1)
    expect(tasks.panelOpen).toBe(true)
    expect(tasks.focused).toBe('modpack:x')
    gate.resolve('ok')
    expect(await first).toEqual({ ok: true, value: 'ok' })
    expect(await second).toEqual({ ok: true, value: 'ok' })
  })

  it('zeigt Fortschritt, Bytes und Geschwindigkeit', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(0)
    const { tasks } = await stores()
    const gate = deferred<void>()
    tasks.run({ key: 'java:21', kind: 'java', title: 'Java 21' }, async (ctx) => {
      ctx.progress(41.7, 'Java wird geladen')
      await gate.promise
    })
    const t = tasks.get('java:21')!
    expect(t.percent).toBe(41)
    expect(t.stage).toBe('Java wird geladen')
    expect(tasks.active).toHaveLength(1)

    tasks.onProgress({ taskId: 'java:21', doneBytes: 0, totalBytes: 4000, paused: false })
    vi.setSystemTime(1000)
    tasks.onProgress({ taskId: 'java:21', doneBytes: 2000, totalBytes: 4000, paused: false })
    expect(t.doneBytes).toBe(2000)
    expect(t.totalBytes).toBe(4000)
    expect(t.speed).toBe(2000)
    expect(tasks.totalSpeed).toBe(2000)
    // Unbekannte Aufgaben werden ignoriert.
    tasks.onProgress({ taskId: 'fremd', doneBytes: 1, totalBytes: 1, paused: false })
    gate.resolve()
    await vi.runAllTimersAsync()
  })

  it('meldet Erfolg einmal, merkt ihn im Verlauf und bietet die Instanz an', async () => {
    const { tasks, toasts } = await stores()
    const nav = vi.fn()
    await tasks.init(nav)
    const result = await tasks.run(
      { key: 'modpack:fo', kind: 'modpack', title: 'Fabulously Optimized', doneText: 'Modpack bereit' },
      async (ctx) => {
        ctx.update({ instanceId: 'fabulously-optimized' })
        return 1
      },
    )
    expect(result.ok).toBe(true)
    const t = tasks.get('modpack:fo')!
    expect(t.status).toBe('done')
    expect(t.percent).toBe(100)
    expect(tasks.active).toHaveLength(0)
    expect(toasts.items).toHaveLength(1)
    expect(toasts.items[0]!.text).toBe('Modpack bereit')
    expect(tasks.history[0]).toMatchObject({ kind: 'modpack', title: 'Fabulously Optimized', outcome: 'done' })
    expect(t.recordId).toBe(tasks.history[0]!.id)

    toasts.items[0]!.action!.run()
    expect(nav).toHaveBeenCalledWith('/instances/fabulously-optimized')
  })

  it('Fehlschlag: Fehler-Toast, Verlaufseintrag und „Erneut versuchen“', async () => {
    const { tasks, toasts } = await stores()
    let attempt = 0
    const work = vi.fn(async () => {
      attempt++
      if (attempt === 1) throw new Error('Netz weg')
      return 'ok'
    })
    const result = await tasks.run({ key: 'import:x', kind: 'import', title: 'Import' }, work)
    expect(result).toMatchObject({ ok: false, cancelled: false })
    const t = tasks.get('import:x')!
    expect(t.status).toBe('failed')
    expect(toasts.items[0]).toMatchObject({ kind: 'error' })
    const record = tasks.history[0]!
    expect(record.outcome).toBe('failed')
    expect(tasks.canRetry(record.id)).toBe(true)

    tasks.retry(record.id)
    await vi.waitFor(() => expect(tasks.get('import:x')!.status).toBe('done'))
    expect(work).toHaveBeenCalledTimes(2)
    expect(tasks.history.filter((r) => r.outcome === 'failed')).toHaveLength(0)
  })

  it('Abbrechen: nur wenn erlaubt, ohne Toast und ohne Verlauf', async () => {
    const { tasks, toasts, cancelledError } = await stores()
    const gate = deferred<void>()
    const running = tasks.run(
      { key: 'modpack:y', kind: 'modpack', title: 'Pack', cancellable: true, pausable: true },
      async () => {
        await gate.promise
      },
    )
    await tasks.pause('modpack:y', true)
    expect(tasks.get('modpack:y')!.paused).toBe(true)
    await tasks.cancel('modpack:y')
    await tasks.cancel('modpack:y')
    expect(calls).toEqual(['pause modpack:y true', 'cancel modpack:y'])
    expect(tasks.get('modpack:y')!.cancelling).toBe(true)
    gate.reject(cancelledError())
    expect(await running).toMatchObject({ ok: false, cancelled: true })
    expect(tasks.get('modpack:y')!.status).toBe('cancelled')
    expect(toasts.items).toHaveLength(0)
    expect(tasks.history).toHaveLength(0)

    // Nicht abbrechbare Aufgaben ignorieren den Wunsch.
    const other = deferred<void>()
    tasks.run({ key: 'export:z', kind: 'export', title: 'Export' }, () => other.promise)
    await tasks.cancel('export:z')
    expect(calls).toHaveLength(2)
    other.resolve()
  })

  it('Frontend-Schleifen sehen den Abbruch über ctx.cancelled()', async () => {
    const { tasks, cancelledError } = await stores()
    const gate = deferred<void>()
    const running = tasks.run(
      { key: 'updates:a', kind: 'content-update', title: 'Updates', cancellable: true },
      async (ctx) => {
        await gate.promise
        if (ctx.cancelled()) throw cancelledError()
      },
    )
    await tasks.cancel('updates:a')
    gate.resolve()
    expect(await running).toMatchObject({ ok: false, cancelled: true })
  })

  it('verworfene Aufgaben hinterlassen keine Spuren', async () => {
    const { tasks, toasts } = await stores()
    const result = await tasks.run({ key: 'mrpack-file', kind: 'modpack-file', title: 'Datei' }, async (ctx) => {
      ctx.discard()
      return null
    })
    expect(result).toMatchObject({ ok: false, discarded: true })
    expect(tasks.get('mrpack-file')).toBeNull()
    expect(toasts.items).toHaveLength(0)
    expect(tasks.history).toHaveLength(0)
  })

  it('lädt den Verlauf aus dem Kern und räumt ihn auf', async () => {
    saved.push({ id: 'alt', kind: 'duplicate', title: 'Kopie', outcome: 'done', finishedAt: '2026-01-01T00:00:00Z' })
    const { tasks } = await stores()
    await tasks.init(() => {})
    expect(tasks.history.map((r) => r.id)).toEqual(['alt'])
    tasks.note({ kind: 'create', title: 'Neu', outcome: 'done', instanceId: 'neu' })
    await vi.waitFor(() => expect(tasks.history).toHaveLength(2))
    expect(tasks.history[0]!.title).toBe('Neu')
    tasks.removeRecord('alt')
    expect(tasks.history.map((r) => r.title)).toEqual(['Neu'])
    tasks.clearHistory()
    expect(tasks.history).toHaveLength(0)
  })
})
