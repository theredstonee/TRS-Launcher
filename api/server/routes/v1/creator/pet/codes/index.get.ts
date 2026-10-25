import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { actingAs } from '../../../../../lib/creator-http'
import { listPetCodes } from '../../../../../lib/creator-pets'
import { requireWebOrUser } from '../../../../../lib/http'

/** Codes für das eigene Haustier + Monatsgrenze (§35.13). → `{ codes, quota }` */
export default defineEventHandler((event) => {
  const auth = requireWebOrUser(event)
  return listPetCodes(useCtx(), auth.uuid, actingAs(event))
})
