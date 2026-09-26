import { limitsByRole, type AdminOnly, type StaffLimits } from '~/utils/team'

// Gemeinsamer Zustand des Team-Bereichs: Rolle, Grenzen und die Zähler der
// Seitenleiste (offene Meldungen, Einsprüche, Uploads). Rechte prüft der
// Server bei jeder Anfrage selbst – hier wird nur ausgeblendet.

interface Counts {
  reports: number
  highPriority: number
  appeals: number
  uploads: number
}

const counts = ref<Counts | null>(null)
let refreshing: Promise<void> | null = null

export function useTeam() {
  const trs = useTrsStore()
  const role = computed(() => trs.role)
  const isAdmin = computed(() => role.value === 'admin')
  const limits = computed<StaffLimits>(() => limitsByRole[role.value ?? 'moderator'])

  /** Darf die eigene Rolle das? Die Liste `adminOnly` nennt alles, was nur Admins dürfen – der Rest geht für beide Rollen. */
  function can(_what: AdminOnly): boolean {
    return isAdmin.value
  }

  /** Zähler neu laden (Seitenleiste, Übersicht). Fehler sind still – die Zähler sind nur Beiwerk. */
  function refreshCounts(): Promise<void> {
    if (refreshing) return refreshing
    refreshing = (async () => {
      try {
        const d = await backend.team.dashboard()
        counts.value = {
          reports: d.reports.open + d.reports.inReview,
          highPriority: d.reports.highPriority,
          appeals: d.appeals.open,
          uploads: d.uploads.capesPending + d.uploads.cosmeticsPending + d.uploads.capesReported + d.uploads.cosmeticsReported,
        }
      } catch {
        // Offline oder keine Rechte: Zähler bleiben leer.
      } finally {
        refreshing = null
      }
    })()
    return refreshing
  }

  return { role, isAdmin, limits, can, counts, refreshCounts }
}
