import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { JOB_ID, eligibility } from '../../../../../lib/applications'
import { useCtx } from '../../../../../lib/context'
import { paramWith, requireWebOrUser } from '../../../../../lib/http'

/** Darf ich mich bewerben? (Website-Sitzung oder Bearer-Token) → `{ eligibility }`. */
export default defineEventHandler((event) => {
  const me = requireWebOrUser(event)
  const id = paramWith(event, 'id', z.string().regex(JOB_ID))
  return { eligibility: eligibility(useCtx(), me.uuid, id) }
})
