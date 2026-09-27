import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { dashboard } from '../../../lib/dashboard'
import { requireStaff } from '../../../lib/http'
import { applicationCounts } from '../../../lib/applications'
import { circuitCounts } from '../../../lib/circuits'
import { can } from '../../../lib/team'

/**
 * Übersicht (§22.5): offene Arbeit, aktive Strafen, Nutzerzahlen, 30-Tage-Reihen, Server-Zustand, letzte Audit-Einträge.
 * Je Recht gekürzt (§24.2): Nutzer-/Server-Zahlen und Reihen nur mit `stats.view`, Audit nur mit `audit.view`,
 * Warteschlangen nur mit dem passenden Recht (sonst `null`).
 */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'dashboard.view')
  const d = dashboard(useCtx())
  const stats = can(staff, 'stats.view')
  return {
    ...d,
    reports: can(staff, 'reports.view') ? d.reports : null,
    appeals: can(staff, 'appeals.handle') ? d.appeals : null,
    sanctions: can(staff, 'players.view') || can(staff, 'appeals.handle') ? d.sanctions : null,
    uploads: can(staff, 'uploads.review') ? d.uploads : null,
    users: stats ? d.users : null,
    hosting: stats || can(staff, 'worlds.view') ? d.hosting : null,
    chat: stats ? d.chat : null,
    series: stats ? d.series : null,
    server: stats ? d.server : null,
    recentAudit: can(staff, 'audit.view') ? d.recentAudit : [],
    applications: can(staff, 'applications.view') ? applicationCounts(useCtx(), staff.uuid) : null,
    circuits: can(staff, 'circuits.manage') ? circuitCounts(useCtx()) : null,
  }
})
