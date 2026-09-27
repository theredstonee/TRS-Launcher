import {
  mainRole,
  noLimits,
  sanctionRights,
  teamCan,
  teamCanAny,
  type Permission,
  type SanctionRights,
  type StaffLimits,
} from '~/utils/teamAccess'
import type { AdminSanction } from '~/utils/team'

// Gemeinsamer Zustand des Team-Bereichs: eigene Rechte (§24.2), Grenzen und die
// Zähler der Seitenleiste. Rechte prüft der Server bei jeder Anfrage selbst –
// hier wird nur ausgeblendet.

interface Counts {
  reports: number
  highPriority: number
  appeals: number
  uploads: number
  applications: number
}

const counts = ref<Counts | null>(null)
let refreshing: Promise<void> | null = null

export function useTeam() {
  const trs = useTrsStore()
  const team = computed(() => trs.team)
  const rank = computed(() => team.value?.rank ?? 0)
  const owner = computed(() => team.value?.owner ?? false)
  const role = computed(() => mainRole(team.value))
  const limits = computed<StaffLimits>(() => team.value?.limits ?? noLimits)

  /** Hat die eigene Rolle dieses Recht? */
  function can(permission: Permission): boolean {
    return teamCan(team.value, permission)
  }

  /** Mindestens eines der Rechte? */
  function canAny(...list: Permission[]): boolean {
    return teamCanAny(team.value, list)
  }

  /** Aufheben/Ende ändern nach Recht und Rang-Regel (`createdRank`). */
  function rightsFor(s: AdminSanction): SanctionRights {
    return sanctionRights(s, team.value, trs.me?.uuid)
  }

  /** Zähler neu laden (Seitenleiste, Übersicht). Fehler sind still – die Zähler sind nur Beiwerk. */
  function refreshCounts(): Promise<void> {
    if (!can('dashboard.view')) {
      counts.value = null
      return Promise.resolve()
    }
    if (refreshing) return refreshing
    refreshing = (async () => {
      try {
        const d = await backend.team.dashboard()
        counts.value = {
          reports: d.reports ? d.reports.open + d.reports.inReview : 0,
          highPriority: d.reports?.highPriority ?? 0,
          appeals: d.appeals?.open ?? 0,
          uploads: d.uploads ? d.uploads.capesPending + d.uploads.cosmeticsPending + d.uploads.capesReported + d.uploads.cosmeticsReported : 0,
          applications: d.applications?.new ?? 0,
        }
      } catch {
        // Offline oder keine Rechte: Zähler bleiben leer.
      } finally {
        refreshing = null
      }
    })()
    return refreshing
  }

  return { team, rank, owner, role, limits, can, canAny, rightsFor, counts, refreshCounts }
}
