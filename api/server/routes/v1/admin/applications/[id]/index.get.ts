import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { adminApplicationDetail } from '../../../../../lib/applications'
import { useCtx } from '../../../../../lib/context'
import { paramWith, requireStaff } from '../../../../../lib/http'

/** Bewerbung mit Antworten, Stimmen, Notizen, Verlauf und (mit players.view) Strafverlauf. */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'applications.view')
  return { application: adminApplicationDetail(useCtx(), staff, paramWith(event, 'id', z.string().regex(/^a[0-9a-f]{16}$/))) }
})
