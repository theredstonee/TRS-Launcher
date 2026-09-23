// Regeln für die Toast-Liste – reine Funktionen, getestet in tests/toasts.test.ts.
// Gleiche Meldungen werden zusammengefasst (×N), und es stehen nie mehr als
// `MAX_VISIBLE_TOASTS` gleichzeitig auf dem Bildschirm.

export type ToastKind = 'ok' | 'error' | 'info'

export interface Toast {
  id: number
  kind: ToastKind
  text: string
  /** Wie oft die gleiche Meldung kam (≥ 1). */
  count: number
}

export const MAX_VISIBLE_TOASTS = 3

export interface ToastAdd {
  items: Toast[]
  /** Toast, dessen Zeitgeber (neu) starten muss. */
  id: number
  /** Toasts, die wegen der Obergrenze entfernt wurden (Zeitgeber aufräumen). */
  dropped: number[]
}

/**
 * Fügt eine Meldung hinzu. Steht dieselbe (gleiche Art, gleicher Text) schon da,
 * zählt sie hoch und rückt nach unten (neueste zuletzt). Bei zu vielen fliegen
 * die ältesten raus – bevorzugt keine Fehler, solange andere da sind.
 */
export function addToast(items: readonly Toast[], kind: ToastKind, text: string, nextId: number, max = MAX_VISIBLE_TOASTS): ToastAdd {
  const existing = items.find((t) => t.kind === kind && t.text === text)
  if (existing) {
    const merged = { ...existing, count: existing.count + 1 }
    return { items: [...items.filter((t) => t.id !== existing.id), merged], id: existing.id, dropped: [] }
  }

  const next = [...items, { id: nextId, kind, text, count: 1 }]
  const dropped: number[] = []
  while (next.length > Math.max(1, max)) {
    // Älteste Nicht-Fehlermeldung zuerst opfern, sonst die älteste überhaupt.
    let index = next.findIndex((t, i) => t.kind !== 'error' && i < next.length - 1)
    if (index < 0) index = 0
    dropped.push(next[index]!.id)
    next.splice(index, 1)
  }
  return { items: next, id: nextId, dropped }
}
