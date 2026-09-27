import { defineEventHandler, setResponseHeaders } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { notFound } from '../../../../../lib/errors'
import { limit, paramWith, requireUser } from '../../../../../lib/http'
import { packByCode, readPackFile } from '../../../../../lib/packs'
import { RULES } from '../../../../../lib/ratelimit'

/** Pack-Datei (`.mrpack`) herunterladen – nur mit TRS-Konto (Launcher). Zählt als Installation (nicht beim Besitzer). */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'read')
  limit(`packDownload:${auth.uuid}`, RULES.packDownloadUser)
  const code = paramWith(event, 'code', z.string().max(32))
  const ctx = useCtx()
  const p = packByCode(ctx, code)
  if (!p) throw notFound('pack_not_found', 'No modpack with this code (wrong code, expired or deleted)')
  let data: Buffer
  try {
    data = readPackFile(ctx, p, p.owner_uuid !== auth.uuid)
  } catch (err) {
    console.error(`[trs-api] could not read shared pack ${p.id}`, (err as Error).message)
    throw notFound('pack_not_found', 'No modpack with this code (wrong code, expired or deleted)')
  }
  setResponseHeaders(event, {
    'Content-Type': 'application/x-modrinth-modpack+zip',
    'Content-Disposition': `attachment; filename="trs-pack-${p.code}-${p.revision}.mrpack"`,
    'Cache-Control': 'private, no-store',
    'X-Pack-Revision': String(p.revision),
    'X-Pack-Sha256': p.sha256,
  })
  return data
})
