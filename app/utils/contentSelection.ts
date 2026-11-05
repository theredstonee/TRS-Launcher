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
