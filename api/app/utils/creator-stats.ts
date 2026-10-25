// Creator-Statistik für das Team (API §35.14): Typen und Spalten der Rangliste – geteilt von Liste und Detailseite.

export type StatTier = 'starter' | 'partner' | 'top'
export type StatState = 'active' | 'revoked' | 'banned'

export interface CreatorStatRow {
  uuid: string
  name: string
  tier: StatTier
  state: StatState
  since: string
  mainCode: string | null
  fans: { total: number, today: number, d7: number, d30: number }
  newPlayers: { total: number, d7: number, d30: number }
  pets: {
    pet: { cosmeticId: string, name: string } | null
    codes: number
    activeCodes: number
    redemptions: number
    month: { month: string, used: number, limit: number, redemptions: number }
  }
  activity: { online7d: number, online30d: number, played7d: number, played30d: number }
}

export interface StatDefinitions {
  newPlayerWindowHours: number
  activitySince: string | null
}

export interface CreatorStatsList {
  generatedAt: string
  definitions: StatDefinitions
  totals: { creators: number, fans: number, fans7d: number, fans30d: number, newPlayers: number, newPlayers30d: number, petRedemptions: number, online7d: number, online30d: number }
  creators: CreatorStatRow[]
}

export interface StatDay {
  day: string
  newFans: number
  newPlayers: number
  redemptions: number
  petRedemptions: number
  activeFans: number | null
  playingFans: number | null
}

export interface CreatorStatsDetail {
  generatedAt: string
  definitions: StatDefinitions
  creator: CreatorStatRow
  fanCape: { holders: number, fanHolders: number }
  series: StatDay[]
}

/** Sortierbare Spalten der Rangliste (Schlüssel = `sort` der API und der Texte `cols`). */
export const STAT_COLUMNS = [
  { key: 'fans', get: (r: CreatorStatRow) => r.fans.total },
  { key: 'fansToday', get: (r: CreatorStatRow) => r.fans.today },
  { key: 'fans7d', get: (r: CreatorStatRow) => r.fans.d7 },
  { key: 'fans30d', get: (r: CreatorStatRow) => r.fans.d30 },
  { key: 'newPlayers', get: (r: CreatorStatRow) => r.newPlayers.total },
  { key: 'newPlayers30d', get: (r: CreatorStatRow) => r.newPlayers.d30 },
  { key: 'petCodes', get: (r: CreatorStatRow) => r.pets.codes },
  { key: 'petRedemptions', get: (r: CreatorStatRow) => r.pets.redemptions },
  { key: 'online7d', get: (r: CreatorStatRow) => r.activity.online7d },
  { key: 'online30d', get: (r: CreatorStatRow) => r.activity.online30d },
  { key: 'played7d', get: (r: CreatorStatRow) => r.activity.played7d },
  { key: 'played30d', get: (r: CreatorStatRow) => r.activity.played30d },
] as const
export type StatColumn = (typeof STAT_COLUMNS)[number]['key'] | 'name'

/** Sortiert eine Kopie: Zahlen absteigend (Standard), Namen aufsteigend; Gleichstand → mehr Fans, dann Name. */
export function sortStats(rows: readonly CreatorStatRow[], key: StatColumn, dir: 'asc' | 'desc'): CreatorStatRow[] {
  const get = key === 'name' ? null : STAT_COLUMNS.find((c) => c.key === key)!.get
  const sign = dir === 'asc' ? 1 : -1
  return [...rows].sort((a, b) => {
    const d = get ? get(a) - get(b) : a.name.localeCompare(b.name, undefined, { sensitivity: 'base' })
    if (d !== 0) return d * sign
    if (a.fans.total !== b.fans.total) return b.fans.total - a.fans.total
    return a.name.localeCompare(b.name)
  })
}

/** Anteil in Prozent (0–100, gerundet); ohne Basis 0. */
export function percentOf(part: number, whole: number): number {
  if (whole <= 0) return 0
  return Math.round((Math.min(part, whole) / whole) * 100)
}
