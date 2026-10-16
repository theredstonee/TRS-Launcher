import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../lib/context'
import { readSymbol } from '../../../lib/creators'
import { paramWith, sendCached, textureViewer } from '../../../lib/http'

/**
 * `GET /v1/creator-symbols/{sha256}.png` (§35.4): Fan-Symbol als PNG-Streifen, unveränderlich (ein Jahr `immutable`).
 * Öffentlich nur freigegebene (aktuelle oder frühere); wartende nur für Prüfer und den Creator selbst (`no-store`).
 */
export default defineEventHandler((event) => {
  const file = paramWith(event, 'file', z.string().regex(/^[0-9a-f]{64}\.png$/))
  const sha = file.slice(0, 64)
  const sym = readSymbol(useCtx(), sha, textureViewer(event))
  return sendCached(event, `${sha.slice(0, 12)}.png`, 'image/png', { body: sym.png, sha256: sym.sha256, public: sym.public })
})
