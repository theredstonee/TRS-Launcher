import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { readCosmeticV2File } from '../../../../lib/cosmetics'
import { v2IdParam } from '../../../../lib/cosmetics-v2'
import { sendCached } from '../../../../lib/http'

/** `GET /v1/cosmetics/{id}/card.png` – Vorschaubild (Tag, ≤ 512 px, Format v2 §11.9), öffentlich; ETag, mit passendem `?v=` immutable. */
export default defineEventHandler((event) => {
  const id = v2IdParam(event)
  return sendCached(event, `${id}-card.png`, 'image/png', readCosmeticV2File(useCtx(), id, 'card'))
})
