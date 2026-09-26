import { defineEventHandler } from 'h3'
import { userInfo } from '../../../../../lib/admin'
import { useCtx } from '../../../../../lib/context'
import { notFound } from '../../../../../lib/errors'
import { paramWith, requireStaff } from '../../../../../lib/http'
import { normalizeUuid } from '../../../../../lib/ids'
import { getUserByName } from '../../../../../lib/users'
import { z } from 'zod'

/** Nutzer per UUID oder (zuletzt gesehenem) Minecraft-Namen. */
export default defineEventHandler((event) => {
  requireStaff(event)
  const ctx = useCtx()
  const key = paramWith(event, 'uuid', z.string().max(36))
  let uuid = normalizeUuid(key)
  if (!uuid && /^[A-Za-z0-9_]{1,16}$/.test(key)) uuid = getUserByName(ctx, key)?.uuid ?? null
  if (!uuid) throw notFound('user_not_found', 'Unknown user')
  return { user: userInfo(ctx, uuid) }
})
