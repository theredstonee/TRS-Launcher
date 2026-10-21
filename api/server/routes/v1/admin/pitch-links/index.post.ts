import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { created, limit, readJson, requireStaff } from '../../../../lib/http'
import { createPitchLink, pitchCreateBody } from '../../../../lib/pitch'
import { RULES } from '../../../../lib/ratelimit'

/** Neuen Pitch-Link erzeugen (§38.2). Die volle Adresse (`url`) gibt es nur in dieser Antwort. */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'pitch.manage')
  limit(`adminPitch:${staff.uuid}`, RULES.adminPitch)
  const body = await readJson(event, pitchCreateBody)
  const { link, url } = createPitchLink(useCtx(), staff, body)
  return created(event, { link, url })
})

