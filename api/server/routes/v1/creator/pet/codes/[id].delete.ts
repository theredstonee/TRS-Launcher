import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { actingAs } from '../../../../../lib/creator-http'
import { revokePetCode } from '../../../../../lib/creator-pets'
import { limit, paramWith, requireWebOrUser } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'

/** Eigenen Haustier-Code widerrufen (§35.13). → `{ codes, quota }` */
export default defineEventHandler((event) => {
  const auth = requireWebOrUser(event, 'write')
  limit(`creator-manage:${auth.uuid}`, RULES.creatorManageUser)
  const id = paramWith(event, 'id', z.coerce.number().int().min(1).max(Number.MAX_SAFE_INTEGER))
  return revokePetCode(useCtx(), auth.uuid, id, actingAs(event))
})
