import { ref, watch, type Ref } from 'vue'
import type { ServerStatus } from '~/types'

// Ping-Test: misst nur die Latenz zu den Servern – schneller macht er sie nicht.
// Sortieren ändert nur die Anzeige, nie die gespeicherte Reihenfolge.

/** So viele Server werden höchstens gleichzeitig angepingt (wie im Kern). */
export const PING_PARALLEL = 4

/** Latenz eines erreichbaren Servers, sonst `null` (offline oder noch unbekannt). */
export function latencyOf(status: ServerStatus | undefined | null): number | null {
  return status?.online ? status.latencyMs : null
}

/**
 * Stabil nach Ping sortiert: erreichbare Server aufsteigend nach Latenz,
 * nicht erreichbare/unbekannte danach in ihrer bisherigen Reihenfolge.
 */
export function sortByPing<T>(items: readonly T[], latency: (item: T) => number | null): T[] {
  return items
    .map((item, index) => ({ item, index, ms: latency(item) }))
    .sort((a, b) => {
      if (a.ms === null || b.ms === null) return a.ms === b.ms ? a.index - b.index : a.ms === null ? 1 : -1
      return a.ms - b.ms || a.index - b.index
    })
    .map((e) => e.item)
}

/** Führt `work` für alle Einträge aus, höchstens `limit` gleichzeitig. */
export async function runLimited<T>(items: readonly T[], limit: number, work: (item: T) => Promise<unknown>): Promise<void> {
  let next = 0
  const worker = async () => {
    while (next < items.length) {
      const item = items[next++]!
      try {
        await work(item)
      } catch {
        // Ein Fehler hält die anderen nicht auf.
      }
    }
  }
  await Promise.all(Array.from({ length: Math.max(1, Math.min(limit, items.length)) }, worker))
}

/** „Nach Ping sortieren“ merken (nur Komfort – ohne Speicher gilt „aus“). */
export function usePingSort(key: string): Ref<boolean> {
  const storageKey = `trs.pingSort.${key}`
  let initial = false
  try {
    initial = localStorage.getItem(storageKey) === '1'
  } catch {
    // Kein Speicher – dann eben nur für diese Sitzung.
  }
  const enabled = ref(initial)
  watch(enabled, (on) => {
    try {
      if (on) localStorage.setItem(storageKey, '1')
      else localStorage.removeItem(storageKey)
    } catch {
      // Nicht speicherbar – nicht schlimm.
    }
  })
  return enabled
}
