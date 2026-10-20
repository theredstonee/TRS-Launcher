import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { adminFeatured } from '../../../../lib/featured'
import { requireStaff } from '../../../../lib/http'

/** Empfohlene Modpacks im Team-Bereich (§37.2): alle Packs, auch geplante und abgelaufene. */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'featured.manage')
  return { packs: adminFeatured(useCtx(), staff) }
})
