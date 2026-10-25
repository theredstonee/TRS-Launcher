import { z } from 'zod'
// Relativ importiert, damit Tests die Datei ohne Nuxt laden können.
import { creatorStates, creatorTiers } from './creator'

// Creator-Statistik für das Team (API §35.14, Recht `creators.manage`): Rangliste
// aller Creator und die Tagesreihen eines Creators. Nur Zahlen – nie Namen von Fans.

const uuid = z.string().regex(/^[0-9a-f]{32}$/)
const count = z.number().int().min(0)
const time = z.string().max(40).nullable()
const day = z.string().regex(/^\d{4}-\d{2}-\d{2}$/)

/** Sortierschlüssel der Rangliste (gleiche Namen wie die API). */
export const statSortKeys = [
  'fans',
  'fansToday',
  'fans7d',
  'fans30d',
  'newPlayers',
  'newPlayers7d',
  'newPlayers30d',
  'petCodes',
  'petRedemptions',
  'online7d',
  'online30d',
  'played7d',
  'played30d',
  'name',
  'since',
] as const
export type StatSortKey = (typeof statSortKeys)[number]
export type SortOrder = 'asc' | 'desc'

export const statsDefinitionsSchema = z.object({
  newPlayerWindowHours: z.number().int().min(1).max(720),
  activitySince: day.nullable(),
})

export const creatorStatRowSchema = z.object({
  uuid,
  name: z.string().max(16),
  tier: z.enum(creatorTiers),
  state: z.enum(creatorStates),
  since: time,
  mainCode: z.string().max(20).nullable(),
  fans: z.object({ total: count, today: count, d7: count, d30: count }),
  newPlayers: z.object({ total: count, d7: count, d30: count }),
  pets: z.object({
    pet: z.object({ cosmeticId: z.string().max(40), name: z.string().max(48) }).nullable(),
    codes: count,
    activeCodes: count,
    redemptions: count,
    month: z.object({ month: z.string().regex(/^\d{4}-\d{2}$/).nullable(), used: count, limit: count, redemptions: count }),
  }),
  activity: z.object({ online7d: count, online30d: count, played7d: count, played30d: count }),
})

export const creatorStatsListSchema = z.object({
  generatedAt: time,
  definitions: statsDefinitionsSchema,
  totals: z.object({
    creators: count,
    fans: count,
    fans7d: count,
    fans30d: count,
    newPlayers: count,
    newPlayers30d: count,
    petRedemptions: count,
    online7d: count,
    online30d: count,
  }),
  creators: z.array(creatorStatRowSchema).max(500),
})

export const seriesDaySchema = z.object({
  day,
  newFans: count,
  newPlayers: count,
  redemptions: count,
  petRedemptions: count,
  activeFans: count.nullable(),
  playingFans: count.nullable(),
})

export const creatorStatsDetailSchema = z.object({
  generatedAt: time,
  definitions: statsDefinitionsSchema,
  creator: creatorStatRowSchema,
  fanCape: z.object({ holders: count, fanHolders: count }),
  series: z.array(seriesDaySchema).max(366),
})

export type StatsDefinitions = z.infer<typeof statsDefinitionsSchema>
export type CreatorStatRow = z.infer<typeof creatorStatRowSchema>
export type CreatorStatsList = z.infer<typeof creatorStatsListSchema>
export type SeriesDay = z.infer<typeof seriesDaySchema>
export type CreatorStatsDetail = z.infer<typeof creatorStatsDetailSchema>

// --- Rangliste ----------------------------------------------------------------------------

/** Wert einer Zeile für eine Spalte (Zahl oder Text). */
export function sortValue(row: CreatorStatRow, key: StatSortKey): number | string {
  switch (key) {
    case 'fans': return row.fans.total
    case 'fansToday': return row.fans.today
    case 'fans7d': return row.fans.d7
    case 'fans30d': return row.fans.d30
    case 'newPlayers': return row.newPlayers.total
    case 'newPlayers7d': return row.newPlayers.d7
    case 'newPlayers30d': return row.newPlayers.d30
    case 'petCodes': return row.pets.codes
    case 'petRedemptions': return row.pets.redemptions
    case 'online7d': return row.activity.online7d
    case 'online30d': return row.activity.online30d
    case 'played7d': return row.activity.played7d
    case 'played30d': return row.activity.played30d
    case 'name': return row.name.toLowerCase()
    case 'since': return row.since ? Date.parse(row.since) || 0 : 0
  }
}

/** Standard-Richtung je Spalte: Namen A–Z, alles andere größte zuerst. */
export function defaultOrder(key: StatSortKey): SortOrder {
  return key === 'name' ? 'asc' : 'desc'
}

/** Sortiert (stabil, ohne die Eingabe zu ändern); Gleichstand → Name A–Z. */
export function sortRows(rows: readonly CreatorStatRow[], key: StatSortKey, order: SortOrder): CreatorStatRow[] {
  const dir = order === 'asc' ? 1 : -1
  return [...rows].sort((a, b) => {
    const x = sortValue(a, key)
    const y = sortValue(b, key)
    const c = typeof x === 'number' && typeof y === 'number' ? x - y : String(x).localeCompare(String(y))
    return c !== 0 ? c * dir : a.name.toLowerCase().localeCompare(b.name.toLowerCase())
  })
}

/** Klick auf dieselbe Spalte dreht die Richtung, eine neue Spalte beginnt mit ihrer Standard-Richtung. */
export function nextSort(current: { key: StatSortKey; order: SortOrder }, key: StatSortKey): { key: StatSortKey; order: SortOrder } {
  if (current.key === key) return { key, order: current.order === 'asc' ? 'desc' : 'asc' }
  return { key, order: defaultOrder(key) }
}

/** Filter auf der geladenen Liste: Suche (Name, UUID, Haupt-Code) und Stufe/Zustand. */
export function filterRows(
  rows: readonly CreatorStatRow[],
  f: { q?: string; tier?: string; state?: string },
): CreatorStatRow[] {
  const q = (f.q ?? '').trim().toLowerCase().replaceAll('-', '')
  return rows.filter((r) => {
    if (f.tier && f.tier !== 'all' && r.tier !== f.tier) return false
    if (f.state && f.state !== 'all' && r.state !== f.state) return false
    if (!q) return true
    return r.name.toLowerCase().includes(q) || r.uuid.includes(q) || (r.mainCode ?? '').toLowerCase().replaceAll('-', '').includes(q)
  })
}

/** Anteil in ganzen Prozent, nie über 100 (0 bei leerer Basis). */
export function percent(part: number, whole: number): number {
  if (!whole) return 0
  return Math.min(100, Math.round((part / whole) * 100))
}

// --- Diagramme ----------------------------------------------------------------------------

export interface ChartPoint {
  day: string
  /** `null` = an diesem Tag nicht erfasst (Lücke). */
  value: number | null
}

/** „Schöne“ Obergrenze der Achse (1, 2, 2,5, 5 × 10^n), mindestens 1. */
export function niceMax(max: number): number {
  if (!Number.isFinite(max) || max <= 1) return 1
  const p = 10 ** Math.floor(Math.log10(max))
  const step = [1, 2, 2.5, 5, 10].find((s) => s * p >= max) ?? 10
  return step * p
}

/** Linien-Abschnitte (SVG-Pfade) ohne Lücken zu überbrücken: jede Folge erfasster Tage = ein Pfad. */
export function lineSegments(points: readonly ChartPoint[], width: number, height: number, top: number): string[] {
  const n = points.length
  if (!n) return []
  const max = niceMax(Math.max(0, ...points.map((p) => p.value ?? 0)))
  const x = (i: number) => (n === 1 ? width / 2 : (i / (n - 1)) * width)
  const y = (v: number) => top + (height - top) * (1 - v / max)
  const out: string[] = []
  let current = ''
  points.forEach((p, i) => {
    if (p.value === null) {
      if (current) out.push(current)
      current = ''
      return
    }
    current += `${current ? 'L' : 'M'}${x(i).toFixed(1)} ${y(p.value).toFixed(1)}`
  })
  if (current) out.push(current)
  return out
}

/** Summe, Höchstwert und Anzahl erfasster Tage einer Reihe. */
export function seriesSummary(points: readonly ChartPoint[]): { total: number; peak: number; known: number } {
  const known = points.filter((p) => p.value !== null) as { day: string; value: number }[]
  return {
    total: known.reduce((s, p) => s + p.value, 0),
    peak: known.reduce((m, p) => Math.max(m, p.value), 0),
    known: known.length,
  }
}

// --- Team-Modus ---------------------------------------------------------------------------

/** Bekannte Team-Aktionen (§35.9) – Text unter `creator.teamMode.actions.<a_b>`. */
export const teamActions = [
  'code.create',
  'code.update',
  'symbol.upload',
  'symbol.withdraw',
  'cape.upload',
  'cape.withdraw',
  'pack.list',
  'pack.unlist',
  'request.create',
  'profile.submit',
  'profile.withdraw',
  'helper.update',
  'helper.remove',
  'pet.code.create',
  'pet.code.revoke',
  'pet.request.create',
  'pet.request.withdraw',
  'pet.request.image',
  'pet.request.image.delete',
] as const
export type TeamAction = (typeof teamActions)[number]

/** Schlüssel des Textes einer Aktion; unbekannte (neuere API) → allgemeiner Text. */
export function teamActionKey(action: string): string {
  return (teamActions as readonly string[]).includes(action) ? `creator.teamMode.actions.${action.replaceAll('.', '_')}` : 'creator.teamMode.actions.other'
}
