import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { touchCircuitSubmitter } from '../../../../../lib/achievements'
import { acceptSubmission, acceptSubmissionBody } from '../../../../../lib/circuits'
import { useCtx } from '../../../../../lib/context'
import { limit, paramWith, readJson, requireStaff } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'

/**
 * Team (§25.5): Einreichung annehmen → Schaltung mit dem Namen des Erstellers (Standard: veröffentlicht). `circuit` =
 * im Editor bearbeitete Fassung (sonst so wie eingereicht). 409 `submission_decided` / `circuit_exists`.
 */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'circuits.manage')
  limit(`adminCircuit:${staff.uuid}`, RULES.adminCircuit)
  const id = paramWith(event, 'id', z.string().regex(/^cs[0-9a-f]{16}$/))
  const body = await readJson(event, acceptSubmissionBody, 300 * 1024)
  const ctx = useCtx()
  const result = acceptSubmission(ctx, staff, id, body)
  touchCircuitSubmitter(ctx, id)
  return result
})
