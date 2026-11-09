/**
 * Bereichsauswahl in Listen, wie im Dateimanager: alle Einträge vom Startpunkt bis zum angeklickten, beide eingeschlossen.
 * Gibt `null` zurück, wenn einer der beiden nicht mehr sichtbar ist (gefiltert, eingeklappt, gelöscht).
 */
export function rangeBetween(keys: readonly string[], from: string, to: string): string[] | null {
  const start = keys.indexOf(from)
  const end = keys.indexOf(to)
  if (start < 0 || end < 0) return null
  return keys.slice(Math.min(start, end), Math.max(start, end) + 1)
}

export type RangeAction = 'select' | 'deselect'

/** Die angeklickte Zeile entscheidet: Ist sie schon ausgewählt, wird der ganze Bereich abgewählt. */
export function rangeAction(selected: ReadonlySet<string>, target: string): RangeAction {
  return selected.has(target) ? 'deselect' : 'select'
}

export function applyRange(selected: ReadonlySet<string>, keys: readonly string[], action: RangeAction): Set<string> {
  const next = new Set(selected)
  for (const key of keys) {
    if (action === 'select') next.add(key)
    else next.delete(key)
  }
  return next
}
