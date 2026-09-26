import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { queryWith, requireStaff } from '../../../../lib/http'
import { listAppeals } from '../../../../lib/sanctions'
import { adminAppealListQuery } from '../../../../lib/schemas'

/** Einsprüche (offen: älteste zuerst). */
export default defineEventHandler((event) => {
  requireStaff(event)
  return listAppeals(useCtx(), queryWith(event, adminAppealListQuery))
})
