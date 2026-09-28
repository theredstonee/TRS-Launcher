// Team-Bereich (§24.2): dieselbe Website-Sitzung wie überall (Microsoft-Anmeldung, useAccount). Rechte, Rang und
// Grenzen kommen aus /v1/web/me → `team`; die Oberfläche blendet danach ein/aus, geprüft wird immer serverseitig.

export type StaffRole = 'admin' | 'moderator'
import { SANCTION_PERMISSION } from '#shared/team'
import type { StaffLimits, TeamRoleRef } from './useAccount'

export interface AdminSession {
  name: string
  uuid: string
  skin: string | null
  csrf: string
  /** Altes Raster (Anzeige): `admin` ab Admin-Rang. */
  role: StaffRole
  rank: number
  owner: boolean
  roles: TeamRoleRef[]
  permissions: string[]
  limits: StaffLimits
}

export interface ApiErrorBody {
  error?: { code?: string, message?: string }
}

/** Lesbare Meldung aus einem $fetch-Fehler (API-Format `{ error: { code, message } }`). */
export function apiMessage(e: unknown): string {
  const data = (e as { data?: ApiErrorBody }).data
  return data?.error?.message ?? data?.error?.code ?? (e as Error).message ?? 'error'
}

/** Stabiler Fehlercode aus einem $fetch-Fehler (oder `''`). */
export function apiCode(e: unknown): string {
  return (e as { data?: ApiErrorBody }).data?.error?.code ?? ''
}

/** Zähler für die Seitenleiste (aus dem Dashboard). */
export interface AdminCounts {
  reports: number
  highPriority: number
  appeals: number
  uploads: number
  applications: number
  /** Offene Schaltungs-Einreichungen (§25). */
  circuits: number
  /** Neue Issues ohne Zuständige (§28). */
  issues?: number
}

export const ADMIN_RANK = 900

export function useAdmin() {
  const { account, load: loadAccount, api, logout } = useAccount()
  const counts = useState<AdminCounts | null>('admin-counts', () => null)

  const session = computed<AdminSession | null>(() => {
    const a = account.value
    if (!a?.team) return null
    return {
      name: a.name,
      uuid: a.uuid,
      skin: a.skin,
      csrf: a.csrf,
      role: a.team.owner || a.team.rank >= ADMIN_RANK ? 'admin' : 'moderator',
      rank: a.team.rank,
      owner: a.team.owner,
      roles: a.team.roles,
      permissions: a.team.permissions,
      limits: a.team.limits,
    }
  })

  /** Sitzung neu laden; `true` = Team-Mitglied. */
  async function load(): Promise<boolean> {
    await loadAccount(true)
    return !!session.value
  }

  /** Recht aus der Sitzung (nur für die Anzeige). */
  function can(permission: string): boolean {
    return session.value?.permissions.includes(permission) ?? false
  }

  const isAdmin = computed(() => session.value?.role === 'admin')

  /**
   * Darf ich diese Strafe ändern? Wie der Server: `lift` braucht `sanctions.lift`, `extend` das Recht der Art,
   * `shorten` eines davon, Konto-Banns zusätzlich `sanctions.ban`; Strafen von höherem Rang sind tabu.
   */
  function canModifySanction(s: { kind: string, createdBy: { uuid: string }, createdRank?: number }, action: 'lift' | 'shorten' | 'extend' = 'lift'): boolean {
    const me = session.value
    if (!me) return false
    if (me.owner) return true
    const kindPerm = SANCTION_PERMISSION[s.kind as keyof typeof SANCTION_PERMISSION]
    if (action === 'lift' && !can('sanctions.lift')) return false
    if (action === 'extend' && !can(kindPerm)) return false
    if (action === 'shorten' && !can('sanctions.lift') && !can(kindPerm)) return false
    if (s.kind === 'account_ban' && !can('sanctions.ban')) return false
    return s.createdBy.uuid === me.uuid || (s.createdRank ?? 0) <= me.rank
  }

  return { session, account, counts, load, api, logout, can, isAdmin, canModifySanction }
}
