import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { beginApplicationVerification, JOB_ID } from '../../../../../lib/applications'
import { useCtx } from '../../../../../lib/context'
import { clientIp, created, limit, paramWith, readJson, requireWebOrUser } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'
import { assertNotSanctioned } from '../../../../../lib/sanctions'

/** Bewerbungsformular absenden; die Bewerbung wird erst nach bestätigter E-Mail angelegt. */
export default defineEventHandler(async (event) => {
  const me = requireWebOrUser(event, 'write')
  const id = paramWith(event, 'id', z.string().regex(JOB_ID))
  limit(`apply:${me.uuid}`, RULES.applyUser)
  limit(`apply-ip:${clientIp(event)}`, RULES.applyIp)
  const ctx = useCtx()
  // Wer im Sozialen gesperrt ist, kann sich während der Sperre auch nicht bewerben.
  assertNotSanctioned(ctx, me.uuid, 'social_ban')
  const body = await readJson(event, z.unknown(), 64 * 1024)
  return created(event, { confirmation: beginApplicationVerification(ctx, me, id, body) })
})
