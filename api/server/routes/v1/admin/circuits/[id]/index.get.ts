import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { listAudit } from '../../../../../lib/audit'
import { adminDetail, getCircuit } from '../../../../../lib/circuits'
import { useCtx } from '../../../../../lib/context'
import { notFound } from '../../../../../lib/errors'
import { paramWith, requireStaff } from '../../../../../lib/http'
import { can } from '../../../../../lib/team'

/** Team (§25.4): eine Schaltung (jeder Status) samt Verlauf (Audit, nur mit `audit.view`). */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'circuits.manage')
  const id = paramWith(event, 'id', z.string().regex(/^[a-z0-9_]{1,48}$/))
  const ctx = useCtx()
  const row = getCircuit(ctx, id)
  if (!row) throw notFound('circuit_not_found', 'Circuit not found')
  const history = can(staff, 'audit.view') || staff.owner ? listAudit(ctx, { ref: `circuit:${id}`, limit: 50 }).entries : []
  return { circuit: adminDetail(row), history }
})
