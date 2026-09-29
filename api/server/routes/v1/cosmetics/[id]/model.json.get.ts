import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { readCosmeticV2File } from '../../../../lib/cosmetics'
import { v2IdParam } from '../../../../lib/cosmetics-v2'
import { sendCached } from '../../../../lib/http'

/** `GET /v1/cosmetics/{id}/model.json` – Modell im Format v2 (§11.9), öffentlich; ETag, mit passendem `?v=` immutable. */
export default defineEventHandler((event) => {
  const id = v2IdParam(event)
  return sendCached(event, `${id}.json`, 'application/json; charset=utf-8', readCosmeticV2File(useCtx(), id, 'model'))
})
