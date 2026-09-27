import { defineEventHandler, setResponseStatus } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { clientIp, limit, paramWith, readJson } from '../../../../lib/http'
import { createAnonymousShareReport } from '../../../../lib/moderation'
import { RULES } from '../../../../lib/ratelimit'
import { shareReportBody } from '../../../../lib/schemas'

/**
 * Öffentlich: geteilten Screenshot melden (Website, ohne Konto). Die IP zählt nur fürs Rate-Limit
 * (im RAM) und wird nicht gespeichert. Immer 202 – auch wenn der Link schon gemeldet ist.
 */
export default defineEventHandler(async (event) => {
  limit(`shareReport:${clientIp(event)}`, RULES.shareReportIp)
  const id = paramWith(event, 'id', z.string().max(64))
  const body = await readJson(event, shareReportBody)
  createAnonymousShareReport(useCtx(), id, body.reason)
  setResponseStatus(event, 202)
  return { ok: true }
})
