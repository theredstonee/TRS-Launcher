import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { actingAs } from '../../../../lib/creator-http'
import { creatorProfile } from '../../../../lib/creators'
import { requireWebOrUser } from '../../../../lib/http'

/** Eigenes Profil (§35.11): `{ approved, pending, rejected }`. Creator oder seine Helfer (`?as=`, nur lesen). */
export default defineEventHandler((event) => {
  const auth = requireWebOrUser(event)
  return creatorProfile(useCtx(), auth.uuid, actingAs(event))
})
