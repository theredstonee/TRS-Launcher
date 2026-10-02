import { createReadStream } from 'node:fs'
import { defineEventHandler, getHeader, setResponseHeaders, setResponseStatus } from 'h3'
import { z } from 'zod'
import { countAchievement } from '../../../../../lib/achievements'
import { useCtx } from '../../../../../lib/context'
import { run } from '../../../../../lib/db'
import { notFound } from '../../../../../lib/errors'
import { limit, paramWith, requireUser } from '../../../../../lib/http'
import { packByCode, planPackDownload } from '../../../../../lib/packs'
import { RULES } from '../../../../../lib/ratelimit'

/**
 * Pack-Datei (`.mrpack`) herunterladen – nur mit TRS-Konto. `Range: bytes=` setzt fort (206, `Content-Length`
 * ist die Länge des Stücks). Eine Installation zählt nur beim ersten Stück (Beginn bei Byte 0), nicht beim Besitzer.
 */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'read')
  limit(`packDownload:${auth.uuid}`, RULES.packDownloadUser)
  const code = paramWith(event, 'code', z.string().max(32))
  const ctx = useCtx()
  const p = packByCode(ctx, code)
  if (!p) throw notFound('pack_not_found', 'No modpack with this code (wrong code, expired or deleted)')
  const plan = planPackDownload(ctx, p, getHeader(event, 'range'), auth.uuid)
  if (plan.countInstall) {
    run(ctx.db, 'UPDATE shared_packs SET installs = installs + 1 WHERE id = ?', p.id)
    countAchievement(ctx, p.owner_uuid, 'pack_installs')
  }
  const headers: Record<string, string> = {
    'Content-Type': 'application/x-modrinth-modpack+zip',
    'Content-Disposition': `attachment; filename="trs-pack-${p.code}-${p.revision}.mrpack"`,
    'Cache-Control': 'private, no-store',
    'Accept-Ranges': 'bytes',
    'Content-Length': String(plan.contentLength),
    'X-Pack-Revision': String(p.revision),
    'X-Pack-Sha256': p.sha256,
  }
  if (plan.contentRange) headers['Content-Range'] = plan.contentRange
  setResponseHeaders(event, headers)
  if (plan.status === 206) setResponseStatus(event, 206)
  if (plan.contentLength === 0) return Buffer.alloc(0)
  return createReadStream(plan.path, { start: plan.start, end: plan.end })
})
