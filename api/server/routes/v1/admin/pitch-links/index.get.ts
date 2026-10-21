import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { requireStaff } from '../../../../lib/http'
import { listPitchLinks } from '../../../../lib/pitch'

/** Pitch-Links im Team-Bereich (§38.2): alle, neueste zuerst – ohne Token (nur die letzten 4 Zeichen). */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'pitch.manage')
  return { links: listPitchLinks(useCtx(), staff) }
})
