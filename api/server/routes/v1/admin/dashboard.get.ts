import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { dashboard } from '../../../lib/dashboard'
import { requireStaff } from '../../../lib/http'

/** Übersicht (§22.5): offene Arbeit, aktive Strafen, Nutzerzahlen, 30-Tage-Reihen, Server-Zustand, letzte Audit-Einträge. */
export default defineEventHandler((event) => {
  requireStaff(event)
  return dashboard(useCtx())
})
