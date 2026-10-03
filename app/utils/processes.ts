// Mehrere Prozesse derselben Instanz („Nochmal starten“). Der Kern vergibt Schlüssel:
// die Instanz-ID beim ersten Prozess, `<id>~2`, `<id>~3` … bei weiteren.
// Getestet in tests/processes.test.ts.

type Phase = 'idle' | 'preparing' | 'running'

/** Ist das ein zusätzlicher Prozess (nicht der erste Start der Instanz)? */
export function isExtraKey(instanceId: string, key: string | undefined | null): boolean {
  return !!key && key !== instanceId
}

/** Laufzeit-Zustand einer Instanz: Läuft irgendein Prozess, gilt sie als laufend; die Vorbereitung hat Vorrang. */
export function aggregatePhase(current: Phase, mainAlive: boolean, extraCount: number): Phase {
  if (current === 'preparing') return current
  return mainAlive || extraCount > 0 ? 'running' : 'idle'
}

/** Standardkonto für den zweiten Start: ein anderes als das, mit dem schon gespielt wird (sonst das aktive). */
export function defaultExtraAccount(accounts: { id: string }[], activeId: string | null, inUse: string[]): string | null {
  const other = accounts.find((a) => !inUse.includes(a.id))
  if (other) return other.id
  return accounts.find((a) => a.id === activeId)?.id ?? accounts[0]?.id ?? null
}
