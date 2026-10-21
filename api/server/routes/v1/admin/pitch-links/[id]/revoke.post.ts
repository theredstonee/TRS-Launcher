import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { limit, paramWith, requireStaff } from '../../../../../lib/http'
import { revokePitchLink } from '../../../../../lib/pitch'
import { RULES } from '../../../../../lib/ratelimit'

/** Pitch-Link widerrufen (§38.2): liefert sofort 404; bleibt in der Liste (Aufrufe sichtbar). */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'pitch.manage')
  limit(`adminPitch:${staff.uuid}`, RULES.adminPitch)
  const id = paramWith(event, 'id', z.string().regex(/^[1-9][0-9]{0,14}$/).transform(Number))
  return { link: revokePitchLink(useCtx(), staff, id) }
})
