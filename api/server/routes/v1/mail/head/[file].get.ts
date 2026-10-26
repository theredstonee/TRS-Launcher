import { defineEventHandler, getQuery, setResponseHeader } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { notFound } from '../../../../lib/errors'
import { clientIp, limit, paramWith, sendCached } from '../../../../lib/http'
import { sha256Hex } from '../../../../lib/ids'
import { headPng, verifyHead } from '../../../../lib/mail/images'
import { RULES } from '../../../../lib/ratelimit'

/** Kopf eines Creators für den Spotlight-Baustein (§40.7): nur mit gültiger Signatur. */
export default defineEventHandler(async (event) => {
  limit(`mailImg:${clientIp(event)}`, RULES.mailImageIp)
  const file = paramWith(event, 'file', z.string().regex(/^[0-9a-f]{32}\.png$/))
  const uuid = file.slice(0, 32)
  const s = getQuery(event).s
  const ctx = useCtx()
  if (typeof s !== 'string' || !verifyHead(ctx, uuid, s)) throw notFound()
  const png = await headPng(ctx, uuid)
  const res = sendCached(event, `${uuid}.png`, 'image/png', { body: png, sha256: sha256Hex(png), public: true })
  // Skins ändern sich: einen Tag statt eines Jahres.
  setResponseHeader(event, 'Cache-Control', 'public, max-age=86400')
  return res
})
