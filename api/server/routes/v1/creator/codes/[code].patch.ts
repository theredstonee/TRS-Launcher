import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { actingAs } from '../../../../lib/creator-http'
import { updateCode } from '../../../../lib/creators'
import { limit, paramWith, readJson, requireWebOrUser } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'

/** Zusatz-Code an/aus, Grenze oder Ablauf ändern (§35.3). Der Haupt-Code → 400. */
export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'write')
  limit(`creator-manage:${auth.uuid}`, RULES.creatorManageUser)
  const code = paramWith(event, 'code', z.string().min(1).max(32))
  return updateCode(useCtx(), auth.uuid, code, await readJson(event, z.unknown()), actingAs(event))
})
