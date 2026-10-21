import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { limit, noContent, paramWith, requireStaff } from '../../../../lib/http'
import { deletePitchLink } from '../../../../lib/pitch'
import { RULES } from '../../../../lib/ratelimit'

/** Pitch-Link endgültig löschen (§38.2). */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'pitch.manage')
  limit(`adminPitch:${staff.uuid}`, RULES.adminPitch)
  const id = paramWith(event, 'id', z.string().regex(/^[1-9][0-9]{0,14}$/).transform(Number))
  deletePitchLink(useCtx(), staff, id)
  return noContent(event)
})
