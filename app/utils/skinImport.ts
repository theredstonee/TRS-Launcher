import type { SkinImportCandidate, SkinImportRequest, SkinImportSource } from '~/types'

// Reine Helfer für „Skin hinzufügen“ (Dialog-Logik ohne Vue, testbar).
// Geprüft wird alles im Kern – hier geht es nur um Auswahl und Reihenfolge.

/** Reihenfolge der Launcher im Auswahl-Dialog. */
export const launcherSources = ['minecraft', 'prism', 'modrinth', 'atlauncher'] as const satisfies readonly SkinImportSource[]

export type LauncherSource = (typeof launcherSources)[number]

export interface SkinSourceGroup {
  source: LauncherSource
  items: SkinImportCandidate[]
}

export function isLauncherSource(source: SkinImportSource): source is LauncherSource {
  return (launcherSources as readonly SkinImportSource[]).includes(source)
}

/** Gruppiert gefundene Skins nach Launcher (feste Reihenfolge, leere Gruppen fallen weg). */
export function groupBySource(candidates: SkinImportCandidate[]): SkinSourceGroup[] {
  return launcherSources
    .map((source) => ({ source, items: candidates.filter((c) => c.source === source) }))
    .filter((g) => g.items.length > 0)
}

/** Vorauswahl: alles, was noch nicht in der Sammlung liegt. */
export function defaultSelection(candidates: SkinImportCandidate[]): Set<string> {
  return new Set(candidates.filter((c) => !c.duplicate).map((c) => c.token))
}

/**
 * Mehrere Dateien auf einmal: erkannte Modelle und Dateinamen übernehmen,
 * Bilder, die schon in der Sammlung liegen, überspringen.
 */
export function bulkPlan(candidates: SkinImportCandidate[]): { requests: SkinImportRequest[]; skipped: string[] } {
  return {
    requests: candidates.filter((c) => !c.duplicate).map((c) => ({ token: c.token })),
    skipped: candidates.filter((c) => c.duplicate).map((c) => c.token),
  }
}

/** Übernimmt die markierten Skins in der angezeigten Reihenfolge. */
export function selectedRequests(candidates: SkinImportCandidate[], selected: ReadonlySet<string>): SkinImportRequest[] {
  return candidates.filter((c) => selected.has(c.token)).map((c) => ({ token: c.token }))
}

/** Marken, die nicht übernommen werden (zum Verwerfen im Kern). */
export function unusedTokens(candidates: SkinImportCandidate[], used: Iterable<string>): string[] {
  const set = new Set(used)
  return candidates.map((c) => c.token).filter((t) => !set.has(t))
}
