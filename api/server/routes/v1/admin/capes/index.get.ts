import { defineEventHandler } from 'h3'
import { listCapesPage } from '../../../../lib/admin'
import { useCtx } from '../../../../lib/context'
import { queryWith, requireStaff } from '../../../../lib/http'
import { adminCapeListQuery } from '../../../../lib/schemas'

/** Hochgeladene Umhänge nach Status (Standard: pending), Besitzer, Name, Zeitraum; Cursor-Seiten (§22.7). */
export default defineEventHandler((event) => {
  requireStaff(event)
  const q = queryWith(event, adminCapeListQuery)
  return listCapesPage(useCtx(), q)
})
