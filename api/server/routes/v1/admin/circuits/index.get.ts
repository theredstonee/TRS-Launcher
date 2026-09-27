import { defineEventHandler } from 'h3'
import { circuitCounts, circuitListQuery, listAdminCircuits } from '../../../../lib/circuits'
import { useCtx } from '../../../../lib/context'
import { queryWith, requireStaff } from '../../../../lib/http'

/** Team (§25.4, `circuits.manage`): alle Schaltungen mit Status, filterbar nach Status, Kategorie und Suchtext. */
export default defineEventHandler((event) => {
  requireStaff(event, 'circuits.manage')
  const q = queryWith(event, circuitListQuery)
  const ctx = useCtx()
  return { circuits: listAdminCircuits(ctx, q), counts: circuitCounts(ctx) }
})
