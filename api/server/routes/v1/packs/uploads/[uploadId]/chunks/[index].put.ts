import { defineEventHandler, getHeader } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../../lib/context'
import { badRequest } from '../../../../../../lib/errors'
import { limit, noContent, paramWith, readLimited, requireUser } from '../../../../../../lib/http'
import { chunkByteLimit, putPackChunk } from '../../../../../../lib/packupload'
import { RULES } from '../../../../../../lib/ratelimit'

const id = z.string().regex(/^[A-Za-z0-9_-]{22}$/)
const indexParam = z.string().regex(/^(0|[1-9][0-9]{0,5})$/)

/** Ein Stück (rohe Bytes, `X-Chunk-Sha256`). Dasselbe Stück noch einmal ist in Ordnung → 204. */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'write')
  limit(`packChunk:${auth.uuid}`, RULES.packChunkUser)
  const uploadId = paramWith(event, 'uploadId', id)
  const index = Number(paramWith(event, 'index', indexParam))
  const hash = (getHeader(event, 'x-chunk-sha256') ?? '').trim()
  if (!/^[0-9a-fA-F]{64}$/.test(hash)) throw badRequest('invalid_request', 'X-Chunk-Sha256 must be 64 hex characters')
  const ctx = useCtx()
  const max = chunkByteLimit(ctx, auth.uuid, uploadId, index)
  const body = await readLimited(event, max)
  putPackChunk(ctx, auth.uuid, uploadId, index, body, hash)
  return noContent(event)
})
