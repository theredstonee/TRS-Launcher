import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { deleteCircuit } from '../../../../../lib/circuits'
import { useCtx } from '../../../../../lib/context'
import { limit, noContent, paramWith, requireStaff } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'

/** Team (§25.4): Schaltung löschen (Grabstein, damit der Seed sie nicht wieder anlegt). */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'circuits.manage')
  limit(`adminCircuit:${staff.uuid}`, RULES.adminCircuit)
  const id = paramWith(event, 'id', z.string().regex(/^[a-z0-9_]{1,48}$/))
  deleteCircuit(useCtx(), staff, id)
  return noContent(event)
})
